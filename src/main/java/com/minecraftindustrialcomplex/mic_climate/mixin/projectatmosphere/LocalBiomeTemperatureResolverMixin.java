package com.minecraftindustrialcomplex.mic_climate.mixin.projectatmosphere;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.minecraftindustrialcomplex.mic_climate.atmosphere.ProjectAtmosphereBase;
import com.minecraftindustrialcomplex.mic_climate.atmosphere.ProjectAtmosphereHooked;
import net.Gabou.projectatmosphere.modules.region.ForecastRegion;
import net.Gabou.projectatmosphere.util.RegionInstanceKey;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The temperature Project Atmosphere freezes water and lays snow by.
 *
 * <p>Its {@code BiomeFreezingMixin} replaces vanilla's {@code Biome.shouldFreeze} and
 * {@code shouldSnow} on the server with "below 0 &deg;C" on
 * {@code LocalBiomeTemperatureResolver.getLocalBiomeTemperature}: the region's forecast blended
 * with the local biome's table range, a few weather terms, and 6.5 &deg;C/km below or above sea
 * level. Its localized precipitation pass and the public {@code ForecastSampling.canAccumulateSnow}
 * go through the same method. In a Deep Time world this answers Deep Time's monthly mean at the
 * block (Deep Time's own lapse rate at the block's height, the local season) plus the region's
 * weather anomaly, so ice and snow follow the planet: none in the tropics, year-round on the ice
 * cap, winter-only in between, on the right side of the equator. In every world Destroy's pollution
 * warming is added, so a polluted sky thaws what it would have frozen.
 */
@Pseudo
@Mixin(targets = "net.Gabou.projectatmosphere.modules.temperature.util.LocalBiomeTemperatureResolver", remap = false)
public abstract class LocalBiomeTemperatureResolverMixin implements ProjectAtmosphereHooked {

    @ModifyReturnValue(
            method = "getLocalBiomeTemperature(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/Gabou/projectatmosphere/util/RegionInstanceKey;Lnet/Gabou/projectatmosphere/modules/region/ForecastRegion;)D",
            at = @At("RETURN"),
            require = 0
    )
    private static double micc$deepTimeLocalTemperature(double original, ServerLevel level, BlockPos pos,
                                                        RegionInstanceKey regionKey, ForecastRegion forecast) {
        return ProjectAtmosphereBase.celsius(level, pos, original, ProjectAtmosphereBase.Reading.FORECAST);
    }
}
