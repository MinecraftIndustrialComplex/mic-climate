package com.minecraftindustrialcomplex.mic_climate.gametest;

import com.minecraftindustrialcomplex.mic_climate.atmosphere.ProjectAtmosphereSeasons;
import net.Gabou.projectatmosphere.seasons.SeasonSnapshot;
import net.Gabou.projectatmosphere.seasons.SeasonTimeHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Project Atmosphere's side of the hemisphere-season tests: the season its regional drift asks for
 * at a position, and its level-wide one, through its own {@code SeasonTimeHelper}.
 */
final class SeasonsAtmosphereTestBridge {

    private SeasonsAtmosphereTestBridge() {}

    /** Its regional season at {@code pos}: {@code STAGE/MOISTURE}. */
    static String regional(ServerLevel level, BlockPos pos) {
        SeasonSnapshot s = SeasonTimeHelper.snapshot(level, pos);
        return s.stage().name() + "/" + s.moistureStage().name();
    }

    /** Its level-wide season: {@code STAGE/MOISTURE}. */
    static String levelWide(ServerLevel level) {
        SeasonSnapshot s = SeasonTimeHelper.snapshot(level);
        return s.stage().name() + "/" + s.moistureStage().name();
    }

    /** Its seasonal sunlight multiplier for the regional season at {@code pos}. */
    static float sunlight(ServerLevel level, BlockPos pos) throws Throwable {
        return ProjectAtmosphereSeasons.sunlightMultiplier(SeasonTimeHelper.snapshot(level, pos));
    }

    static String describe(ServerLevel level, BlockPos pos) {
        return ProjectAtmosphereSeasons.describe(level, pos);
    }

    /** Whether its tropical wet/dry stage would apply at {@code pos} for a tropical biome. */
    static boolean tropicalStage(ServerLevel level, BlockPos pos) {
        return ProjectAtmosphereSeasons.tropical(level, pos, true);
    }

    static int boundTargets() {
        return ProjectAtmosphereSeasons.boundTargets();
    }
}
