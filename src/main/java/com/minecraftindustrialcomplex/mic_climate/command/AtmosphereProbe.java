package com.minecraftindustrialcomplex.mic_climate.command;

import com.minecraftindustrialcomplex.mic_climate.Compat;
import com.minecraftindustrialcomplex.mic_climate.atmosphere.ProjectAtmosphereBase;
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
 * to, and how much of Destroy's warming the pollution hook is putting inside
 * Project Atmosphere's temperature ({@code pollution.projectAtmosphere}).
 *
 * <p>The {@code pa-base} line reports the hooks into Project Atmosphere
 * ({@code atmosphere.ProjectAtmosphereBase}): whether their mixins bound,
 * whether the Deep Time part is active here, the region's seasonal base with
 * and without it, and the two per-block temperatures Project Atmosphere decides
 * weather by.
 */
final class AtmosphereProbe {

    private AtmosphereProbe() {}

    static String probeLine(ServerLevel level, BlockPos pos) {
        WeatherSnapshot snapshot = AtmoApi.getInstance().getCurrentWeather(level, pos);
        if (snapshot == null)
            return ClimateCommands.line("atmosphere", "-", "(AtmoApi.getCurrentWeather returned nothing)");

        RegionInstanceKey key = RegionInstanceKey.from(pos);
        float pollution = ProjectAtmosphereBase.pollutionShift(level);

        return ClimateCommands.line(
                "atmosphere",
                ClimateCommands.celsius(snapshot.temperatureC()),
                String.format(
                        Locale.ROOT,
                        "(AtmoApi.getCurrentWeather)  region (%d, %d)  pollution inside %+.2f%s",
                        key.regionX(), key.regionZ(), pollution,
                        ProjectAtmosphereBase.pollutionActive(level) ? "" : " (pollution part off)"));
    }

    /**
     * {@code pa-base : <snow/freeze C> (hook: active|off (why), bound n/5; pollution inside p; region
     * seasonal base x vs PA's own y ..., live z; rain-or-snow w C; snow here yes|no)}. The value
     * column is the temperature Project Atmosphere freezes water and lays snow by at this block,
     * hooked or not; the region's seasonal base is what its state reports now (with whichever hook
     * parts are on), against Project Atmosphere's own base plus its global season offset.
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
            region = String.format(Locale.ROOT, "region seasonal base %.2f vs PA's own %.2f (base %.2f %+.2f season), live %.2f",
                    state.getEffectiveBaseTemperature(), own, state.getBaseTemperature(),
                    SeasonalAtmosphericDrift.currentTemperatureOffsetC(), state.getTemperature());
        }
        float local = (float) LocalBiomeTemperatureResolver.getLocalBiomeTemperature(level, pos, key, null);
        float precipitation = ForecastOrchestrator.getCurrentTemperature(level, pos, level.getDayTime());
        boolean snow = level.getBiome(pos).value().shouldSnow(level, pos);
        return ClimateCommands.line(
                "pa-base",
                ClimateCommands.celsius(local),
                String.format(Locale.ROOT, "(snow/freeze temperature; hook %s, bound %d/5; pollution inside %+.2f; %s; "
                                + "rain-or-snow %.2f C; snow here %s)",
                        why, bound, ProjectAtmosphereBase.pollutionShift(level), region, precipitation, snow ? "yes" : "no"));
    }
}
