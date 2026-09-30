package com.minecraftindustrialcomplex.mic_climate.gametest;

import com.minecraftindustrialcomplex.mic_climate.seasons.SeasonsHooked;
import com.minecraftindustrialcomplex.mic_climate.seasons.SereneSeasonsHemispheres;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import sereneseasons.api.season.ISeasonState;
import sereneseasons.api.season.Season;
import sereneseasons.api.season.SeasonHelper;
import sereneseasons.init.ModConfig;
import sereneseasons.init.ModFertility;
import sereneseasons.season.SeasonHooks;

import java.util.Locale;

/**
 * Serene Seasons' side of {@link HemisphereSeasonsGameTests}: its own entry points (the ones its
 * snow, ice, melt and crop code call), so a passing test is a claim about what Serene Seasons
 * decides and not about this mod's helper. The only class of the suite that names Serene Seasons'
 * internals.
 */
final class SeasonsTestBridge {

    private SeasonsTestBridge() {}

    /** The four server-side Serene Seasons classes the hemisphere-season mixins target, and how many bound. */
    static int boundTargets() {
        int n = 0;
        for (Class<?> target : new Class<?>[] {SeasonHooks.class, ModFertility.class,
                sereneseasons.season.RandomUpdateHandler.class, sereneseasons.block.SeasonSensorBlock.class}) {
            if (SeasonsHooked.class.isAssignableFrom(target))
                n++;
        }
        return n;
    }

    /** {@code /season set <sub-season>}, as an operator would. */
    static void setSeason(ServerLevel level, Season.SubSeason season) {
        level.getServer().getCommands().performPrefixedCommand(
                level.getServer().createCommandSourceStack().withLevel(level).withSuppressedOutput(),
                "season set " + season.name().toLowerCase(Locale.ROOT));
    }

    static Season.SubSeason subSeason(ServerLevel level) {
        return SeasonHelper.getSeasonState(level).getSubSeason();
    }

    static Holder<Biome> plains(ServerLevel level) {
        return level.registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS);
    }

    /** Serene Seasons' seasonal biome temperature, through the method its snow and ice code calls. */
    static float temperature(ServerLevel level, Holder<Biome> biome, BlockPos pos) {
        return SeasonHooks.getBiomeTemperature(level, biome, pos);
    }

    /** Serene Seasons' temperature for a given sub-season (not hooked: the reference values). */
    static float inSeason(Season.SubSeason season, Holder<Biome> biome, BlockPos pos) {
        return SeasonHooks.getBiomeTemperatureInSeason(season, biome, pos);
    }

    static boolean fertile(String crop, ServerLevel level, BlockPos pos) {
        return ModFertility.isCropFertile(crop, level, pos);
    }

    static SereneSeasonsHemispheres.Here here(ServerLevel level, BlockPos pos) {
        return SereneSeasonsHemispheres.here(level, pos, SeasonHelper.getSeasonState(level));
    }

    static ISeasonState state(ServerLevel level) {
        return SeasonHelper.getSeasonState(level);
    }

    /** The melt chance (percent) Serene Seasons' config gives a sub-season. */
    static float meltChance(Season.SubSeason season) {
        return ModConfig.seasons.getSeasonProperties(season).meltChance();
    }

    /** The loop's melt chance on this level, as the melt mixin hands it to Serene Seasons. */
    static float meltLoopChance(ServerLevel level, float levelChance) {
        return SereneSeasonsHemispheres.meltLoopChance(level, levelChance);
    }

    /** The sub-season a chunk melts (and Serene Seasons Plus judges it) by. */
    static Season.SubSeason chunkSeason(ServerLevel level, ChunkPos chunk) {
        return SereneSeasonsHemispheres.snowPolicySeason(level, chunk, subSeason(level));
    }
}
