package com.minecraftindustrialcomplex.mic_climate.gametest;

import com.minecraftindustrialcomplex.mic_climate.Compat;
import com.minecraftindustrialcomplex.mic_climate.MicClimate;
import com.minecraftindustrialcomplex.mic_climate.config.ClimateConfig;
import com.minecraftindustrialcomplex.mic_climate.seasons.LatitudeSeasons;
import com.minecraftindustrialcomplex.mic_climate.seasons.LocalSeasonState;
import com.minecraftindustrialcomplex.mic_climate.seasons.SereneSeasonsHemispheres;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import sereneseasons.api.season.ISeasonState;
import sereneseasons.api.season.Season;

import java.util.Locale;

/**
 * The hemisphere seasons: Serene Seasons' seasons by latitude on a Deep Time planet
 * ({@code seasons.*}, mixins in {@code mixin.seasons} and {@code mixin.projectatmosphere}).
 *
 * <p>This server's world is not a Deep Time planet and Deep Time is not on its classpath, so a
 * stand-in planet ({@code ClimateConfig.Test.seasonTestPlanet}) makes the overworld one, with the
 * equator moved so that the test's own blocks sit at the latitude a test wants (45 degrees north,
 * the equator, 45 degrees south, ...). Everything is then read through Serene Seasons', Serene
 * Seasons Plus's and Project Atmosphere's own entry points, and compared with Serene Seasons' own
 * value for the expected sub-season, so a pass is a claim about what those mods decide.
 */
@GameTestHolder(MicClimate.MODID)
@PrefixGameTestTemplate(false)
public final class HemisphereSeasonsGameTests {

    /** The stand-in planet's circumference: 16,384 blocks, one of Deep Time's sizes. */
    private static final int C = 16_384;
    private static final String WHEAT = "minecraft:wheat";

    private HemisphereSeasonsGameTests() {}

    /** The latitude arithmetic, with no Minecraft state. */
    @GameTest(template = GameTests.TEMPLATE, timeoutTicks = 100)
    public static void hemisphereSeasonsArithmetic(GameTestHelper helper) {
        if (GameTests.skipWithout(helper, Compat.SERENE_SEASONS))
            return;
        runArithmetic();
        helper.succeed();
    }

