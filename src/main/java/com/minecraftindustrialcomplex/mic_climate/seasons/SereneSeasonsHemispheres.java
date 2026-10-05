package com.minecraftindustrialcomplex.mic_climate.seasons;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.minecraftindustrialcomplex.mic_climate.MicClimate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.FoliageColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;
import sereneseasons.api.season.ISeasonColorProvider;
import sereneseasons.api.season.ISeasonState;
import sereneseasons.api.season.Season;
import sereneseasons.api.season.SeasonHelper;
import sereneseasons.config.SeasonsConfig;
import sereneseasons.init.ModConfig;
import sereneseasons.init.ModFertility;
import sereneseasons.init.ModTags;
import sereneseasons.util.SeasonColorUtil;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The runtime side of the hemisphere-season mixins into Serene Seasons and Serene Seasons Plus
 * ({@code mixin.seasons}): what each of their decision points should see at a position on a Deep
 * Time planet ({@link LatitudeSeasons}, {@link PlanetLatitude}).
 *
 * <p>Every entry point hands back exactly what the patched mod computed when the position is not
 * on a Deep Time planet, when the seasons are switched off, north of the full-season latitude
 * (where the level's season is the local one), or on any exception (logged once). Serene Seasons'
 * own code does the work in every case: these only choose which sub-season it works with, or
 * blend two of its own answers.
 *
 * <p><b>Its tropical rule.</b> Serene Seasons treats the biomes in its {@code tropical_biomes} tag
 * (jungles, savannas, deserts, badlands, ...) by a separate rule: no temperature shift, a wet/dry
 * calendar instead of the temperate one for colours and for whether it rains, summer crops only. On a
 * planet those biomes follow the wet/dry rule only inside the wet/dry band and the temperate seasons
 * beyond it ("Temperate seasons there", {@link LatitudeSeasons#temperateWeight}), and the wet season is
 * each hemisphere's summer half ("Follow the sun", {@link LatitudeSeasons#shiftTropical}). For the
 * decisions Serene Seasons makes by reading its tag, a thread-local {@link TropicalRule} says what it
 * should see while it decides ({@link #tropicalTag}, {@link #precipitationState}); it still does the
 * deciding.
 *
 * <p>Serene Seasons is All Rights Reserved; nothing of it is copied here. The mixins name its
 * classes and methods, and this class calls its public API and public static helpers.
 */
public final class SereneSeasonsHemispheres {

    private static final AtomicBoolean LOGGED_FAILURE = new AtomicBoolean();

    /**
     * The chunk the melt pass last handled, so the level's remaining melt rolls for it are skipped
     * ({@link #melt}). Server thread only; reset at the start of every pass.
     */
    @Nullable
    private static LevelChunk lastMeltChunk;

    /**
     * The season {@link #yearRoundCrop} is asking Serene Seasons about, on this thread, while it asks;
     * {@link #discreteState} answers it. Null otherwise.
     */
    private static final ThreadLocal<Season> ASKING = new ThreadLocal<>();

    /**
     * What Serene Seasons' tropical rule should see at the position it is deciding for, on this thread,
     * while it decides; null (the default) leaves it alone.
     */
    private enum TropicalRule {
        /** The biome is not tropical here: its tag reads false (the temperate rule, or no wet/dry cycle). */
        OFF,
        /** Tropical, in the wet/dry band of the northern hemisphere: the local tropical season. */
        NORTH,
        /** Tropical, in the wet/dry band of the southern hemisphere. */
        SOUTH
    }

    private static final ThreadLocal<TropicalRule> TROPICAL = new ThreadLocal<>();

    private SereneSeasonsHemispheres() {}

    // ------------------------------------------------------------------
    // Serene Seasons: temperature, fertility, sensor, birch.
    // ------------------------------------------------------------------

    /**
     * Serene Seasons' seasonal biome temperature at {@code pos} ({@code SeasonHooks.getBiomeTemperature}):
     * its own {@code getBiomeTemperatureInSeason} for the local (shifted) sub-season, blended toward
     * its value for Mid Summer by the season's strength. Everything that decides snow, ice, rain or
     * snow and melting through Serene Seasons reads this.
     *
     * <p>Serene Seasons gives its tropical biomes no temperature shift. Beyond the wet/dry band they
     * follow the temperate seasons, cross-fading from the unshifted value (20 degrees) to the
     * temperate one (25): asked again under {@link TropicalRule#OFF}, its tropical rule reads false.
     */
    public static float biomeTemperature(Level level, Season.SubSeason global, Holder<Biome> biome, BlockPos pos,
                                         Operation<Float> inSeason) {
        double lat;
        try {
            lat = pos == null ? Double.NaN : PlanetLatitude.latitude(level, pos.getX(), pos.getZ());
        } catch (Throwable t) {
            logOnce(t);
            lat = Double.NaN;
        }
        if (Double.isNaN(lat))
            return inSeason.call(global, biome, pos);
        double w = PlanetLatitude.strength(lat);
        double x = LatitudeSeasons.temperateWeight(lat);
        if (x > 0.0 && biome.is(ModTags.Biomes.TROPICAL_BIOMES)) {
            float base = inSeason.call(global, biome, pos);
            TropicalRule before = TROPICAL.get();
            TROPICAL.set(TropicalRule.OFF);
            float temperate;
            try {
                temperate = seasonalTemperature(global, lat, w, biome, pos, inSeason);
            } finally {
                restore(before);
            }
            return x >= 1.0 ? temperate : LatitudeSeasons.lerp(base, temperate, x);
        }
        return seasonalTemperature(global, lat, w, biome, pos, inSeason);
    }

    /** The hemisphere's temperature, blended toward Mid Summer's by the season's strength {@code w}. */
    private static float seasonalTemperature(Season.SubSeason global, double lat, double w, Holder<Biome> biome,
                                             BlockPos pos, Operation<Float> inSeason) {
        if (LatitudeSeasons.unchanged(lat, w))
            return inSeason.call(global, biome, pos);
        float seasonal = inSeason.call(LatitudeSeasons.shifted(global, lat), biome, pos);
        if (w >= 1.0)
            return seasonal;
        float neutral = inSeason.call(LatitudeSeasons.NEUTRAL, biome, pos);
        return LatitudeSeasons.lerp(neutral, seasonal, w);
    }

    private static void restore(@Nullable TropicalRule before) {
        if (before == null)
            TROPICAL.remove();
        else
            TROPICAL.set(before);
    }

    /**
     * Serene Seasons' "is this biome in the tag" as its tropical rule reads it, in the two places the
     * rule decides on a position it does not have ({@code getBiomeTemperatureInSeason} and
     * {@code hasPrecipitationSeasonal}): false for the tropical-biome tag while {@link TropicalRule#OFF}
     * is set, its own answer otherwise and for every other tag.
     */
    public static boolean tropicalTag(TagKey<Biome> tag, boolean original) {
        if (!original || tag != ModTags.Biomes.TROPICAL_BIOMES)
            return original;
        return TROPICAL.get() != TropicalRule.OFF;
    }

    /**
     * Whether precipitation falls in {@code biome} at {@code pos} before temperature decides rain or
     * snow ({@code SeasonHooks.hasPrecipitationSeasonal}, which Serene Seasons asks of the level only).
     * Tropical biomes in the wet/dry band get Serene Seasons' dry and wet seasons from the local
     * tropical calendar ({@link #precipitationState}); elsewhere on a planet (no wet/dry cycle near
     * the equator, the temperate seasons beyond the band) the biome's own precipitation decides.
     * Serene Seasons' own answer off a planet and for every other biome.
     */
    public static boolean hasPrecipitation(Level level, Holder<Biome> biome, @Nullable BlockPos pos,
                                           Operation<Boolean> original) {
        TropicalRule rule = null;
        if (pos != null) {
            try {
                double lat = PlanetLatitude.latitude(level, pos.getX(), pos.getZ());
                if (!Double.isNaN(lat) && biome.is(ModTags.Biomes.TROPICAL_BIOMES)) {
                    rule = !LatitudeSeasons.wetDryRule(lat) ? TropicalRule.OFF
                            : LatitudeSeasons.south(lat) ? TropicalRule.SOUTH : TropicalRule.NORTH;
                }
            } catch (Throwable t) {
                logOnce(t);
                rule = null;
            }
        }
        if (rule == null)
            return original.call(level, biome);
        TropicalRule before = TROPICAL.get();
        TROPICAL.set(rule);
        try {
            return original.call(level, biome);
        } finally {
            restore(before);
        }
    }

    /**
     * The season state Serene Seasons' tropical precipitation rule reads while {@link #hasPrecipitation}
     * decides: the local tropical calendar (the hemisphere's wet season is its summer half). The
     * level's own state otherwise.
     */
    public static ISeasonState precipitationState(ISeasonState global) {
        TropicalRule rule = TROPICAL.get();
        if (global == null || rule == null || rule == TropicalRule.OFF)
            return global;
        return new LocalSeasonState(global, rule == TropicalRule.SOUTH ? -1.0 : 1.0, 1.0, false);
    }

    /**
     * The season state a discrete decision at {@code pos} should see: shifted for the hemisphere and
     * pulled toward Mid Summer where the seasons fade. Crop fertility, the season sensor and Project
     * Atmosphere's regional season use it. {@code global} when there is nothing to change.
     */
    public static ISeasonState discreteState(Level level, @Nullable BlockPos pos, ISeasonState global) {
        Season asking = ASKING.get();
        if (asking != null && global != null)
            return LocalSeasonState.inSeason(global, asking);
        return localState(level, pos, global, true);
    }

    /**
     * Serene Seasons' crop fertility, with every crop in season in the seasonless band near the equator
     * (Ben, 2026-09-30: "Let them grow year-round"). Outside the band, or when Serene Seasons already
     * says fertile, its answer stands. Inside, a crop it refused is asked about again in each of the
     * four seasons, through its own {@code isCropFertile}, and grows if any season lets it: its other
     * rules (infertile biomes, cold biomes' winter crops, greenhouse, underground) still decide.
     */
    public static boolean yearRoundCrop(boolean fertile, String crop, Level level, @Nullable BlockPos pos) {
        if (fertile || pos == null || ASKING.get() != null)
            return fertile;
        try {
            if (!inSeasonlessBand(level, pos))
                return false;
            for (Season season : Season.values()) {
                ASKING.set(season);
                if (ModFertility.isCropFertile(crop, level, pos))
                    return true;
            }
            return false;
        } catch (Throwable t) {
            logOnce(t);
            return fertile;
        } finally {
            ASKING.remove();
        }
    }

    /**
     * Serene Seasons' "is this a tropical biome" as its crop fertility asks it: false in the seasonless
     * band, so tropical biomes there grow every crop too rather than only the summer ones (its tropical
     * rule), and false beyond the middle of the wet/dry band's fade ({@link LatitudeSeasons#temperateRule}),
     * so they follow the temperate crop seasons there; its answer everywhere else, and for every other tag.
     */
    public static boolean cropBiomeTag(TagKey<Biome> tag, boolean original, Level level, @Nullable BlockPos pos) {
        if (!original || pos == null || tag != ModTags.Biomes.TROPICAL_BIOMES)
            return original;
        try {
            double lat = PlanetLatitude.latitude(level, pos.getX(), pos.getZ());
            if (Double.isNaN(lat))
                return original;
            return !(LatitudeSeasons.seasonless(PlanetLatitude.strength(lat)) || LatitudeSeasons.temperateRule(lat));
        } catch (Throwable t) {
            logOnce(t);
            return original;
        }
    }

    private static boolean inSeasonlessBand(Level level, BlockPos pos) {
        double lat = PlanetLatitude.latitude(level, pos.getX(), pos.getZ());
        return !Double.isNaN(lat) && LatitudeSeasons.seasonless(PlanetLatitude.strength(lat));
    }

    /** The season state for a blended quantity at {@code pos}: shifted, not faded (the caller fades). */
    public static ISeasonState shiftedState(Level level, @Nullable BlockPos pos, ISeasonState global) {
        return localState(level, pos, global, false);
    }

    private static ISeasonState localState(Level level, @Nullable BlockPos pos, ISeasonState global, boolean discrete) {
        if (global == null || pos == null)
            return global;
        try {
            double lat = PlanetLatitude.latitude(level, pos.getX(), pos.getZ());
            if (Double.isNaN(lat))
                return global;
            double w = PlanetLatitude.strength(lat);
            if (LatitudeSeasons.unchanged(lat, w))
                return global;
            return new LocalSeasonState(global, lat, w, discrete);
        } catch (Throwable t) {
            logOnce(t);
            return global;
        }
    }

    /**
     * A birch leaf colour Serene Seasons computed for the local season, blended toward vanilla's birch
     * colour (Mid Summer's, and Early Dry's) by the season's strength at {@code pos}. In Serene Seasons'
     * tropical biomes {@code colour} is its wet/dry colour (the local tropical season, the north's
     * moved half a cycle), blended by the tropical strength; beyond the wet/dry band they take the
     * temperate birch colour for the hemisphere's season instead, blended by the temperate strength,
     * and the two cross-fade over 20 to 25 degrees. Elsewhere the temperate strength.
     */
    public static int birchColour(Level level, @Nullable BlockPos pos, int colour) {
        if (pos == null || level == null)
            return colour;
        try {
            double lat = PlanetLatitude.latitude(level, pos.getX(), pos.getZ());
            if (Double.isNaN(lat))
                return colour;
            int vanilla = FoliageColor.getBirchColor();
            double w = PlanetLatitude.strength(lat);
            Holder<Biome> biome = level.getBiome(pos);
            if (!SeasonHelper.usesTropicalSeasons(biome))
                return LatitudeSeasons.lerpRgb(vanilla, colour, w);
            double x = LatitudeSeasons.temperateWeight(lat);
            int temperate = x <= 0.0 ? vanilla : temperateBirch(level, biome, lat);
            return LatitudeSeasons.tropicalBiomeColour(vanilla, colour, temperate, LatitudeSeasons.tropicalStrength(lat), w, x);
        } catch (Throwable t) {
            logOnce(t);
            return colour;
        }
    }

    /**
     * The birch colour Serene Seasons gives a non-tropical biome in the hemisphere's season, for a biome
     * it treats as tropical (its birch handler cannot be asked twice): its own colour for the sub-season,
     * mixed toward vanilla's in its lesser-colour biomes, vanilla's where it leaves birch alone.
     */
    private static int temperateBirch(Level level, Holder<Biome> biome, double lat) {
        int vanilla = FoliageColor.getBirchColor();
        if (!ModConfig.seasons.changeBirchColor || !ModConfig.seasons.isDimensionWhitelisted(level.dimension())
                || biome.is(ModTags.Biomes.BLACKLISTED_BIOMES))
            return vanilla;
        ISeasonColorProvider season = LatitudeSeasons.shifted(SeasonHelper.getSeasonState(level).getSubSeason(), lat);
        int colour = season.getBirchColor();
        return biome.is(ModTags.Biomes.LESSER_COLOR_CHANGE_BIOMES)
                ? SeasonColorUtil.mixColours(colour, vanilla, 0.75f) : colour;
    }

    // ------------------------------------------------------------------
    // Serene Seasons: melting (RandomUpdateHandler.onWorldTick).
    // ------------------------------------------------------------------

    /** Start of a melt pass. */
    public static void resetMelt() {
        lastMeltChunk = null;
    }

    /**
     * The melt chance (percent) Serene Seasons' melt pass gates its chunk loop on. On a planet the
     * loop has to run whenever the season melts snow anywhere, so this is the largest chance of any
     * sub-season; {@link #melt} then applies each chunk's own.
     */
    public static float meltLoopChance(ServerLevel level, float chance) {
        try {
            if (!onPlanet(level))
                return chance;
            float max = chance;
            for (Season.SubSeason s : Season.SubSeason.VALUES)
                max = Math.max(max, ModConfig.seasons.getSeasonProperties(s).meltChance());
            return max;
        } catch (Throwable t) {
            logOnce(t);
            return chance;
        }
    }

    /** The melt rolls per chunk the loop runs: as {@link #meltLoopChance}, the largest of any sub-season. */
    public static int meltLoopRolls(ServerLevel level, int rolls) {
        try {
            if (!onPlanet(level))
                return rolls;
            int max = rolls;
            for (Season.SubSeason s : Season.SubSeason.VALUES)
                max = Math.max(max, ModConfig.seasons.getSeasonProperties(s).meltRolls());
            return max;
        } catch (Throwable t) {
            logOnce(t);
            return rolls;
        }
    }

    /**
     * One of Serene Seasons' melt rolls in {@code chunk}. On a planet the first roll for a chunk
     * runs that chunk's own number of rolls at its own chance (its discrete local sub-season's melt
     * properties from Serene Seasons' config; the level's own sub-season north of the full-season
     * latitude), and the loop's remaining rolls for it are skipped: the loop itself runs at the
     * largest chance and roll count of any sub-season ({@link #meltLoopChance}). Serene Seasons' own
     * {@code meltInChunk} does each roll, temperature test included. Off a planet the call is
     * Serene Seasons' own.
     */
    public static void melt(ServerLevel level, Season.SubSeason global, ChunkMap map, LevelChunk chunk, float chance,
                            Operation<Void> meltInChunk) {
        Season.SubSeason local;
        try {
            local = localSubSeason(level, chunk.getPos(), global);
        } catch (Throwable t) {
            logOnce(t);
            local = null;
        }
        if (local == null) {
            meltInChunk.call(map, chunk, chance);
            return;
        }
        if (chunk == lastMeltChunk)
            return;
        lastMeltChunk = chunk;
        SeasonsConfig.SeasonProperties p = ModConfig.seasons.getSeasonProperties(local);
        float localChance = p.meltChance() / 100f;
        for (int i = 0; i < p.meltRolls(); i++)
            meltInChunk.call(map, chunk, localChance);
    }

    // ------------------------------------------------------------------
    // Serene Seasons Plus: its per-chunk snow policy.
    // ------------------------------------------------------------------

    /**
     * The sub-season Serene Seasons Plus's snow policy should judge {@code chunk} by
     * ({@code SnowAccumulationPolicy.evaluateChunk}): its discrete local sub-season at the chunk's
     * middle. {@code global} (Serene Seasons Plus's own, global one) off a planet.
     */
    public static Season.SubSeason snowPolicySeason(ServerLevel level, ChunkPos chunk, Season.SubSeason global) {
        if (global == null || chunk == null)
            return global;
        try {
            Season.SubSeason local = localSubSeason(level, chunk, global);
            return local == null ? global : local;
        } catch (Throwable t) {
            logOnce(t);
            return global;
        }
    }

    /**
     * The discrete local sub-season at a chunk's middle ({@code global} itself north of the
     * full-season latitude), or null off a planet.
     */
    @Nullable
    private static Season.SubSeason localSubSeason(Level level, ChunkPos chunk, Season.SubSeason global) {
        double lat = PlanetLatitude.latitude(level, chunk.getMiddleBlockX(), chunk.getMiddleBlockZ());
        if (Double.isNaN(lat))
            return null;
        return LatitudeSeasons.discrete(global, lat, PlanetLatitude.strength(lat));
    }

    // ------------------------------------------------------------------
    // Diagnostics.
    // ------------------------------------------------------------------

    /** Whether the hemisphere seasons apply anywhere in {@code level}. */
    public static boolean onPlanet(Level level) {
        return PlanetLatitude.switchedOn() && PlanetLatitude.circumference(level) > 0;
    }

    /**
     * What a position's seasons are, for {@code /mic_climate probe} and the GameTests.
     *
     * @param latitude  degrees north (NaN off a planet)
     * @param strength  the season's strength (1 off a planet)
     * @param global    Serene Seasons' own sub-season for the level
     * @param shifted   the hemisphere's sub-season (what colours and temperature blend from)
     * @param discrete  the sub-season decisions use (crops, melting, the sensor, Serene Seasons Plus,
     *                  Project Atmosphere)
     * @param tropical  the tropical season of the hemisphere (Serene Seasons' own off a planet): the
     *                  wet season is its summer half
     */
    public record Here(double latitude, double strength, Season.SubSeason global, Season.SubSeason shifted,
                       Season.SubSeason discrete, Season.TropicalSeason tropical) {

        public String describe() {
            if (Double.isNaN(latitude))
                return String.format(Locale.ROOT, "%s, %s everywhere (not a Deep Time planet, or switched off)", global, tropical);
            return String.format(Locale.ROOT, "lat %.1f%s, strength %.2f; level %s -> hemisphere %s, decisions %s; tropical %s (%s)",
                    Math.abs(latitude), latitude < 0 ? "S" : "N", strength, global, shifted, discrete, tropical,
                    LatitudeSeasons.wet(tropical) ? "wet" : "dry");
        }
    }

    public static Here here(Level level, BlockPos pos, ISeasonState global) {
        Season.SubSeason g = global.getSubSeason();
        double lat = PlanetLatitude.latitude(level, pos.getX(), pos.getZ());
        if (Double.isNaN(lat))
            return new Here(lat, 1.0, g, g, g, global.getTropicalSeason());
        double w = PlanetLatitude.strength(lat);
        return new Here(lat, w, g, LatitudeSeasons.shifted(g, lat), LatitudeSeasons.discrete(g, lat, w),
                LatitudeSeasons.shifted(global.getTropicalSeason(), lat));
    }

    private static void logOnce(Throwable t) {
        if (LOGGED_FAILURE.compareAndSet(false, true))
            MicClimate.LOGGER.warn("Hemisphere seasons failed; Serene Seasons keeps its own season there", t);
    }
}
