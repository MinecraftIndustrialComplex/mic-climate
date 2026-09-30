package com.minecraftindustrialcomplex.mic_climate.mixin.seasons.sereneseasons;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.minecraftindustrialcomplex.mic_climate.seasons.SeasonsHooked;
import com.minecraftindustrialcomplex.mic_climate.seasons.SereneSeasonsHemispheres;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import sereneseasons.api.season.ISeasonState;

/**
 * Serene Seasons' birch leaf colour, by latitude (client).
 *
 * <p>Birch leaves are not coloured by the biome resolvers Serene Seasons' resolver override covers;
 * {@code ModClient} registers a block colour handler for them (a lambda) that reads the level's
 * season. On a Deep Time planet the season it reads becomes the hemisphere's, and its answer is
 * blended toward vanilla's birch colour (Mid Summer's) where the seasons fade. Where Serene Seasons
 * leaves birch alone (its option off, a blacklisted biome) its answer is vanilla's already and the
 * blend keeps it.
 */
@Pseudo
@Mixin(targets = "sereneseasons.init.ModClient", remap = false)
public abstract class ModClientMixin implements SeasonsHooked {

    private static final String BIRCH = "lambda$registerBlockColors$0(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;I)I";

    @WrapOperation(
            method = BIRCH,
            at = @At(
                    value = "INVOKE",
                    target = "Lsereneseasons/api/season/SeasonHelper;getSeasonState(Lnet/minecraft/world/level/Level;)Lsereneseasons/api/season/ISeasonState;"
            ),
            require = 0
    )
    private static ISeasonState micc$localBirchSeason(Level level, Operation<ISeasonState> original,
                                                      @Local(argsOnly = true) BlockPos pos) {
        return SereneSeasonsHemispheres.shiftedState(level, pos, original.call(level));
    }

    @ModifyReturnValue(method = BIRCH, at = @At("RETURN"), require = 0)
    private static int micc$fadedBirch(int colour, @Local(argsOnly = true) BlockPos pos) {
        return SereneSeasonsHemispheres.birchColour(Minecraft.getInstance().level, pos, colour);
    }
}
