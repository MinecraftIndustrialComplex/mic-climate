package com.minecraftindustrialcomplex.mic_climate.provider;

import com.github.thedeathlycow.thermoo.api.season.ThermooSeason;
import com.minecraftindustrialcomplex.mic_climate.Compat;
import net.minecraft.world.level.Level;

import java.util.Optional;

/**
 * Where in the year the world is, as a fraction of Deep Time's simulated year.
 *
 * <p>Deep Time's year has twelve equal months, month 0 being about January; its climate data then
 * gives every block its own seasons (southern summers in the northern winter, a monsoon where the
 * planet has one). Minecraft has no calendar of its own, so the date comes from the season mod:
 *
 * <ol>
 *   <li>Serene Seasons, to the tick: its cycle starts with Early Spring, read as the start of March
 *       (meteorological spring), so the cycle maps linearly onto the year from month 2;</li>
 *   <li>otherwise Thermoo's season (fed by Thermoo Patches or another season mod), as the middle
 *       month of the season;</li>
 *   <li>otherwise no date ({@code NaN}): the caller uses the annual mean.</li>
 * </ol>
 *
 * <p>Serene Seasons' seasons are global, so its own effects (leaf colours, crop seasons, its
 * snowfall shift) stay northern everywhere; only the temperature this mod reports follows the
 * hemisphere.
 */
public final class YearClock {

    /** Year fraction at the start of Serene Seasons' cycle (Early Spring = the start of March). */
    public static final double CYCLE_START = 2.0 / 12.0;

    private YearClock() {}

    /** A date and where it came from ({@code serene_seasons}, {@code thermoo}, {@code none}). */
    public record Date(double yearFraction, String source) {}

    public static Date now(Level level) {
        if (level == null)
            return new Date(Double.NaN, "none");
        if (Compat.isLoaded(Compat.SERENE_SEASONS)) {
            try {
                Double f = SereneSeasonsClock.yearFraction(level);
                if (f != null)
                    return new Date(f, "serene_seasons");
            } catch (Throwable ignored) {
                // An API change in Serene Seasons: fall through to Thermoo's coarser season.
            }
        }
        try {
            Optional<ThermooSeason> season = ThermooSeason.getCurrentSeason(level);
            if (season.isPresent()) {
                double f = fromThermooSeason(season.get());
                if (!Double.isNaN(f))
                    return new Date(f, "thermoo");
            }
        } catch (Throwable ignored) {
            // No season source at all.
        }
        return new Date(Double.NaN, "none");
    }

    /** Serene Seasons' cycle position onto Deep Time's year: tick 0 is the start of month 2. */
    public static double fromSeasonCycle(long cycleTicks, long cycleDuration) {
        if (cycleDuration <= 0)
            return Double.NaN;
        double f = CYCLE_START + (double) Math.floorMod(cycleTicks, cycleDuration) / (double) cycleDuration;
        return f - Math.floor(f);
    }

    /** A season's middle month (spring = April, ...); NaN for the tropical seasons. */
    public static double fromThermooSeason(ThermooSeason season) {
        return switch (season) {
            case SPRING -> 3.5 / 12.0;
            case SUMMER -> 6.5 / 12.0;
            case AUTUMN -> 9.5 / 12.0;
            case WINTER -> 0.5 / 12.0;
            case TROPICAL_DRY, TROPICAL_WET -> Double.NaN;
        };
    }
}
