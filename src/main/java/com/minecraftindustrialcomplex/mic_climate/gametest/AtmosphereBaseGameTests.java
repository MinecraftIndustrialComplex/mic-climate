package com.minecraftindustrialcomplex.mic_climate.gametest;

import com.minecraftindustrialcomplex.mic_climate.Compat;
import com.minecraftindustrialcomplex.mic_climate.MicClimate;
import com.minecraftindustrialcomplex.mic_climate.atmosphere.ProjectAtmosphereVersions;
import com.minecraftindustrialcomplex.mic_climate.config.ClimateConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.VersionRange;

/**
 * The Deep Time base hook: Deep Time's climate as Project Atmosphere's own base temperature
 * ({@code atmosphere.ProjectAtmosphereBase}, mixins in {@code mixin.projectatmosphere}).
 *
 * <p>This server's world is not a Deep Time world and Deep Time is not on its classpath, so the
 * hook is exercised with {@code ClimateConfig.Test.projectAtmosphereTestClimate}: a constant
 * stand-in for Deep Time's reading. That is enough to prove each of the five mixins bound and
 * moves the number Project Atmosphere decides by, through Project Atmosphere's own entry points:
 * its snapshot, its rain-or-snow temperature, its freeze/snow temperature and the actual
 * {@code Biome.shouldSnow}/{@code shouldFreeze} answers its mixin gives, crop stress, and a region's
 * seasonal base, targets and day/night band. And that without the stand-in (no Deep Time world)
 * every one of them is Project Atmosphere's own, to the bit.
 *
 * <p>Project Atmosphere is only on the classpath with {@code -PwithAtmosphere}; without it these
 * skip. A Project Atmosphere outside {@link ProjectAtmosphereVersions#KNOWN_RANGE} also skips
 * (the plugin does not apply the hook there); inside it, a hook that fails to bind is a failure.
 */
@GameTestHolder(MicClimate.MODID)
@PrefixGameTestTemplate(false)
public final class AtmosphereBaseGameTests {

    private AtmosphereBaseGameTests() {}

    /** All five mixins reached their Project Atmosphere class. */
    @GameTest(template = GameTests.TEMPLATE, timeoutTicks = 100)
    public static void atmosphereBaseHooksBind(GameTestHelper helper) {
        if (GameTests.skipWithout(helper, Compat.PROJECT_ATMOSPHERE))
            return;
        if (skipOutsideKnownRange(helper))
            return;
        int bound = AtmosphereBaseTestBridge.boundTargets();
        GameTests.record("Project Atmosphere hook targets bound", bound + "/5");
        GameTests.assertTrue("all five Project Atmosphere hook mixins applied", bound == 5);
        int client = AtmosphereBaseTestBridge.boundClientTargets();
        GameTests.record("client-table targets bound", client + "/2");
        GameTests.assertTrue("both client-table mixins applied", client == 2);
        GameTests.assertTrue("the hybrid's scheduler mixin applied", AtmosphereBaseTestBridge.schedulerBound());
        helper.succeed();
    }

    /**
     * Not a Deep Time world, switch on: Project Atmosphere's numbers are its own, exactly. The
     * region's seasonal base is its base plus its global season offset, and its snapshot is the
     * region's live temperature, both compared bit for bit.
     *
     * <p>Alone in its batch: it sets {@code ClimateConfig.Test} overrides.
     */
    @GameTest(template = GameTests.TEMPLATE, timeoutTicks = 100, batch = "mic_climate_pa_base_off")
    public static void atmosphereBaseIsInvisibleOutsideDeepTime(GameTestHelper helper) {
        if (GameTests.skipWithout(helper, Compat.PROJECT_ATMOSPHERE))
            return;
        runInvisible(helper);
    }

    private static void runInvisible(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = GameTests.centre(helper);
        try {
            ClimateConfig.Test.deepTimeEnabled(true);
            ClimateConfig.Test.projectAtmosphereBase(true);
            // The pollution part is on in every world; this is about the Deep Time part alone.
            ClimateConfig.Test.pollutionProjectAtmosphere(false);
            GameTests.assertTrue("the hook is inactive in a world Deep Time did not generate",
                    !AtmosphereBaseTestBridge.active(level));
            assertProjectAtmosphereOwn(level, pos, "switch on, no Deep Time world");
        } finally {
            ClimateConfig.Test.clear();
        }
        helper.succeed();
    }

