package com.minecraftindustrialcomplex.mic_climate.gametest;

import com.minecraftindustrialcomplex.mic_climate.Climate;
import com.minecraftindustrialcomplex.mic_climate.Compat;
import com.minecraftindustrialcomplex.mic_climate.MicClimate;
import com.minecraftindustrialcomplex.mic_climate.config.ClimateConfig;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import sfiomn.legendarysurvivaloverhaul.api.temperature.TemperatureUtil;

/**
 * The Legendary Survival Overhaul bridge.
 *
 * <p>Both tests are differences rather than absolute values. LSO's world
 * temperature is a sum over every registered modifier — biome, altitude, time,
 * wetness and the rest — and reproducing that sum in a test would be a
 * reimplementation, not an assertion. Taking the same reading twice with one
 * switch flipped cancels everything except the term under test.
 */
@GameTestHolder(MicClimate.MODID)
@PrefixGameTestTemplate(false)
public final class LsoGameTests {

    private static final ResourceLocation BASIN_HEATER =
            ResourceLocation.fromNamespaceAndPath("powergrid", "basin_heater");

    /** What {@code DeviceHeatModifier} treats as "not hot". */
    private static final float DEVICE_NEUTRAL_CELSIUS = 22f;

    private LsoGameTests() {}

    /**
     * LSO's world temperature contains the pack's climate, on LSO's scale.
     *
     * <p>Registering onto another mod's {@code DeferredRegister} is the kind of
     * thing that fails silently — a load-order change and our modifiers are
     * simply never summed, with no error anywhere. The difference being exactly
     * {@code (celsius - neutral) * unitsPerDegree} is the proof that they are.
     */
    @GameTest(template = GameTests.TEMPLATE, timeoutTicks = 400, batch = "mic_climate_lso_switch")
    public static void lsoWorldTemperatureIncludesClimate(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = GameTests.centre(helper);

        float celsius = Climate.celsius(level, pos);
        float expected = (celsius - ClimateConfig.lsoNeutralCelsius())
                * ClimateConfig.lsoUnitsPerDegree();

        float with = TemperatureUtil.getWorldTemperature(level, pos);

        ClimateConfig.Test.lsoEnabled(false);
        float without;
        try {
            without = TemperatureUtil.getWorldTemperature(level, pos);
        } finally {
            ClimateConfig.Test.clear();
        }

        GameTests.record("LSO world temperature with mic_climate", with);
        GameTests.record("LSO world temperature without mic_climate", without);
        GameTests.record("unified ambient (C)", celsius);
        // 0.1, not 0.01: LSO rounds getWorldTemperature to one decimal place, so
        // a difference of two of its readings carries up to 0.1 of rounding and
        // nothing finer can be asserted through this API.
        GameTests.assertNear(
                "mic_climate's contribution to LSO is (celsius - neutral) * unitsPerDegree",
                with - without, expected, 0.1
        );

        helper.succeed();
    }

    /**
     * Standing on a seething basin heater is warmer than not.
     *
     * <p>{@code DeviceHeatModifier}'s full influence applies within half a block,
     * so the reading is taken at the device's own position, where the expected
     * contribution is the plateau value {@code (T - 22) * tempScalar / 100} with
     * no distance fade to model. The comparison is the same position with
     * {@code lso.deviceHeat.enabled = false}, which cancels every other modifier
     * — including any device another test might have left standing nearby.
     *
     * <p>The 15-tick gap is not padding: {@code DeviceHeatModifier} caches its
     * answer per position for 10 ticks, so a second reading taken immediately
     * would be the first one again. The behaviour's temperature is re-pinned
     * before each reading because Power Grid cools it toward ambient every tick.
     *
     * <p>Its own batch, for the same reason as the Power Grid device test: it
     * stands up a heat source.
     */
    @GameTest(template = GameTests.TEMPLATE, timeoutTicks = 600, batch = "mic_climate_device_heat")
    public static void lsoDeviceHeatFromHotBehaviour(GameTestHelper helper) {
        if (!Compat.isLoaded(Compat.POWERGRID)) {
            GameTests.record("powergrid", "absent -- device heat has no source");
            helper.succeed();
            return;
        }

        float hotCelsius = 1600f;
        float expected = (hotCelsius - DEVICE_NEUTRAL_CELSIUS)
                * ClimateConfig.lsoDeviceHeatTempScalar() / 100f;

        BlockPos devicePos = helper.absolutePos(GameTests.DEVICE);
        helper.setBlock(GameTests.DEVICE, blockOrFail(BASIN_HEATER));

        float[] hot = new float[1];

        helper.startSequence()
                .thenIdle(5)
                .thenExecute(() -> {
                    pin(helper, devicePos, hotCelsius);
                    hot[0] = TemperatureUtil.getWorldTemperature(helper.getLevel(), devicePos);
                    GameTests.record("LSO world temperature at a 1600C heater", hot[0]);
                    ClimateConfig.Test.lsoDeviceHeatEnabled(false);
                })
                // DeviceHeatModifier caches per position for 10 ticks.
                .thenIdle(15)
                .thenExecute(() -> {
                    pin(helper, devicePos, hotCelsius);
                    float cold = TemperatureUtil.getWorldTemperature(helper.getLevel(), devicePos);
                    GameTests.record("LSO world temperature with device heat off", cold);
                    // Same 0.1: LSO rounds each reading to one decimal, so the
                    // expected 0.6312 can only ever show up here as 0.6 or 0.7.
                    GameTests.assertNear(
                            "a 1600C device contributes (T - 22) * tempScalar / 100 LSO units",
                            hot[0] - cold, expected, 0.1
                    );
                })
                .thenExecute(ClimateConfig.Test::clear)
                .thenSucceed();
    }

    /** Holds the device at a temperature Power Grid's own tick would bleed off. */
    private static void pin(GameTestHelper helper, BlockPos absolutePos, float celsius) {
        BlockEntity blockEntity = helper.getLevel().getBlockEntity(absolutePos);
        GameTests.assertTrue("a block entity exists at " + absolutePos, blockEntity != null);

        org.patryk3211.powergrid.electricity.base.ThermalBehaviour behaviour =
                BlockEntityBehaviour.get(
                        blockEntity, org.patryk3211.powergrid.electricity.base.ThermalBehaviour.TYPE);
        GameTests.assertTrue("the basin heater carries a ThermalBehaviour", behaviour != null);
        behaviour.setTemperature(celsius);
    }

    private static net.minecraft.world.level.block.state.BlockState blockOrFail(ResourceLocation id) {
        Block block = BuiltInRegistries.BLOCK.get(id);
        GameTests.assertTrue(id + " is a registered block", block != null && block != Blocks.AIR);
        return block.defaultBlockState();
    }
}