    private static void runArithmetic() {
        double full = LatitudeSeasons.DEFAULT_FULL_LATITUDE;
        GameTests.assertNear("strength at the equator", LatitudeSeasons.strength(0, full), 0, 0);
        GameTests.assertNear("strength at 22.5 (half the full latitude)", LatitudeSeasons.strength(22.5, full), 0.5, 1e-12);
        GameTests.assertNear("strength at 11.25", LatitudeSeasons.strength(-11.25, full), 0.15625, 1e-12);
        GameTests.assertNear("strength at 45 S", LatitudeSeasons.strength(-45, full), 1, 0);
        GameTests.assertNear("strength at 80 N", LatitudeSeasons.strength(80, full), 1, 0);
        GameTests.assertTrue("the south is half a year out: Mid Winter -> Mid Summer, Early Spring -> Early Autumn",
                LatitudeSeasons.shifted(Season.SubSeason.MID_WINTER, -10) == Season.SubSeason.MID_SUMMER
                        && LatitudeSeasons.shifted(Season.SubSeason.EARLY_SPRING, -10) == Season.SubSeason.EARLY_AUTUMN
                        && LatitudeSeasons.shifted(Season.SubSeason.MID_WINTER, 10) == Season.SubSeason.MID_WINTER);
        GameTests.assertTrue("the south's tropical season is half a cycle out: Early Dry -> Early Wet",
                LatitudeSeasons.shifted(Season.TropicalSeason.EARLY_DRY, -1) == Season.TropicalSeason.EARLY_WET);
        GameTests.assertTrue("discrete seasons fade toward Mid Summer: Mid Winter at full, 30, 11.25 and 0 degrees",
                LatitudeSeasons.discrete(Season.SubSeason.MID_WINTER, 45, 1.0) == Season.SubSeason.MID_WINTER
                        && LatitudeSeasons.discrete(Season.SubSeason.MID_WINTER, 30, LatitudeSeasons.strength(30, full)) == Season.SubSeason.EARLY_SPRING
                        && LatitudeSeasons.discrete(Season.SubSeason.MID_WINTER, 11.25, 0.15625) == Season.SubSeason.EARLY_SUMMER
                        && LatitudeSeasons.discrete(Season.SubSeason.MID_WINTER, 0, 0) == Season.SubSeason.MID_SUMMER
                        && LatitudeSeasons.discrete(Season.SubSeason.MID_SUMMER, -45, 1.0) == Season.SubSeason.MID_WINTER);
        GameTests.assertTrue("colours blend channel by channel",
                LatitudeSeasons.lerpRgb(0x00000000, 0xFFFFFFFF, 0.5) == 0x80808080
                        && LatitudeSeasons.lerpRgb(0x123456, 0xABCDEF, 0) == 0x123456
                        && LatitudeSeasons.lerpRgb(0x123456, 0xABCDEF, 1) == 0xABCDEF);

        // A level at Mid Winter, 5 ticks in, seen from 45 degrees south: Mid Summer, 5 ticks in.
        ISeasonState winter = fixedState(Season.SubSeason.MID_WINTER.ordinal() * 1000 + 5);
        LocalSeasonState south = new LocalSeasonState(winter, -45, 1.0, true);
        GameTests.assertTrue("a southern view of Mid Winter is Mid Summer, same progress: " + south.getSeasonCycleTicks(),
                south.getSubSeason() == Season.SubSeason.MID_SUMMER && south.getSeason() == Season.SUMMER
                        && south.getSeasonCycleTicks() == Season.SubSeason.MID_SUMMER.ordinal() * 1000 + 5
                        && south.getTropicalSeason() == LatitudeSeasons.shifted(winter.getTropicalSeason(), -45));
    }

    /** Off a planet (no stand-in), with every switch on, Serene Seasons' answers are its own, bit for bit. */
    @GameTest(template = GameTests.TEMPLATE, timeoutTicks = 100, batch = "mic_climate_seasons_off")
    public static void hemisphereSeasonsInvisibleOffPlanet(GameTestHelper helper) {
        if (GameTests.skipWithout(helper, Compat.SERENE_SEASONS))
            return;
        runInvisible(helper);
    }

    private static void runInvisible(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos centre = GameTests.centre(helper).above(2);
        Holder<Biome> plains = SeasonsTestBridge.plains(level);
        try {
            ClimateConfig.Test.deepTimeEnabled(true);
            ClimateConfig.Test.hemisphereSeasons(true);
            Season.SubSeason global = SeasonsTestBridge.subSeason(level);
            for (int z : new int[] {-4096, -2048, 0, 2048, 4096}) {
                BlockPos pos = new BlockPos(centre.getX(), centre.getY(), z);
                GameTests.assertNear("off a planet, z " + z + ": Serene Seasons' temperature is its own",
                        SeasonsTestBridge.temperature(level, plains, pos), SeasonsTestBridge.inSeason(global, plains, pos), 0.0);
                GameTests.assertTrue("off a planet, z " + z + ": no latitude",
                        Double.isNaN(SeasonsTestBridge.here(level, pos).latitude()));
            }
            boolean on = SeasonsTestBridge.fertile(WHEAT, level, centre);
            ClimateConfig.Test.hemisphereSeasons(false);
            boolean off = SeasonsTestBridge.fertile(WHEAT, level, centre);
            GameTests.assertTrue("off a planet: wheat's fertility is the same with the switch on and off", on == off);
            GameTests.assertNear("off a planet: the melt loop's chance is Serene Seasons' own",
                    SeasonsTestBridge.meltLoopChance(level, 3.5f), 3.5, 0.0);
        } finally {
            ClimateConfig.Test.clear();
        }
        helper.succeed();
    }

