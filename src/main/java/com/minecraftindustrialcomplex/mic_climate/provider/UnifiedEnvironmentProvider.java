package com.minecraftindustrialcomplex.mic_climate.provider;

import com.github.thedeathlycow.thermoo.api.environment.component.EnvironmentComponentTypes;
import com.github.thedeathlycow.thermoo.api.environment.provider.EnvironmentProvider;
import com.github.thedeathlycow.thermoo.api.environment.provider.EnvironmentProviderType;
import com.github.thedeathlycow.thermoo.api.util.TemperatureRecord;
import com.github.thedeathlycow.thermoo.api.util.TemperatureUnit;
import com.minecraftindustrialcomplex.mic_climate.Compat;
import com.minecraftindustrialcomplex.mic_climate.MicClimate;
import com.minecraftindustrialcomplex.mic_climate.config.ClimateConfig;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@code mic_climate:unified} — the single producer of the pack's temperature.
 *
 * <p>A datapack attaches this to every overworld biome at priority 2000, above
 * anything Thermoo or another mod might define, and it answers with one number
 * assembled from:
 *
 * <ol>
 *   <li>Project Atmosphere's forecast, when that mod is installed and the
 *       config allows it;</li>
 *   <li>otherwise a biome-and-season estimate built to sit on the same scale
 *       ({@link BiomeSeasonFallback});</li>
 *   <li>plus Destroy's pollution warming, exactly once, so that no consumer
 *       downstream has to know pollution exists ({@link DestroyPollutionShift}).
 *       Project Atmosphere carries the warming itself
 *       ({@code pollution.projectAtmosphere}); where it is simulating a region
 *       the warming reaches this value at its pace through its reading, and
 *       elsewhere (only passive updates, no player, never simulated) a change
 *       applies at once: the hybrid rule of
 *       {@code atmosphere.ProjectAtmosphereBase#pollutionCorrection}.</li>
 * </ol>
 *
 * <p>Steps 1 and 3 touch optional mods, so each is reached only behind a
 * {@link Compat#isLoaded(String)} guard and lives in its own class; this one
 * imports nothing but Thermoo and Minecraft.
 */
public final class UnifiedEnvironmentProvider implements EnvironmentProvider {

    public static final MapCodec<UnifiedEnvironmentProvider> CODEC = RecordCodecBuilder.mapCodec(
            instance -> instance.group(
                    Codec.BOOL
                            .optionalFieldOf("pollution", true)
                            .forGetter(UnifiedEnvironmentProvider::pollution)
            ).apply(instance, UnifiedEnvironmentProvider::new)
    );

    public static final EnvironmentProviderType<UnifiedEnvironmentProvider> TYPE =
            new EnvironmentProviderType<>(CODEC);

    private static final AtomicBoolean WARNED_NO_ATMOSPHERE = new AtomicBoolean();
    private static final AtomicBoolean WARNED_ATMOSPHERE_MODE = new AtomicBoolean();

    private final boolean pollution;

    public UnifiedEnvironmentProvider(boolean pollution) {
        this.pollution = pollution;
    }

    /** Whether this instance folds Destroy's pollution into its answer. */
    public boolean pollution() {
        return this.pollution;
    }

    @Override
    public void buildCurrentComponents(
            Level world,
            BlockPos pos,
            Holder<Biome> biome,
            DataComponentMap.Builder builder
    ) {
        ClimateConfig.Source source = ClimateConfig.source();

        // Deep Time worlds: the planet's own climate is the base, Project Atmosphere adds weather.
        if (ClimateConfig.deepTimeEnabled() && Compat.isLoaded(Compat.DEEP_TIME)) {
            DeepTimeTemperature dt = deepTime(world, pos, source);
            if (dt != null) {
                // Destroy's warming, once. With pollution inside Project Atmosphere the region's seasonal
                // base carries the whole shift and its live temperature only what it has taken up, so the
                // weather anomaly (live minus base) holds "taken up - shift": adding the shift leaves
                // Project Atmosphere's pace, and the hybrid correction makes it at once where Project
                // Atmosphere is not simulating the region. No anomaly (unsimulated region): just the shift.
                float celsius = dt.celsius();
                if (this.pollution && Compat.isLoaded(Compat.DESTROY)) {
                    warnRetiredMode();
                    celsius += DestroyPollutionShift.shift(world);
                    if (dt.withWeather() && Compat.isLoaded(Compat.PROJECT_ATMOSPHERE)) {
                        Float correction = ProjectAtmosphereSource.pollutionCorrection(world, pos);
                        if (correction != null)
                            celsius += correction;
                    }
                }
                builder.set(EnvironmentComponentTypes.TEMPERATURE, new TemperatureRecord(celsius, TemperatureUnit.CELSIUS));
                return;
            }
        }

        Float celsius = null;
        Float pollutionCorrection = null;
        if (source != ClimateConfig.Source.THERMOO && Compat.isLoaded(Compat.PROJECT_ATMOSPHERE)) {
            // Asked before the reading: which of Project Atmosphere's paths answers depends on whether
            // the region exists yet, and reading it can create the region.
            pollutionCorrection = ProjectAtmosphereSource.pollutionCorrection(world, pos);
            celsius = ProjectAtmosphereSource.celsius(world, pos, biome);
            if (celsius == null)
                pollutionCorrection = null;
        }

        if (celsius == null && source == ClimateConfig.Source.PROJECT_ATMOSPHERE
                && WARNED_NO_ATMOSPHERE.compareAndSet(false, true)) {
            MicClimate.LOGGER.warn(
                    "mic_climate source is set to PROJECT_ATMOSPHERE, but Project Atmosphere did not "
                            + "answer (is it installed?). Falling back to the biome and season estimate."
            );
        }

        if (celsius == null)
            celsius = BiomeSeasonFallback.celsius(world, pos, biome);

        if (this.pollution && Compat.isLoaded(Compat.DESTROY)) {
            warnRetiredMode();
            // Project Atmosphere's reading with the hybrid correction, or the whole shift where Project
            // Atmosphere carries none of it (pollution part off, not installed, or the biome fallback).
            celsius += pollutionCorrection != null ? pollutionCorrection : DestroyPollutionShift.shift(world);
        }

        builder.set(
                EnvironmentComponentTypes.TEMPERATURE,
                new TemperatureRecord(celsius, TemperatureUnit.CELSIUS)
        );
    }

    /**
     * The temperature in a Deep Time world, before pollution.
     *
     * @param celsius     Deep Time's monthly mean at the block (its height and the season's date)
     *                    plus Project Atmosphere's weather anomaly when that was added
     * @param base        Deep Time's part alone
     * @param anomaly     the anomaly added (0 when none), after the cap
     * @param withWeather whether Project Atmosphere's anomaly (and with it any pollution pushed into
     *                    it) is in {@code celsius}
     */
    public record DeepTimeTemperature(float celsius, float base, float anomaly, boolean withWeather) {}

    /**
     * Deep Time's climate plus Project Atmosphere's weather, or {@code null} when the level is not a
     * Deep Time world with a simulated climate (then the usual sources answer).
     *
     * <p>Why an anomaly and not Project Atmosphere's own number: Project Atmosphere derives its
     * regional base from biome base temperatures (through a per-biome table that has no entries for
     * Terralith's biomes, which then count as 0 &deg;C), averages it over 2000-block regions (about
     * a quarter of a 16k Deep Time planet's pole-to-pole height) and applies one global season offset
     * to both hemispheres; none of that can be corrected through its API (its base is final, and
     * written temperatures erode within a few updates). So the base comes from the planet and only
     * the departure of Project Atmosphere's live weather from its own base is kept. The season comes
     * from Deep Time's monthly curve at the Serene Seasons date ({@link YearClock}), so Project
     * Atmosphere's season offset cancels out of the anomaly rather than being counted twice.
     */
    @org.jetbrains.annotations.Nullable
    public static DeepTimeTemperature deepTime(Level world, BlockPos pos, ClimateConfig.Source source) {
        YearClock.Date date = YearClock.now(world);
        DeepTimeSource.Reading reading = DeepTimeSource.read(world, pos, date.yearFraction());
        if (reading == null)
            return null;
        Float anomaly = null;
        if (ClimateConfig.deepTimeWeather() && source != ClimateConfig.Source.THERMOO
                && Compat.isLoaded(Compat.PROJECT_ATMOSPHERE) && world instanceof net.minecraft.server.level.ServerLevel server)
            anomaly = ProjectAtmosphereSource.weatherAnomaly(server, pos);
        float capped = anomaly == null ? 0f : cap(anomaly, ClimateConfig.deepTimeMaxAnomaly());
        return new DeepTimeTemperature(reading.celsius() + capped, reading.celsius(), capped, anomaly != null);
    }

    /** {@code anomaly} limited to ±{@code max}. */
    public static float cap(float anomaly, float max) {
        return Math.max(-max, Math.min(max, anomaly));
    }

    /**
     * {@code pollution.mode = ATMOSPHERE} was retired on 2026-09-30: it pushed the warming into
     * Project Atmosphere's regional state through its public API, where Project Atmosphere eroded it.
     * The key still loads and now means {@code MODIFIER}; Project Atmosphere gets the warming through
     * {@code pollution.projectAtmosphere} instead. Says so once.
     */
    private static void warnRetiredMode() {
        if (ClimateConfig.pollutionMode() == ClimateConfig.PollutionMode.ATMOSPHERE
                && WARNED_ATMOSPHERE_MODE.compareAndSet(false, true)) {
            MicClimate.LOGGER.warn(
                    "mic_climate pollution.mode = ATMOSPHERE is retired and now behaves as MODIFIER: Destroy's "
                            + "warming reaches Project Atmosphere through pollution.projectAtmosphere instead.");
        }
    }

    @Override
    public EnvironmentProviderType<UnifiedEnvironmentProvider> getType() {
        return TYPE;
    }
}