    /**
     * With a stand-in climate of -30 C and then +40 C, every entry point Project Atmosphere decides
     * weather by reads the stand-in (plus the region's weather anomaly, zero on this server since
     * Project Atmosphere never simulates without a player): it lays snow and freezes water at -30,
     * does neither at +40, flags cold and then heat stress; its region's seasonal base is the
     * stand-in while the forecast's daily shape and the day/night band are unchanged. Switching the
     * hook off, or clearing the stand-in, gives Project Atmosphere its own numbers back.
     *
     * <p>Alone in its batch: it sets {@code ClimateConfig.Test} overrides and places water.
     */
    @GameTest(template = GameTests.TEMPLATE, timeoutTicks = 200, batch = "mic_climate_pa_base_on")
    public static void atmosphereBaseFollowsItsClimate(GameTestHelper helper) {
        if (GameTests.skipWithout(helper, Compat.PROJECT_ATMOSPHERE))
            return;
        if (skipOutsideKnownRange(helper))
            return;
        runFollows(helper);
    }

    private static void runFollows(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = GameTests.centre(helper);
        // GameTests.CENTRE and DEVICE are y = 1, which the gametest framework puts on the template's
        // floor layer; snow and water go in the air one block above it.
        BlockPos air = pos.above();
        BlockPos water = helper.absolutePos(GameTests.DEVICE).above();
        long dayTime = level.getDayTime();
        try {
            ClimateConfig.Test.deepTimeEnabled(true);
            ClimateConfig.Test.projectAtmosphereBase(true);
            ClimateConfig.Test.pollutionProjectAtmosphere(false);
            for (float climate : new float[] {-30f, 40f}) {
                ClimateConfig.Test.projectAtmosphereTestClimate(climate);
                String at = String.format(java.util.Locale.ROOT, "stand-in %.0f C", climate);
                GameTests.assertTrue(at + ": the hook is active", AtmosphereBaseTestBridge.active(level));

                float anomaly = AtmosphereBaseTestBridge.anomaly(level, pos);
                GameTests.record(at + ": region weather anomaly", anomaly);
                float expected = climate + anomaly;
                AtmosphereBaseTestBridge.Readings r = AtmosphereBaseTestBridge.readings(level, pos);
                GameTests.assertNear(at + ": AtmoApi snapshot", r.snapshot(), expected, 1e-3);
                GameTests.assertNear(at + ": rain-or-snow temperature", r.precipitation(), expected, 1e-3);
                GameTests.assertNear(at + ": snow/freeze temperature", r.local(), expected, 1e-3);
                GameTests.assertTrue(at + ": cold crop stress iff below 0", r.cold() == (expected < 0f));
                GameTests.assertTrue(at + ": heat crop stress iff above 35", r.heat() == (expected > 35f));

                Biome biome = level.getBiome(air).value();
                boolean snows = biome.shouldSnow(level, air);
                level.setBlockAndUpdate(water, Blocks.WATER.defaultBlockState());
                boolean freezes = level.getBiome(water).value().shouldFreeze(level, water, false);
                level.setBlockAndUpdate(water, Blocks.AIR.defaultBlockState());
                GameTests.record(at + ": shouldSnow / shouldFreeze", snows + " / " + freezes
                        + " (block " + level.getBlockState(air) + ", block light " + level.getBrightness(LightLayer.BLOCK, air)
                        + ", snow survives " + Blocks.SNOW.defaultBlockState().canSurvive(level, air)
                        + ", biome " + level.getBiome(air).getRegisteredName() + ")");
                GameTests.assertTrue(at + ": Project Atmosphere lays snow iff below 0",
                        snows == (climate + AtmosphereBaseTestBridge.anomaly(level, air) < 0f));
                GameTests.assertTrue(at + ": Project Atmosphere freezes water iff below 0",
                        freezes == (climate + AtmosphereBaseTestBridge.anomaly(level, water) < 0f));

                AtmosphereBaseTestBridge.Region region = AtmosphereBaseTestBridge.region(pos);
                if (region == null) {
                    GameTests.record(at + ": region", "none at the test position; region checks skipped");
                    continue;
                }
                GameTests.assertNear(at + ": region seasonal base", region.effectiveBase(), climate, 1e-3);
                GameTests.assertNear(at + ": the forecast's daily shape is kept",
                        region.target(dayTime) - region.effectiveBase(),
                        region.baseTarget(dayTime) - region.base(), 1e-3);
                GameTests.assertNear(at + ": the day/night band is kept", region.bandWidth(), region.rawBandWidth(), 1e-3);

                // The per-player client table: the biome here reads the stand-in (plus the weather anomaly).
                var table = AtmosphereBaseTestBridge.clientTable(level, air, 32);
                var here = level.getBiome(air).unwrapKey().orElseThrow().location();
                GameTests.record(at + ": client table", table.size() + " biome(s), " + here + " = "
                        + (table.containsKey(here) ? table.get(here)[0] : "missing"));
                GameTests.assertTrue(at + ": the client table has the biome here", table.containsKey(here));
                GameTests.assertNear(at + ": its value is the stand-in", table.get(here)[0], expected, 0.5);
            }

            ClimateConfig.Test.projectAtmosphereBase(false);
            GameTests.assertTrue("switch off: the hook is inactive", !AtmosphereBaseTestBridge.active(level));
            assertProjectAtmosphereOwn(level, pos, "switch off, stand-in still set");
        } finally {
            level.setBlockAndUpdate(water, Blocks.AIR.defaultBlockState());
            ClimateConfig.Test.clear();
        }
        assertProjectAtmosphereOwn(level, pos, "overrides cleared");
        helper.succeed();
    }

