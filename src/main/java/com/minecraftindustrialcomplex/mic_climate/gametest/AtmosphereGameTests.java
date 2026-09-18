package com.minecraftindustrialcomplex.mic_climate.gametest;

import com.minecraftindustrialcomplex.mic_climate.Climate;
import com.minecraftindustrialcomplex.mic_climate.Compat;
import com.minecraftindustrialcomplex.mic_climate.MicClimate;
import com.minecraftindustrialcomplex.mic_climate.config.ClimateConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Project Atmosphere as the pack's temperature source.
 *
 * <p>These are deliberately the two claims that do not depend on Project
 * Atmosphere's simulation having converged: that its forecast is what the pack
 * reports, and that switching {@code pollution.mode} to {@code ATMOSPHERE}
 * removes the shift from the provider. Asserting the round trip — pollution
 * pushed into a region, read back out through the forecast — needs a longer run
 * than a gametest batch: Project Atmosphere erodes 60% of an externally written
 * offset every 20 ticks, so the value at any instant depends on where in the
 * top-up cycle the reading lands. See PLAN.md §5.7.
 *
 * <p>Both skip themselves when Project Atmosphere is absent, which on this
 * machine is the normal case — see the long comment in {@code build.gradle}
 * about Project Atmosphere 0.8.1.0 against Serene Seasons Plus 5.1.2. The skip
 * has to be the first thing each method does, and every Project Atmosphere call
 * has to sit behind {@link AtmosphereTestBridge}: NeoForge discovers
 * {@code @GameTestHolder} classes from mod scan data, so this class is loaded
 * and its tests are queued whether or not the mod they are about is installed.
 */
@GameTestHolder(MicClimate.MODID)
@PrefixGameTestTemplate(false)
public final class AtmosphereGameTests {

    private AtmosphereGameTests() {}

    /**
     * With Project Atmosphere driving and a clean sky, the pack's temperature is
     * Project Atmosphere's temperature.
     *
     * <p>Pollution has to be out of the picture for this to be an equality
     * rather than an inequality, hence the multiplier override;
     * {@code pollutionWarmsOnce} covers the polluted case.
     *
     * <p>Alone in its batch: it overrides {@code source} and
     * {@code pollution.multiplier}, which are process-wide.
     */
    @GameTest(template = GameTests.TEMPLATE, timeoutTicks = 400,
              batch = "mic_climate_atmosphere_source")
    public static void atmosphereDrivesTheUnifiedValue(GameTestHelper helper) {
        if (GameTests.skipWithout(helper, Compat.PROJECT_ATMOSPHERE))
            return;
        runAtmosphereDrivesTheUnifiedValue(helper);
    }

    private static void runAtmosphereDrivesTheUnifiedValue(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = GameTests.centre(helper);

        Float atmosphere = AtmosphereTestBridge.forecastCelsius(level, pos);
        GameTests.assertTrue("Project Atmosphere answered with a snapshot", atmosphere != null);
        GameTests.record("PA temperatureC", atmosphere);

        ClimateConfig.Test.source(ClimateConfig.Source.PROJECT_ATMOSPHERE);
        ClimateConfig.Test.pollutionMultiplier(0.0);
        try {
            Climate.invalidate(level);
            GameTests.assertNear(
                    "with pollution neutralised the unified value is Project Atmosphere's",
                    Climate.celsius(level, pos), atmosphere, 0.5
            );
        } finally {
            ClimateConfig.Test.clear();
            Climate.invalidate(level);
        }

        helper.succeed();
    }

    /**
     * {@code pollution.mode = ATMOSPHERE} takes the shift out of the provider.
     *
     * <p>That is the whole no-double-count rule for the experimental mode: in
     * {@code ATMOSPHERE} the warming is pushed into Project Atmosphere's own
     * regional state and read back through the forecast, so the provider must
     * stop adding it separately. The difference between the two modes, measured
     * with the sky saturated, must be exactly the shift Destroy reports.
     *
     * <p>Readings are taken immediately after the switch, before
     * {@code PollutionAtmosphereEffect}'s next pass, so the regional state is
     * the same in both — which is what makes the difference attributable to the
     * provider alone.
     *
     * <p>Alone in its batch: it saturates the level's pollution, which every
     * other temperature reading in the world would see.
     */
    @GameTest(template = GameTests.TEMPLATE, timeoutTicks = 900,
              batch = "mic_climate_atmosphere_mode")
    public static void atmosphereModeSkipsProviderShift(GameTestHelper helper) {
        if (GameTests.skipWithout(helper, Compat.PROJECT_ATMOSPHERE))
            return;
        if (GameTests.skipWithout(helper, Compat.DESTROY))
            return;
        runAtmosphereModeSkipsProviderShift(helper);
    }

    private static void runAtmosphereModeSkipsProviderShift(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = GameTests.centre(helper);

        float[] shift = new float[1];
        float[] modifier = new float[1];

        helper.startSequence()
                .thenExecute(() -> {
                    ClimateConfig.Test.pollutionMode(ClimateConfig.PollutionMode.MODIFIER);
                    DestroyPollutionTestBridge.saturateGreenhouse(level);
                    Climate.invalidate(level);
                })
                // DestroyPollutionShift and Climate each cache for 20 ticks.
                .thenIdle(45)
                .thenExecute(() -> {
                    shift[0] = DestroyPollutionTestBridge.outdoorShift(level)
                            * ClimateConfig.pollutionMultiplier();
                    modifier[0] = Climate.celsius(level, pos);
                    GameTests.record("MODIFIER mode ambient", modifier[0]);
                    GameTests.record("Destroy outdoor shift", shift[0]);
                    GameTests.assertAtLeast("the sky is actually polluted", shift[0], 1.0);

                    ClimateConfig.Test.pollutionMode(ClimateConfig.PollutionMode.ATMOSPHERE);
                    Climate.invalidate(level);
                })
                .thenExecute(() -> {
                    float atmosphere = Climate.celsius(level, pos);
                    GameTests.record("ATMOSPHERE mode ambient", atmosphere);
                    GameTests.assertNear(
                            "ATMOSPHERE mode drops the provider's own pollution term",
                            modifier[0] - atmosphere, shift[0], 0.05
                    );
                })
                .thenExecute(() -> {
                    DestroyPollutionTestBridge.clearGreenhouse(level);
                    ClimateConfig.Test.clear();
                    Climate.invalidate(level);
                })
                .thenSucceed();
    }
}
