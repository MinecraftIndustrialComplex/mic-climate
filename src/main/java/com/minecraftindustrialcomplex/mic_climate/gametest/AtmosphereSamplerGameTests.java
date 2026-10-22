package com.minecraftindustrialcomplex.mic_climate.gametest;

import com.minecraftindustrialcomplex.mic_climate.Compat;
import com.minecraftindustrialcomplex.mic_climate.MicClimate;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Project Atmosphere's biome sampler shares one {@code RandomState} per server
 * ({@code atmosphere.ProjectAtmosphereRandomStates}, {@code mixin.projectatmosphere.BiomeSamplerMixin}).
 *
 * <p>Without it, every forecast square builds and (with C2ME) compiles the overworld noise router
 * again, about a second on whichever thread asked, the server thread included. These show that a
 * second sampler, a forecast square and a temperature query in a region nobody has asked about
 * build no new state, and that the shared state samples exactly what Project Atmosphere's own would.
 *
 * <p>Project Atmosphere is only on the classpath with {@code -PwithAtmosphere}; without it these
 * skip, as they do outside its checked versions.
 */
@GameTestHolder(MicClimate.MODID)
@PrefixGameTestTemplate(false)
public final class AtmosphereSamplerGameTests {

    private AtmosphereSamplerGameTests() {}

    /**
     * The mixin bound; a second sampler, Project Atmosphere's {@code sampleSquare} and a temperature
     * query in a region nobody has asked about build no state, and take the cached one. The query
     * reaches the sampler only where the level's sea level is at or above Project Atmosphere's
     * sea-level cut (its forecast samples at sea level and skips centres below the cut); where it is
     * not, the query is only checked to build nothing.
     */
    @GameTest(template = GameTests.TEMPLATE, timeoutTicks = 200, batch = "mic_climate_pa_sampler")
    public static void atmosphereSamplerSharesOneState(GameTestHelper helper) throws ReflectiveOperationException {
        if (GameTests.skipWithout(helper, Compat.PROJECT_ATMOSPHERE))
            return;
        if (AtmosphereBaseGameTests.skipOutsideKnownRange(helper))
            return;
        ServerLevel level = helper.getLevel();
        GameTests.assertTrue("the biome sampler mixin applied", AtmosphereSamplerTestBridge.bound());
        GameTests.record("Project Atmosphere seed", AtmosphereSamplerTestBridge.seed());

        AtmosphereSamplerTestBridge.Sharing sharing = AtmosphereSamplerTestBridge.twoSamplers(level);
        GameTests.record("states built by the first sampler (0 if one was already cached)", sharing.builtByFirst());
        GameTests.record("states built by the second sampler", sharing.builtBySecond());
        GameTests.record("cache hits by the second sampler", sharing.reusedBySecond());
        GameTests.assertTrue("the first sampler built at most one state", sharing.builtByFirst() <= 1);
        GameTests.assertTrue("the second sampler built no state", sharing.builtBySecond() == 0);
        GameTests.assertTrue("the second sampler took the cached one", sharing.reusedBySecond() == 1);
        GameTests.assertTrue("both samplers sample through one climate sampler", sharing.sameState());

        // Far from anything this server or an earlier run (a reused world keeps its regions) has sampled.
        int far = 3_000_000 + (int) (System.nanoTime() % 5000) * 4000;
        AtmosphereSamplerTestBridge.Square square = AtmosphereSamplerTestBridge.sampleSquare(level, far, far, 256);
        GameTests.record("Project Atmosphere's forecast sea-level cut", square.seaLevel());
        GameTests.record("sampleSquare samples", square.samples());
        GameTests.record("sampleSquare states built", square.built());
        GameTests.record("sampleSquare cache hits", square.reused());
        GameTests.assertTrue("sampleSquare built no state", square.built() == 0);
        GameTests.assertTrue("sampleSquare took the cached state", square.reused() == 1);

        BlockPos pos = new BlockPos(-far, level.getSeaLevel() + 1, far);
        AtmosphereSamplerTestBridge.Query query = AtmosphereSamplerTestBridge.temperature(level, pos);
        GameTests.record("temperature in a new region", query.temperatureC());
        GameTests.record("states built by that query", query.built());
        GameTests.record("cache hits by that query", query.reused());
        GameTests.record("level sea level", level.getSeaLevel());
        GameTests.assertTrue("a temperature query in a new region built no state", query.built() == 0);
        if (level.getSeaLevel() >= square.seaLevel())
            GameTests.assertTrue("that query sampled biomes through the cached state", query.reused() >= 1);
        helper.succeed();
    }

    /**
     * The shared state samples what Project Atmosphere's own state would, to the bit: its six climate
     * parameters and the overworld biome it picks, over 1,875 points from -24,000 to 24,000 on three
     * heights. Building that own state for comparison does not touch the cache.
     */
    @GameTest(template = GameTests.TEMPLATE, timeoutTicks = 400, batch = "mic_climate_pa_sampler")
    public static void atmosphereSamplerSamplesItsOwnValues(GameTestHelper helper) throws ReflectiveOperationException {
        if (GameTests.skipWithout(helper, Compat.PROJECT_ATMOSPHERE))
            return;
        if (AtmosphereBaseGameTests.skipOutsideKnownRange(helper))
            return;
        AtmosphereSamplerTestBridge.Equivalence eq = AtmosphereSamplerTestBridge.compareWithOwnState(helper.getLevel());
        GameTests.record("points compared", eq.points());
        GameTests.record("distinct overworld biomes on the grid", eq.biomes().size());
        GameTests.record("climate points that differ from Project Atmosphere's own state", eq.climateDiffers());
        GameTests.record("biomes that differ from Project Atmosphere's own state", eq.biomeDiffers());
        GameTests.record("climate points that differ with the next seed", eq.otherSeedDiffers());
        if (!eq.firstDiff().isEmpty())
            GameTests.record("first difference", eq.firstDiff());
        GameTests.assertTrue("the own state is a separate state", eq.freshIsAnotherState());
        GameTests.assertTrue("building the own state did not touch the cache", eq.builtByFresh() == 0);
        GameTests.assertTrue("the grid spans several biomes", eq.biomes().size() >= 8);
        GameTests.assertTrue("the grid tells seeds apart", eq.otherSeedDiffers() > eq.points() / 2);
        GameTests.assertTrue("every climate point equals Project Atmosphere's own", eq.climateDiffers() == 0);
        GameTests.assertTrue("every biome equals Project Atmosphere's own", eq.biomeDiffers() == 0);
        helper.succeed();
    }
}