    /**
     * On a stand-in planet, at northern midwinter and then midsummer: Serene Seasons' temperature
     * (snow, ice, rain or snow, melting), crop fertility, the sub-season decisions use and the melt
     * rate at 45 N, 30 N, 11.25 N, the equator and 45 S, each against Serene Seasons' own value for
     * the sub-season expected there.
     */
    @GameTest(template = GameTests.TEMPLATE, timeoutTicks = 200, batch = "mic_climate_seasons_on")
    public static void hemisphereSeasonsFollowLatitude(GameTestHelper helper) {
        if (GameTests.skipWithout(helper, Compat.SERENE_SEASONS))
            return;
        runFollow(helper);
    }

    private static void runFollow(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = GameTests.centre(helper).above(2);
        Holder<Biome> plains = SeasonsTestBridge.plains(level);
        Season.SubSeason before = SeasonsTestBridge.subSeason(level);
        try {
            ClimateConfig.Test.deepTimeEnabled(true);
            ClimateConfig.Test.hemisphereSeasons(true);

            // Is wheat here seasonal at all (a temperate biome under open sky)? Serene Seasons' own answer.
            SeasonsTestBridge.setSeason(level, Season.SubSeason.MID_SUMMER);
            boolean summerWheat = SeasonsTestBridge.fertile(WHEAT, level, pos);
            SeasonsTestBridge.setSeason(level, Season.SubSeason.MID_WINTER);
            boolean winterWheat = SeasonsTestBridge.fertile(WHEAT, level, pos);
            boolean crops = summerWheat && !winterWheat;
            GameTests.record("wheat at the test position, Serene Seasons alone: summer / winter",
                    summerWheat + " / " + winterWheat + (crops ? "" : " (not seasonal here: fertility checks skipped)")
                            + ", biome " + level.getBiome(pos).getRegisteredName());

            GameTests.assertTrue("the level is at Mid Winter", SeasonsTestBridge.subSeason(level) == Season.SubSeason.MID_WINTER);
            float winter = SeasonsTestBridge.inSeason(Season.SubSeason.MID_WINTER, plains, pos);
            float summer = SeasonsTestBridge.inSeason(Season.SubSeason.MID_SUMMER, plains, pos);
            GameTests.record("Serene Seasons' plains temperature, Mid Winter / Mid Summer", winter + " / " + summer);
            GameTests.assertTrue("plains snow in Serene Seasons' winter and not in its summer", winter < 0.15f && summer >= 0.15f);

            // Northern midwinter.
            checkAt(helper, level, plains, pos, 45, winter, Season.SubSeason.MID_WINTER, crops ? Boolean.FALSE : null);
            checkAt(helper, level, plains, pos, 30, Float.NaN, Season.SubSeason.EARLY_SPRING, null);
            checkAt(helper, level, plains, pos, 11.25, LatitudeSeasons.lerp(summer, winter, 0.15625),
                    Season.SubSeason.EARLY_SUMMER, crops ? Boolean.TRUE : null);
            checkAt(helper, level, plains, pos, 0, summer, Season.SubSeason.MID_SUMMER, crops ? Boolean.TRUE : null);
            checkAt(helper, level, plains, pos, -45, summer, Season.SubSeason.MID_SUMMER, crops ? Boolean.TRUE : null);

            // Melting: a chunk at 45 S melts at Serene Seasons' summer rate while the level has none.
            ChunkPos chunk = new ChunkPos(pos);
            planetWith(chunk.getMiddleBlockZ(), -45);
            Season.SubSeason chunkSeason = SeasonsTestBridge.chunkSeason(level, chunk);
            GameTests.record("melt at 45 S in northern midwinter: chunk sub-season / its melt chance / the level's",
                    chunkSeason + " / " + SeasonsTestBridge.meltChance(chunkSeason) + " / "
                            + SeasonsTestBridge.meltChance(Season.SubSeason.MID_WINTER));
            GameTests.assertTrue("a chunk at 45 S melts by Mid Summer", chunkSeason == Season.SubSeason.MID_SUMMER);
            float maxChance = 0f;
            for (Season.SubSeason s : Season.SubSeason.VALUES)
                maxChance = Math.max(maxChance, SeasonsTestBridge.meltChance(s));
            GameTests.assertNear("on a planet the melt loop runs at the largest chance of any sub-season",
                    SeasonsTestBridge.meltLoopChance(level, SeasonsTestBridge.meltChance(Season.SubSeason.MID_WINTER)), maxChance, 0.0);

            // Northern midsummer: the other way round.
            SeasonsTestBridge.setSeason(level, Season.SubSeason.MID_SUMMER);
            checkAt(helper, level, plains, pos, 45, summer, Season.SubSeason.MID_SUMMER, crops ? Boolean.TRUE : null);
            checkAt(helper, level, plains, pos, 0, summer, Season.SubSeason.MID_SUMMER, crops ? Boolean.TRUE : null);
            checkAt(helper, level, plains, pos, -45, winter, Season.SubSeason.MID_WINTER, crops ? Boolean.FALSE : null);

            // Switched off on the planet, and off the planet: Serene Seasons' own again.
            planetWith(pos.getZ(), -45);
            ClimateConfig.Test.hemisphereSeasons(false);
            GameTests.assertNear("switched off: 45 S reads the level's season",
                    SeasonsTestBridge.temperature(level, plains, pos), summer, 0.0);
            ClimateConfig.Test.hemisphereSeasons(true);
            ClimateConfig.Test.seasonTestPlanet(null);
            GameTests.assertNear("no planet: the level's season", SeasonsTestBridge.temperature(level, plains, pos), summer, 0.0);
        } finally {
            ClimateConfig.Test.clear();
            SeasonsTestBridge.setSeason(level, before);
        }
        helper.succeed();
    }

