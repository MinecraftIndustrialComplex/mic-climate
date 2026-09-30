package com.minecraftindustrialcomplex.mic_climate.mixin.seasons.sereneseasons;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.minecraftindustrialcomplex.mic_climate.seasons.SeasonsHooked;
import com.minecraftindustrialcomplex.mic_climate.seasons.SereneSeasonsHemispheres;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import sereneseasons.api.season.ISeasonState;

/**
 * Serene Seasons' crop fertility, by latitude.
 *
 * <p>{@code ModFertility.isCropFertile(String, Level, BlockPos)} decides whether a crop grows (and
 * takes bonemeal) in the current season, from the level's season. It already has the crop's
 * position; on a Deep Time planet the season it reads becomes the local one: the hemisphere's,
 * pulled toward Mid Summer where the seasons fade, so near the equator the summer crops grow all
 * year, as Serene Seasons' own tropical biomes do. Its other rules (underground, greenhouse glass,
 * infertile, tropical and cold biomes) are untouched.
 */
@Pseudo
@Mixin(targets = "sereneseasons.init.ModFertility", remap = false)
public abstract class ModFertilityMixin implements SeasonsHooked {

    @WrapOperation(
            method = "isCropFertile(Ljava/lang/String;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)Z",
            at = @At(
                    value = "INVOKE",
                    target = "Lsereneseasons/api/season/SeasonHelper;getSeasonState(Lnet/minecraft/world/level/Level;)Lsereneseasons/api/season/ISeasonState;"
            ),
            require = 0
    )
    private static ISeasonState micc$localCropSeason(Level level, Operation<ISeasonState> original,
                                                     @Local(argsOnly = true) BlockPos pos) {
        return SereneSeasonsHemispheres.discreteState(level, pos, original.call(level));
    }
}
