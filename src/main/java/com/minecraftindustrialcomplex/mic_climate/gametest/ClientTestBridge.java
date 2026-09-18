package com.minecraftindustrialcomplex.mic_climate.gametest;

import com.minecraftindustrialcomplex.mic_climate.Climate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * The only class in the suite that names {@code net.minecraft.client}.
 *
 * <p>NeoForge's {@code RuntimeDistCleaner} refuses to load client classes on a
 * dedicated server, and NeoForge queues every {@code @GameTestHolder} it finds
 * regardless of side — so {@link ClientGameTests} is loaded on the headless
 * server too, and the only thing keeping that from being a hard failure is that
 * none of its own method bodies mention a client type. This is where they live
 * instead, reached only after the {@code Dist.CLIENT} check.
 *
 * <p>Note also that these run on the <em>server</em> thread of an integrated
 * server. Nothing here draws or mutates client state; it reads fields the
 * client publishes and asks the renderer dispatcher a question, which is safe
 * from another thread and is as far as a gametest tick can reach into a client.
 */
final class ClientTestBridge {

    private ClientTestBridge() {}

    /**
     * The client's {@code Climate} read is Thermoo's 20&nbsp;&deg;C default,
     * while the server's is the pack's real temperature.
     */
    static void assertClientReadsDefault(GameTestHelper helper) {
        BlockPos pos = GameTests.centre(helper);

        float server = Climate.celsius(helper.getLevel(), pos);
        GameTests.record("server-side Climate.celsius", server);

        ClientLevel clientLevel = Minecraft.getInstance().level;
        GameTests.assertTrue("the client has a level loaded", clientLevel != null);

        float client = Climate.celsius(clientLevel, pos);
        GameTests.record("client-side Climate.celsius", client);

        GameTests.assertNear(
                "the client falls back to Thermoo's default, as documented in PLAN.md",
                client, Climate.FALLBACK_CELSIUS, 0.01
        );
        GameTests.assertTrue(
                "the server value is the pack's, not the client fallback -- if this fails "
                        + "because the two now agree, Thermoo has started answering client-side "
                        + "and the documented limitation should be deleted",
                Math.abs(server - Climate.FALLBACK_CELSIUS) > 0.01f
        );

        helper.succeed();
    }

    /** The thermometer exists on the client and has a renderer. */
    static void assertThermometerIsRenderable(BlockPos absolutePos) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        GameTests.assertTrue("the client has a level loaded", level != null);

        GameTests.record("client block at the thermometer", level.getBlockState(absolutePos));

        BlockEntity blockEntity = level.getBlockEntity(absolutePos);
        GameTests.assertTrue(
                "the client received the thermometer's block entity", blockEntity != null);
        GameTests.assertTrue(
                "the client has a renderer for it",
                minecraft.getBlockEntityRenderDispatcher().getRenderer(blockEntity) != null
        );
    }
}
