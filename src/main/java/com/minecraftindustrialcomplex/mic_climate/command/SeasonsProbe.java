package com.minecraftindustrialcomplex.mic_climate.command;

import com.minecraftindustrialcomplex.mic_climate.seasons.LatitudeSeasons;
import com.minecraftindustrialcomplex.mic_climate.seasons.PlanetLatitude;
import com.minecraftindustrialcomplex.mic_climate.seasons.SeasonsHooked;
import com.minecraftindustrialcomplex.mic_climate.seasons.SereneSeasonsHemispheres;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import sereneseasons.api.season.ISeasonState;
import sereneseasons.api.season.SeasonHelper;
import sereneseasons.config.SeasonsConfig;
import sereneseasons.init.ModConfig;
import sereneseasons.init.ModFertility;
import sereneseasons.season.SeasonHooks;

import java.util.Locale;

/**
 * The {@code seasons} line of {@code /mic_climate probe}: Serene Seasons' season as it is seen at the
 * block (the hemisphere seasons, {@code seasons.SereneSeasonsHemispheres}), and what it decides
 * there: its seasonal biome temperature (snow and ice below 0.15) beside the level's own, whether
 * wheat grows, and the melt rate. The only class in this package that names Serene Seasons.
 */
final class SeasonsProbe {

    private static final String CROP = "minecraft:wheat";
    /** A spring and autumn crop: grows in the seasonless band only because every crop is in season there. */
    private static final String OFF_SEASON_CROP = "minecraft:carrots";

    private SeasonsProbe() {}

    static String probeLine(ServerLevel level, BlockPos pos) {
        ISeasonState global = SeasonHelper.getSeasonState(level);
        SereneSeasonsHemispheres.Here here = SereneSeasonsHemispheres.here(level, pos, global);
        Holder<Biome> biome = level.getBiome(pos);
        float t = SeasonHooks.getBiomeTemperature(level, biome, pos);
        float levels = SeasonHooks.getBiomeTemperatureInSeason(global.getSubSeason(), biome, pos);
        boolean fertile = ModFertility.isCropFertile(CROP, level, pos);
        boolean offSeason = ModFertility.isCropFertile(OFF_SEASON_CROP, level, pos);
        double lat = here.latitude();
        double tropical = Double.isNaN(lat) ? 0 : LatitudeSeasons.tropicalStrength(lat);
        double temperate = Double.isNaN(lat) ? 0 : LatitudeSeasons.temperateWeight(lat);
        String rule = Double.isNaN(lat) ? "Serene Seasons' own" : LatitudeSeasons.wetDryRule(lat) ? "wet/dry"
                : LatitudeSeasons.temperateRule(lat) ? "temperate" : "no wet/dry cycle";
        SeasonsConfig.SeasonProperties melt = ModConfig.seasons.getSeasonProperties(here.discrete());
        return ClimateCommands.line("seasons", here.discrete().name(), String.format(Locale.ROOT,
                "(%s; tropical wet/dry strength %.2f, temperate share %.2f, tropical biomes here: %s; SS temperature %.3f, level's %.3f, snow/ice below 0.15: %s; wheat %s, carrots %s; melt %.2f%% x%d; C %d, projection %s, hooks %d/4)",
                here.describe(), tropical, temperate, rule, t, levels, t < 0.15f ? "yes" : "no", fertile ? "grow" : "no",
                offSeason ? "grow" : "no", melt.meltChance(), melt.meltRolls(), PlanetLatitude.circumference(level),
                PlanetLatitude.projection(level).isEmpty() ? "-" : PlanetLatitude.projection(level), boundTargets()));
    }

    /** How many of the four server-side Serene Seasons classes the hemisphere-season mixins reached. */
    static int boundTargets() {
        int n = 0;
        for (String target : new String[] {"sereneseasons.season.SeasonHooks", "sereneseasons.init.ModFertility",
                "sereneseasons.season.RandomUpdateHandler", "sereneseasons.block.SeasonSensorBlock"}) {
            try {
                if (SeasonsHooked.class.isAssignableFrom(Class.forName(target)))
                    n++;
            } catch (Throwable ignored) {
                // not there
            }
        }
        return n;
    }
}
