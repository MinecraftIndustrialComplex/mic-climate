package com.minecraftindustrialcomplex.mic_climate.seasons;

import com.minecraftindustrialcomplex.mic_climate.Compat;
import com.minecraftindustrialcomplex.mic_climate.config.ClimateConfig;
import com.minecraftindustrialcomplex.mic_climate.provider.DeepTimeSource;
import net.minecraft.world.level.Level;

/**
 * Where on a Deep Time planet a block is: its latitude, and the season's strength there. The gate
 * every hemisphere-season hook goes through, on the server and on clients.
 *
 * <p>Latitude is Deep Time's: φ = −z · 360° / C, north positive, with C the planet's circumference
 * from Deep Time's climate API (version 2; the server's generator, or on a client the planet info
 * the server sent). A level is a planet only when that circumference is positive, so worlds Deep
 * Time did not generate, other dimensions, a client before the info arrives and a Deep Time without
 * API 2 all answer NaN, and every hook then hands the mod it patched its own value back.
 *
 * <p>Cheap enough for the hottest callers (colour resolvers on mesh and Distant Horizons threads,
 * precipitation per column per frame): a couple of volatile reads, cached config values and, on a
 * planet, one multiplication; no allocation.
 */
public final class PlanetLatitude {

    /** Cached {@code Compat.isLoaded("deeptime")}: null until first asked (after mod loading). */
    private static volatile Boolean deepTime;

    private PlanetLatitude() {}

    /**
     * Latitude in degrees (north positive, clamped to ±90) of block {@code z} in {@code level}, or
     * NaN when the hemisphere seasons do not apply there: not a Deep Time planet, or switched off
     * ({@code deepTime.enabled}, {@code deepTime.hemisphereSeasons}).
     */
    public static double latitude(Level level, double z) {
        if (level == null || !switchedOn())
            return Double.NaN;
        ClimateConfig.Test.TestPlanet test = ClimateConfig.Test.seasonTestPlanet();
        if (test != null) {
            if (level.isClientSide() || level.dimension() != Level.OVERWORLD || test.circumference() <= 0)
                return Double.NaN;
            return clamp(-(z - test.equatorZ()) * 360.0 / test.circumference());
        }
        if (!deepTimeLoaded())
            return Double.NaN;
        int c = DeepTimeSource.circumferenceBlocks(level);
        return c > 0 ? clamp(-z * 360.0 / c) : Double.NaN;
    }

    /** The season's strength at {@code latitudeDeg} with the configured full-season latitude. */
    public static double strength(double latitudeDeg) {
        return LatitudeSeasons.strength(latitudeDeg, ClimateConfig.fullSeasonLatitude());
    }

    /** Whether the hemisphere seasons are switched on at all (not whether this is a planet). */
    public static boolean switchedOn() {
        return ClimateConfig.deepTimeEnabled() && ClimateConfig.hemisphereSeasons();
    }

    /** The planet's circumference for {@code level}, or 0; for the probe and the client's re-mesh trigger. */
    public static int circumference(Level level) {
        if (level == null)
            return 0;
        ClimateConfig.Test.TestPlanet test = ClimateConfig.Test.seasonTestPlanet();
        if (test != null)
            return !level.isClientSide() && level.dimension() == Level.OVERWORLD ? test.circumference() : 0;
        return deepTimeLoaded() ? DeepTimeSource.circumferenceBlocks(level) : 0;
    }

    private static boolean deepTimeLoaded() {
        Boolean loaded = deepTime;
        if (loaded == null) {
            if (net.neoforged.fml.ModList.get() == null)
                return false; // too early to know; ask again next time
            loaded = Compat.isLoaded(Compat.DEEP_TIME);
            deepTime = loaded;
        }
        return loaded;
    }

    private static double clamp(double latitudeDeg) {
        return Math.max(-90.0, Math.min(90.0, latitudeDeg));
    }
}
