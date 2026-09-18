package com.minecraftindustrialcomplex.mic_climate.mixin.powergrid;

import org.patryk3211.powergrid.electricity.light.bulb.LightBulbState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Light bulbs keep their own thermal model rather than using a
 * {@code ThermalBehaviour}, but they read the same ambient lookup that
 * {@link ThermalBehaviourMixin} redirects, and they cache it once, on first
 * tick. Dropping the cache periodically makes the bulb re-sample it, so bulbs
 * are not the one device that never notices the weather.
 */
@Mixin(LightBulbState.class)
public abstract class LightBulbStateMixin {

    @Unique
    private static final int REFRESH_TICKS = 100;

    @Shadow
    private Float cachedAmbientTemperature;

    @Unique
    private int micc$refreshCountdown;

    @Inject(method = "tick", at = @At("HEAD"))
    private void micc$refreshAmbient(CallbackInfo ci) {
        if (micc$refreshCountdown-- > 0)
            return;
        micc$refreshCountdown = REFRESH_TICKS;
        cachedAmbientTemperature = null;
    }
}
