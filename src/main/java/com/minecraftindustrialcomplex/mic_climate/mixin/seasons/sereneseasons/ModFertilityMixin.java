package com.minecraftindustrialcomplex.mic_climate.mixin.seasons.sereneseasons;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.minecraftindustrialcomplex.mic_climate.seasons.SeasonsHooked;
import com.minecraftindustrialcomplex.mic_climate.seasons.SereneSeasonsHemispheres;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
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
 *
 * <p>In the seasonless band near the equator every crop counts as in season (Ben, 2026-09-30): a
 * crop Serene Seasons refuses there is asked about again in each season ({@code RETURN}), and its
 * tropical-biome rule (summer crops only) is lifted there ({@code Holder.is}).
 */
@Pseudo
@Mixin(targets = "sereneseasons.init.ModFertility", remap = false)
public abstract class ModFertilityMixin implements SeasonsHooked {

    private static final String FERTILE = "isCropFertile(Ljava/lang/String;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)Z";

    @WrapOperation(
            method = FERTILE,
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

    @WrapOperation(
            method = FERTILE,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/core/Holder;is(Lnet/minecraft/tags/TagKey;)Z"),
            require = 0
    )
    private static boolean micc$cropBiomeTag(Holder<Biome> biome, TagKey<Biome> tag, Operation<Boolean> original,
                                             @Local(argsOnly = true) Level level, @Local(argsOnly = true) BlockPos pos) {
        return SereneSeasonsHemispheres.cropBiomeTag(tag, original.call(biome, tag), level, pos);
    }

    @ModifyReturnValue(method = FERTILE, at = @At("RETURN"), require = 0)
    private static boolean micc$yearRound(boolean fertile, String crop, Level level, BlockPos pos) {
        return SereneSeasonsHemispheres.yearRoundCrop(fertile, crop, level, pos);
    }
}
