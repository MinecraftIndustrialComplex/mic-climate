package com.minecraftindustrialcomplex.mic_climate.mixin.projectatmosphere;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.minecraftindustrialcomplex.mic_climate.atmosphere.ProjectAtmosphereBase;
import com.minecraftindustrialcomplex.mic_climate.atmosphere.ProjectAtmosphereHooked;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The temperature Project Atmosphere stresses crops by.
 *
 * <p>{@code CropStressManager.evaluate(ServerLevel, BlockPos)} flags heat above 35 &deg;C and cold
 * below 0 &deg;C from the region's live temperature, one number for 2000 blocks. It already has
 * the crop's position, so in a Deep Time world the reading becomes Deep Time's monthly mean at
 * that block plus the region's weather anomaly: a highland farm feels its altitude, and a
 * southern one its own season. Only the temperature is replaced; humidity stays Project
 * Atmosphere's.
 */
@Pseudo
@Mixin(targets = "net.Gabou.projectatmosphere.manager.CropStressManager", remap = false)
public abstract class CropStressManagerMixin implements ProjectAtmosphereHooked {

    @ModifyExpressionValue(
            method = "evaluate(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;)Ljava/util/EnumSet;",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/Gabou/projectatmosphere/manager/ForecastOrchestrator;getCurrentTemperature(Lnet/Gabou/projectatmosphere/util/RegionInstanceKey;J)F"
            ),
            require = 0
    )
    private static float micc$deepTimeCropTemperature(float original, ServerLevel level, BlockPos pos) {
        return ProjectAtmosphereBase.celsius(level, pos, original);
    }
}
