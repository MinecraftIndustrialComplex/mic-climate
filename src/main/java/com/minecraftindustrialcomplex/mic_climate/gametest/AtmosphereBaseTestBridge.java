package com.minecraftindustrialcomplex.mic_climate.gametest;

import com.minecraftindustrialcomplex.mic_climate.atmosphere.ProjectAtmosphereBase;
import com.minecraftindustrialcomplex.mic_climate.atmosphere.ProjectAtmosphereClientCache;
import com.minecraftindustrialcomplex.mic_climate.atmosphere.ProjectAtmosphereHooked;
import net.Gabou.projectatmosphere.api.AtmoApi;
import net.Gabou.projectatmosphere.api.CropStressType;
import net.Gabou.projectatmosphere.manager.CropStressManager;
import net.Gabou.projectatmosphere.manager.ForecastOrchestrator;
import net.Gabou.projectatmosphere.modules.atmosphere.AtmosphericStateRegistry;
import net.Gabou.projectatmosphere.modules.atmosphere.RegionAtmosphereState;
import net.Gabou.projectatmosphere.modules.atmosphere.SeasonalAtmosphericDrift;
import net.Gabou.projectatmosphere.modules.temperature.util.LocalBiomeTemperatureResolver;
import net.Gabou.projectatmosphere.util.RegionInstanceKey;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;

/**
 * Project Atmosphere's side of {@link AtmosphereBaseGameTests}: every number the Deep Time base hook
 * is supposed to move, read through Project Atmosphere's own entry points (the ones its snow,
 * freezing, rain-or-snow, snapshot and crop code call), so a passing test is a claim about what
 * Project Atmosphere decides and not about this mod's helper. The only class of the suite, with
 * {@link AtmosphereTestBridge}, that names {@code net.Gabou.*}.
 */
final class AtmosphereBaseTestBridge {

    private AtmosphereBaseTestBridge() {}

    /** What Project Atmosphere answers at a block, through each of its entry points. */
    record Readings(float snapshot, float precipitation, double local, boolean cold, boolean heat) {}

    static Readings readings(ServerLevel level, BlockPos pos) {
        float snapshot = AtmoApi.getInstance().getCurrentWeather(level, pos).temperatureC();
        float precipitation = ForecastOrchestrator.getCurrentTemperature(level, pos, level.getDayTime());
        double local = LocalBiomeTemperatureResolver.getLocalBiomeTemperature(level, pos, RegionInstanceKey.from(pos), null);
        EnumSet<CropStressType> stress = CropStressManager.evaluate(level, pos);
        return new Readings(snapshot, precipitation, local,
                stress.contains(CropStressType.COLD), stress.contains(CropStressType.HEAT));
    }

    /** The region's live state at {@code pos}, or null when Project Atmosphere has none there. */
    @Nullable
    static Region region(BlockPos pos) {
        RegionAtmosphereState state = AtmosphericStateRegistry.getState(RegionInstanceKey.from(pos));
        return state == null ? null : new Region(state);
    }

    /** A view of one region's temperatures, as Project Atmosphere's scheduler sees them. */
    static final class Region {
        private final RegionAtmosphereState state;

        Region(RegionAtmosphereState state) {
            this.state = state;
        }

        float live() {
            return state.getTemperature();
        }

        float base() {
            return state.getBaseTemperature();
        }

        float effectiveBase() {
            return state.getEffectiveBaseTemperature();
        }

        float target(long dayTime) {
            return state.getTargetTemperature(dayTime);
        }

        float baseTarget(long dayTime) {
            return state.getBaseTargetTemperature(dayTime);
        }

        float bandWidth() {
            return state.getBaselineMaxTemperature() - state.getBaselineMinTemperature();
        }

        float rawBandWidth() {
            return state.getBaselineTemperatureSpan();
        }

        /** Writes the live temperature, standing in for Project Atmosphere's scheduler. */
        void setLive(float celsius) {
            state.setTemperature(celsius);
        }
    }

    /** Project Atmosphere's own global season offset, which the hook replaces per region. */
    static float paSeasonOffset() {
        return SeasonalAtmosphericDrift.currentTemperatureOffsetC();
    }

    static int boundTargets() {
        return ProjectAtmosphereBase.boundTargets();
    }

    static boolean active(ServerLevel level) {
        return ProjectAtmosphereBase.active(level);
    }

    static float anomaly(ServerLevel level, BlockPos pos) {
        return ProjectAtmosphereBase.anomaly(level, pos);
    }

    static float pollutionShift(ServerLevel level) {
        return ProjectAtmosphereBase.pollutionShift(level);
    }

    /** The two client-cache targets: Project Atmosphere's forecast sender and Serene Seasons' precipitation. */
    static int boundClientTargets() {
        int n = 0;
        for (String name : new String[] {"net.Gabou.projectatmosphere.manager.ForecastGenerator", "sereneseasons.season.SeasonHooks"}) {
            try {
                if (ProjectAtmosphereHooked.class.isAssignableFrom(Class.forName(name)))
                    n++;
            } catch (ClassNotFoundException ignored) {
                // Serene Seasons absent.
            }
        }
        return n;
    }

    /** The client's rain-or-snow rule for a table value, and what the hook does on a server level. */
    static String precipitationRule(ServerLevel level, BlockPos pos) {
        var biome = level.getBiome(pos);
        return ProjectAtmosphereClientCache.decide(-0.1f) + "," + ProjectAtmosphereClientCache.decide(0.1f) + ","
                + ProjectAtmosphereClientCache.precipitation(level, biome, net.minecraft.world.level.biome.Biome.Precipitation.RAIN) + ","
                + ProjectAtmosphereClientCache.precipitation(level, biome, net.minecraft.world.level.biome.Biome.Precipitation.NONE);
    }

    /** The per-player Deep Time table around {@code pos}. */
    static java.util.Map<net.minecraft.resources.ResourceLocation, float[]> clientTable(ServerLevel level, BlockPos pos, int radius) {
        return ProjectAtmosphereClientCache.local(level, pos, radius);
    }
}
