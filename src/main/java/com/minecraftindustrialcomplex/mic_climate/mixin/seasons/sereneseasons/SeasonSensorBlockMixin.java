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
 * Serene Seasons' season sensor, by latitude: a sensor on a Deep Time planet outputs the local
 * season (the one crop fertility uses), so a redstone farm in the south switches with its own
 * growing season. {@code SeasonSensorBlock.updatePower(Level, BlockPos)} reads the level's season
 * ticks; this hands it the local state's.
 */
@Pseudo
@Mixin(targets = "sereneseasons.block.SeasonSensorBlock", remap = false)
public abstract class SeasonSensorBlockMixin implements SeasonsHooked {

    @WrapOperation(
            method = "updatePower(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lsereneseasons/api/season/SeasonHelper;getSeasonState(Lnet/minecraft/world/level/Level;)Lsereneseasons/api/season/ISeasonState;"
            ),
            require = 0
    )
    private ISeasonState micc$localSensorSeason(Level level, Operation<ISeasonState> original,
                                                @Local(argsOnly = true) BlockPos pos) {
        return SereneSeasonsHemispheres.discreteState(level, pos, original.call(level));
    }
}
