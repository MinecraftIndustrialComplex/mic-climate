package com.minecraftindustrialcomplex.mic_climate.command;

import com.minecraftindustrialcomplex.mic_climate.provider.DestroyPollutionShift;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import petrolpark.mc.destroy.DestroyPollutionTypes;
import petrolpark.mc.destroy.core.pollution.PollutionHelper;
import petrolpark.mc.destroy.core.pollution.PollutionType;

import java.util.Locale;

/**
 * The {@code destroy} line of {@code /mic_climate probe}, and the greenhouse
 * dial behind {@code /mic_climate pollution}. The only class in this package
 * that names {@code petrolpark.*}.
 *
 * <p>Destroy is the one mod that is on both sides of this connector — it
 * produces the pollution shift the unified provider folds in, and it consumes
 * the unified value through {@code PollutionHelper.getLocalTemperature} — so
 * its line carries both: the temperature it now reports, and the shift that
 * went into it. A reader comparing the two against the {@code climate} line can
 * see at a glance whether the pollution warming was counted once or twice.
 */
final class DestroyProbe {

    private DestroyProbe() {}

    static String probeLine(Level level, BlockPos pos) {
        float kelvin = PollutionHelper.getLocalTemperature(level, pos);
        float shift = DestroyPollutionShift.shift(level);
        float proportion = PollutionHelper.getPollutionProportion(level, greenhouse());

        return ClimateCommands.line(
                "destroy",
                ClimateCommands.kelvin(kelvin),
                String.format(
                        Locale.ROOT,
                        "(PollutionHelper.getLocalTemperature)  pollution shift %+.2f  greenhouse %.0f%%%s",
                        shift, proportion * 100f,
                        PollutionHelper.isPollutionEnabled() ? "" : "  [Destroy pollution DISABLED]"));
    }

    /**
     * Sets Destroy's level-wide greenhouse pollution to a fraction of its own
     * maximum.
     *
     * <p>Delegating to {@code PollutionHelper.setPollution} rather than to
     * Destroy's command is what keeps a smoke-test script independent of
     * Destroy's command syntax; taking a fraction rather than a raw count keeps
     * it independent of Destroy's scale, which is a config value.
     *
     * @return the value Destroy actually holds afterwards
     */
    static int setGreenhouseFraction(Level level, float fraction) {
        PollutionType<Level> type = greenhouse();
        int max = PollutionHelper.getLevelPollutionTypeProperties(type).max();
        int target = Mth.clamp(Math.round(fraction * max), 0, max);
        return PollutionHelper.setPollution(level, type, target);
    }

    private static PollutionType<Level> greenhouse() {
        return DestroyPollutionTypes.GREENHOUSE.get();
    }
}
