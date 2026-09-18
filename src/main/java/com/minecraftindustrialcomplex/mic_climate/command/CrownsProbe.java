package com.minecraftindustrialcomplex.mic_climate.command;

import com.minecraftindustrialcomplex.mic_climate.crowns.CrownsBridge;
import com.rae.crowns.content.fields.util.PhysicsSaveManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * The {@code crowns} line of {@code /mic_climate probe}, and the only class in
 * this package that names {@code com.rae.*}.
 *
 * <p>What it reports is the <em>default</em> temperature CROWNS would seed an
 * air cell at this position with — the DEFAULT_TEMPERATURE layer's input, which
 * is exactly what {@code mixin.crowns.PhysicsSaveManagerMixin} replaces. It is
 * not the live TEMPERATURE layer: that is the simulation, it only exists for
 * sections CROWNS is actively solving, and it relaxes toward this number rather
 * than equalling it. Asking for the default is the question this mod's bridge
 * actually answers, and it can be asked anywhere rather than only inside a
 * simulated section.
 *
 * <p>The call is made the way CROWNS makes it, including publishing the level
 * through {@link CrownsBridge#CURRENT_LEVEL} — the method is static and takes
 * no {@code Level}, so without that the mixin declines and CROWNS' own biome
 * table answers instead, which would silently make this line disagree with
 * every other one.
 */
final class CrownsProbe {

    private CrownsProbe() {}

    static String probeLine(ServerLevel level, BlockPos pos) {
        LevelChunk chunk = level.getChunkAt(pos);
        int index = chunk.getSectionIndex(pos.getY());
        if (index < 0 || index >= chunk.getSections().length)
            return ClimateCommands.line("crowns", "-", "(no chunk section at this height)");

        LevelChunkSection section = chunk.getSection(index);

        CrownsBridge.CURRENT_LEVEL.set(level);
        try {
            float kelvin = PhysicsSaveManager.getDefaultTemperature(
                    section,
                    new Vec3i(pos.getX(), pos.getY(), pos.getZ()),
                    Blocks.AIR.defaultBlockState());
            return ClimateCommands.line(
                    "crowns",
                    ClimateCommands.kelvin(kelvin),
                    "(PhysicsSaveManager.getDefaultTemperature for air = DEFAULT_TEMPERATURE seed)");
        } finally {
            CrownsBridge.CURRENT_LEVEL.remove();
        }
    }
}
