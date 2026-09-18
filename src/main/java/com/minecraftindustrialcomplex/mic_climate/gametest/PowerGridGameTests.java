package com.minecraftindustrialcomplex.mic_climate.gametest;

import com.minecraftindustrialcomplex.mic_climate.Climate;
import com.minecraftindustrialcomplex.mic_climate.MicClimate;
import com.minecraftindustrialcomplex.mic_climate.config.ClimateConfig;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.patryk3211.powergrid.electricity.base.ThermalBehaviour;

import java.lang.reflect.Field;

/**
 * The Power Grid bridge: that {@code ThermalBehaviourMixin} bound, and that the
 * cache-refresh injection really does carry a changed ambient into a device.
 */
@GameTestHolder(MicClimate.MODID)
@PrefixGameTestTemplate(false)
public final class PowerGridGameTests {

    /** A Power Grid device that carries a {@code ThermalBehaviour}. */
    private static final ResourceLocation BASIN_HEATER =
            ResourceLocation.fromNamespaceAndPath("powergrid", "basin_heater");

    private PowerGridGameTests() {}

    /**
     * Power Grid's ambient is the pack's ambient.
     *
     * <p>This is the whole Power Grid bridge in one line. Power Grid's own
     * formula is {@code 13.65 x biomeBase + 7.1} and knows nothing about
     * weather, seasons or pollution, so if the mixin had failed to apply the two
     * numbers would differ by whatever the season and the weather are worth
     * today — and with {@code defaultRequire: 1} a failure to apply would have
     * killed the launch long before this ran. What this adds is proof the
     * injection returns the right value rather than merely existing.
     */
    @GameTest(template = GameTests.TEMPLATE, timeoutTicks = 400)
    public static void powerGridAmbientFollowsClimate(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = GameTests.centre(helper);

        float powerGrid = ThermalBehaviour.getAmbientTemperature(level, pos);
        float unified = Climate.celsius(level, pos);
        GameTests.record("ThermalBehaviour.getAmbientTemperature", powerGrid);
        GameTests.assertNear(
                "Power Grid's ambient equals the unified value", powerGrid, unified, 0.01);

        helper.succeed();
    }

    /**
     * With the bridge switched off, Power Grid is back on its own formula.
     *
     * <p>The complement of the test above, and the one that would catch a mixin
     * that ignored the config: {@code powergrid.enabled = false} must give back
     * exactly {@code 13.65 x biomeBase + 7.1}, which is recomputed here from the
     * biome rather than read from Power Grid, so the two paths are independent.
     */
    @GameTest(template = GameTests.TEMPLATE, timeoutTicks = 400, batch = "mic_climate_powergrid_switch")
    public static void powerGridBridgeCanBeDisabled(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = GameTests.centre(helper);

        ClimateConfig.Test.powergridEnabled(false);
        try {
            float powerGrid = ThermalBehaviour.getAmbientTemperature(level, pos);
            float own = 13.65f * level.getBiome(pos).value().getBaseTemperature() + 7.1f;
            GameTests.record("biome base temperature", level.getBiome(pos).value().getBaseTemperature());
            GameTests.assertNear(
                    "with powergrid.enabled=false Power Grid uses its own biome formula",
                    powerGrid, own, 0.01
            );
        } finally {
            ClimateConfig.Test.clear();
        }

        helper.succeed();
    }

    /**
     * A placed device notices that the world got warmer.
     *
     * <p>A {@code ThermalBehaviour} samples the ambient once, on its first tick,
     * and would otherwise keep that figure until the chunk unloads — which is
     * exactly what the {@code tick} injection exists to fix. So: stand a basin
     * heater up, let it take its first sample, move the ambient, and watch the
     * device's private {@code cachedAmbientTemperature} follow within the
     * injection's 100-tick refresh window.
     *
     * <p>The ambient is moved with {@link ClimateConfig.Test#forcedCelsius},
     * not by polluting the level. The claim under test is "the device re-reads",
     * and pinning the input keeps that claim from resting on how fast Destroy's
     * pollution model and two layers of cache happen to settle;
     * {@code DestroyGameTests.pollutionWarmsOnce} is where the pollution path
     * itself is asserted.
     *
     * <p>The field is read by reflection on purpose. An accessor mixin would be
     * a second thing that could break in the same way as the thing under test;
     * reflection fails loudly and independently.
     *
     * <p>Its own batch, because it stands up a device that radiates heat into
     * anything else measuring device proximity nearby.
     */
    @GameTest(template = GameTests.TEMPLATE, timeoutTicks = 600, batch = "mic_climate_device_cache")
    public static void thermalDeviceCacheRefreshes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos devicePos = helper.absolutePos(GameTests.DEVICE);

        helper.setBlock(GameTests.DEVICE, blockOrFail(helper, BASIN_HEATER));

        float[] before = new float[1];
        float target = 45f;

        helper.startSequence()
                // Let the behaviour take its own first-tick sample.
                .thenIdle(10)
                .thenExecute(() -> {
                    before[0] = cachedAmbient(helper, devicePos);
                    GameTests.record("basin heater cachedAmbientTemperature before", before[0]);
                    GameTests.assertNear(
                            "the device's first sample is the unified ambient",
                            before[0], Climate.celsius(level, devicePos), 0.01
                    );
                    GameTests.assertTrue(
                            "the test's target ambient differs from the world's",
                            Math.abs(target - before[0]) > 5f
                    );
                    ClimateConfig.Test.forcedCelsius(target);
                    Climate.invalidate(level);
                })
                // The device re-reads every 100 ticks; 150 clears that plus the
                // 20-tick chunk cache with room to spare.
                .thenIdle(150)
                .thenExecute(() -> {
                    float after = cachedAmbient(helper, devicePos);
                    GameTests.record("basin heater cachedAmbientTemperature after", after);
                    GameTests.assertNear(
                            "the device re-sampled the new ambient", after, target, 0.01);
                })
                .thenExecute(ClimateConfig.Test::clear)
                .thenSucceed();
    }

    /** Reads Power Grid's private per-behaviour ambient cache. */
    private static float cachedAmbient(GameTestHelper helper, BlockPos absolutePos) {
        BlockEntity blockEntity = helper.getLevel().getBlockEntity(absolutePos);
        GameTests.assertTrue("a block entity exists at " + absolutePos, blockEntity != null);

        ThermalBehaviour behaviour = BlockEntityBehaviour.get(blockEntity, ThermalBehaviour.TYPE);
        GameTests.assertTrue(
                blockEntity.getClass().getSimpleName() + " carries a ThermalBehaviour",
                behaviour != null
        );

        try {
            Field field = ThermalBehaviour.class.getDeclaredField("cachedAmbientTemperature");
            field.setAccessible(true);
            return field.getFloat(behaviour);
        } catch (ReflectiveOperationException e) {
            throw new net.minecraft.gametest.framework.GameTestAssertException(
                    "could not read ThermalBehaviour.cachedAmbientTemperature: " + e);
        }
    }

    static net.minecraft.world.level.block.state.BlockState blockOrFail(
            GameTestHelper helper, ResourceLocation id) {
        Block block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(id);
        GameTests.assertTrue(
                id + " is a registered block",
                block != null && block != net.minecraft.world.level.block.Blocks.AIR
        );
        return block.defaultBlockState();
    }
}
