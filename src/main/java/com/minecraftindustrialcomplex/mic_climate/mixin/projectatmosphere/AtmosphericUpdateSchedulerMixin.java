package com.minecraftindustrialcomplex.mic_climate.mixin.projectatmosphere;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.minecraftindustrialcomplex.mic_climate.atmosphere.ProjectAtmosphereBase;
import com.minecraftindustrialcomplex.mic_climate.atmosphere.ProjectAtmosphereHooked;
import net.Gabou.projectatmosphere.modules.atmosphere.RegionAtmosphereState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Notes each update Project Atmosphere's scheduler gives a region, for the hybrid pollution rule.
 *
 * <p>{@code AtmosphericUpdateScheduler.applyDeltas} is where Project Atmosphere moves every region it
 * simulates, the ACTIVE pass (regions within 1000 blocks of a player, every 20 ticks) and the PASSIVE
 * one (the rest, in batches every 100 ticks), one {@code adjustTemperature} per region. Right after
 * that call this tells {@code atmosphere.ProjectAtmosphereBase#onScheduledUpdate} the region moved,
 * so it can keep track of which regions Project Atmosphere is actively simulating and how much of
 * Destroy's warming each region's live temperature already holds. The update itself is untouched.
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
}
