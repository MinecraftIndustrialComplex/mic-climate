package com.minecraftindustrialcomplex.mic_climate.gametest;

import com.minecraftindustrialcomplex.mic_climate.Climate;
import com.minecraftindustrialcomplex.mic_climate.config.ClimateConfig;
import com.minecraftindustrialcomplex.mic_climate.crowns.CrownsBridge;
import com.rae.crowns.content.fields.util.PhysicsSaveManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * The only class in the suite that names {@code com.rae.crowns}, for the same
 * reason as the other bridges here: NeoForge loads every
 * {@code @GameTestHolder} it finds, present mod or not.
 */
final class CrownsTestBridge {

    private CrownsTestBridge() {}

    /**
     * Calls {@code PhysicsSaveManager.getDefaultTemperature} exactly as CROWNS
     * does — a section, an absolute position and a block state, with the level
     * published through {@code CrownsBridge.CURRENT_LEVEL} — and checks the
     * answer against both the pack's ambient (with the bridge on) and CROWNS'
     * own table (with it off).
     */
    static void assertDefaultTemperatureIsUnified(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = GameTests.centre(helper);

        LevelChunk chunk = level.getChunkAt(pos);
        LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(pos.getY()));
        Vec3i at = new Vec3i(pos.getX(), pos.getY(), pos.getZ());

        // CROWNS reaches this from its physics thread with the level in hand;
        // the bridge's ThreadLocal is how the static method learns which one.
        CrownsBridge.CURRENT_LEVEL.set(level);
        try {
            float unified = PhysicsSaveManager.getDefaultTemperature(
                    section, at, Blocks.AIR.defaultBlockState());
            GameTests.record("CROWNS getDefaultTemperature for air (K)", unified);
            GameTests.assertNear(
                    "CROWNS' default air temperature is the unified value in kelvin",
                    unified, Climate.kelvin(level, pos), 0.01
            );

            ClimateConfig.Test.crownsEnabled(false);
            float own;
            try {
                own = PhysicsSaveManager.getDefaultTemperature(
                        section, at, Blocks.AIR.defaultBlockState());
            } finally {
                ClimateConfig.Test.clear();
            }
            GameTests.record("CROWNS' own biome-table default (K)", own);
            GameTests.assertTrue(
                    "with crowns.enabled=false CROWNS is back on its own table, which "
                            + "is a different number -- otherwise this test proves nothing",
                    Math.abs(own - unified) > 0.01f
            );
        } finally {
            CrownsBridge.CURRENT_LEVEL.remove();
        }

        helper.succeed();
    }
}
