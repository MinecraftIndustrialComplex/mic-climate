package com.minecraftindustrialcomplex.mic_climate.mixin.powergrid;

import net.minecraft.world.level.Level;
import org.patryk3211.powergrid.electricity.base.ThermalBehaviour;
import org.patryk3211.powergrid.electricity.solarpanel.SolarPanelBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The Solar Panel samples the ambient temperature once on its first tick and
 * keeps it for the life of the block entity, feeding it into cell efficiency —
 * hot cells make less power. With the ambient now following the weather and the
 * seasons, that first sample would be the panel's only opinion until a chunk
 * reload. Re-sampling every {@link #REFRESH_TICKS} ticks closes the gap, the
 * same way {@link ThermalBehaviourMixin} does for thermal behaviours.
 *
 * <p>Smog dimming of the panel's irradiance is a Destroy-and-Power-Grid feature
 * and stays in {@code mic-destroy-electric}; this mixin only handles the
 * temperature side.
 */
@Mixin(SolarPanelBlockEntity.class)
public abstract class SolarPanelBlockEntityMixin {

    @Unique
    private static final int REFRESH_TICKS = 100;

    @Shadow
    private float ambientTemp;

    @Unique
    private int micc$refreshCountdown;

    @Inject(method = "electricalTick", at = @At("HEAD"))
    private void micc$refreshAmbient(CallbackInfo ci) {
        if (micc$refreshCountdown-- > 0)
            return;
        micc$refreshCountdown = REFRESH_TICKS;

        SolarPanelBlockEntity self = (SolarPanelBlockEntity) (Object) this;
        Level world = self.getLevel();
        if (world == null)
            return;
        float ambient = ThermalBehaviour.getAmbientTemperature(world, self.getBlockPos());
        if (ambient <= ThermalBehaviour.ABSOLUTE_ZERO)
            ambient = 22f;
        ambientTemp = ambient;
    }
}
