package com.minecraftindustrialcomplex.mic_climate.seasons;

import sereneseasons.api.season.Season;

/**
 * Serene Seasons' calendar at a latitude: the arithmetic behind the hemisphere seasons, with no
 * Minecraft state in it.
 *
 * <p>Serene Seasons keeps one season per level. On a Deep Time planet that is the season at the
 * mid-northern latitudes. Ben's decision (2026-09-30, "full latitude damping") for every other
 * place:
 *
 * <ul>
 *   <li><b>The south is half a year out.</b> South of the equator the calendar is shifted by half
 *       a cycle. That is exactly six of Serene Seasons' twelve sub-seasons and three of its six
 *       tropical seasons, so the local boundaries fall on the global ones and nothing changes at a
 *       moment Serene Seasons does not already handle.</li>
 *   <li><b>Seasons fade toward the equator.</b> The season's strength is {@link #strength}: 1 at
 *       and beyond {@code fullLatitude} degrees, 0 at the equator, and a smoothstep in between, so
 *       it has no kink anywhere and reaches no seasons at the equator. Because the strength is 0
 *       there, the half-year jump across the equator is invisible.</li>
 * </ul>
 *
 * <p>"No seasons" means Mid Summer, which is what Serene Seasons itself treats as neutral: Mid
 * Summer's grass and foliage overlays are white (the biome's own colour), its birch colour is
 * vanilla's, its biome temperature adjustment is 0 by default, and its tropical biomes grow the
 * summer crops all year. A faded season is therefore pulled toward Mid Summer in two ways, one
 * for continuous quantities and one for decisions:
 *
 * <ul>
 *   <li><b>Blended</b> ({@link #lerp}, {@link #lerpRgb}): colours and the biome temperature are the
 *       shifted season's value blended with the Mid Summer value by the strength. They vary
 *       smoothly with latitude, so a snow line follows the biomes' temperatures rather than a line
 *       of constant z.</li>
 *   <li><b>Discrete</b> ({@link #damp}): where Serene Seasons, Serene Seasons Plus or Project
 *       Atmosphere must pick one sub-season (crop fertility, the melt rate, the season sensor,
 *       Serene Seasons Plus's snow policy, Project Atmosphere's regional season), the shifted
 *       sub-season's distance from Mid Summer is scaled by the strength and rounded. With the
 *       default 45 degrees, winter as Serene Seasons sees it is found only poleward of about 33
 *       degrees, Mid Summer all year within about 8 degrees of the equator.</li>
 * </ul>
 */
public final class LatitudeSeasons {

    /** The latitude, in degrees, where the seasons reach full strength when the config says nothing. */
    public static final double DEFAULT_FULL_LATITUDE = 45.0;

    /** Sub-seasons in Serene Seasons' cycle. */
    public static final int SUB_SEASONS = 12;

    /** Tropical seasons in Serene Seasons' cycle. */
    public static final int TROPICAL_SEASONS = 6;

    /** The sub-season a faded season is pulled toward. */
    public static final Season.SubSeason NEUTRAL = Season.SubSeason.MID_SUMMER;

    /**
     * The strength below which the discrete tropical wet/dry season is dropped altogether (Project
     * Atmosphere's moisture stage): about 22.5 degrees with the default full latitude.
     */
    public static final double TROPICAL_CUTOFF = 0.5;

    private LatitudeSeasons() {}

    /**
     * Season strength at {@code latitudeDeg}: 0 at the equator, 1 at and beyond
     * {@code fullLatitudeDeg}, smoothstep between. A non-positive full latitude means full strength
     * everywhere.
     */
    public static double strength(double latitudeDeg, double fullLatitudeDeg) {
        if (!(fullLatitudeDeg > 0))
            return 1.0;
        double t = Math.min(1.0, Math.abs(latitudeDeg) / fullLatitudeDeg);
        return t * t * (3.0 - 2.0 * t);
    }

    /** True south of the equator, where the calendar runs half a year out. */
    public static boolean south(double latitudeDeg) {
        return latitudeDeg < 0;
    }

    /** True where Serene Seasons' own season applies unchanged: north, at full strength. */
    public static boolean unchanged(double latitudeDeg, double strength) {
        return !south(latitudeDeg) && strength >= 1.0;
    }

    /** A sub-season index (0 = Early Spring) shifted half a year in the south. */
    public static int shift(int subSeason, boolean south) {
        return south ? (subSeason + SUB_SEASONS / 2) % SUB_SEASONS : subSeason;
    }

    /** A tropical season index (0 = Early Dry) shifted half a year in the south. */
    public static int shiftTropical(int tropicalSeason, boolean south) {
        return south ? (tropicalSeason + TROPICAL_SEASONS / 2) % TROPICAL_SEASONS : tropicalSeason;
    }

    /**
     * A sub-season index pulled toward Mid Summer by {@code strength}: its signed distance from Mid
     * Summer (-6..5 sub-seasons) scaled and rounded. Strength 1 keeps it, 0 gives Mid Summer.
     */
    public static int damp(int subSeason, double strength) {
        if (strength >= 1.0)
            return subSeason;
        int neutral = NEUTRAL.ordinal();
        int offset = Math.floorMod(subSeason - neutral + SUB_SEASONS / 2, SUB_SEASONS) - SUB_SEASONS / 2;
        int damped = (int) Math.round(Math.max(0.0, strength) * offset);
        return Math.floorMod(neutral + damped, SUB_SEASONS);
    }

    /** The discrete local sub-season: shifted for the hemisphere, then pulled toward Mid Summer. */
    public static Season.SubSeason discrete(Season.SubSeason global, double latitudeDeg, double strength) {
        return Season.SubSeason.VALUES[damp(shift(global.ordinal(), south(latitudeDeg)), strength)];
    }

    /** The shifted local sub-season, not faded (the blended quantities fade it themselves). */
    public static Season.SubSeason shifted(Season.SubSeason global, double latitudeDeg) {
        return Season.SubSeason.VALUES[shift(global.ordinal(), south(latitudeDeg))];
    }

    /** The shifted local tropical season. */
    public static Season.TropicalSeason shifted(Season.TropicalSeason global, double latitudeDeg) {
        return Season.TropicalSeason.VALUES[shiftTropical(global.ordinal(), south(latitudeDeg))];
    }

    /** {@code neutral} blended toward {@code seasonal} by {@code strength}. */
    public static float lerp(float neutral, float seasonal, double strength) {
        return (float) (neutral + (seasonal - neutral) * strength);
    }

    /** Two packed colours blended channel by channel (alpha included), {@code strength} toward {@code seasonal}. */
    public static int lerpRgb(int neutral, int seasonal, double strength) {
        if (strength >= 1.0)
            return seasonal;
        if (strength <= 0.0)
            return neutral;
        int out = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            int a = (neutral >>> shift) & 0xFF;
            int b = (seasonal >>> shift) & 0xFF;
            int c = (int) Math.round(a + (b - a) * strength);
            out |= (c & 0xFF) << shift;
        }
        return out;
    }
}
