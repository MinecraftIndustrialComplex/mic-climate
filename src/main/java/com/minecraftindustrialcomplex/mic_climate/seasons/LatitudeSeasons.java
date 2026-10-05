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
 *   <li><b>The south is half a year out.</b> South of the equator the temperate calendar is shifted by
 *       half a cycle. That is exactly six of Serene Seasons' twelve sub-seasons (and three of its six
 *       tropical seasons, which {@link #shiftTropical} also uses), so the local boundaries fall on the
 *       global ones and nothing changes at a moment Serene Seasons does not already handle.</li>
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
 *
 * <p>Two things are exempt from the fading (Ben, 2026-09-30):
 *
 * <ul>
 *   <li><b>Crops grow year-round in the seasonless band</b> ({@link #seasonless}), where the discrete
 *       season never leaves Mid Summer: every crop counts as in season there, spring- and
 *       autumn-only ones too.</li>
 *   <li><b>The tropical wet/dry cycle</b> has its own strength ({@link #tropicalStrength}): none within
 *       5 degrees of the equator, full between 10 and 20 degrees, none beyond 25. Serene Seasons uses
 *       it for its tropical biomes' colours and Project Atmosphere for its tropical moisture stage.</li>
 * </ul>
 *
 * <p>And two decisions about the tropics (Ben, 2026-09-30, the second follow-up):
 *
 * <ul>
 *   <li><b>"Temperate seasons there."</b> Serene Seasons never gives its tropical biomes (jungles,
 *       savannas, deserts, badlands, ...) the temperate cycle, so beyond the wet/dry band a desert at
 *       40 degrees north would have no winter. They now follow the normal temperate seasons outside
 *       the band, cross-fading over 20 to 25 degrees ({@link #temperateWeight}): full wet/dry at 20,
 *       full temperate at 25 and beyond. Colours blend continuously; the decisions that need one rule
 *       (crops, precipitation, Project Atmosphere's stage) switch at the middle of the fade, 22.5
 *       degrees ({@link #temperateRule}, {@link #wetDryRule}).</li>
 *   <li><b>"Follow the sun."</b> Serene Seasons' own tropical calendar has its wet season from Early
 *       Winter to Late Spring, which is the southern tropics' wet season on Earth, and the northern
 *       tropics' dry one. Here the wet season is each hemisphere's summer half: the south keeps
 *       Serene Seasons' calendar, the north moves it half a cycle ({@link #shiftTropical}), so at 15
 *       degrees north it is wet from Early Summer to Late Autumn and dry from Early Winter to Late
 *       Spring, and the reverse at 15 degrees south.</li>
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
     * The strength at or below which the discrete season is Mid Summer all year (the damped offset of
     * every sub-season rounds to 0): the seasonless band, about 7.9 degrees either side of the equator
     * with the default full latitude.
     */
    public static final double SEASONLESS_STRENGTH = 1.0 / 12.0;

    /** Where the tropical wet/dry cycle starts, reaches full strength, starts to fade, and ends (degrees). */
    public static final double TROPICS_START = 5.0, TROPICS_FULL = 10.0, TROPICS_FADE = 20.0, TROPICS_END = 25.0;

    /**
     * The tropical strength at or above which Project Atmosphere's discrete tropical moisture stage
     * (wet or dry) applies: from 7.5 to 22.5 degrees.
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
        return smooth(Math.min(1.0, Math.abs(latitudeDeg) / fullLatitudeDeg));
    }

    /** True in the seasonless band, where every crop counts as in season. */
    public static boolean seasonless(double strength) {
        return strength <= SEASONLESS_STRENGTH;
    }

    /**
     * Strength of the tropical wet/dry cycle at {@code latitudeDeg}: 0 within {@link #TROPICS_START}
     * degrees of the equator and beyond {@link #TROPICS_END}, 1 from {@link #TROPICS_FULL} to
     * {@link #TROPICS_FADE}, smoothsteps between.
     */
    public static double tropicalStrength(double latitudeDeg) {
        double a = Math.abs(latitudeDeg);
        if (a <= TROPICS_START || a >= TROPICS_END)
            return 0.0;
        if (a < TROPICS_FULL)
            return smooth((a - TROPICS_START) / (TROPICS_FULL - TROPICS_START));
        if (a <= TROPICS_FADE)
            return 1.0;
        return smooth((TROPICS_END - a) / (TROPICS_END - TROPICS_FADE));
    }

    /**
     * How much of an SS-tropical biome's season is the temperate one at {@code latitudeDeg} ("Temperate
     * seasons there"): 0 up to {@link #TROPICS_FADE} degrees (the wet/dry band), a smoothstep to 1 at
     * {@link #TROPICS_END}, 1 beyond. The complement of {@link #tropicalStrength} beyond the fade, so a
     * tropical biome at 22.5 degrees is half wet/dry, half temperate.
     */
    public static double temperateWeight(double latitudeDeg) {
        double a = Math.abs(latitudeDeg);
        if (a <= TROPICS_FADE)
            return 0.0;
        if (a >= TROPICS_END)
            return 1.0;
        return smooth((a - TROPICS_FADE) / (TROPICS_END - TROPICS_FADE));
    }

    /**
     * Whether an SS-tropical biome follows the temperate seasons for the decisions that need one rule
     * (crops, precipitation, Project Atmosphere's moisture stage): beyond the middle of the 20 to 25
     * degree fade, 22.5 degrees.
     */
    public static boolean temperateRule(double latitudeDeg) {
        return temperateWeight(latitudeDeg) > 0.5;
    }

    /**
     * Whether Serene Seasons' tropical wet/dry rule applies to an SS-tropical biome for the decisions
     * that need one rule: where {@link #tropicalStrength} is at least {@link #TROPICAL_CUTOFF}, 7.5 to
     * 22.5 degrees. Nearer the equator there is no wet/dry cycle (the biome's own precipitation all
     * year), beyond it the temperate seasons ({@link #temperateRule}).
     */
    public static boolean wetDryRule(double latitudeDeg) {
        return tropicalStrength(latitudeDeg) >= TROPICAL_CUTOFF;
    }

    /**
     * An SS-tropical biome's grass, foliage or birch colour at one place: its wet/dry colour blended
     * from {@code original} by the tropical strength {@code t}, its temperate colour blended by the
     * temperate strength {@code w}, and the two cross-faded by {@link #temperateWeight} {@code x}.
     * {@code wetDry} is only read when {@code x < 1} and {@code temperate} only when {@code x > 0}.
     */
    public static int tropicalBiomeColour(int original, int wetDry, int temperate, double t, double w, double x) {
        int tropical = x >= 1.0 ? original : lerpRgb(original, wetDry, t);
        int temperateColour = x <= 0.0 ? original : lerpRgb(original, temperate, w);
        return lerpRgb(tropical, temperateColour, x);
    }

    private static double smooth(double t) {
        return t * t * (3.0 - 2.0 * t);
    }

    /** True south of the equator, where the calendar runs half a year out. */
    public static boolean south(double latitudeDeg) {
        return latitudeDeg < 0;
    }

    /**
     * True where Serene Seasons' own temperate season applies unchanged: north, at full strength. (Its
     * tropical calendar never does: the north's is moved, {@link #shiftTropical}.)
     */
    public static boolean unchanged(double latitudeDeg, double strength) {
        return !south(latitudeDeg) && strength >= 1.0;
    }

    /** A sub-season index (0 = Early Spring) shifted half a year in the south. */
    public static int shift(int subSeason, boolean south) {
        return south ? (subSeason + SUB_SEASONS / 2) % SUB_SEASONS : subSeason;
    }

    /**
     * A tropical season index (0 = Early Dry) as the hemisphere sees it ("Follow the sun"): the wet
     * season is the hemisphere's summer half. Serene Seasons' own tropical calendar is wet from
     * Early Winter to Late Spring, that is the southern summer and autumn, so the south keeps it and
     * the north is moved half a cycle (three of the six tropical seasons, exactly six sub-seasons, so
     * every boundary still falls on one of Serene Seasons' own).
     */
    public static int shiftTropical(int tropicalSeason, boolean south) {
        return south ? tropicalSeason : (tropicalSeason + TROPICAL_SEASONS / 2) % TROPICAL_SEASONS;
    }

    /** True in the wet half of Serene Seasons' tropical cycle (Early Wet, Mid Wet, Late Wet). */
    public static boolean wet(Season.TropicalSeason season) {
        return season.ordinal() >= Season.TropicalSeason.EARLY_WET.ordinal();
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
