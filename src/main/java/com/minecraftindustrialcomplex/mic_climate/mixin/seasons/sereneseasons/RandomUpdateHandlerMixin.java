package com.minecraftindustrialcomplex.mic_climate.mixin.seasons.sereneseasons;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.minecraftindustrialcomplex.mic_climate.seasons.SeasonsHooked;
import com.minecraftindustrialcomplex.mic_climate.seasons.SereneSeasonsHemispheres;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sereneseasons.api.season.Season;

/**
 * Serene Seasons' melting, by latitude.
 *
 * <p>{@code RandomUpdateHandler.onWorldTick} takes a melt chance and a number of rolls from the
 * level's sub-season (none at all in winter), then, for every chunk near a player, calls
 * {@code meltInChunk} that many times at that chance. With one season for the planet, snow south
 * of the equator would never melt during the northern winter. On a Deep Time planet:
 *
 * <ul>
 *   <li>the loop runs whenever any sub-season melts, at the largest chance and roll count of any
 *       ({@code meltChance()}, {@code meltRolls()});</li>
 *   <li>the first roll for a chunk runs that chunk's own rolls at its own chance, from its local
 *       sub-season's properties in Serene Seasons' config, and the loop's remaining rolls for the
 *       chunk are skipped ({@code meltInChunk}).</li>
 * </ul>
 *
 * <p>Serene Seasons' own loop (which chunks, in what order) and its own {@code meltInChunk} (the
 * column it picks, the temperature test) do all the work; nothing of them is reproduced. Its
 * weather frequency stays the level's. Off a planet every value and call is Serene Seasons' own.
 */
@Pseudo
@Mixin(targets = "sereneseasons.season.RandomUpdateHandler", remap = false)
public abstract class RandomUpdateHandlerMixin implements SeasonsHooked {

    @Inject(method = "onWorldTick(Lglitchcore/event/TickEvent$Level;)V", at = @At("HEAD"), require = 0)
    private static void micc$startMeltPass(CallbackInfo ci) {
        SereneSeasonsHemispheres.resetMelt();
    }

    @ModifyExpressionValue(
            method = "onWorldTick(Lglitchcore/event/TickEvent$Level;)V",
            at = @At(value = "INVOKE", target = "Lsereneseasons/config/SeasonsConfig$SeasonProperties;meltChance()F"),
            require = 0
    )
    private static float micc$meltLoopChance(float chance, @Local ServerLevel level) {
        return SereneSeasonsHemispheres.meltLoopChance(level, chance);
    }

    @ModifyExpressionValue(
            method = "onWorldTick(Lglitchcore/event/TickEvent$Level;)V",
            at = @At(value = "INVOKE", target = "Lsereneseasons/config/SeasonsConfig$SeasonProperties;meltRolls()I"),
            require = 0
    )
    private static int micc$meltLoopRolls(int rolls, @Local ServerLevel level) {
        return SereneSeasonsHemispheres.meltLoopRolls(level, rolls);
    }

    @WrapOperation(
            method = "onWorldTick(Lglitchcore/event/TickEvent$Level;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lsereneseasons/season/RandomUpdateHandler;meltInChunk(Lnet/minecraft/server/level/ChunkMap;Lnet/minecraft/world/level/chunk/LevelChunk;F)V"
            ),
            require = 0
    )
    private static void micc$localMelt(ChunkMap map, LevelChunk chunk, float chance, Operation<Void> original,
                                       @Local ServerLevel level, @Local Season.SubSeason global) {
        SereneSeasonsHemispheres.melt(level, global, map, chunk, chance, original);
    }
}
