package com.minecraftindustrialcomplex.mic_climate.gametest;

import com.minecraftindustrialcomplex.mic_climate.Compat;
import com.minecraftindustrialcomplex.mic_climate.MicClimate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The two things only a real client can answer.
 *
 * <p>Everything else in this suite runs on a dedicated server, which is the
 * right place for it: this mod's job is to make four mods agree about a number,
 * and that agreement is server-side physics. Two claims are not:
 *
 * <ol>
 *   <li>the documented client-side limitation — Thermoo attaches environment
 *       definitions to biomes at {@code SERVER_STARTED} only, so a client-side
 *       {@code Climate} read falls through to the 20&nbsp;&deg;C default rather
 *       than to the pack's temperature (PLAN.md build log, 2026-09-04). That is
 *       a known and accepted gap, and a test is how it stays known: if a future
 *       Thermoo makes the client answer properly, this test fails and the
 *       limitation gets deleted from the docs rather than quietly outliving its
 *       truth;</li>
 *   <li>that a Power Grid thermometer, whose reading now comes from our mixin,
 *       can be placed in a rendered world without the client throwing.</li>
 * </ol>
 *
 * <p>They run under {@code gametest-client.sh} (mc-runtime-test / HeadlessMC),
 * which boots the real client into a singleplayer world. On a dedicated server
 * both skip themselves, so the headless suite is unaffected.
 *
 * <p>Everything touching {@code net.minecraft.client} lives in
 * {@link ClientTestBridge}: NeoForge's {@code RuntimeDistCleaner} removes
 * client classes on a dedicated server, so naming one from a method body that
 * runs there is a hard failure, not a skipped test.
 */
@GameTestHolder(MicClimate.MODID)
@PrefixGameTestTemplate(false)
public final class ClientGameTests {

    private static final ResourceLocation THERMOMETER =
            ResourceLocation.fromNamespaceAndPath("powergrid", "thermometer");

    private ClientGameTests() {}

    /**
     * On the client, {@code Climate} reads Thermoo's default, not the pack's
     * temperature — and that is the known limitation, asserted rather than
     * asserted-about.
     *
     * <p>The same position is read twice, once through the integrated server's
     * level and once through the client's own {@code ClientLevel}. The server
     * value is the pack's; the client value is 20&nbsp;&deg;C. If they ever
     * agree, the limitation is gone.
     */
    @GameTest(template = GameTests.TEMPLATE, timeoutTicks = 400, batch = "mic_climate_client")
    public static void clientClimateFallsBackToThermooDefault(GameTestHelper helper) {
        if (skipOnServer(helper))
            return;
        ClientTestBridge.assertClientReadsDefault(helper);
    }

    /**
     * A Power Grid thermometer can stand in a world the client is rendering.
     *
     * <p>Deliberately shallow. The client cannot be made to draw a frame from a
     * gametest tick, so what this proves is that the block, its block entity and
     * its renderer all exist on the client and that placing it did not throw —
     * and mc-runtime-test supplies the rest by failing the whole run on any
     * client-side exception, including one from the render thread.
     */
    @GameTest(template = GameTests.TEMPLATE, timeoutTicks = 600, batch = "mic_climate_client")
    public static void thermometerRendersOnTheClient(GameTestHelper helper) {
        if (skipOnServer(helper))
            return;
        if (GameTests.skipWithout(helper, Compat.POWERGRID))
            return;

        helper.setBlock(GameTests.DEVICE, blockOrFail(THERMOMETER));
        helper.startSequence()
                // The block has to reach the client before the client can be
                // asked about it.
                .thenIdle(20)
                .thenExecute(() -> ClientTestBridge.assertThermometerIsRenderable(
                        helper.absolutePos(GameTests.DEVICE)))
                .thenSucceed();
    }

    /** Passes, loudly, when there is no client to ask. */
    private static boolean skipOnServer(GameTestHelper helper) {
        if (FMLEnvironment.dist == Dist.CLIENT)
            return false;
        MicClimate.LOGGER.info(
                "[gametest] SKIPPED: this is a dedicated server, and the test is about the client");
        helper.succeed();
        return true;
    }

    private static net.minecraft.world.level.block.state.BlockState blockOrFail(ResourceLocation id) {
        Block block = BuiltInRegistries.BLOCK.get(id);
        GameTests.assertTrue(id + " is a registered block", block != null && block != Blocks.AIR);
        return block.defaultBlockState();
    }
}
