package com.minecraftindustrialcomplex.mic_climate.command;

import com.minecraftindustrialcomplex.mic_climate.atmosphere.PollutionAtmosphereEffect;
import net.Gabou.projectatmosphere.api.AtmoApi;
import net.Gabou.projectatmosphere.api.WeatherSnapshot;
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
 * <p>Only Project Atmosphere's public API is read; nothing here or anywhere
 * else in this mod mixes into it.
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
}
