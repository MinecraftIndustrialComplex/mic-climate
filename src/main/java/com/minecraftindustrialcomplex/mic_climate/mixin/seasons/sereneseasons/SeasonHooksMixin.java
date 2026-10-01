package com.minecraftindustrialcomplex.mic_climate.mixin.seasons.sereneseasons;

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
 *
 * <p>The same class holds the places where Serene Seasons' tropical rule (its {@code tropical_biomes}
 * tag) decides without a position or a level, which cannot be asked for a latitude directly. The
 * wrapped calls read a thread-local that the latitude-aware entry above them
 * ({@code getBiomeTemperature}, {@code getPrecipitationAtSeasonal}) sets while Serene Seasons decides:
 * <ul>
 *   <li>{@code getBiomeTemperatureInSeason}: its tropical tag reads false where tropical biomes
 *       follow the temperate seasons (beyond the wet/dry band), so it shifts their temperature too;</li>
 *   <li>{@code hasPrecipitationSeasonal}, called from {@code getPrecipitationAtSeasonal}, which has the
 *       position: its tag reads false where there is no wet/dry cycle or the temperate seasons rule,
 *       and where there is one its season state is the local tropical calendar (the wet season is each
 *       hemisphere's summer half).</li>
 * </ul>
 */
@Pseudo
@Mixin(targets = "sereneseasons.season.SeasonHooks", remap = false)
public abstract class SeasonHooksMixin implements SeasonsHooked {

    private static final String GET_SEASON_STATE =
            "Lsereneseasons/api/season/SeasonHelper;getSeasonState(Lnet/minecraft/world/level/Level;)Lsereneseasons/api/season/ISeasonState;";
    private static final String HOLDER_IS = "Lnet/minecraft/core/Holder;is(Lnet/minecraft/tags/TagKey;)Z";
    private static final String IN_SEASON =
            "getBiomeTemperatureInSeason(Lsereneseasons/api/season/Season$SubSeason;Lnet/minecraft/core/Holder;Lnet/minecraft/core/BlockPos;)F";
    private static final String HAS_PRECIPITATION =
            "hasPrecipitationSeasonal(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/Holder;)Z";
    private static final String PRECIPITATION_AT =
            "getPrecipitationAtSeasonal(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/Holder;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/biome/Biome$Precipitation;";

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

    @WrapOperation(method = IN_SEASON, at = @At(value = "INVOKE", target = HOLDER_IS), require = 0)
    private static boolean micc$temperatureTropicalTag(Holder<Biome> biome, TagKey<Biome> tag, Operation<Boolean> original) {
        return SereneSeasonsHemispheres.tropicalTag(tag, original.call(biome, tag));
    }

    @WrapOperation(
            method = PRECIPITATION_AT,
            at = @At(value = "INVOKE", target = "Lsereneseasons/season/SeasonHooks;" + HAS_PRECIPITATION),
            require = 0
    )
    private static boolean micc$localPrecipitation(Level level, Holder<Biome> biome, Operation<Boolean> original,
                                                   @Local(argsOnly = true) BlockPos pos) {
        return SereneSeasonsHemispheres.hasPrecipitation(level, biome, pos, original);
    }

    @WrapOperation(method = HAS_PRECIPITATION, at = @At(value = "INVOKE", target = HOLDER_IS), require = 0)
    private static boolean micc$precipitationTropicalTag(Holder<Biome> biome, TagKey<Biome> tag, Operation<Boolean> original) {
        return SereneSeasonsHemispheres.tropicalTag(tag, original.call(biome, tag));
    }

    @WrapOperation(method = HAS_PRECIPITATION, at = @At(value = "INVOKE", target = GET_SEASON_STATE), require = 0)
    private static ISeasonState micc$tropicalCalendar(Level level, Operation<ISeasonState> original) {
        return SereneSeasonsHemispheres.precipitationState(original.call(level));
    }
}
