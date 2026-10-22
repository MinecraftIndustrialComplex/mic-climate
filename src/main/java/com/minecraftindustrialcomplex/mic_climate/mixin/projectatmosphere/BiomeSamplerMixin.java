package com.minecraftindustrialcomplex.mic_climate.mixin.projectatmosphere;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.minecraftindustrialcomplex.mic_climate.atmosphere.ProjectAtmosphereHooked;
import com.minecraftindustrialcomplex.mic_climate.atmosphere.ProjectAtmosphereRandomStates;
import net.minecraft.core.HolderGetter;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Project Atmosphere's biome sampler takes its {@link RandomState} from
 * {@code atmosphere.ProjectAtmosphereRandomStates} instead of building a new one for every
 * forecast square: the same state, built once per server, so a region's first temperature query no
 * longer recompiles the overworld noise router on the asking thread.
 */
@Pseudo
@Mixin(targets = "net.Gabou.projectatmosphere.async.BiomeSampler", remap = false)
public abstract class BiomeSamplerMixin implements ProjectAtmosphereHooked {

    @WrapOperation(
            method = "<init>(JLnet/minecraft/core/RegistryAccess;Lnet/minecraft/world/level/biome/BiomeSource;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/levelgen/RandomState;create(Lnet/minecraft/world/level/levelgen/NoiseGeneratorSettings;Lnet/minecraft/core/HolderGetter;J)Lnet/minecraft/world/level/levelgen/RandomState;"
            ),
            require = 0
    )
    private RandomState micc$sharedState(NoiseGeneratorSettings settings, HolderGetter<NormalNoise.NoiseParameters> noises,
                                         long seed, Operation<RandomState> original) {
        return ProjectAtmosphereRandomStates.get(settings, noises, seed, () -> original.call(settings, noises, seed));
    }
}
