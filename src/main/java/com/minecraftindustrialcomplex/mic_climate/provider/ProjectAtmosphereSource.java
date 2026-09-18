package com.minecraftindustrialcomplex.mic_climate.provider;

import com.minecraftindustrialcomplex.mic_climate.MicClimate;
import net.Gabou.projectatmosphere.api.AtmoApi;
import net.Gabou.projectatmosphere.api.WeatherSnapshot;
import net.Gabou.projectatmosphere.client.BiomeClientTemperatureCache;
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
