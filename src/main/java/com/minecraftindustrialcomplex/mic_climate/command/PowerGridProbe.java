package com.minecraftindustrialcomplex.mic_climate.command;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.patryk3211.powergrid.electricity.base.ThermalBehaviour;

/**
 * The {@code powergrid} line of {@code /mic_climate probe}.
 *
 * <p>The only class in this package that names {@code org.patryk3211}, for the
 * same reason as every other bridge here: {@link ClimateCommands} is loaded on
 * any pack, and a pack without Power Grid must not have to resolve these
 * imports to register a command.
 *
 * <p>The number it reports is the one the mixin returns, taken through Power
 * Grid's own public entry point rather than from {@code Climate} — the whole
 * point of the line is that they are the same number arrived at by different
 * routes, and reading ours twice would prove nothing.
 */
final class PowerGridProbe {

    private PowerGridProbe() {}

    static float ambientCelsius(Level level, BlockPos pos) {
        return ThermalBehaviour.getAmbientTemperature(level, pos);
    }
}
