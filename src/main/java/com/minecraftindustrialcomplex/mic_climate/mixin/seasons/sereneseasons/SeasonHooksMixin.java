package com.minecraftindustrialcomplex.mic_climate.mixin.seasons.sereneseasons;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.minecraftindustrialcomplex.mic_climate.seasons.SeasonsHooked;
import com.minecraftindustrialcomplex.mic_climate.seasons.SereneSeasonsHemispheres;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import sereneseasons.api.season.Season;

/**
 * Serene Seasons' seasonal biome temperature, by latitude.
 *
 * <p>{@code SeasonHooks.getBiomeTemperature(Level, Holder, BlockPos)} is where Serene Seasons turns
 * its one season into a temperature shift (-0.8 in winter by default): it asks
 * {@code getBiomeTemperatureInSeason} with the level's sub-season. Snow and ice placement, rain or
 * snow (server and client), {@code isRainingAt}, the melt test and Serene Seasons Plus's snow test
 * all read it. This wraps that one call: on a Deep Time planet the sub-season is the hemisphere's,
 * and where the seasons fade the answer is blended toward Serene Seasons' own Mid Summer value.
 * Serene Seasons computes both; nothing of its logic is reproduced.
 */
@Pseudo
@Mixin(targets = "sereneseasons.season.SeasonHooks", remap = false)
public abstract class SeasonHooksMixin implements SeasonsHooked {

    @WrapOperation(
            method = "getBiomeTemperature(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/Holder;Lnet/minecraft/core/BlockPos;)F",
            at = @At(
                    value = "INVOKE",
                    target = "Lsereneseasons/season/SeasonHooks;getBiomeTemperatureInSeason(Lsereneseasons/api/season/Season$SubSeason;Lnet/minecraft/core/Holder;Lnet/minecraft/core/BlockPos;)F"
            ),
            require = 0
    )
    private static float micc$latitudeTemperature(Season.SubSeason subSeason, Holder<Biome> biome, BlockPos pos,
                                                  Operation<Float> original, @Local(argsOnly = true) Level level) {
        return SereneSeasonsHemispheres.biomeTemperature(level, subSeason, biome, pos, original);
    }
}
