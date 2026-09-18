package com.minecraftindustrialcomplex.mic_climate.mixin.powergrid;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.minecraftindustrialcomplex.mic_climate.Climate;
import com.minecraftindustrialcomplex.mic_climate.config.ClimateConfig;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.patryk3211.powergrid.electricity.base.ThermalBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Power Grid's one answer to "how warm is the air here?" — replaced with the
 * pack's.
 *
 * <p>Since Power Grid 0.6 every consumer of that question (thermal behaviours,
 * wires, light bulbs, solar panels, the thermometer block, circuit boards) goes
 * through the static {@link ThermalBehaviour#getAmbientTemperature(Level, BlockPos)},
 * whose own answer is a straight line through the biome's base temperature:
 * {@code 13.65 x base + 7.1}. Nothing about weather, time of day, seasons or
 * pollution reaches it. Returning {@link Climate#celsius} instead puts every
 * one of those devices on the unified value.
 *
 * <p>Thermal behaviours sample the ambient once on their first tick and keep
 * the figure for the life of the block entity, which was fine for a constant
 * and is not fine for a value that follows the weather. The second injection
 * re-samples the cached field every {@link #REFRESH_TICKS} ticks. That, not the
 * {@code cacheTicks} config, is what bounds how stale a device's idea of the
 * ambient can be.
 *
 * <p>Deliberately not touched: {@code STANDARD_TEMPERATURE}, the design-point
 * constant devices are <em>rated</em> against. Shifting that would re-rate
 * every device rather than warm it.
 */
@Mixin(ThermalBehaviour.class)
public abstract class ThermalBehaviourMixin {

    @Unique
    private static final int REFRESH_TICKS = 100;

    @Shadow
    private float cachedAmbientTemperature;

    @Unique
    private int micc$refreshCountdown;

    @ModifyReturnValue(method = "getAmbientTemperature", at = @At("RETURN"))
    private static float micc$unified(float original, Level level, BlockPos pos) {
        if (!ClimateConfig.powergridEnabled())
            return original;
        return Climate.celsius(level, pos);
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void micc$refreshAmbient(CallbackInfo ci) {
        if (micc$refreshCountdown-- > 0)
            return;
        micc$refreshCountdown = REFRESH_TICKS;

        BlockEntityBehaviour self = (BlockEntityBehaviour) (Object) this;
        Level world = self.getWorld();
        if (world == null)
            return;
        cachedAmbientTemperature = ThermalBehaviour.getAmbientTemperature(world, self.getPos());
    }
}