    /**
     * The client's rain-or-snow rule: snow below 0 C of the table value, rain above; and on a server
     * level (and for a biome that does not precipitate) Serene Seasons' answer is left alone.
     */
    @GameTest(template = GameTests.TEMPLATE, timeoutTicks = 100)
    public static void clientPrecipitationRule(GameTestHelper helper) {
        if (GameTests.skipWithout(helper, Compat.PROJECT_ATMOSPHERE))
            return;
        String rule = AtmosphereBaseTestBridge.precipitationRule(helper.getLevel(), GameTests.centre(helper));
        GameTests.record("decide(-0.1), decide(0.1), server RAIN, server NONE", rule);
        GameTests.assertTrue("snow below 0, rain above; the server's answer untouched", rule.equals("SNOW,RAIN,RAIN,NONE"));
        helper.succeed();
    }

    /** The region's seasonal base and the snapshot are exactly Project Atmosphere's own. */
    private static void assertProjectAtmosphereOwn(ServerLevel level, BlockPos pos, String when) {
        AtmosphereBaseTestBridge.Region region = AtmosphereBaseTestBridge.region(pos);
        AtmosphereBaseTestBridge.Readings r = AtmosphereBaseTestBridge.readings(level, pos);
        GameTests.record(when + ": PA snapshot / rain-or-snow / snow-freeze",
                r.snapshot() + " / " + r.precipitation() + " / " + r.local());
        if (region == null) {
            GameTests.record(when + ": region", "none at the test position; region checks skipped");
            return;
        }
        GameTests.assertNear(when + ": region seasonal base is PA's base + its season offset",
                region.effectiveBase(), region.base() + AtmosphereBaseTestBridge.paSeasonOffset(), 0.0);
        GameTests.assertNear(when + ": AtmoApi snapshot is the region's live temperature",
                r.snapshot(), region.live(), 0.0);
    }

    /** Skips, as a pass, when Project Atmosphere's version is outside the hook's checked range. */
    private static boolean skipOutsideKnownRange(GameTestHelper helper) {
        ArtifactVersion version = ModList.get().getModContainerById(Compat.PROJECT_ATMOSPHERE)
                .map(c -> c.getModInfo().getVersion()).orElse(null);
        boolean known;
        try {
            known = version != null
                    && VersionRange.createFromVersionSpec(ProjectAtmosphereVersions.KNOWN_RANGE).containsVersion(version);
        } catch (Exception e) {
            known = false;
        }
        GameTests.record("Project Atmosphere version", version + (known ? " (checked range)" : " (outside "
                + ProjectAtmosphereVersions.KNOWN_RANGE + ")"));
        if (known)
            return false;
        MicClimate.LOGGER.warn("[gametest] SKIPPED: Project Atmosphere {} is outside {}; the hook is not applied there",
                version, ProjectAtmosphereVersions.KNOWN_RANGE);
        helper.succeed();
        return true;
    }
}
