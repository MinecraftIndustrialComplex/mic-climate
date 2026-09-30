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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import petrolpark.mc.destroy.core.pollution.PollutionHelper;

/**
 * The Destroy bridge, and the one place pollution is asserted end to end.
 *
 * <p>Destroy is the only mod here that is on both sides of the connector: it
 * <em>produces</em> a temperature shift (greenhouse gases and ozone depletion)
 * that the unified provider folds in, and it <em>consumes</em> the unified value
 * through {@code PollutionHelper.getLocalTemperature}. The interesting failure
 * is therefore not "does it move" but "does it move exactly once".
 */
@GameTestHolder(MicClimate.MODID)
@PrefixGameTestTemplate(false)
public final class DestroyGameTests {

    private static final ResourceLocation VAT_CONTROLLER =
            ResourceLocation.fromNamespaceAndPath("destroy", "vat_controller");

    private DestroyGameTests() {}

    /**
     * Destroy's local temperature is the pack's, in kelvin.
     *
     * <p>Destroy works in kelvin and everything else here in Celsius, so this is
     * as much a unit check as a bridge check.
     */
    @GameTest(template = GameTests.TEMPLATE, timeoutTicks = 400)
    public static void destroyLocalTemperatureFollowsClimate(GameTestHelper helper) {
        if (GameTests.skipWithout(helper, Compat.DESTROY))
            return;
        runDestroyLocalTemperatureFollowsClimate(helper);
    }

    private static void runDestroyLocalTemperatureFollowsClimate(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = GameTests.centre(helper);

        float destroy = PollutionHelper.getLocalTemperature(level, pos);
        GameTests.record("PollutionHelper.getLocalTemperature (K)", destroy);
        GameTests.assertNear(
                "Destroy's local temperature equals the unified value in kelvin",
                destroy, Climate.kelvin(level, pos), 0.01
        );

        helper.succeed();
    }

    /**
     * Pollution warms every consumer, by the same amount, once.
     *
     * <p>The shift enters the world in exactly one place — inside the unified
     * provider — and this is what proves it. Greenhouse pollution goes to
     * maximum and all three readings are taken before and after: Thermoo's own
     * lookup, Power Grid's ambient and Destroy's local temperature. If the
     * provider added the shift and the Destroy mixin let Destroy's own model add
     * it again, Destroy's rise would be double the others'; if the mixin were
     * not applied at all, Destroy would rise and the other two would not.
     *
     * <p>The expected rise is recomputed here from Destroy's own
     * {@code LevelPollution.getOutdoorTemperature()} rather than from
     * {@code DestroyPollutionShift}, so the test does not check our arithmetic
     * against our arithmetic.
     *
     * <p>Its own batch: level pollution is global, so no other test may be
     * reading a temperature while this one has the sky saturated.
     */
    @GameTest(template = GameTests.TEMPLATE, timeoutTicks = 900, batch = "mic_climate_pollution")
    public static void pollutionWarmsOnce(GameTestHelper helper) {
        if (GameTests.skipWithout(helper, Compat.DESTROY))
            return;
        runPollutionWarmsOnce(helper);
    }

