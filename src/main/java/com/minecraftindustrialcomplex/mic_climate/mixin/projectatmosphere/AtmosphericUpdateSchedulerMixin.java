package com.minecraftindustrialcomplex.mic_climate.mixin.projectatmosphere;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.minecraftindustrialcomplex.mic_climate.atmosphere.ProjectAtmosphereBase;
import com.minecraftindustrialcomplex.mic_climate.atmosphere.ProjectAtmosphereHooked;
import com.minecraftindustrialcomplex.mic_climate.atmosphere.ProjectAtmosphereSeasons;
import net.Gabou.projectatmosphere.modules.atmosphere.RegionAtmosphereState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Two hooks into Project Atmosphere's scheduler: the hybrid pollution rule's note of each update,
 * and the seasonal sunlight by region.
 *
 * <p><b>Updates.</b> {@code AtmosphericUpdateScheduler.applyDeltas} is where Project Atmosphere moves
 * every region it simulates, the ACTIVE pass (regions within 1000 blocks of a player, every 20 ticks)
 * and the PASSIVE one (the rest, in batches every 100 ticks), one {@code adjustTemperature} per
 * region. Right after that call this tells {@code atmosphere.ProjectAtmosphereBase#onScheduledUpdate}
 * the region moved, so it can keep track of which regions Project Atmosphere is actively simulating
 * and how much of Destroy's warming each region's live temperature already holds. The update itself
 * is untouched.
 *
 * <p><b>Sunlight.</b> The scheduler heats each region across the day by {@code daylight x seasonal x
 * the region's biome multiplier}, where the seasonal multiplier (0.72 in winter, 1.08 in summer,
 * scaled by the season's progress) is one number for the whole level. {@code buildStateView} copies
 * each region's biome multiplier into the view the scheduler works from; on a Deep Time planet this
 * rescales it by the region's own season's multiplier over the level's, so the product is the
 * region's own.
 */
@Pseudo
@Mixin(targets = "net.Gabou.projectatmosphere.modules.atmosphere.AtmosphericUpdateScheduler", remap = false)
public abstract class AtmosphericUpdateSchedulerMixin implements ProjectAtmosphereHooked {

    @WrapOperation(
            method = "applyDeltas(Ljava/util/List;JLjava/lang/String;Lnet/Gabou/projectatmosphere/modules/atmosphere/AtmosphericUpdateScheduler$UpdateMode;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/Gabou/projectatmosphere/modules/atmosphere/RegionAtmosphereState;adjustTemperature(F)V"
            ),
            require = 0
    )
    private static void micc$noteUpdate(RegionAtmosphereState state, float delta, Operation<Void> original) {
        original.call(state, delta);
        ProjectAtmosphereBase.onScheduledUpdate(state);
    }

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
