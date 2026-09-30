package com.minecraftindustrialcomplex.mic_climate.command;

import com.minecraftindustrialcomplex.mic_climate.Compat;
import com.minecraftindustrialcomplex.mic_climate.config.ClimateConfig;
import com.minecraftindustrialcomplex.mic_climate.provider.DeepTimeSource;
import com.minecraftindustrialcomplex.mic_climate.provider.ProjectAtmosphereSource;
import com.minecraftindustrialcomplex.mic_climate.provider.UnifiedEnvironmentProvider;
import com.minecraftindustrialcomplex.mic_climate.provider.YearClock;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The {@code deeptime} lines of {@code /mic_climate probe}: Deep Time's climate at the block, the
 * date it was read at, and how Project Atmosphere's weather was added on top. Printed only in a Deep
 * Time world with a simulated climate. Reaches Deep Time and Project Atmosphere only through the
 * provider classes that are allowed to import them.
 */
final class DeepTimeProbe {

    private DeepTimeProbe() {}

    static List<String> probeLines(ServerLevel level, BlockPos pos) {
        List<String> lines = new ArrayList<>();
        if (!DeepTimeSource.hasClimate(level))
            return lines;
        YearClock.Date date = YearClock.now(level);
        DeepTimeSource.Reading r = DeepTimeSource.read(level, pos, date.yearFraction());
        if (r == null)
            return lines;
        lines.add(ClimateCommands.line("deeptime", ClimateCommands.celsius(r.celsius()),
                "(" + DeepTimeSource.describe(r, date.yearFraction(), date.source()) + ")"));
        UnifiedEnvironmentProvider.DeepTimeTemperature dt =
                UnifiedEnvironmentProvider.deepTime(level, pos, ClimateConfig.source());
        String weather;
        if (dt == null) {
            weather = "(no Deep Time reading)";
        } else if (!dt.withWeather()) {
            weather = "(no weather added: " + (!ClimateConfig.deepTimeEnabled() ? "deepTime.enabled is off"
                    : !ClimateConfig.deepTimeWeather() ? "deepTime.weatherAnomaly is off"
                    : !Compat.isLoaded(Compat.PROJECT_ATMOSPHERE) ? "Project Atmosphere is not installed"
                    : "no live Project Atmosphere region here") + ")";
        } else {
            float[] region = ProjectAtmosphereSource.regionTemperatures(level, pos);
            weather = region == null ? String.format(Locale.ROOT, "(Deep Time %.2f %+.2f atmosphere anomaly)", dt.base(), dt.anomaly())
                    : String.format(Locale.ROOT,
                    "(Deep Time %.2f %+.2f atmosphere anomaly = region live %.2f - effective base %.2f [base %.2f], cap %.0f)",
                    dt.base(), dt.anomaly(), region[0], region[1], region[2], ClimateConfig.deepTimeMaxAnomaly());
        }
        lines.add(ClimateCommands.line("dt+weather", dt == null ? "-" : ClimateCommands.celsius(dt.celsius()), weather));
        return lines;
    }
}
