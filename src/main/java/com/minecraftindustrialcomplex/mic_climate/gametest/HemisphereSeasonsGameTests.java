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
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import sereneseasons.api.season.ISeasonState;
import sereneseasons.api.season.Season;

import java.util.List;
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
    /** Spring and autumn only, in Serene Seasons' tags. */
    private static final String CARROTS = "minecraft:carrots";

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
        GameTests.assertTrue("follow the sun: the south keeps Serene Seasons' tropical calendar, the north's is half a cycle out",
                LatitudeSeasons.shifted(Season.TropicalSeason.EARLY_DRY, -1) == Season.TropicalSeason.EARLY_DRY
                        && LatitudeSeasons.shifted(Season.TropicalSeason.EARLY_DRY, 1) == Season.TropicalSeason.EARLY_WET
                        && LatitudeSeasons.shifted(Season.TropicalSeason.MID_WET, 1) == Season.TropicalSeason.MID_DRY
                        && LatitudeSeasons.shifted(Season.TropicalSeason.LATE_WET, 10) == Season.TropicalSeason.LATE_DRY);
        boolean opposite = true;
        for (Season.TropicalSeason t : Season.TropicalSeason.VALUES) {
            opposite &= LatitudeSeasons.wet(LatitudeSeasons.shifted(t, 15)) != LatitudeSeasons.wet(LatitudeSeasons.shifted(t, -15));
        }
        GameTests.assertTrue("the two hemispheres' tropical calendars are always opposite: one wet, the other dry", opposite);
        GameTests.assertTrue("discrete seasons fade toward Mid Summer: Mid Winter at full, 30, 11.25 and 0 degrees",
                LatitudeSeasons.discrete(Season.SubSeason.MID_WINTER, 45, 1.0) == Season.SubSeason.MID_WINTER
                        && LatitudeSeasons.discrete(Season.SubSeason.MID_WINTER, 30, LatitudeSeasons.strength(30, full)) == Season.SubSeason.EARLY_SPRING
                        && LatitudeSeasons.discrete(Season.SubSeason.MID_WINTER, 11.25, 0.15625) == Season.SubSeason.EARLY_SUMMER
                        && LatitudeSeasons.discrete(Season.SubSeason.MID_WINTER, 0, 0) == Season.SubSeason.MID_SUMMER
                        && LatitudeSeasons.discrete(Season.SubSeason.MID_SUMMER, -45, 1.0) == Season.SubSeason.MID_WINTER);
        GameTests.assertTrue("the seasonless band: strength up to 1/12 (5 degrees in, 11.25 out)",
                LatitudeSeasons.seasonless(LatitudeSeasons.strength(5, full))
                        && !LatitudeSeasons.seasonless(LatitudeSeasons.strength(11.25, full))
                        && LatitudeSeasons.seasonless(1.0 / 12.0));
        GameTests.assertNear("tropical wet/dry strength at the equator", LatitudeSeasons.tropicalStrength(0), 0, 0);
        GameTests.assertNear("tropical wet/dry strength at 5 S", LatitudeSeasons.tropicalStrength(-5), 0, 0);
        GameTests.assertNear("tropical wet/dry strength at 7.5 N", LatitudeSeasons.tropicalStrength(7.5), 0.5, 1e-12);
        GameTests.assertNear("tropical wet/dry strength at 15 S", LatitudeSeasons.tropicalStrength(-15), 1, 0);
        GameTests.assertNear("tropical wet/dry strength at 22.5 N", LatitudeSeasons.tropicalStrength(22.5), 0.5, 1e-12);
        GameTests.assertNear("tropical wet/dry strength at 30 N", LatitudeSeasons.tropicalStrength(30), 0, 0);
        // "Temperate seasons there": an SS-tropical biome is wet/dry up to 20 degrees, temperate from 25, between a blend.
        GameTests.assertNear("temperate share at 20 N", LatitudeSeasons.temperateWeight(20), 0, 0);
        GameTests.assertNear("temperate share at 20 S", LatitudeSeasons.temperateWeight(-20), 0, 0);
        GameTests.assertNear("temperate share at 22.5 N", LatitudeSeasons.temperateWeight(22.5), 0.5, 1e-12);
        GameTests.assertNear("temperate share at 25 S", LatitudeSeasons.temperateWeight(-25), 1, 0);
        GameTests.assertNear("temperate share at 40 N", LatitudeSeasons.temperateWeight(40), 1, 0);
        GameTests.assertNear("temperate share at the equator", LatitudeSeasons.temperateWeight(0), 0, 0);
        GameTests.assertNear("temperate share and wet/dry strength sum to 1 at 21", LatitudeSeasons.temperateWeight(21)
                + LatitudeSeasons.tropicalStrength(21), 1, 1e-12);
        GameTests.assertNear("temperate share and wet/dry strength sum to 1 at 24 S", LatitudeSeasons.temperateWeight(-24)
                + LatitudeSeasons.tropicalStrength(-24), 1, 1e-12);
        GameTests.assertTrue("the wet/dry rule applies from 7.5 to 22.5 degrees, both hemispheres, nowhere else",
                !LatitudeSeasons.wetDryRule(0) && !LatitudeSeasons.wetDryRule(7.4) && LatitudeSeasons.wetDryRule(7.6)
                        && LatitudeSeasons.wetDryRule(15) && LatitudeSeasons.wetDryRule(-22.4)
                        && !LatitudeSeasons.wetDryRule(22.6) && !LatitudeSeasons.wetDryRule(-30));
        GameTests.assertTrue("the temperate rule applies beyond 22.5 degrees, both hemispheres, and not within it",
                !LatitudeSeasons.temperateRule(0) && !LatitudeSeasons.temperateRule(15) && !LatitudeSeasons.temperateRule(22.4)
                        && LatitudeSeasons.temperateRule(22.6) && LatitudeSeasons.temperateRule(-30) && LatitudeSeasons.temperateRule(70));
        // A tropical biome's colour: grey 100, wet/dry colour 200, temperate colour 0 (every channel).
        int grey = 0x646464, wetDryColour = 0xC8C8C8, temperateColour = 0x000000;
        int at15 = tropicalColour(grey, wetDryColour, temperateColour, 15, full);
        int at20 = tropicalColour(grey, wetDryColour, temperateColour, 20, full);
        int at225 = tropicalColour(grey, wetDryColour, temperateColour, 22.5, full);
        int at25 = tropicalColour(grey, wetDryColour, temperateColour, 25, full);
        int at40 = tropicalColour(grey, wetDryColour, temperateColour, 40, full);
        int at40S = tropicalColour(grey, wetDryColour, temperateColour, -40, full);
        GameTests.record("a tropical biome's colour channel at 15 / 20 / 22.5 / 25 / 40 / 40 S (grey 100, wet/dry 200, temperate 0)",
                (at15 & 0xFF) + " / " + (at20 & 0xFF) + " / " + (at225 & 0xFF) + " / " + (at25 & 0xFF) + " / " + (at40 & 0xFF)
                        + " / " + (at40S & 0xFF));
        GameTests.assertTrue("inside the wet/dry band the colour is the wet/dry one (15 and 20 degrees)",
                at15 == wetDryColour && at20 == wetDryColour);
        GameTests.assertTrue("at 25 degrees and beyond it is the temperate colour at the temperate strength, as for any biome",
                at25 == LatitudeSeasons.lerpRgb(grey, temperateColour, LatitudeSeasons.strength(25, full))
                        && at40 == LatitudeSeasons.lerpRgb(grey, temperateColour, LatitudeSeasons.strength(40, full))
                        && at40S == at40);
        GameTests.assertTrue("at 22.5 degrees the colour is between the wet/dry and the temperate one: " + Integer.toHexString(at225),
                (at225 & 0xFF) < (at20 & 0xFF) && (at225 & 0xFF) > (at25 & 0xFF) && at225 == 0x646464);
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

    /** The colour {@link LatitudeSeasons#tropicalBiomeColour} gives an SS-tropical biome at a latitude, as the client asks it. */
    private static int tropicalColour(int original, int wetDry, int temperate, double latitude, double full) {
        return LatitudeSeasons.tropicalBiomeColour(original, wetDry, temperate, LatitudeSeasons.tropicalStrength(latitude),
                LatitudeSeasons.strength(latitude, full), LatitudeSeasons.temperateWeight(latitude));
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

            // Are wheat and carrots seasonal here at all (a temperate biome under open sky)? Serene
            // Seasons' own answer.
            SeasonsTestBridge.setSeason(level, Season.SubSeason.MID_SPRING);
            boolean springCarrots = SeasonsTestBridge.fertile(CARROTS, level, pos);
            SeasonsTestBridge.setSeason(level, Season.SubSeason.MID_SUMMER);
            boolean summerWheat = SeasonsTestBridge.fertile(WHEAT, level, pos);
            boolean summerCarrots = SeasonsTestBridge.fertile(CARROTS, level, pos);
            SeasonsTestBridge.setSeason(level, Season.SubSeason.MID_WINTER);
            boolean winterWheat = SeasonsTestBridge.fertile(WHEAT, level, pos);
            boolean crops = summerWheat && !winterWheat && springCarrots && !summerCarrots;
            GameTests.record("at the test position, Serene Seasons alone: wheat summer / winter, carrots spring / summer",
                    summerWheat + " / " + winterWheat + ", " + springCarrots + " / " + summerCarrots
                            + (crops ? "" : " (not seasonal here: fertility checks skipped)")
                            + ", biome " + level.getBiome(pos).getRegisteredName());

            GameTests.assertTrue("the level is at Mid Winter", SeasonsTestBridge.subSeason(level) == Season.SubSeason.MID_WINTER);
            float winter = SeasonsTestBridge.inSeason(Season.SubSeason.MID_WINTER, plains, pos);
            float summer = SeasonsTestBridge.inSeason(Season.SubSeason.MID_SUMMER, plains, pos);
            GameTests.record("Serene Seasons' plains temperature, Mid Winter / Mid Summer", winter + " / " + summer);
            GameTests.assertTrue("plains snow in Serene Seasons' winter and not in its summer", winter < 0.15f && summer >= 0.15f);

            // Northern midwinter. Carrots grow at 30 N (its early spring) and in the seasonless band
            // (5 N, the equator) where every crop is in season, not at 11.25 N (its early summer).
            checkAt(level, plains, pos, 45, winter, Season.SubSeason.MID_WINTER, crops, false, false);
            checkAt(level, plains, pos, 30, Float.NaN, Season.SubSeason.EARLY_SPRING, crops, false, true);
            checkAt(level, plains, pos, 11.25, LatitudeSeasons.lerp(summer, winter, 0.15625),
                    Season.SubSeason.EARLY_SUMMER, crops, true, false);
            checkAt(level, plains, pos, 5, Float.NaN, Season.SubSeason.MID_SUMMER, crops, true, true);
            checkAt(level, plains, pos, 0, summer, Season.SubSeason.MID_SUMMER, crops, true, true);
            checkAt(level, plains, pos, -45, summer, Season.SubSeason.MID_SUMMER, crops, true, false);

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
            checkAt(level, plains, pos, 45, summer, Season.SubSeason.MID_SUMMER, crops, true, false);
            checkAt(level, plains, pos, 0, summer, Season.SubSeason.MID_SUMMER, crops, true, true);
            checkAt(level, plains, pos, -45, winter, Season.SubSeason.MID_WINTER, crops, false, false);

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
     * {@code discrete}, and, when {@code crops} (the position is seasonal at all), wheat's and
     * carrots' fertility are {@code wheat} and {@code carrots}.
     */
    private static void checkAt(ServerLevel level, Holder<Biome> plains, BlockPos pos, double latitude, float temperature,
                                Season.SubSeason discrete, boolean crops, boolean wheat, boolean carrots) {
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
        if (crops) {
            GameTests.assertTrue(at + ": wheat " + (wheat ? "grows" : "does not grow"),
                    SeasonsTestBridge.fertile(WHEAT, level, pos) == wheat);
            GameTests.assertTrue(at + ": carrots " + (carrots ? "grow" : "do not grow"),
                    SeasonsTestBridge.fertile(CARROTS, level, pos) == carrots);
        }
    }

    /** The stand-in planet, with its equator placed so that block row {@code z} is at {@code latitude}. */
    private static void planetWith(int z, double latitude) {
        int equatorZ = z + (int) Math.round(latitude * C / 360.0);
        ClimateConfig.Test.seasonTestPlanet(new ClimateConfig.Test.TestPlanet(C, equatorZ));
    }

    /**
     * "Temperate seasons there": Serene Seasons' tropical biomes (a savanna and a desert here) follow the
     * temperate seasons outside the wet/dry band. At northern midwinter, midspring and midsummer, on the
     * stand-in planet: at 40 N and 40 S the season decisions use is winter / summer, and the reverse in
     * the south; wheat and carrots follow the temperate crop seasons (Serene Seasons' own tropical rule
     * lets wheat grow all year and carrots never); precipitation is the biome's own, not the tropical
     * wet/dry calendar; at 22 and 23 degrees the crop rule flips from tropical to temperate; a
     * tropical biome of a temperature Serene Seasons would shift (a mangrove swamp) gets the temperate
     * shift beyond the band, none inside it, and half-way between at 22.5 degrees. Serene Seasons' own
     * rule is untouched off the planet.
     */
    @GameTest(template = GameTests.TEMPLATE, timeoutTicks = 200, batch = "mic_climate_seasons_on")
    public static void hemisphereTropicalBiomesFollowTemperateSeasons(GameTestHelper helper) {
        if (GameTests.skipWithout(helper, Compat.SERENE_SEASONS))
            return;
        runTemperateTropics(helper);
    }

    private static void runTemperateTropics(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = GameTests.centre(helper).above(2);
        Season.SubSeason before = SeasonsTestBridge.subSeason(level);
        SeasonsTestBridge.BiomeSwap swap = new SeasonsTestBridge.BiomeSwap(level, pos);
        try {
            ClimateConfig.Test.deepTimeEnabled(true);
            ClimateConfig.Test.hemisphereSeasons(true);
            for (ResourceKey<Biome> key : List.of(Biomes.SAVANNA, Biomes.DESERT)) {
                swap.set(key);
                Holder<Biome> biome = SeasonsTestBridge.biome(level, key);
                String name = key.location().getPath();
                GameTests.assertTrue(name + " is one of Serene Seasons' tropical biomes", SeasonsTestBridge.tropical(biome));
                GameTests.assertTrue(name + " is the biome at the test position",
                        SeasonsTestBridge.biomeAt(level, pos).equals(key));

                // Serene Seasons alone (no planet): its tropical rule, wheat in every season and carrots in none.
                ClimateConfig.Test.seasonTestPlanet(null);
                SeasonsTestBridge.setSeason(level, Season.SubSeason.MID_WINTER);
                boolean ownWinterWheat = SeasonsTestBridge.fertile(WHEAT, level, pos);
                SeasonsTestBridge.setSeason(level, Season.SubSeason.MID_SPRING);
                boolean ownSpringCarrots = SeasonsTestBridge.fertile(CARROTS, level, pos);
                GameTests.record(name + ", Serene Seasons alone: wheat in winter / carrots in spring", ownWinterWheat + " / " + ownSpringCarrots);
                GameTests.assertTrue(name + ": Serene Seasons' tropical rule grows wheat in winter and carrots never",
                        ownWinterWheat && !ownSpringCarrots);

                // Northern midwinter, midspring, midsummer at 40 N and 40 S.
                //                      level's season                  | 40 N: discrete, wheat, carrots | 40 S
                checkTropical(level, pos, name, 40, Season.SubSeason.MID_WINTER, Season.SubSeason.MID_WINTER, false, false);
                checkTropical(level, pos, name, 40, Season.SubSeason.MID_SPRING, Season.SubSeason.MID_SPRING, false, true);
                checkTropical(level, pos, name, 40, Season.SubSeason.MID_SUMMER, Season.SubSeason.MID_SUMMER, true, false);
                checkTropical(level, pos, name, -40, Season.SubSeason.MID_WINTER, Season.SubSeason.MID_SUMMER, true, false);
                checkTropical(level, pos, name, -40, Season.SubSeason.MID_SPRING, Season.SubSeason.MID_AUTUMN, true, true);
                checkTropical(level, pos, name, -40, Season.SubSeason.MID_SUMMER, Season.SubSeason.MID_WINTER, false, false);

                // Inside the wet/dry band the tropical rule stays (22 degrees), beyond its middle the temperate one (23).
                checkTropical(level, pos, name, 22, Season.SubSeason.MID_WINTER, null, true, false);
                checkTropical(level, pos, name, 23, Season.SubSeason.MID_WINTER, Season.SubSeason.MID_SPRING, false, true);
                checkTropical(level, pos, name, -22, Season.SubSeason.MID_SUMMER, null, true, false);
                checkTropical(level, pos, name, -23, Season.SubSeason.MID_SUMMER, Season.SubSeason.MID_SPRING, false, true);
                checkTropical(level, pos, name, 15, Season.SubSeason.MID_WINTER, null, true, false);
                checkTropical(level, pos, name, 15, Season.SubSeason.MID_SPRING, null, true, false);
                // The seasonless band near the equator keeps its own rule: every crop, year-round.
                checkTropical(level, pos, name, 2, Season.SubSeason.MID_WINTER, Season.SubSeason.MID_SUMMER, true, true);
            }

            // Precipitation: a jungle rains and a savanna does not, as the biomes themselves; the tropical
            // wet/dry calendar decides only inside the band.
            Holder<Biome> jungle = SeasonsTestBridge.biome(level, Biomes.JUNGLE);
            Holder<Biome> savanna = SeasonsTestBridge.biome(level, Biomes.SAVANNA);
            ClimateConfig.Test.seasonTestPlanet(null);
            SeasonsTestBridge.setSeason(level, Season.SubSeason.LATE_SUMMER);
            Biome.Precipitation ownJungle = SeasonsTestBridge.precipitation(level, jungle, pos);
            SeasonsTestBridge.setSeason(level, Season.SubSeason.EARLY_SPRING);
            Biome.Precipitation ownSavanna = SeasonsTestBridge.precipitation(level, savanna, pos);
            GameTests.record("Serene Seasons alone: a jungle in its Late Summer (its Mid Dry) / a savanna in its Early Spring (its Mid Wet)",
                    ownJungle + " / " + ownSavanna);
            GameTests.assertTrue("Serene Seasons alone: a dry season takes a jungle's rain, a wet season rains on a savanna",
                    ownJungle == Biome.Precipitation.NONE && ownSavanna == Biome.Precipitation.RAIN);
            planetWith(pos.getZ(), 40);
            SeasonsTestBridge.setSeason(level, Season.SubSeason.LATE_SUMMER);
            Biome.Precipitation north40Jungle = SeasonsTestBridge.precipitation(level, jungle, pos);
            SeasonsTestBridge.setSeason(level, Season.SubSeason.EARLY_SPRING);
            Biome.Precipitation north40Savanna = SeasonsTestBridge.precipitation(level, savanna, pos);
            planetWith(pos.getZ(), -40);
            Biome.Precipitation south40Savanna = SeasonsTestBridge.precipitation(level, savanna, pos);
            GameTests.record("at 40 N, a jungle in Late Summer / a savanna in Early Spring; at 40 S the savanna",
                    north40Jungle + " / " + north40Savanna + " / " + south40Savanna);
            GameTests.assertTrue("at 40 N the jungle rains in Serene Seasons' tropical dry season and a savanna never does: the biome's own",
                    north40Jungle == Biome.Precipitation.RAIN && north40Savanna == Biome.Precipitation.NONE
                            && south40Savanna == Biome.Precipitation.NONE);
            planetWith(pos.getZ(), 2);
            SeasonsTestBridge.setSeason(level, Season.SubSeason.LATE_SUMMER);
            GameTests.assertTrue("at 2 N there is no dry season: the jungle rains all year, the savanna never does",
                    SeasonsTestBridge.precipitation(level, jungle, pos) == Biome.Precipitation.RAIN
                            && SeasonsTestBridge.precipitation(level, savanna, pos) == Biome.Precipitation.NONE);
            planetWith(pos.getZ(), 40);
            ClimateConfig.Test.hemisphereSeasons(false);
            SeasonsTestBridge.setSeason(level, Season.SubSeason.LATE_SUMMER);
            GameTests.assertTrue("switched off: Serene Seasons' own dry season again at 40 N",
                    SeasonsTestBridge.precipitation(level, jungle, pos) == Biome.Precipitation.NONE);
            ClimateConfig.Test.hemisphereSeasons(true);

            // Temperature: a tropical biome Serene Seasons would shift if it were not tropical (base 0.8 or less).
            Holder<Biome> mangrove = SeasonsTestBridge.biome(level, Biomes.MANGROVE_SWAMP);
            GameTests.assertTrue("a mangrove swamp is a tropical biome", SeasonsTestBridge.tropical(mangrove));
            SeasonsTestBridge.setSeason(level, Season.SubSeason.MID_WINTER);
            float own = SeasonsTestBridge.ownTemperature(mangrove, pos);
            float temperateWinter = SeasonsTestBridge.temperateTemperature(mangrove, pos, Season.SubSeason.MID_WINTER);
            GameTests.record("a mangrove swamp, its own temperature / with Serene Seasons' Mid Winter shift", own + " / " + temperateWinter);
            GameTests.assertTrue("Serene Seasons' Mid Winter would cool it", temperateWinter < own);
            for (double latitude : new double[] {0, 15, 20, 22.5, 25, 40, 50, -40}) {
                planetWith(pos.getZ(), latitude);
                SereneSeasonsHemispheres.Here here = SeasonsTestBridge.here(level, pos);
                double lat = here.latitude(), w = LatitudeSeasons.strength(lat, 45.0), x = LatitudeSeasons.temperateWeight(lat);
                float seasonal = SeasonsTestBridge.temperateTemperature(mangrove, pos, LatitudeSeasons.shifted(Season.SubSeason.MID_WINTER, lat));
                float neutral = SeasonsTestBridge.temperateTemperature(mangrove, pos, Season.SubSeason.MID_SUMMER);
                float temperate = LatitudeSeasons.lerp(neutral, seasonal, w);
                float expected = LatitudeSeasons.lerp(own, temperate, x);
                float actual = SeasonsTestBridge.temperature(level, mangrove, pos);
                GameTests.assertNear(String.format(Locale.ROOT, "a mangrove swamp at %.1f: own %.3f, temperate %.3f, share %.2f, Serene Seasons' temperature",
                        lat, own, temperate, x), actual, expected, 1e-5);
                if (x == 0.0)
                    GameTests.assertNear("inside the wet/dry band a tropical biome has no temperature shift", actual, own, 1e-6);
                if (Math.abs(lat) >= 24.99 && lat > 0)
                    GameTests.assertTrue("beyond the band the mangrove swamp is cooled by the winter: " + actual + " < " + own, actual < own);
                if (Math.abs(lat - 22.5) < 0.05)
                    GameTests.assertTrue("at 22.5 the shift is between none and the temperate one: " + actual,
                            actual < own && actual > temperate);
            }
        } finally {
            ClimateConfig.Test.clear();
            SeasonsTestBridge.setSeason(level, before);
            swap.restore();
        }
        helper.succeed();
    }

    /**
     * At {@code latitude} on the stand-in planet with the level in {@code season}: the sub-season decisions
     * use is {@code discrete} (null: not checked), and the biome at {@code pos} lets wheat and carrots grow
     * or not as given.
     */
    private static void checkTropical(ServerLevel level, BlockPos pos, String biome, double latitude, Season.SubSeason season,
                                      Season.SubSeason discrete, boolean wheat, boolean carrots) {
        SeasonsTestBridge.setSeason(level, season);
        planetWith(pos.getZ(), latitude);
        String at = String.format(Locale.ROOT, "%s, %s %.0f%s", biome, season, Math.abs(latitude), latitude < 0 ? "S" : "N");
        SereneSeasonsHemispheres.Here here = SeasonsTestBridge.here(level, pos);
        GameTests.record(at + ": seasons here", here.describe());
        if (discrete != null)
            GameTests.assertTrue(at + ": decisions use " + discrete + ", got " + here.discrete(), here.discrete() == discrete);
        GameTests.assertTrue(at + ": wheat " + (wheat ? "grows" : "does not grow"), SeasonsTestBridge.fertile(WHEAT, level, pos) == wheat);
        GameTests.assertTrue(at + ": carrots " + (carrots ? "grow" : "do not grow"), SeasonsTestBridge.fertile(CARROTS, level, pos) == carrots);
    }

    /**
     * "Follow the sun": the tropical wet season is each hemisphere's summer half. At 15 degrees north
     * Serene Seasons' tropical season is wet from Early Summer to Late Autumn and dry from Early Winter to
     * Late Spring, at 15 degrees south the reverse; at northern midsummer it is wet in the north and dry
     * in the south, at northern midwinter dry in the north and wet in the south. Precipitation in a
     * savanna and a jungle (Serene Seasons' dry season takes the rain, its wet one rains on a desert's
     * biome) agrees, in both hemispheres.
     */
    @GameTest(template = GameTests.TEMPLATE, timeoutTicks = 200, batch = "mic_climate_seasons_on")
    public static void hemisphereTropicalWetSeasonFollowsTheSun(GameTestHelper helper) {
        if (GameTests.skipWithout(helper, Compat.SERENE_SEASONS))
            return;
        runFollowTheSun(helper);
    }

    private static void runFollowTheSun(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = GameTests.centre(helper).above(2);
        Season.SubSeason before = SeasonsTestBridge.subSeason(level);
        SeasonsTestBridge.BiomeSwap swap = new SeasonsTestBridge.BiomeSwap(level, pos);
        try {
            ClimateConfig.Test.deepTimeEnabled(true);
            ClimateConfig.Test.hemisphereSeasons(true);
            swap.set(Biomes.SAVANNA);
            Holder<Biome> savanna = SeasonsTestBridge.biome(level, Biomes.SAVANNA);
            Holder<Biome> jungle = SeasonsTestBridge.biome(level, Biomes.JUNGLE);
            StringBuilder north = new StringBuilder(), south = new StringBuilder();
            for (Season.SubSeason s : Season.SubSeason.VALUES) {
                SeasonsTestBridge.setSeason(level, s);
                planetWith(pos.getZ(), 15);
                SereneSeasonsHemispheres.Here n = SeasonsTestBridge.here(level, pos);
                planetWith(pos.getZ(), -15);
                SereneSeasonsHemispheres.Here so = SeasonsTestBridge.here(level, pos);
                boolean northWet = LatitudeSeasons.wet(n.tropical()), southWet = LatitudeSeasons.wet(so.tropical());
                north.append(s.ordinal()).append(northWet ? "W " : "d ");
                south.append(s.ordinal()).append(southWet ? "W " : "d ");
                boolean summerHalf = s.ordinal() >= Season.SubSeason.EARLY_SUMMER.ordinal()
                        && s.ordinal() <= Season.SubSeason.LATE_AUTUMN.ordinal();
                GameTests.assertTrue(s + ": at 15 N the tropical season is " + n.tropical() + (summerHalf ? ", wet" : ", dry"),
                        northWet == summerHalf);
                GameTests.assertTrue(s + ": at 15 S the tropical season is " + so.tropical() + (summerHalf ? ", dry" : ", wet"),
                        southWet != summerHalf);
            }
            GameTests.record("tropical season by the level's sub-season (0 = Early Spring), 15 N (W wet, d dry)", north.toString().trim());
            GameTests.record("the same at 15 S", south.toString().trim());

            // Summer and winter themselves.
            SeasonsTestBridge.setSeason(level, Season.SubSeason.MID_SUMMER);
            planetWith(pos.getZ(), 15);
            GameTests.assertTrue("15 N in northern midsummer is wet", LatitudeSeasons.wet(SeasonsTestBridge.here(level, pos).tropical()));
            planetWith(pos.getZ(), -15);
            GameTests.assertTrue("15 S in northern midsummer (its midwinter) is dry", !LatitudeSeasons.wet(SeasonsTestBridge.here(level, pos).tropical()));
            SeasonsTestBridge.setSeason(level, Season.SubSeason.MID_WINTER);
            planetWith(pos.getZ(), 15);
            GameTests.assertTrue("15 N in northern midwinter is dry", !LatitudeSeasons.wet(SeasonsTestBridge.here(level, pos).tropical()));
            planetWith(pos.getZ(), -15);
            GameTests.assertTrue("15 S in northern midwinter (its midsummer) is wet", LatitudeSeasons.wet(SeasonsTestBridge.here(level, pos).tropical()));

            // Precipitation reads the same calendar: Late Summer is the north's Mid Wet and the south's Mid Dry,
            // Late Winter the north's Mid Dry and the south's Mid Wet (Serene Seasons' rule: Mid Wet rains on a
            // savanna, Mid Dry takes a jungle's rain).
            SeasonsTestBridge.setSeason(level, Season.SubSeason.LATE_SUMMER);
            planetWith(pos.getZ(), 15);
            Biome.Precipitation northSavannaWet = SeasonsTestBridge.precipitation(level, savanna, pos);
            Biome.Precipitation northJungleWet = SeasonsTestBridge.precipitation(level, jungle, pos);
            planetWith(pos.getZ(), -15);
            Biome.Precipitation southSavannaDry = SeasonsTestBridge.precipitation(level, savanna, pos);
            Biome.Precipitation southJungleDry = SeasonsTestBridge.precipitation(level, jungle, pos);
            SeasonsTestBridge.setSeason(level, Season.SubSeason.LATE_WINTER);
            Biome.Precipitation southSavannaWet = SeasonsTestBridge.precipitation(level, savanna, pos);
            Biome.Precipitation southJungleWet = SeasonsTestBridge.precipitation(level, jungle, pos);
            planetWith(pos.getZ(), 15);
            Biome.Precipitation northSavannaDry = SeasonsTestBridge.precipitation(level, savanna, pos);
            Biome.Precipitation northJungleDry = SeasonsTestBridge.precipitation(level, jungle, pos);
            GameTests.record("precipitation (savanna / jungle): 15 N in Late Summer, 15 S in Late Summer, 15 S in Late Winter, 15 N in Late Winter",
                    northSavannaWet + "/" + northJungleWet + ", " + southSavannaDry + "/" + southJungleDry + ", "
                            + southSavannaWet + "/" + southJungleWet + ", " + northSavannaDry + "/" + northJungleDry);
            GameTests.assertTrue("15 N in its wet season (Late Summer) rains on the savanna and the jungle",
                    northSavannaWet == Biome.Precipitation.RAIN && northJungleWet == Biome.Precipitation.RAIN);
            GameTests.assertTrue("15 S in its dry season (northern Late Summer) has no precipitation",
                    southSavannaDry == Biome.Precipitation.NONE && southJungleDry == Biome.Precipitation.NONE);
            GameTests.assertTrue("15 S in its wet season (northern Late Winter) rains on the savanna and the jungle",
                    southSavannaWet == Biome.Precipitation.RAIN && southJungleWet == Biome.Precipitation.RAIN);
            GameTests.assertTrue("15 N in its dry season (Late Winter) has no precipitation",
                    northSavannaDry == Biome.Precipitation.NONE && northJungleDry == Biome.Precipitation.NONE);
            // Off the planet Serene Seasons' own calendar: Late Winter is its Mid Wet.
            ClimateConfig.Test.seasonTestPlanet(null);
            GameTests.assertTrue("Serene Seasons alone: Late Winter is its Mid Wet, it rains on the savanna",
                    SeasonsTestBridge.precipitation(level, savanna, pos) == Biome.Precipitation.RAIN);
        } finally {
            ClimateConfig.Test.clear();
            SeasonsTestBridge.setSeason(level, before);
            swap.restore();
        }
        helper.succeed();
    }

    /**
     * The two settings live in the world's server config, which NeoForge syncs to clients, and read
     * their defaults (on, 45 degrees) from it.
     */
    @GameTest(template = GameTests.TEMPLATE, timeoutTicks = 100)
    public static void hemisphereSeasonsServerConfig(GameTestHelper helper) {
        GameTests.assertTrue("the world's server config (mic_climate-server.toml) is loaded", ClimateConfig.serverLoaded());
        GameTests.assertTrue("deepTime.hemisphereSeasons is on by default", ClimateConfig.hemisphereSeasons());
        GameTests.assertNear("deepTime.fullSeasonLatitude defaults to 45", ClimateConfig.fullSeasonLatitude(), 45.0, 0.0);
        helper.succeed();
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
            // Its tropical wet/dry stage keeps its own band, whatever the temperate seasons do.
            StringBuilder band = new StringBuilder();
            boolean ok = true;
            for (double lat : new double[] {2, 7, 8, 15, 22, 23, 30, -15, -2}) {
                planetWith(pos.getZ(), lat);
                boolean wetDry = SeasonsAtmosphereTestBridge.tropicalStage(level, pos);
                band.append(lat).append(':').append(wetDry).append(' ');
                ok &= wetDry == (Math.abs(lat) >= 7.5 && Math.abs(lat) <= 22.5);
            }
            GameTests.record("its tropical wet/dry stage applies at (latitude:yes)", band.toString().trim());
            GameTests.assertTrue("its tropical wet/dry stage applies from 7.5 to 22.5 degrees, both hemispheres", ok);
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

    /**
     * Project Atmosphere's tropical wet/dry stage in a savanna agrees with Serene Seasons' tropical season
     * ("Follow the sun"): at 15 N it is WET in northern midsummer and DRY in midwinter, at 15 S the
     * reverse; it applies inside 22.5 degrees and gives way to the temperate stage beyond (40 N, 40 S).
     */
    @GameTest(template = GameTests.TEMPLATE, timeoutTicks = 100, batch = "mic_climate_seasons_pa")
    public static void hemisphereAtmosphereWetDryFollowsTheSun(GameTestHelper helper) {
        if (GameTests.skipWithout(helper, Compat.PROJECT_ATMOSPHERE))
            return;
        runAtmosphereWetDry(helper);
    }

    private static void runAtmosphereWetDry(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = GameTests.centre(helper).above(2);
        Season.SubSeason before = SeasonsTestBridge.subSeason(level);
        SeasonsTestBridge.BiomeSwap swap = new SeasonsTestBridge.BiomeSwap(level, pos);
        try {
            ClimateConfig.Test.deepTimeEnabled(true);
            ClimateConfig.Test.hemisphereSeasons(true);
            swap.set(Biomes.SAVANNA);
            GameTests.assertTrue("a savanna is one of Serene Seasons' tropical biomes", SeasonsTestBridge.tropical(SeasonsTestBridge.biome(level, Biomes.SAVANNA)));
            GameTests.assertTrue("the test position is a savanna", SeasonsTestBridge.biomeAt(level, pos).equals(Biomes.SAVANNA));
            StringBuilder seen = new StringBuilder();
            for (Season.SubSeason s : new Season.SubSeason[] {Season.SubSeason.MID_SUMMER, Season.SubSeason.MID_WINTER}) {
                SeasonsTestBridge.setSeason(level, s);
                boolean summer = s == Season.SubSeason.MID_SUMMER;
                planetWith(pos.getZ(), 15);
                String north = SeasonsAtmosphereTestBridge.regional(level, pos);
                boolean northWet = LatitudeSeasons.wet(SeasonsTestBridge.here(level, pos).tropical());
                planetWith(pos.getZ(), -15);
                String south = SeasonsAtmosphereTestBridge.regional(level, pos);
                boolean southWet = LatitudeSeasons.wet(SeasonsTestBridge.here(level, pos).tropical());
                seen.append(s).append(": 15 N ").append(north).append(", 15 S ").append(south).append("; ");
                GameTests.assertTrue(s + ": Project Atmosphere's savanna at 15 N is " + (summer ? "WET" : "DRY") + ", got " + north,
                        north.endsWith(summer ? "/WET" : "/DRY"));
                GameTests.assertTrue(s + ": Project Atmosphere's savanna at 15 S is " + (summer ? "DRY" : "WET") + ", got " + south,
                        south.endsWith(summer ? "/DRY" : "/WET"));
                GameTests.assertTrue(s + ": and it agrees with Serene Seasons' tropical season (north wet "
                        + northWet + ", south wet " + southWet + ")", northWet == summer && southWet != summer);
            }
            GameTests.record("Project Atmosphere in a savanna, stage/moisture", seen.toString().trim());
            SeasonsTestBridge.setSeason(level, Season.SubSeason.MID_SUMMER);
            for (double lat : new double[] {22, -22, 23, -23, 40, -40, 2}) {
                planetWith(pos.getZ(), lat);
                String regional = SeasonsAtmosphereTestBridge.regional(level, pos);
                boolean wetDry = !regional.endsWith("/NEUTRAL");
                GameTests.record("Project Atmosphere in a savanna at " + lat + ", northern midsummer", regional);
                GameTests.assertTrue("a savanna at " + lat + " has " + (Math.abs(lat) >= 7.5 && Math.abs(lat) <= 22.5 ? "" : "no ")
                        + "wet/dry stage, got " + regional, wetDry == (Math.abs(lat) >= 7.5 && Math.abs(lat) <= 22.5));
            }
            // Beyond the band the stage is the temperate season of the hemisphere: summer in the north, winter in the south.
            planetWith(pos.getZ(), 40);
            GameTests.assertTrue("a savanna at 40 N in northern midsummer is in SUMMER", SeasonsAtmosphereTestBridge.regional(level, pos).startsWith("SUMMER/"));
            planetWith(pos.getZ(), -40);
            GameTests.assertTrue("a savanna at 40 S in northern midsummer is in WINTER", SeasonsAtmosphereTestBridge.regional(level, pos).startsWith("WINTER/"));
        } catch (Throwable t) {
            if (t instanceof RuntimeException r)
                throw r;
            throw new IllegalStateException(t);
        } finally {
            ClimateConfig.Test.clear();
            SeasonsTestBridge.setSeason(level, before);
            swap.restore();
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
