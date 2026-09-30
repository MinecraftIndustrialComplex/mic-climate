package com.minecraftindustrialcomplex.mic_climate.mixin.seasons.sereneseasonsplus;

import com.llamalad7.mixinextras.sugar.Local;
import com.minecraftindustrialcomplex.mic_climate.seasons.SeasonsHooked;
import com.minecraftindustrialcomplex.mic_climate.seasons.SereneSeasonsHemispheres;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import sereneseasons.api.season.Season;

/**
 * Serene Seasons Plus's snow policy, by latitude.
 *
 * <p>Serene Seasons Plus decides per chunk whether to lay its storm snow, melt it, or leave it, in
 * {@code SnowAccumulationPolicy.evaluateChunk}. The sub-season it judges by is a global it caches
 * on every season change; its chunk tick ({@code SnowChunkWeatherLogic.run}) and its load
 * reconciler ({@code SnowChunkLoadReconciler}) both read it and pass it in here, as does its
 * re-check of queued work. So in the northern summer it would melt the southern winter's snow. On
 * a Deep Time planet the sub-season it judges each chunk by is the chunk's own (its middle's
 * discrete local sub-season). Its cold test already reads Serene Seasons' patched temperature.
 * Its storm counter and winter reset stay global (see plans/phase-09-hemisphere-seasons.md).
 */
@Pseudo
@Mixin(targets = "com.Gabou.sereneseasonsplus.features.logic.SnowAccumulationPolicy", remap = false)
public abstract class SnowAccumulationPolicyMixin implements SeasonsHooked {

    @ModifyVariable(
            method = "evaluateChunk(Lnet/minecraft/server/level/ServerLevel;Lsereneseasons/api/season/Season$SubSeason;Lcom/Gabou/sereneseasonsplus/access/ISnowTrackedChunk;Lnet/minecraft/world/level/ChunkPos;ZIZ)Lcom/Gabou/sereneseasonsplus/features/logic/SnowAccumulationPolicy$ChunkDecision;",
            at = @At("HEAD"),
            argsOnly = true,
            require = 0
    )
    private Season.SubSeason micc$localSnowSeason(Season.SubSeason season, @Local(argsOnly = true) ServerLevel level,
                                                  @Local(argsOnly = true) ChunkPos chunkPos) {
        return SereneSeasonsHemispheres.snowPolicySeason(level, chunkPos, season);
    }
}
