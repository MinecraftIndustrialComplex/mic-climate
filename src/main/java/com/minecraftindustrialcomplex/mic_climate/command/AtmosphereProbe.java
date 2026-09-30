package com.minecraftindustrialcomplex.mic_climate.command;

import com.minecraftindustrialcomplex.mic_climate.Compat;
import com.minecraftindustrialcomplex.mic_climate.atmosphere.PollutionAtmosphereEffect;
import com.minecraftindustrialcomplex.mic_climate.atmosphere.ProjectAtmosphereBase;
import com.minecraftindustrialcomplex.mic_climate.atmosphere.ProjectAtmosphereSeasons;
import com.minecraftindustrialcomplex.mic_climate.config.ClimateConfig;
import net.Gabou.projectatmosphere.api.AtmoApi;
import net.Gabou.projectatmosphere.api.WeatherSnapshot;
import net.Gabou.projectatmosphere.manager.ForecastOrchestrator;
import net.Gabou.projectatmosphere.modules.atmosphere.AtmosphericStateRegistry;
import net.Gabou.projectatmosphere.modules.atmosphere.RegionAtmosphereState;
import net.Gabou.projectatmosphere.modules.atmosphere.SeasonalAtmosphericDrift;
import net.Gabou.projectatmosphere.modules.temperature.util.LocalBiomeTemperatureResolver;
import net.Gabou.projectatmosphere.util.RegionInstanceKey;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.Locale;

/**
 * The {@code atmosphere} line of {@code /mic_climate probe}, and the only class
 * in this package that names {@code net.Gabou.*}.
 *
 * <p>Three things on one line, because they are only meaningful together:
 * Project Atmosphere's own answer at this position, the region key it belongs
 * to, and how much of a pollution offset {@code pollution.mode = ATMOSPHERE}
 * believes it is currently holding in that region. Without the last of those,
 * "the atmosphere line went up" cannot be told apart from "we pushed it up",
 * which is the whole question that mode raises.
 *
 * <p>The {@code pa-base} line reports the one place this mod does mix into
 * Project Atmosphere, the optional Deep Time base hook
 * ({@code atmosphere.ProjectAtmosphereBase}): whether its mixins bound, whether
 * it is active here, the region's seasonal base with and without it, and the
 * two per-block temperatures Project Atmosphere decides weather by.
 */
final class AtmosphereProbe {

    private AtmosphereProbe() {}

    static String probeLine(ServerLevel level, BlockPos pos) {
        WeatherSnapshot snapshot = AtmoApi.getInstance().getCurrentWeather(level, pos);
        if (snapshot == null)
            return ClimateCommands.line("atmosphere", "-", "(AtmoApi.getCurrentWeather returned nothing)");

        RegionInstanceKey key = RegionInstanceKey.from(pos);
        float applied = PollutionAtmosphereEffect.appliedOffset(key);

        return ClimateCommands.line(
                "atmosphere",
                ClimateCommands.celsius(snapshot.temperatureC()),
                String.format(
                        Locale.ROOT,
                        "(AtmoApi.getCurrentWeather)  region (%d, %d)  applied offset %+.2f",
                        key.regionX(), key.regionZ(), applied));
    }

    /**
     * {@code pa-season : <regional stage> (region STAGE/MOISTURE (sunlight xN) vs level STAGE/MOISTURE ...)}:
     * the season Project Atmosphere's regional drift uses at this block (the hemisphere seasons,
     * {@code atmosphere.ProjectAtmosphereSeasons}) beside its level-wide one.
     */
    static String seasonsLine(ServerLevel level, BlockPos pos) {
        try {
            return ClimateCommands.line("pa-season",
                    net.Gabou.projectatmosphere.seasons.SeasonTimeHelper.snapshot(level, pos).stage().name(),
                    "(" + ProjectAtmosphereSeasons.describe(level, pos) + ")");
        } catch (Throwable t) {
            return ClimateCommands.line("pa-season", "-", "(" + t + ")");
        }
    }

    /**
     * {@code pa-base : <snow/freeze C> (hook: active|off (why), bound n/5; region base DT x vs PA y;
     * rain-or-snow z C; snow here yes|no)}. The value column is the temperature Project Atmosphere
     * freezes water and lays snow by at this block, hooked or not.
     */
    static String baseLine(ServerLevel level, BlockPos pos) {
        int bound = ProjectAtmosphereBase.boundTargets();
        boolean active = ProjectAtmosphereBase.active(level);
        String why = active ? "active"
                : !ClimateConfig.deepTimeEnabled() ? "off: deepTime.enabled is off"
                : !ClimateConfig.projectAtmosphereBase() ? "off: deepTime.projectAtmosphereBase is off"
                : bound == 0 ? "off: mixins not applied (see the log)"
                : !Compat.isLoaded(Compat.DEEP_TIME) ? "off: Deep Time is not installed"
                : "off: not a Deep Time overworld with a climate";
        if (ClimateConfig.projectAtmosphereBaseOverride() != null)
            why += " (session override)";

        RegionInstanceKey key = RegionInstanceKey.from(pos);
        RegionAtmosphereState state = AtmosphericStateRegistry.getState(key);
        String region;
        if (state == null) {
            region = "no live region";
        } else {
            float own = state.getBaseTemperature() + SeasonalAtmosphericDrift.currentTemperatureOffsetC();
            float dt = ProjectAtmosphereBase.regionBase(level, pos);
            region = String.format(Locale.ROOT, "region seasonal base %s vs PA's own %.2f (base %.2f %+.2f season), live %.2f",
                    Float.isNaN(dt) ? "-" : String.format(Locale.ROOT, "%.2f", dt), own, state.getBaseTemperature(),
                    SeasonalAtmosphericDrift.currentTemperatureOffsetC(), state.getTemperature());
        }
        float local = (float) LocalBiomeTemperatureResolver.getLocalBiomeTemperature(level, pos, key, null);
        float precipitation = ForecastOrchestrator.getCurrentTemperature(level, pos, level.getDayTime());
        boolean snow = level.getBiome(pos).value().shouldSnow(level, pos);
        return ClimateCommands.line(
                "pa-base",
                ClimateCommands.celsius(local),
                String.format(Locale.ROOT, "(snow/freeze temperature; hook %s, bound %d/5; %s; rain-or-snow %.2f C; snow here %s)",
                        why, bound, region, precipitation, snow ? "yes" : "no"));
    }
}
