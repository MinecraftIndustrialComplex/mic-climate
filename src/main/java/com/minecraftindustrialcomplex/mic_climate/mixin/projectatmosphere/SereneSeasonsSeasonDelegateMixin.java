package com.minecraftindustrialcomplex.mic_climate.mixin.projectatmosphere;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.minecraftindustrialcomplex.mic_climate.atmosphere.ProjectAtmosphereHooked;
import com.minecraftindustrialcomplex.mic_climate.atmosphere.ProjectAtmosphereSeasons;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import sereneseasons.api.season.ISeasonState;

/**
 * Project Atmosphere's regional season, by latitude.
 *
 * <p>{@code SereneSeasonsSeasonDelegate.snapshot(Level, BlockPos)} is how Project Atmosphere asks
 * for the season at a position (its seasonal drift asks once per region); it ignores the position
 * and reads Serene Seasons' level-wide state. On a Deep Time planet, asked with a position, it now
 * reads the local state there (the hemisphere's season, pulled toward Mid Summer where the seasons
 * fade), in {@code snapshot} and in its tropical {@code moistureStage}; the tropical wet/dry stage
 * applies in its own band (7.5 to 22.5 degrees), inverted in the south. Level-wide calls (no
 * position) are unchanged.
 */
@Pseudo
@Mixin(targets = "net.Gabou.projectatmosphere.seasons.SereneSeasonsSeasonDelegate", remap = false)
public abstract class SereneSeasonsSeasonDelegateMixin implements ProjectAtmosphereHooked {

    private static final String GET_SEASON_STATE =
            "Lsereneseasons/api/season/SeasonHelper;getSeasonState(Lnet/minecraft/world/level/Level;)Lsereneseasons/api/season/ISeasonState;";
    private static final String MOISTURE_STAGE =
            "moistureStage(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)Lnet/Gabou/projectatmosphere/seasons/SeasonMoistureStage;";

    @WrapOperation(
            method = "snapshot(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)Lnet/Gabou/projectatmosphere/seasons/SeasonSnapshot;",
            at = @At(value = "INVOKE", target = GET_SEASON_STATE),
            require = 0
    )
    private ISeasonState micc$localSeason(Level level, Operation<ISeasonState> original,
                                          @Local(argsOnly = true) BlockPos pos) {
        return ProjectAtmosphereSeasons.delegateState(level, pos, original.call(level));
    }

    @WrapOperation(method = MOISTURE_STAGE, at = @At(value = "INVOKE", target = GET_SEASON_STATE), require = 0)
    private static ISeasonState micc$localMoistureSeason(Level level, Operation<ISeasonState> original,
                                                         @Local(argsOnly = true) BlockPos pos) {
        return ProjectAtmosphereSeasons.delegateState(level, pos, original.call(level));
    }

    @WrapOperation(
            method = MOISTURE_STAGE,
            at = @At(value = "INVOKE", target = "Lsereneseasons/api/season/SeasonHelper;usesTropicalSeasons(Lnet/minecraft/core/Holder;)Z"),
            require = 0
    )
    private static boolean micc$tropicalHere(Holder<Biome> biome, Operation<Boolean> original,
                                             @Local(argsOnly = true) Level level, @Local(argsOnly = true) BlockPos pos) {
        return ProjectAtmosphereSeasons.tropical(level, pos, original.call(biome));
    }
}
