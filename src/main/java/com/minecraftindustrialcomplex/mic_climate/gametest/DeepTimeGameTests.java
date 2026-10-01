package com.minecraftindustrialcomplex.mic_climate.gametest;

import com.github.thedeathlycow.thermoo.api.season.ThermooSeason;
import com.minecraftindustrialcomplex.mic_climate.Climate;
import com.minecraftindustrialcomplex.mic_climate.Compat;
import com.minecraftindustrialcomplex.mic_climate.MicClimate;
import com.minecraftindustrialcomplex.mic_climate.config.ClimateConfig;
import com.minecraftindustrialcomplex.mic_climate.provider.UnifiedEnvironmentProvider;
import com.minecraftindustrialcomplex.mic_climate.provider.YearClock;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The Deep Time coupling's parts that do not need a Deep Time world: the season calendar mapped
 * onto Deep Time's year, the cap on Project Atmosphere's anomaly, and that a world Deep Time did
 * not generate keeps exactly the temperature it had before the coupling existed. (The coupling on
 * a real Deep Time planet is checked from Deep Time's side, with its planet server and this mod's
 * {@code /mic_climate probe}.)
 */
@GameTestHolder(MicClimate.MODID)
@PrefixGameTestTemplate(false)
public final class DeepTimeGameTests {

    private DeepTimeGameTests() {}

    @GameTest(template = GameTests.TEMPLATE, timeoutTicks = 100)
    public static void seasonCalendarMapsOntoTheYear(GameTestHelper helper) {
        long d = 2_304_000L; // Serene Seasons' default cycle: 12 sub-seasons of 8 days
        GameTests.assertNear("cycle start = start of March", YearClock.fromSeasonCycle(0, d), 2.0 / 12.0, 1e-12);
        GameTests.assertNear("mid-cycle = start of September", YearClock.fromSeasonCycle(d / 2, d), 8.0 / 12.0, 1e-12);
        GameTests.assertNear("Late Winter's end wraps to March", YearClock.fromSeasonCycle(d - 1, d), 2.0 / 12.0, 1e-6);
        GameTests.assertNear("Early Winter = December", YearClock.fromSeasonCycle(9 * d / 12, d), 11.0 / 12.0, 1e-12);
        GameTests.assertNear("a cycle later is the same date", YearClock.fromSeasonCycle(d + 5, d),
                YearClock.fromSeasonCycle(5, d), 1e-12);
        GameTests.assertNear("summer = July", YearClock.fromThermooSeason(ThermooSeason.SUMMER), 6.5 / 12.0, 1e-12);
        GameTests.assertNear("winter = January", YearClock.fromThermooSeason(ThermooSeason.WINTER), 0.5 / 12.0, 1e-12);
        GameTests.assertTrue("tropical seasons carry no date",
                Double.isNaN(YearClock.fromThermooSeason(ThermooSeason.TROPICAL_WET)));
        YearClock.Date now = YearClock.now(helper.getLevel());
        GameTests.record("date now", now);
        GameTests.assertTrue("a date from Serene Seasons when it is installed",
                !Compat.isLoaded(Compat.SERENE_SEASONS)
                        || (now.source().equals("serene_seasons") && now.yearFraction() >= 0 && now.yearFraction() < 1));
        helper.succeed();
    }

    @GameTest(template = GameTests.TEMPLATE, timeoutTicks = 100)
    public static void atmosphereAnomalyIsCapped(GameTestHelper helper) {
        GameTests.assertNear("inside the cap", UnifiedEnvironmentProvider.cap(3.5f, 20f), 3.5, 1e-6);
        GameTests.assertNear("warm glitch capped", UnifiedEnvironmentProvider.cap(35f, 20f), 20, 1e-6);
        GameTests.assertNear("cold glitch capped", UnifiedEnvironmentProvider.cap(-35f, 20f), -20, 1e-6);
        helper.succeed();
    }

    /**
     * The unified provider covers {@code #mic_climate:overworld}: all of {@code #minecraft:is_overworld}
     * plus the Terralith biomes that tag misses (Deep Time places {@code terralith:deep_warm_ocean};
     * without this, Thermoo answered its 20 C default there). The Terralith entries are optional, so
     * the tag also loads in this runtime, which has no Terralith.
     */
    @GameTest(template = GameTests.TEMPLATE, timeoutTicks = 100)
    public static void providerCoversTheWholeOverworld(GameTestHelper helper) {
        var biomes = helper.getLevel().registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BIOME);
        var ours = net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.BIOME, MicClimate.asResource("overworld"));
        var vanilla = net.minecraft.tags.BiomeTags.IS_OVERWORLD;
        GameTests.assertTrue("#mic_climate:overworld loaded", biomes.getTag(ours).isPresent());
        long missing = biomes.getTag(vanilla).map(set -> set.stream().filter(b -> !b.is(ours)).count()).orElse(-1L);
        GameTests.record("#minecraft:is_overworld biomes outside #mic_climate:overworld", missing);
        GameTests.assertTrue("every #minecraft:is_overworld biome is covered", missing == 0);
        GameTests.assertTrue("plains is covered",
                biomes.getHolderOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS).is(ours));
        helper.succeed();
    }

    /** Outside a Deep Time world the coupling is invisible: the same number with it on or off. */
    @GameTest(template = GameTests.TEMPLATE, timeoutTicks = 200)
    public static void otherWorldsAreUnchanged(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = GameTests.centre(helper);
        try {
            ClimateConfig.Test.deepTimeEnabled(true);
            float on = Climate.uncachedCelsius(level, pos);
            ClimateConfig.Test.deepTimeEnabled(false);
            float off = Climate.uncachedCelsius(level, pos);
            GameTests.record("deeptime loaded", Compat.isLoaded(Compat.DEEP_TIME));
            // With Deep Time installed, a level it did not generate still has no reading.
            if (Compat.isLoaded(Compat.DEEP_TIME))
                GameTests.assertTrue("no Deep Time reading in a level Deep Time did not generate",
                        UnifiedEnvironmentProvider.deepTime(level, pos, ClimateConfig.source()) == null);
            GameTests.assertNear("deepTime.enabled on vs off in a non-Deep-Time world", on, off, 0.0);
        } finally {
            ClimateConfig.Test.clear();
        }
        helper.succeed();
    }
}
