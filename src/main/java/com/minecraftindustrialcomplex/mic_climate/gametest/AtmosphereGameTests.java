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
 * <p>Two claims: that Project Atmosphere's reading is what the pack reports, and
 * that Destroy's warming goes inside Project Atmosphere's own temperature once
 * (the pollution hook, {@code pollution.projectAtmosphere}) and is counted once
 * by the unified value. The old {@code pollution.mode = ATMOSPHERE} push, which
 * Project Atmosphere eroded, is retired.
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
     * Destroy's warming goes inside Project Atmosphere's own temperature, once, and holds.
     *
     * <p>With the sky saturated and {@code pollution.projectAtmosphere} on, compared with it off:
     * the region's seasonal base (what Project Atmosphere relaxes toward) and its two forecast-built
     * per-block readings (rain or snow, snow and freeze) rise by exactly the shift Destroy reports,
     * while its live snapshot does not move until the region simulates. The unified value counts the
     * warming once in both states of the region: added by the provider while the region has not
     * simulated, and taken from Project Atmosphere's reading once it has (the region's live
     * temperature set onto its seasonal base stands in for the scheduler). The retired
     * {@code pollution.mode = ATMOSPHERE} gives the same unified value as {@code MODIFIER}.
     *
     * <p>Alone in its batch: it saturates the level's pollution and writes a region's live
     * temperature, both of which every other reading in the world would see.
     */
    @GameTest(template = GameTests.TEMPLATE, timeoutTicks = 900,
              batch = "mic_climate_atmosphere_pollution")
    public static void pollutionWarmsProjectAtmosphereOnce(GameTestHelper helper) {
        if (GameTests.skipWithout(helper, Compat.PROJECT_ATMOSPHERE))
            return;
        if (GameTests.skipWithout(helper, Compat.DESTROY))
            return;
        runPollutionWarmsProjectAtmosphereOnce(helper);
    }

    private static void runPollutionWarmsProjectAtmosphereOnce(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = GameTests.centre(helper);
        float[] shift = new float[1];

        helper.startSequence()
                .thenExecute(() -> {
                    ClimateConfig.Test.pollutionMode(ClimateConfig.PollutionMode.MODIFIER);
                    DestroyPollutionTestBridge.saturateGreenhouse(level);
                    Climate.invalidate(level);
                })
                // DestroyPollutionShift and Climate each cache for 20 ticks; the hook publishes each tick.
                .thenIdle(45)
                .thenExecute(() -> {
                    shift[0] = DestroyPollutionTestBridge.outdoorShift(level) * ClimateConfig.pollutionMultiplier();
                    GameTests.record("Destroy outdoor shift", shift[0]);
                    GameTests.assertAtLeast("the sky is actually polluted", shift[0], 1.0);
                    GameTests.assertNear("the hook publishes Destroy's shift",
                            AtmosphereBaseTestBridge.pollutionShift(level), shift[0], 0.05);

                    AtmosphereBaseTestBridge.Region region = AtmosphereBaseTestBridge.region(pos);
                    AtmosphereBaseTestBridge.Readings on = AtmosphereBaseTestBridge.readings(level, pos);
                    float baseOn = region == null ? Float.NaN : region.effectiveBase();
                    ClimateConfig.Test.pollutionProjectAtmosphere(false);
                    AtmosphereBaseTestBridge.Readings off = AtmosphereBaseTestBridge.readings(level, pos);
                    float baseOff = region == null ? Float.NaN : region.effectiveBase();
                    ClimateConfig.Test.pollutionProjectAtmosphere(null);

                    GameTests.assertNear("rain-or-snow temperature rises by the shift",
                            on.precipitation() - off.precipitation(), shift[0], 0.05);
                    GameTests.assertNear("snow/freeze temperature rises by the shift",
                            on.local() - off.local(), shift[0], 0.05);
                    if (region != null) {
                        GameTests.assertNear("the region's seasonal base rises by the shift", baseOn - baseOff, shift[0], 0.05);
                        GameTests.assertNear("an unsimulated region's snapshot has not moved yet",
                                on.snapshot() - off.snapshot(), 0.0, 1e-4);

                        // Not simulated: the provider adds the warming to Project Atmosphere's reading.
                        Climate.invalidate(level);
                        GameTests.assertNear("unsimulated: unified = Project Atmosphere's reading + the shift",
                                Climate.uncachedCelsius(level, pos), on.snapshot() + shift[0], 0.05);

                        // Simulated: the live temperature sits on the warmed base; the provider adds nothing.
                        float creation = region.live();
                        region.setLive(baseOn);
                        try {
                            float snapshot = AtmosphereBaseTestBridge.readings(level, pos).snapshot();
                            GameTests.assertNear("the simulated region's snapshot carries the warming", snapshot, baseOn, 1e-3);
                            Climate.invalidate(level);
                            GameTests.assertNear("simulated: unified = Project Atmosphere's reading, not it plus the shift",
                                    Climate.uncachedCelsius(level, pos), snapshot, 0.05);
                        } finally {
                            region.setLive(creation);
                        }
                    }

                    Climate.invalidate(level);
                    float modifier = Climate.uncachedCelsius(level, pos);
                    ClimateConfig.Test.pollutionMode(ClimateConfig.PollutionMode.ATMOSPHERE);
                    float atmosphere = Climate.uncachedCelsius(level, pos);
                    GameTests.assertNear("retired ATMOSPHERE mode = MODIFIER", atmosphere, modifier, 1e-4);
                })
                .thenExecute(() -> {
                    DestroyPollutionTestBridge.clearGreenhouse(level);
                    ClimateConfig.Test.clear();
                    Climate.invalidate(level);
                })
                .thenSucceed();
    }
}
