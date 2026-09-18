package com.minecraftindustrialcomplex.mic_climate.mixin.destroy;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.minecraftindustrialcomplex.mic_climate.Climate;
import com.minecraftindustrialcomplex.mic_climate.config.ClimateConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import petrolpark.mc.destroy.core.pollution.PollutionHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Destroy's chemistry now happens in the pack's weather.
 *
 * <p>{@link PollutionHelper#getLocalTemperature(Level, BlockPos)} is where
 * Destroy asks how warm it is outside, in kelvin, and its own answer is the
 * greenhouse-adjusted outdoor temperature plus ten times the biome's base
 * value. Vats cool toward it, an empty vat reads it out to the player,
 * distillation towers and basin reactions run against it — and none of it moved
 * with the time of day or the season.
 *
 * <p>This replaces the value outright rather than shifting it, and that is
 * safe against double counting: the pollution term Destroy adds here is already
 * inside the unified value, contributed once by
 * {@code DestroyPollutionShift} inside our environment provider. Destroy's
 * {@code enablePollution} and {@code temperatureAffected} configs still decide
 * whether it contributes anything, because the shift is derived from the same
 * {@code getOutdoorTemperature()} they gate.
 */
@Mixin(PollutionHelper.class)
public abstract class PollutionHelperMixin {

    @ModifyReturnValue(method = "getLocalTemperature", at = @At("RETURN"))
    private static float micc$unified(float original, Level level, BlockPos pos) {
        if (!ClimateConfig.destroyEnabled())
            return original;
        return Climate.kelvin(level, pos);
    }
}
