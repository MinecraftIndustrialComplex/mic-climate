package com.minecraftindustrialcomplex.mic_climate.seasons;

import com.minecraftindustrialcomplex.mic_climate.Compat;
import com.minecraftindustrialcomplex.mic_climate.config.ClimateConfig;
import com.minecraftindustrialcomplex.mic_climate.provider.DeepTimeSource;
import net.minecraft.world.level.Level;

import java.lang.ref.WeakReference;

/**
 * Where on a Deep Time planet a block is: its latitude, and the season's strength there. The gate
 * every hemisphere-season hook goes through, on the server and on clients.
 *
 * <p>Latitude is Deep Time's, from its planet API ({@link DeepTimeSource#latitudeDeg}), which reads
 * the world as the sphere it stands for whatever map lays it out in blocks: nothing here knows which
 * projection a world uses, where its seams, edges or faces are, or how latitude depends on x and z.
 * A level is a planet only when Deep Time knows it (the server's generator, or on a client the
 * planet info the server sent), so worlds Deep Time did not generate, other dimensions and a client
 * before the info arrives all answer NaN, as does a position the map leaves off the planet, and
 * every hook then hands the mod it patched its own value back.
 *
 * <p>Cheap enough for the hottest callers (colour resolvers on mesh and Distant Horizons threads,
 * precipitation per column per frame): Deep Time's answer allocates a little, so each thread keeps a
 * small cache of the columns it asked about last; a hit is a hash, a compare and a read.
 */
public final class PlanetLatitude {

    /** Cached {@code Compat.isLoaded("deeptime")}: null until first asked (after mod loading). */
    private static volatile Boolean deepTime;

    /** Columns remembered per thread (a power of two). */
    private static final int CACHE_SIZE = 1024;

    private PlanetLatitude() {}

    /** One thread's last answers for one level: direct-mapped, finite answers only (NaN marks an empty slot). */
    private static final class Cache {
        WeakReference<Level> level = new WeakReference<>(null);
        final long[] keys = new long[CACHE_SIZE];
        final double[] values = new double[CACHE_SIZE];

        void clear() {
            java.util.Arrays.fill(values, Double.NaN);
        }
    }

    private static final ThreadLocal<Cache> CACHE = ThreadLocal.withInitial(() -> {
        Cache c = new Cache();
        c.clear();
        return c;
    });

    /**
     * Latitude in degrees (north positive, clamped to ±90) of block column (x, z) in {@code level}, or
     * NaN when the hemisphere seasons do not apply there: not a Deep Time planet, or switched off
     * ({@code deepTime.hemisphereSeasons} in the server config), or off the planet.
     */
    public static double latitude(Level level, double x, double z) {
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
        Cache cache = CACHE.get();
        if (cache.level.get() != level) {
            cache.level = new WeakReference<>(level);
            cache.clear();
        }
        long bx = (long) Math.floor(x), bz = (long) Math.floor(z);
        long key = (bx << 32) ^ (bz & 0xFFFFFFFFL);
        int slot = (int) ((key * 0x9E3779B97F4A7C15L) >>> 54) & (CACHE_SIZE - 1);
        double cached = cache.values[slot];
        if (cache.keys[slot] == key && !Double.isNaN(cached))
            return cached;
        double lat = DeepTimeSource.latitudeDeg(level, bx + 0.5, bz + 0.5);
        if (Double.isNaN(lat))
            return Double.NaN;
        lat = clamp(lat);
        cache.keys[slot] = key;
        cache.values[slot] = lat;
        return lat;
    }

    /** The season's strength at {@code latitudeDeg} with the configured full-season latitude. */
    public static double strength(double latitudeDeg) {
        return LatitudeSeasons.strength(latitudeDeg, ClimateConfig.fullSeasonLatitude());
    }

    /**
     * Whether the hemisphere seasons are switched on at all (not whether this is a planet): the
     * server config's {@code deepTime.hemisphereSeasons}, which clients receive when they join.
     */
    public static boolean switchedOn() {
        return ClimateConfig.hemisphereSeasons();
    }

    /**
     * The radius in metres of the planet {@code level} is, or 0 when it is not one; for the melt
     * pass's gate, the probe and the client's re-mesh trigger. The stand-in planet ({@link
     * ClimateConfig.Test.TestPlanet}) counts as a sphere of C / 2&pi; blocks of a metre each.
     */
    public static double radiusMetres(Level level) {
        if (level == null)
            return 0.0;
        ClimateConfig.Test.TestPlanet test = ClimateConfig.Test.seasonTestPlanet();
        if (test != null)
            return !level.isClientSide() && level.dimension() == Level.OVERWORLD && test.circumference() > 0
                    ? test.circumference() / (2.0 * Math.PI) : 0.0;
        return deepTimeLoaded() ? DeepTimeSource.planetRadiusMetres(level) : 0.0;
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
