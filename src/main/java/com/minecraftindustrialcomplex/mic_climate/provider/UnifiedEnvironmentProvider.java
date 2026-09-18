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
 *   <li>plus Destroy's pollution warming, added exactly once, here at the
 *       source, so that no consumer downstream has to know pollution exists
 *       ({@link DestroyPollutionShift}) — unless {@code pollution.mode} is
 *       {@code ATMOSPHERE}, in which case it has already been pushed into
 *       Project Atmosphere and arrives through step 1.</li>
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

        Float celsius = null;
        if (source != ClimateConfig.Source.THERMOO && Compat.isLoaded(Compat.PROJECT_ATMOSPHERE))
            celsius = ProjectAtmosphereSource.celsius(world, pos, biome);

        if (celsius == null && source == ClimateConfig.Source.PROJECT_ATMOSPHERE
                && WARNED_NO_ATMOSPHERE.compareAndSet(false, true)) {
            MicClimate.LOGGER.warn(
                    "mic_climate source is set to PROJECT_ATMOSPHERE, but Project Atmosphere did not "
                            + "answer (is it installed?). Falling back to the biome and season estimate."
            );
        }

        if (celsius == null)
            celsius = BiomeSeasonFallback.celsius(world, pos, biome);

        if (this.pollution && Compat.isLoaded(Compat.DESTROY) && pollutionShiftApplies())
            celsius += DestroyPollutionShift.shift(world);

        builder.set(
                EnvironmentComponentTypes.TEMPERATURE,
                new TemperatureRecord(celsius, TemperatureUnit.CELSIUS)
        );
    }

    /**
     * Whether Destroy's warming is added here rather than somewhere else.
     *
     * <p>{@code pollution.mode = ATMOSPHERE} puts the shift into Project
     * Atmosphere's own regional state instead
     * ({@code atmosphere.PollutionAtmosphereEffect}), and step 1 above then
     * reads it back out of Project Atmosphere like any other weather — so
     * adding it here as well would count it twice.
     *
     * <p>With Project Atmosphere absent there is nothing to push into and
     * nothing to read back, so the mode falls back to {@code MODIFIER} with one
     * warning: silently dropping the pollution effect would be worse than
     * applying it in the old place.
     */
    private static boolean pollutionShiftApplies() {
        if (ClimateConfig.pollutionMode() != ClimateConfig.PollutionMode.ATMOSPHERE)
            return true;

        if (Compat.isLoaded(Compat.PROJECT_ATMOSPHERE))
            return false;

        if (WARNED_ATMOSPHERE_MODE.compareAndSet(false, true)) {
            MicClimate.LOGGER.warn(
                    "mic_climate pollution.mode = ATMOSPHERE needs Project Atmosphere, which is not "
                            + "installed; adding Destroy's pollution warming here instead, as MODIFIER would."
            );
        }
        return true;
    }

    @Override
    public EnvironmentProviderType<UnifiedEnvironmentProvider> getType() {
        return TYPE;
    }
}