    /**
     * Puts {@code pos} at {@code latitude} on the stand-in planet and checks Serene Seasons there:
     * its temperature equals {@code temperature} (NaN: not checked), the sub-season decisions use is
     * {@code discrete}, and wheat's fertility is {@code wheat} (null: not checked).
     */
    private static void checkAt(GameTestHelper helper, ServerLevel level, Holder<Biome> plains, BlockPos pos,
                                double latitude, float temperature, Season.SubSeason discrete, Boolean wheat) {
        planetWith(pos.getZ(), latitude);
        String at = String.format(Locale.ROOT, "%s, %.2f%s", SeasonsTestBridge.subSeason(level),
                Math.abs(latitude), latitude < 0 ? "S" : "N");
        SereneSeasonsHemispheres.Here here = SeasonsTestBridge.here(level, pos);
        GameTests.record(at + ": seasons here", here.describe());
        // The stand-in's equator sits on a whole block, so the latitude is within a block of the asked one.
        GameTests.assertNear(at + ": latitude", here.latitude(), latitude, 360.0 / C);
        GameTests.assertTrue(at + ": decisions use " + discrete + ", got " + here.discrete(), here.discrete() == discrete);
        if (!Float.isNaN(temperature))
            GameTests.assertNear(at + ": Serene Seasons' temperature", SeasonsTestBridge.temperature(level, plains, pos), temperature, 1e-5);
        if (wheat != null)
            GameTests.assertTrue(at + ": wheat " + (wheat ? "grows" : "does not grow"),
                    SeasonsTestBridge.fertile(WHEAT, level, pos) == wheat);
    }

