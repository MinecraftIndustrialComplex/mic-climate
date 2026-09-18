package com.minecraftindustrialcomplex.mic_climate.gametest;

import net.minecraft.world.level.Level;
import petrolpark.mc.destroy.DestroyAttachmentTypes;
import petrolpark.mc.destroy.DestroyPollutionTypes;
import petrolpark.mc.destroy.core.pollution.LevelPollution;
import petrolpark.mc.destroy.core.pollution.PollutionHelper;
import petrolpark.mc.destroy.core.pollution.PollutionType;

/**
 * Turning Destroy's greenhouse dial, for tests that are not otherwise about
 * Destroy.
 *
 * <p>Same rule as the mod's own bridges: the {@code petrolpark} imports live in
 * one class that is only resolved from behind a
 * {@code Compat.isLoaded("destroy")} guard, so {@link AtmosphereGameTests} stays
 * loadable in a pack with Project Atmosphere but without Destroy.
 */
final class DestroyPollutionTestBridge {

    private DestroyPollutionTestBridge() {}

    static void saturateGreenhouse(Level level) {
        PollutionHelper.setPollution(level, greenhouse(), Integer.MAX_VALUE);
    }

    static void clearGreenhouse(Level level) {
        PollutionHelper.setPollution(level, greenhouse(), 0);
    }

    /** Destroy's own outdoor warming above its clean-world baseline, in kelvin. */
    static float outdoorShift(Level level) {
        return level.getData(DestroyAttachmentTypes.LEVEL_POLLUTION).getOutdoorTemperature()
                - LevelPollution.BASELINE_OUTDOOR_TEMPERATURE_K;
    }

    private static PollutionType<Level> greenhouse() {
        return DestroyPollutionTypes.GREENHOUSE.get();
    }
}
