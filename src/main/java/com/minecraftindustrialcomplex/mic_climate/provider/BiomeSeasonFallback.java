package com.minecraftindustrialcomplex.mic_climate.provider;

import com.github.thedeathlycow.thermoo.api.season.ThermooSeason;
import com.minecraftindustrialcomplex.mic_climate.config.ClimateConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

import java.util.Optional;

/**
 * What the world feels like when Project Atmosphere is not there to say.
 *
 * <p>Thermoo ships no environment definitions of its own, so without this every
 * biome would read a flat 20&nbsp;&deg;C and the whole point of the mod would
 * be lost on a pack that does not install Project Atmosphere.
 *
 * <p>The biome term uses Project Atmosphere's own vanilla mapping — Minecraft's
 * -0.5&hellip;2.0 base temperature stretched over -20&hellip;+56&nbsp;&deg;C —
 * so a world that later gains Project Atmosphere does not lurch onto a
 * different scale. A snowy plains (-0.5) reads -20&nbsp;&deg;C, plains (0.8)
 * reads about 19.5&nbsp;&deg;C, a desert (2.0) reads 56&nbsp;&deg;C.
 *
 * <p>The season term comes from Thermoo, which is fed by Thermoo Patches from
 * Serene Seasons. With no seasons mod installed Thermoo returns nothing and the
 * offset is zero, so this degrades to a plain biome reading.
 */
public final class BiomeSeasonFallback {

    private static final float MIN_BASE = -0.5f;
    private static final float MAX_BASE = 2.0f;

    /** Celsius at {@link #MIN_BASE}. */
    private static final float ORIGIN_CELSIUS = -20f;

    /** Degrees Celsius per unit of biome base temperature. */
    private static final float DEGREES_PER_BASE = 30.4f;

    /** Used when a biome cannot be read at all; vanilla's plains value. */
    private static final float DEFAULT_BASE = 0.8f;

    private BiomeSeasonFallback() {}

    public static float celsius(Level level, BlockPos pos, Holder<Biome> biome) {
        float base = DEFAULT_BASE;
        try {
            if (biome != null)
                base = biome.value().getBaseTemperature();
        } catch (Throwable ignored) {
            // Virtual levels hand out biome holders that are not bound to a
            // value; the default reads as a temperate day, which is right.
        }

        base = Mth.clamp(base, MIN_BASE, MAX_BASE);
        return ORIGIN_CELSIUS + DEGREES_PER_BASE * (base - MIN_BASE) + seasonOffset(level, pos);
    }

    private static float seasonOffset(Level level, BlockPos pos) {
        if (level == null)
            return 0f;
        try {
            Optional<ThermooSeason> season = ThermooSeason.getCurrentSeason(level);
            if (season.isEmpty())
                season = ThermooSeason.getCurrentTropicalSeason(level, pos);
            return season.map(ClimateConfig::seasonOffset).orElse(0f);
        } catch (Throwable ignored) {
            return 0f;
        }
    }
}