    /** The stand-in planet, with its equator placed so that block row {@code z} is at {@code latitude}. */
    private static void planetWith(int z, double latitude) {
        int equatorZ = z + (int) Math.round(latitude * C / 360.0);
        ClimateConfig.Test.seasonTestPlanet(new ClimateConfig.Test.TestPlanet(C, equatorZ));
    }

    /** The mixins into Serene Seasons (and Serene Seasons Plus, Project Atmosphere when present) bound. */
    @GameTest(template = GameTests.TEMPLATE, timeoutTicks = 100)
    public static void hemisphereSeasonsHooksBind(GameTestHelper helper) {
        if (GameTests.skipWithout(helper, Compat.SERENE_SEASONS))
            return;
        int ss = SeasonsTestBridge.boundTargets();
        GameTests.record("Serene Seasons hemisphere-season targets bound", ss + "/4");
        GameTests.assertTrue("all four server-side Serene Seasons mixins applied", ss == 4);
        if (Compat.isLoaded(Compat.SERENE_SEASONS_PLUS)) {
            boolean ssp = SeasonsSnowPlusTestBridge.bound();
            GameTests.record("Serene Seasons Plus snow policy mixin bound", ssp);
            GameTests.assertTrue("the Serene Seasons Plus mixin applied", ssp);
        }
        if (Compat.isLoaded(Compat.PROJECT_ATMOSPHERE)) {
            int pa = SeasonsAtmosphereTestBridge.boundTargets();
            GameTests.record("Project Atmosphere hemisphere-season targets bound (server)", pa + "/2");
            GameTests.assertTrue("both server-side Project Atmosphere season mixins applied", pa == 2);
        }
        helper.succeed();
    }

    /**
     * Serene Seasons Plus's snow policy on the stand-in planet, its global season Mid Summer: a chunk at
     * 45 N is in its warm season and melts, the same chunk at 45 S is in its winter and keeps its snow.
     */
    @GameTest(template = GameTests.TEMPLATE, timeoutTicks = 100, batch = "mic_climate_seasons_ssp")
    public static void hemisphereSnowPolicyFollowsLatitude(GameTestHelper helper) {
        if (GameTests.skipWithout(helper, Compat.SERENE_SEASONS_PLUS))
            return;
        runSnowPolicy(helper);
    }

    private static void runSnowPolicy(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = GameTests.centre(helper);
        int middleZ = new ChunkPos(pos).getMiddleBlockZ();
        try {
            ClimateConfig.Test.deepTimeEnabled(true);
            ClimateConfig.Test.hemisphereSeasons(true);
            String own = SeasonsSnowPlusTestBridge.decide(level, pos, Season.SubSeason.MID_SUMMER);
            planetWith(middleZ, 45);
            String north = SeasonsSnowPlusTestBridge.decide(level, pos, Season.SubSeason.MID_SUMMER);
            planetWith(middleZ, -45);
            String south = SeasonsSnowPlusTestBridge.decide(level, pos, Season.SubSeason.MID_SUMMER);
            planetWith(middleZ, 0);
            String equator = SeasonsSnowPlusTestBridge.decide(level, pos, Season.SubSeason.MID_WINTER);
            GameTests.record("Serene Seasons Plus in its Mid Summer: no planet / 45 N / 45 S; in its Mid Winter at the equator",
                    own + " / " + north + " / " + south + " / " + equator);
            GameTests.assertTrue("no planet: it melts in its warm season", own.startsWith("MELT/WARM_SEASON"));
            GameTests.assertTrue("45 N: it melts in its warm season", north.startsWith("MELT/WARM_SEASON"));
            GameTests.assertTrue("45 S: its winter, nothing to melt", south.startsWith("NONE"));
            GameTests.assertTrue("the equator in its Mid Winter: Mid Summer there, it melts", equator.startsWith("MELT/WARM_SEASON"));
        } finally {
            ClimateConfig.Test.clear();
        }
        helper.succeed();
    }

