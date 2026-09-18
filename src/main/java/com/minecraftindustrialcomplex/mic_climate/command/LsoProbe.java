package com.minecraftindustrialcomplex.mic_climate.command;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import sfiomn.legendarysurvivaloverhaul.api.temperature.TemperatureUtil;

/**
 * The {@code lso} line of {@code /mic_climate probe}, and the only class in
 * this package that names {@code sfiomn.*}.
 *
 * <p>Legendary Survival Overhaul's world temperature is in LSO's own units, not
 * degrees, and it is a <em>sum</em> over every registered modifier — altitude,
 * wetness, shade and the rest, plus the two this mod registers. So this line is
 * deliberately not compared against the others by the smoke test: it is
 * recorded, and what matters about it is that it moves when the climate moves.
 * LSO also rounds its answer to one decimal place, which is why nothing finer
 * than 0.1 can be read out of it.
 */
final class LsoProbe {

    private LsoProbe() {}

    static String probeLine(Level level, BlockPos pos) {
        float units = TemperatureUtil.getWorldTemperature(level, pos);
        return ClimateCommands.line(
                "lso",
                String.format(java.util.Locale.ROOT, "%.2f units", units),
                "(TemperatureUtil.getWorldTemperature; a sum over all LSO modifiers)");
    }
}
