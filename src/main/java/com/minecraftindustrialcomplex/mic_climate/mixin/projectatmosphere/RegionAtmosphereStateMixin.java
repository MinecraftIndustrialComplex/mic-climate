package com.minecraftindustrialcomplex.mic_climate.mixin.projectatmosphere;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.minecraftindustrialcomplex.mic_climate.atmosphere.ProjectAtmosphereBase;
import com.minecraftindustrialcomplex.mic_climate.atmosphere.ProjectAtmosphereHooked;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A Project Atmosphere region's seasonal base, from Deep Time's climate.
 *
 * <p>Project Atmosphere keeps one {@code RegionAtmosphereState} per 2000-block region. Its base
 * temperature is a {@code private final} average of a forecast built from biome base
 * temperatures, fixed when the region is created, and one global season offset
 * ({@code SeasonalAtmosphericDrift.currentTemperatureOffsetC()}: +6 in summer, -8 in winter, the
 * same in both hemispheres) is added to it wherever the state reports a base:
 *
 * <ul>
 *   <li>{@code getEffectiveBaseTemperature()}, what the state relaxes toward;</li>
 *   <li>{@code getTargetTemperature(long)}, the forecast's daily curve the scheduler restores;</li>
 *   <li>{@code getBaselineMinTemperature()} / {@code getBaselineMaxTemperature()}, the band the
 *       sunlight term moves the region across between night and noon.</li>
 * </ul>
 *
 * <p>This wraps that one call in those four methods and, in a Deep Time world, answers
 * <em>Deep Time's monthly mean over the region at today's date, minus the region's own base</em>
 * instead. All four then sit on Deep Time's climate for that region and date (its own seasons,
 * southern ones included), and everything Project Atmosphere does on top is unchanged: the
 * forecast's day/night shape, sunlight, rain cooling, advection, its erosion toward the target.
 * The {@code private final} base itself is never written, so a region Project Atmosphere never
 * simulated still reads exactly its base and can still be recognised as such.
 *
 * <p>Outside a Deep Time world, with {@code deepTime.projectAtmosphereBase} off, or on any error,
 * the handler returns Project Atmosphere's own offset: the call is unchanged.
 */
@Pseudo
@Mixin(targets = "net.Gabou.projectatmosphere.modules.atmosphere.RegionAtmosphereState", remap = false)
public abstract class RegionAtmosphereStateMixin implements ProjectAtmosphereHooked {

    @WrapOperation(
            method = {
                    "getTargetTemperature(J)F",
                    "getEffectiveBaseTemperature()F",
                    "getBaselineMinTemperature()F",
                    "getBaselineMaxTemperature()F"
            },
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/Gabou/projectatmosphere/modules/atmosphere/SeasonalAtmosphericDrift;currentTemperatureOffsetC()F"
            ),
            require = 0
    )
    private float micc$deepTimeSeasonOffset(Operation<Float> original) {
        return ProjectAtmosphereBase.seasonOffset(this, original.call());
    }
}