    /**
     * Project Atmosphere's regional season (what its drift uses) on the stand-in planet at northern
     * midwinter: winter at 45 N, summer at 45 S and on the equator (where its tropical wet/dry stage
     * is dropped), while its level-wide season stays winter; the regional sunlight follows.
     */
    @GameTest(template = GameTests.TEMPLATE, timeoutTicks = 100, batch = "mic_climate_seasons_pa")
    public static void hemisphereAtmosphereSeasonsFollowLatitude(GameTestHelper helper) {
        if (GameTests.skipWithout(helper, Compat.PROJECT_ATMOSPHERE))
            return;
        runAtmosphere(helper);
    }

    private static void runAtmosphere(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = GameTests.centre(helper);
        Season.SubSeason before = SeasonsTestBridge.subSeason(level);
        try {
            ClimateConfig.Test.deepTimeEnabled(true);
            ClimateConfig.Test.hemisphereSeasons(true);
            SeasonsTestBridge.setSeason(level, Season.SubSeason.MID_WINTER);
            String own = SeasonsAtmosphereTestBridge.regional(level, pos);
            planetWith(pos.getZ(), 45);
            String north = SeasonsAtmosphereTestBridge.regional(level, pos);
            float sunNorth = SeasonsAtmosphereTestBridge.sunlight(level, pos);
            planetWith(pos.getZ(), 0);
            String equator = SeasonsAtmosphereTestBridge.regional(level, pos);
            planetWith(pos.getZ(), -45);
            String south = SeasonsAtmosphereTestBridge.regional(level, pos);
            float sunSouth = SeasonsAtmosphereTestBridge.sunlight(level, pos);
            String levelWide = SeasonsAtmosphereTestBridge.levelWide(level);
            GameTests.record("Project Atmosphere at northern midwinter: no planet / 45 N / equator / 45 S / level-wide",
                    own + " / " + north + " / " + equator + " / " + south + " / " + levelWide);
            GameTests.record("its regional sunlight multiplier, 45 N / 45 S", sunNorth + " / " + sunSouth);
            GameTests.record("probe line at 45 S", SeasonsAtmosphereTestBridge.describe(level, pos));
            GameTests.assertTrue("no planet: its regional season is its level-wide one", own.equals(levelWide));
            GameTests.assertTrue("45 N: winter", north.startsWith("WINTER/"));
            GameTests.assertTrue("the equator: summer, no wet/dry stage", equator.equals("SUMMER/NEUTRAL"));
            GameTests.assertTrue("45 S: summer", south.startsWith("SUMMER/"));
            GameTests.assertTrue("level-wide: still winter", levelWide.startsWith("WINTER/"));
            GameTests.assertTrue("southern summer sunlight is stronger than northern winter's", sunSouth > sunNorth);
        } catch (Throwable t) {
            if (t instanceof RuntimeException r)
                throw r;
            throw new IllegalStateException(t);
        } finally {
            ClimateConfig.Test.clear();
            SeasonsTestBridge.setSeason(level, before);
        }
        helper.succeed();
    }

    /** A fixed Serene Seasons state (1000-tick sub-seasons) for the arithmetic test. */
    private static ISeasonState fixedState(int ticks) {
        return new ISeasonState() {
            @Override public int getDayDuration() { return 250; }
            @Override public int getSubSeasonDuration() { return 1000; }
            @Override public int getSeasonDuration() { return 3000; }
            @Override public int getCycleDuration() { return 12000; }
            @Override public int getSeasonCycleTicks() { return ticks; }
            @Override public int getDay() { return ticks / 250; }
            @Override public Season.SubSeason getSubSeason() { return Season.SubSeason.VALUES[ticks / 1000 % 12]; }
            @Override public Season getSeason() { return getSubSeason().getSeason(); }
            @Override public Season.TropicalSeason getTropicalSeason() { return Season.TropicalSeason.MID_DRY; }
        };
    }
}
