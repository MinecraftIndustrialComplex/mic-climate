package com.minecraftindustrialcomplex.mic_climate.provider;

import com.minecraftindustrialcomplex.deeptime.api.climate.ClimateSample;
import com.minecraftindustrialcomplex.deeptime.api.climate.DeepTimeClimate;
import com.minecraftindustrialcomplex.mic_climate.MicClimate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The only class in this mod that imports Deep Time.
 *
 * <p>Deep Time generates a world from a simulated planet, and its public climate API
 * ({@code com.minecraftindustrialcomplex.deeptime.api.climate}, API version 1) hands out that
 * planet's climate per block: twelve monthly mean temperatures at the block's height, precipitation
 * and the Köppen class. In a Deep Time world the biome is only a coarse band picked from that
 * climate, so the biome-derived temperature every other source here starts from would throw most of
 * it away (an ice-cap plateau at -45 &deg;C reads as "snowy", i.e. -20 &deg;C, and the southern
 * hemisphere gets northern seasons). This class reads the climate itself.
 *
 * <p>Loaded lazily from {@link UnifiedEnvironmentProvider} behind a
 * {@code Compat.isLoaded("deeptime")} guard, so without Deep Time the JVM never resolves these
 * imports. Server side only: Deep Time keeps its world data on the server, and on a client level
 * (or any level Deep Time did not generate) this answers {@code null}.
 */
public final class DeepTimeSource {

    private static final AtomicBoolean LOGGED_FAILURE = new AtomicBoolean();

    private DeepTimeSource() {}

    /**
     * What Deep Time says about one block.
     *
     * @param celsius       the mean temperature at {@code yearFraction} (the annual mean when it is NaN)
     * @param meanC         annual mean at the block's height
     * @param warmestC      warmest month's mean
     * @param coldestC      coldest month's mean
     * @param month         the month {@code yearFraction} falls in, 0-11 (0 is about January), or -1
     * @param koppen        Köppen class ({@code sea} at sea)
     * @param precipMm      annual precipitation, mm
     * @param elevationM    height above the sea the lapse rate was applied over, m
     */
    public record Reading(float celsius, double meanC, double warmestC, double coldestC, int month, String koppen,
                          double precipMm, double elevationM) {}

    /** True for a server level Deep Time generated with a simulated climate. */
    public static boolean hasClimate(Level level) {
        try {
            return level instanceof ServerLevel && DeepTimeClimate.of(level).hasClimate();
        } catch (Throwable t) {
            logOnce(t);
            return false;
        }
    }

    /** Deep Time's climate at {@code pos} at year fraction {@code yearFraction} (NaN: the annual mean), or null. */
    @Nullable
    public static Reading read(Level level, BlockPos pos, double yearFraction) {
        try {
            if (!(level instanceof ServerLevel))
                return null;
            DeepTimeClimate climate = DeepTimeClimate.of(level);
            if (!climate.hasClimate())
                return null;
            ClimateSample s = climate.at(pos).orElse(null);
            if (s == null)
                return null;
            boolean dated = !Double.isNaN(yearFraction);
            double t = dated ? s.temperatureC(yearFraction) : s.meanC();
            int month = dated ? (int) Math.floor((yearFraction - Math.floor(yearFraction)) * DeepTimeClimate.MONTHS) : -1;
            if (!Double.isFinite(t))
                return null;
            return new Reading((float) t, s.meanC(), s.warmestMonthC(), s.coldestMonthC(), month, s.koppen(),
                    s.annualPrecipitationMm(), s.elevationMetres());
        } catch (Throwable t) {
            logOnce(t);
            return null;
        }
    }

    /**
     * Deep Time's mean temperature at the block at year fraction {@code yearFraction} (NaN: the
     * annual mean), &deg;C, or NaN without a reading. The allocation-light twin of {@link #read}
     * for the per-block hooks into Project Atmosphere ({@code atmosphere.ProjectAtmosphereBase}),
     * which run from its snow, freeze and rain checks.
     */
    public static float celsiusAt(Level level, BlockPos pos, double yearFraction) {
        try {
            DeepTimeClimate climate = DeepTimeClimate.of(level);
            ClimateSample s = climate.at(pos).orElse(null);
            if (s == null)
                return Float.NaN;
            double t = Double.isNaN(yearFraction) ? s.meanC() : s.temperatureC(yearFraction);
            return Double.isFinite(t) ? (float) t : Float.NaN;
        } catch (Throwable t) {
            logOnce(t);
            return Float.NaN;
        }
    }

    /**
     * The twelve monthly means at sea level averaged over a {@code grid} &times; {@code grid} lattice
     * covering the square {@code [minX, minX + size) x [minZ, minZ + size)}, or null without a
     * climate there. Project Atmosphere's region base is a sea-level figure averaged over the region
     * (its forecast samples every 64 blocks at sea level and applies no lapse rate), so this is the
     * Deep Time quantity that stands in for it.
     */
    @Nullable
    public static double[] regionMonthlyC(Level level, int minX, int minZ, int size, int grid) {
        try {
            DeepTimeClimate climate = DeepTimeClimate.of(level);
            if (!climate.hasClimate())
                return null;
            int y = climate.seaLevelY();
            double[] sum = new double[DeepTimeClimate.MONTHS];
            int n = 0;
            for (int i = 0; i < grid; i++) {
                for (int j = 0; j < grid; j++) {
                    int x = minX + (int) ((i + 0.5) * size / grid);
                    int z = minZ + (int) ((j + 0.5) * size / grid);
                    ClimateSample s = climate.at(x, y, z).orElse(null);
                    if (s == null)
                        continue;
                    double[] m = s.monthlyC();
                    for (int k = 0; k < sum.length; k++)
                        sum[k] += m[k];
                    n++;
                }
            }
            if (n == 0)
                return null;
            for (int k = 0; k < sum.length; k++)
                sum[k] /= n;
            return sum;
        } catch (Throwable t) {
            logOnce(t);
            return null;
        }
    }

    /**
     * Monthly means {@code v} at year fraction {@code f}: linear between the months' midpoints and
     * periodic, exactly as {@code ClimateSample.temperatureC}; NaN {@code f} gives the annual mean.
     */
    public static double atYearFraction(double[] v, double f) {
        if (Double.isNaN(f)) {
            double s = 0;
            for (double x : v)
                s += x;
            return s / v.length;
        }
        double u = (f - Math.floor(f)) * v.length - 0.5;
        double fu = Math.floor(u);
        double frac = u - fu;
        int m0 = Math.floorMod((int) fu, v.length);
        int m1 = (m0 + 1) % v.length;
        return v[m0] + (v[m1] - v[m0]) * frac;
    }

    /** One line for {@code /mic_climate probe}. */
    public static String describe(Reading r, double yearFraction, String calendar) {
        return String.format(Locale.ROOT,
                "%s, %s; annual %.2f, warmest month %.2f, coldest %.2f; %.0f mm/yr; %.0f m above the sea",
                r.koppen(),
                Double.isNaN(yearFraction) ? "no season calendar: annual mean"
                        : String.format(Locale.ROOT, "year %.3f (month %d) from %s", yearFraction, r.month(), calendar),
                r.meanC(), r.warmestC(), r.coldestC(), r.precipMm(), r.elevationM());
    }

    /** Deep Time's climate API version, for the log. */
    public static int apiVersion() {
        return DeepTimeClimate.API_VERSION;
    }

    private static void logOnce(Throwable t) {
        if (LOGGED_FAILURE.compareAndSet(false, true))
            MicClimate.LOGGER.warn("Deep Time climate lookup failed; using the other sources", t);
    }
}