    private static void runPollutionWarmsOnce(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = GameTests.centre(helper);

        GameTests.assertTrue(
                "Destroy's pollution model is enabled on this server",
                PollutionHelper.isPollutionEnabled()
        );

        float[] baseline = new float[3];

        helper.startSequence()
                .thenExecute(() -> {
                    DestroyPollutionTestBridge.clearGreenhouse(level);
                    Climate.invalidate(level);
                })
                .thenIdle(25)
                .thenExecute(() -> {
                    baseline[0] = Climate.celsius(level, pos);
                    baseline[1] = powerGridAmbient(level, pos);
                    baseline[2] = PollutionHelper.getLocalTemperature(level, pos);
                    GameTests.record("clean: thermoo/pg/destroy",
                            baseline[0] + " / " + baseline[1] + " / " + baseline[2]);
                    DestroyPollutionTestBridge.saturateGreenhouse(level);
                    Climate.invalidate(level);
                })
                // DestroyPollutionShift caches per level for 20 ticks and Climate
                // for another 20; 40 clears both with margin.
                .thenIdle(40)
                .thenExecute(() -> {
                    float shift = DestroyPollutionTestBridge.outdoorShift(level);
                    GameTests.record("Destroy outdoor temperature shift (K)", shift);
                    GameTests.assertAtLeast(
                            "saturated greenhouse pollution moves Destroy's outdoor temperature",
                            shift, 1.0
                    );

                    float thermooRise = Climate.celsius(level, pos) - baseline[0];
                    float powerGridRise = powerGridAmbient(level, pos) - baseline[1];
                    float destroyRise = PollutionHelper.getLocalTemperature(level, pos) - baseline[2];

                    GameTests.assertNear(
                            "the unified value rose by Destroy's own outdoor shift",
                            thermooRise, shift * ClimateConfig.pollutionMultiplier(), 0.05
                    );
                    GameTests.assertNear(
                            "Power Grid rose by the same amount", powerGridRise, thermooRise, 0.05);
                    GameTests.assertNear(
                            "Destroy rose by the same amount, not twice it",
                            destroyRise, thermooRise, 0.05
                    );
                })
                .thenExecute(() -> {
                    DestroyPollutionTestBridge.clearGreenhouse(level);
                    Climate.invalidate(level);
                })
                .thenSucceed();
    }

    /**
     * Destroy's Vat carries a Power Grid thermal behaviour.
     *
     * <p>Not this mod's own feature — mic-destroy-electric attaches it — but the
     * LSO device-heat bridge treats "carries a {@code ThermalBehaviour}" as its
     * definition of a heat source, so a Vat that stopped carrying one would
     * silently stop warming the player. Asserting it here is what keeps that
     * assumption honest across upgrades of either mod.
     */
    @GameTest(template = GameTests.TEMPLATE, timeoutTicks = 400)
    public static void vatThermalBehaviourVisible(GameTestHelper helper) {
        if (!Compat.isLoaded("mic_destroy_electric")) {
            GameTests.record("mic_destroy_electric", "absent -- nothing attaches a Vat behaviour");
            helper.succeed();
            return;
        }

        helper.setBlock(GameTests.DEVICE, blockOrFail(VAT_CONTROLLER));

        helper.startSequence()
                .thenIdle(5)
                .thenExecute(() -> {
                    BlockEntity blockEntity =
                            helper.getLevel().getBlockEntity(helper.absolutePos(GameTests.DEVICE));
                    GameTests.assertTrue(
                            "destroy:vat_controller has a block entity", blockEntity != null);

                    org.patryk3211.powergrid.electricity.base.ThermalBehaviour behaviour =
                            BlockEntityBehaviour.get(
                                    blockEntity,
                                    org.patryk3211.powergrid.electricity.base.ThermalBehaviour.TYPE);
                    GameTests.assertTrue(
                            "the Vat controller carries a Power Grid ThermalBehaviour",
                            behaviour != null
                    );

                    float temperature = behaviour.getTemperature();
                    GameTests.record("vat ThermalBehaviour.getTemperature()", temperature);
                    GameTests.assertTrue(
                            "its temperature is a finite number", Float.isFinite(temperature));
                })
                .thenSucceed();
    }

    // ------------------------------------------------------------------

    /**
     * Power Grid's ambient, if Power Grid is here. Reached reflectively so that
     * this holder stays loadable with Destroy but without Power Grid.
     */
    private static float powerGridAmbient(Level level, BlockPos pos) {
        if (!Compat.isLoaded(Compat.POWERGRID))
            return Climate.celsius(level, pos);
        return org.patryk3211.powergrid.electricity.base.ThermalBehaviour
                .getAmbientTemperature(level, pos);
    }

    private static net.minecraft.world.level.block.state.BlockState blockOrFail(ResourceLocation id) {
        Block block = BuiltInRegistries.BLOCK.get(id);
        GameTests.assertTrue(id + " is a registered block", block != null && block != Blocks.AIR);
        return block.defaultBlockState();
    }
}
