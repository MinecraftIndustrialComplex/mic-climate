package com.minecraftindustrialcomplex.mic_climate.mixin.projectatmosphere;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.minecraftindustrialcomplex.mic_climate.atmosphere.ProjectAtmosphereHooked;
import com.minecraftindustrialcomplex.mic_climate.atmosphere.ProjectAtmosphereSeasons;
import net.Gabou.projectatmosphere.modules.atmosphere.RegionAtmosphereState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Project Atmosphere's seasonal sunlight, by region.
 *
 * <p>Its scheduler heats each region across the day by {@code daylight x seasonal x the region's
 * biome multiplier}, where the seasonal multiplier (0.72 in winter, 1.08 in summer, scaled by the
 * season's progress) is one number for the whole level. {@code buildStateView} copies each region's
 * biome multiplier into the view the scheduler works from; on a Deep Time planet this rescales it
 * by the region's own season's multiplier over the level's, so the product is the region's own.
 */
@Pseudo
@Mixin(targets = "net.Gabou.projectatmosphere.modules.atmosphere.AtmosphericUpdateScheduler", remap = false)
public abstract class AtmosphericUpdateSchedulerMixin implements ProjectAtmosphereHooked {

    @WrapOperation(
            method = "buildStateView(Lnet/Gabou/projectatmosphere/util/RegionInstanceKey;Lnet/Gabou/projectatmosphere/modules/atmosphere/RegionAtmosphereState;JJ)Lnet/Gabou/projectatmosphere/modules/atmosphere/AtmosphericUpdateScheduler$StateView;",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/Gabou/projectatmosphere/modules/atmosphere/RegionAtmosphereState;getBiomeSunlightMultiplier()F"
            ),
            require = 0
    )
    private static float micc$regionalSunlight(RegionAtmosphereState state, Operation<Float> original) {
        return ProjectAtmosphereSeasons.sunlight(state, original.call(state));
    }
}
