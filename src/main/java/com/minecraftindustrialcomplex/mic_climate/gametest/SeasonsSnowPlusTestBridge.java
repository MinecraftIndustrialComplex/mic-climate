package com.minecraftindustrialcomplex.mic_climate.gametest;

import com.Gabou.sereneseasonsplus.access.ISnowTrackedChunk;
import com.Gabou.sereneseasonsplus.features.CommonSnowBlockFeature;
import com.Gabou.sereneseasonsplus.features.logic.SnowAccumulationPolicy;
import com.minecraftindustrialcomplex.mic_climate.seasons.SeasonsHooked;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import sereneseasons.api.season.Season;

/**
 * Serene Seasons Plus's side of {@link HemisphereSeasonsGameTests}: its own snow policy, asked the
 * way its chunk tick asks it. The only class of the suite that names Serene Seasons Plus.
 */
final class SeasonsSnowPlusTestBridge {

    private SeasonsSnowPlusTestBridge() {}

    static boolean bound() {
        return SeasonsHooked.class.isAssignableFrom(SnowAccumulationPolicy.class);
    }

    /**
     * What Serene Seasons Plus decides for the chunk at {@code pos} when its global season is
     * {@code global} and the chunk is not cold enough for snow: {@code MELT} in its warm season,
     * {@code NONE} otherwise (outside early winter).
     */
    static String decide(ServerLevel level, BlockPos pos, Season.SubSeason global) {
        LevelChunk chunk = level.getChunkAt(pos);
        if (!(chunk instanceof ISnowTrackedChunk tracked))
            return "untracked";
        ChunkPos cp = chunk.getPos();
        SnowAccumulationPolicy.ChunkDecision d = CommonSnowBlockFeature.SNOW_ACCUMULATION_POLICY.evaluateChunk(
                level, global, tracked, cp, false, pos.getY(), false);
        return d.action().name() + "/" + d.reason().name();
    }
}
