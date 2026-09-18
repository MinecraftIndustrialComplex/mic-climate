package com.minecraftindustrialcomplex.mic_climate.mixin.powergrid;

import net.minecraft.world.level.Level;
import org.patryk3211.powergrid.electricity.base.ThermalBehaviour;
import org.patryk3211.powergrid.general.ceilingtile.solar.CeilingTileSolarBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The solar ceiling tile is the Solar Panel's flat sibling and models cell
 * efficiency identically, so it needs the same periodic re-sample of its
 * once-cached ambient temperature.
 *
 * @see SolarPanelBlockEntityMixin
 */
@Mixin(CeilingTileSolarBlockEntity.class)
public abstract class CeilingTileSolarBlockEntityMixin {

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

        CeilingTileSolarBlockEntity self = (CeilingTileSolarBlockEntity) (Object) this;
        Level world = self.getLevel();
        if (world == null)
            return;
        float ambient = ThermalBehaviour.getAmbientTemperature(world, self.getBlockPos());
        if (ambient <= ThermalBehaviour.ABSOLUTE_ZERO)
            ambient = 22f;
        ambientTemp = ambient;
    }
}
