package com.minecraftindustrialcomplex.mic_climate.gametest;

import com.minecraftindustrialcomplex.mic_climate.seasons.SeasonsHooked;
import com.minecraftindustrialcomplex.mic_climate.seasons.SereneSeasonsHemispheres;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import sereneseasons.api.season.ISeasonState;
import sereneseasons.api.season.Season;
import sereneseasons.api.season.SeasonHelper;
import sereneseasons.init.ModConfig;
import sereneseasons.init.ModFertility;
import sereneseasons.season.SeasonHooks;

import java.util.ArrayList;
import java.util.List;
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

    static Holder<Biome> biome(ServerLevel level, ResourceKey<Biome> key) {
        return level.registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(key);
    }

    /** The biome at {@code pos}. */
    static ResourceKey<Biome> biomeAt(ServerLevel level, BlockPos pos) {
        return level.getBiome(pos).unwrapKey().orElseThrow();
    }

    /**
     * Changes the biome around a position for the length of a test, and puts it back. {@code Level.getBiome}
     * does not read the one 4x4x4 cell a block is in: the biome manager's zoom picks, by a hash of the
     * block, any of the cells around, so the 3x3x3 cells around the position are all set, straight into
     * the chunk sections' biome containers (as {@code /fillbiome} would, which refuses the far-out
     * coordinates a GameTest structure sits at). Crops and precipitation read the level's biome.
     */
    static final class BiomeSwap {
        private final ServerLevel level;
        private final BlockPos pos;
        /** Every cell set so far and the biome it held, in order. */
        private final List<BlockPos> cells = new ArrayList<>();
        private final List<Holder<Biome>> before = new ArrayList<>();

        BiomeSwap(ServerLevel level, BlockPos pos) {
            this.level = level;
            this.pos = pos;
        }

        void set(ResourceKey<Biome> biome) {
            Holder<Biome> holder = biome(level, biome);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos cell = new BlockPos(pos.getX() + 4 * dx,
                                Mth.clamp(pos.getY() + 4 * dy, level.getMinBuildHeight(), level.getMaxBuildHeight() - 1),
                                pos.getZ() + 4 * dz);
                        cells.add(cell);
                        before.add(put(cell, holder));
                    }
                }
            }
        }

        /** Undoes every {@link #set}, last first. */
        void restore() {
            for (int i = cells.size() - 1; i >= 0; i--)
                put(cells.get(i), before.get(i));
            cells.clear();
            before.clear();
        }

        @SuppressWarnings("unchecked")
        private Holder<Biome> put(BlockPos cell, Holder<Biome> holder) {
            LevelChunk chunk = level.getChunkAt(cell);
            LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(cell.getY()));
            PalettedContainer<Holder<Biome>> biomes = (PalettedContainer<Holder<Biome>>) section.getBiomes();
            Holder<Biome> was = biomes.getAndSetUnchecked(QuartPos.fromBlock(cell.getX()) & 3,
                    QuartPos.fromBlock(cell.getY()) & 3, QuartPos.fromBlock(cell.getZ()) & 3, holder);
            chunk.setUnsaved(true);
            return was;
        }
    }

    /** Whether Serene Seasons' tropical-biome tag holds the biome. */
    static boolean tropical(Holder<Biome> biome) {
        return SeasonHelper.usesTropicalSeasons(biome);
    }

    /** Serene Seasons' precipitation (rain, snow or none) for a biome at a position, as its client and {@code isRainingAt} ask. */
    static Biome.Precipitation precipitation(ServerLevel level, Holder<Biome> biome, BlockPos pos) {
        return SeasonHooks.getPrecipitationAtSeasonal(level, biome, pos);
    }

    /**
     * The temperature Serene Seasons would give a biome that is not tropical to it, in a sub-season: its
     * own {@code biomeTempAdjustment} for the season added to the biome's temperature, clamped as it
     * does (the reference for tropical biomes beyond the wet/dry band).
     */
    static float temperateTemperature(Holder<Biome> biome, BlockPos pos, Season.SubSeason season) {
        float base = ownTemperature(biome, pos);
        return Mth.clamp(base + ModConfig.seasons.getSeasonProperties(season).biomeTempAdjustment(), -0.5f, 2.0f);
    }

    /**
     * A tropical biome's own temperature at a position: what Serene Seasons gives it in every season (it
     * shifts none), here asked of its own {@code getBiomeTemperatureInSeason} with no latitude involved.
     */
    static float ownTemperature(Holder<Biome> biome, BlockPos pos) {
        return SeasonHooks.getBiomeTemperatureInSeason(Season.SubSeason.MID_SUMMER, biome, pos);
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
