package com.minecraftindustrialcomplex.mic_climate.mixin.crowns;

import com.minecraftindustrialcomplex.mic_climate.crowns.CrownsBridge;
import com.rae.crowns.content.fields.util.AbstractMatrixPhysicsSolver;
import com.rae.crowns.content.fields.util.PhysicsWorldData;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Carries the {@link ServerLevel} down to CROWNS' layer initializers, and
 * gives the default temperature layer a heartbeat.
 *
 * <p>These two methods are CROWNS' whole physics tick as far as this mod is
 * concerned: {@code initialise} builds the data layers for sections that have
 * just started being simulated, {@code updateChangedBlocks} rebuilds single
 * cells where a block changed. Both eventually call
 * {@code PhysicsSaveManager.getDefaultTemperature}, which is where
 * {@code PhysicsSaveManagerMixin} substitutes the pack's ambient — and neither
 * passes the level down, so it goes through a thread local instead. Both run on
 * CROWNS' {@code PhysicThread}, so per-thread is exactly the right scope; a
 * dedicated server ticks its levels through that one thread in turn, and each
 * pass overwrites the last.
 *
 * <p>The return injection on {@code initialise} does the second half of the
 * refresh described in {@link CrownsBridge}: sections whose default layer was
 * just rebuilt go back to the solver so the cells can actually move toward it.
 *
 * <p>Both methods have a single {@code RETURN} in the compiled 2.2.5 class, so
 * one injection point each covers every exit.
 */
@Mixin(PhysicsWorldData.class)
public abstract class PhysicsWorldDataMixin {

    @Inject(method = "initialise", at = @At("HEAD"))
    private void micc$beginInitialise(ServerLevel level, CallbackInfo ci) {
        CrownsBridge.beginInitialise((PhysicsWorldData) (Object) this, level);
    }

    @Inject(method = "initialise", at = @At("RETURN"))
    private void micc$endInitialise(ServerLevel level, CallbackInfo ci) {
        CrownsBridge.endInitialise((PhysicsWorldData) (Object) this, level);
    }

    @Inject(method = "updateChangedBlocks", at = @At("HEAD"))
    private void micc$beginUpdateChangedBlocks(
            ServerLevel level, AbstractMatrixPhysicsSolver<?> solver, CallbackInfo ci) {
        CrownsBridge.enter(level);
    }

    @Inject(method = "updateChangedBlocks", at = @At("RETURN"))
    private void micc$endUpdateChangedBlocks(
            ServerLevel level, AbstractMatrixPhysicsSolver<?> solver, CallbackInfo ci) {
        CrownsBridge.exit();
    }
}
