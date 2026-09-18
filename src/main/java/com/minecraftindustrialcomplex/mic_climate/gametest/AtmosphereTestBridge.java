package com.minecraftindustrialcomplex.mic_climate.gametest;

import net.Gabou.projectatmosphere.api.AtmoApi;
import net.Gabou.projectatmosphere.api.WeatherSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

/**
 * The only class in the suite that names Project Atmosphere.
 *
 * <p>Same reason as {@code provider.ProjectAtmosphereSource} in the mod proper:
 * NeoForge loads every {@code @GameTestHolder} class it finds in the mod's scan
 * data, so {@link AtmosphereGameTests} is loaded even in a runtime without
 * Project Atmosphere. Keeping the {@code net.Gabou.*} references out of its
 * method bodies is what stops that from being a {@code NoClassDefFoundError}
 * instead of a skipped test.
 */
final class AtmosphereTestBridge {

    private AtmosphereTestBridge() {}

    /** Project Atmosphere's own answer at a position, or {@code null}. */
    @Nullable
    static Float forecastCelsius(ServerLevel level, BlockPos pos) {
        WeatherSnapshot snapshot = AtmoApi.getInstance().getCurrentWeather(level, pos);
        return snapshot == null ? null : snapshot.temperatureC();
    }
}
