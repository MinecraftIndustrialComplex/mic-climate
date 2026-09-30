package com.minecraftindustrialcomplex.mic_climate.mixin.projectatmosphere;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.minecraftindustrialcomplex.mic_climate.atmosphere.ProjectAtmosphereBase;
import com.minecraftindustrialcomplex.mic_climate.atmosphere.ProjectAtmosphereHooked;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Project Atmosphere's "temperature at this block", which decides rain or snow.
 *
 * <p>{@code ForecastOrchestrator.getCurrentTemperature(ServerLevel, BlockPos, long)} samples the
 * region's biome-built forecast and adds the global season offset. Project Atmosphere's own
 * {@code SeasonHooksMixin} asks it whether Serene Seasons' {@code warmEnoughToRainSeasonal} holds
 * (below 0 &deg;C precipitation falls as snow), and its thermometer, seasonal-tree vigour,
 * commands and public {@code ForecastSampling} read it too. In a Deep Time world the answer
 * becomes Deep Time's monthly mean at the block plus the region's weather anomaly, per block and
 * with the local hemisphere's season; in every world Destroy's pollution warming is added (the
 * forecast it samples does not carry it). The original still runs first, so any region Project
 * Atmosphere loads on demand here is still loaded.
 */
@Pseudo
@Mixin(targets = "net.Gabou.projectatmosphere.manager.ForecastOrchestrator", remap = false)
public abstract class ForecastOrchestratorMixin implements ProjectAtmosphereHooked {

    @ModifyReturnValue(
            method = "getCurrentTemperature(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;J)F",
            at = @At("RETURN"),
            require = 0
    )
    private static float micc$deepTimeTemperature(float original, ServerLevel level, BlockPos pos, long tick) {
        return ProjectAtmosphereBase.celsius(level, pos, original, ProjectAtmosphereBase.Reading.FORECAST);
    }
}
