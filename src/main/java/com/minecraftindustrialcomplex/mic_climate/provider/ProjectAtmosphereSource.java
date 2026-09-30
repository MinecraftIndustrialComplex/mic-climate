package com.minecraftindustrialcomplex.mic_climate.provider;

import com.minecraftindustrialcomplex.mic_climate.MicClimate;
import net.Gabou.projectatmosphere.api.AtmoApi;
import net.Gabou.projectatmosphere.api.WeatherSnapshot;
import net.Gabou.projectatmosphere.client.BiomeClientTemperatureCache;
import net.Gabou.projectatmosphere.modules.atmosphere.AtmosphericStateRegistry;
import net.Gabou.projectatmosphere.modules.atmosphere.RegionAtmosphereState;
import net.Gabou.projectatmosphere.util.RegionInstanceKey;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The only class in this mod that imports Project Atmosphere.
 *
 * <p>It is loaded lazily from {@link UnifiedEnvironmentProvider}, behind a
 * {@code Compat.isLoaded("projectatmosphere")} guard, so on a pack without
 * Project Atmosphere the JVM never resolves these imports.
 *
 * <p>Only Project Atmosphere's public API is used, and nothing in this mod ever
 * mixes into it — its licence allows compatible addons but forbids modified
 * builds.
 *
 * <p>The two sides read different things. Project Atmosphere simulates on the
 * server and syncs a per-biome daily forecast curve to clients, so the server
 * asks for the real regional state and the client interpolates its cached
 * curve. The client value can therefore lag the server's by the sync interval;
 * that is fine for tooltips and rendering, and the server value is what drives
 * physics.
 */
public final class ProjectAtmosphereSource {

    /**
     * What {@link BiomeClientTemperatureCache#getTemperature} returns when it
     * has no forecast for a biome — it has no "do you know this biome?" query,
     * so this constant is the only signal available. A genuine forecast landing
     * on exactly 0.5 &deg;C would be misread as "unknown" and fall through to
     * the biome estimate for that tick, which is harmless.
     */
    private static final float CLIENT_CACHE_MISS = 0.5f;

    /** Anything outside this is a bug or an uninitialised field, not weather. */
    private static final float MIN_SANE_CELSIUS = -150f;
    private static final float MAX_SANE_CELSIUS = 150f;

    private static final AtomicBoolean LOGGED_FAILURE = new AtomicBoolean();

    private ProjectAtmosphereSource() {}

    /**
     * Project Atmosphere's temperature at a position, or {@code null} when it
     * cannot answer and the caller should fall back.
     */
    @Nullable
    public static Float celsius(Level level, BlockPos pos, @Nullable Holder<Biome> biome) {
        try {
            if (level instanceof ServerLevel serverLevel) {
                // getCurrentWeather resolves the region's live atmospheric
                // state if there is one and samples the forecast if there is
                // not, so it always produces a value; only a null level or pos
                // gives the all-zero snapshot, and both are excluded here.
                WeatherSnapshot snapshot = AtmoApi.getInstance().getCurrentWeather(serverLevel, pos);
                return snapshot == null ? null : sane(snapshot.temperatureC());
            }

            ResourceLocation biomeId = biomeId(biome);
            if (biomeId == null)
                return null;

            float temperature = BiomeClientTemperatureCache.getTemperature(biomeId, level);
            if (temperature == CLIENT_CACHE_MISS)
                return null;

            return sane(temperature);
        } catch (Throwable t) {
            if (LOGGED_FAILURE.compareAndSet(false, true))
                MicClimate.LOGGER.debug("Project Atmosphere temperature lookup failed; using the fallback", t);
            return null;
        }
    }

    /**
     * Project Atmosphere's weather at a position as an <em>anomaly</em>: its live regional
     * temperature minus the region's own effective base (the base it derived from the biomes plus
     * its season offset), or {@code null} when there is no live region there.
     *
     * <p>This is the part of Project Atmosphere's temperature that is weather rather than climate:
     * the day/night swing (the region is pulled between its baseline minimum and maximum by
     * sunlight), rain and cloud cooling, the day-to-day forecast and whatever neighbouring regions
     * advect in. It is what a Deep Time world adds on top of the planet's own climate, because the
     * absolute value is built from biome base temperatures, over 2000-block regions, and does not
     * know the planet (see {@code UnifiedEnvironmentProvider}). Pollution pushed into the region in
     * {@code pollution.mode = ATMOSPHERE} shows up here too, since it moves the live value and not
     * the base.
     *
     * <p>Server side, overworld only: Project Atmosphere simulates the overworld and its region keys
     * have no dimension, so any other dimension would read the overworld's weather at the same x/z.
     * {@code AtmosphericStateRegistry} and {@code RegionAtmosphereState} are public classes of Project
     * Atmosphere that this mod already reads and writes for pollution; nothing is mixed into it.
     */
    @Nullable
    public static Float weatherAnomaly(ServerLevel level, BlockPos pos) {
        try {
            if (level.dimension() != Level.OVERWORLD)
                return null;
            RegionAtmosphereState state = AtmosphericStateRegistry.getState(RegionInstanceKey.from(pos));
            if (state == null)
                return null;
            // A region Project Atmosphere created but has not simulated yet (it only simulates while
            // players are online) still holds its base exactly: its live value then carries none of
            // the season offset the effective base includes, so it has no weather to report.
            if (state.getTemperature() == state.getBaseTemperature())
                return null;
            float anomaly = state.getTemperature() - state.getEffectiveBaseTemperature();
            return Float.isFinite(anomaly) ? anomaly : null;
        } catch (Throwable t) {
            if (LOGGED_FAILURE.compareAndSet(false, true))
                MicClimate.LOGGER.debug("Project Atmosphere anomaly lookup failed; adding no weather", t);
            return null;
        }
    }

    /** The region's live and effective-base temperatures, for the probe; null without a live region. */
    @Nullable
    public static float[] regionTemperatures(ServerLevel level, BlockPos pos) {
        try {
            RegionAtmosphereState state = AtmosphericStateRegistry.getState(RegionInstanceKey.from(pos));
            if (state == null)
                return null;
            return new float[] {state.getTemperature(), state.getEffectiveBaseTemperature(), state.getBaseTemperature()};
        } catch (Throwable t) {
            return null;
        }
    }

    @Nullable
    private static ResourceLocation biomeId(@Nullable Holder<Biome> biome) {
        if (biome == null)
            return null;
        return biome.unwrapKey().map(ResourceKey::location).orElse(null);
    }

    @Nullable
    private static Float sane(float celsius) {
        if (!Float.isFinite(celsius) || celsius < MIN_SANE_CELSIUS || celsius > MAX_SANE_CELSIUS)
            return null;
        return celsius;
    }
}
