package com.minecraftindustrialcomplex.mic_climate.mixin.projectatmosphere;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.minecraftindustrialcomplex.mic_climate.atmosphere.ProjectAtmosphereBase;
import com.minecraftindustrialcomplex.mic_climate.atmosphere.ProjectAtmosphereHooked;
import net.Gabou.projectatmosphere.api.WeatherSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Project Atmosphere's public weather snapshot at a block: Deep Time's climate there, plus the
 * region's weather.
 *
 * <p>{@code AtmoApi.getWeatherSnapshot} answers with its 2000-block region's live temperature, the
 * same number for every block of the region. It is what other mods read ({@code
 * getCurrentWeather}), what Project Atmosphere syncs to each player's HUD, and what its world
 * effects sample. In a Deep Time world this replaces that temperature with Deep Time's monthly
 * mean at the block (its height and the date) plus the region's departure from its own (Deep
 * Time) base, the same weather anomaly mic-climate adds, and recomputes the snapshot's
 * {@code isSnowing} from it. Everything else in the snapshot is Project Atmosphere's.
 */
@Pseudo
@Mixin(targets = "net.Gabou.projectatmosphere.api.AtmoApi", remap = false)
public abstract class AtmoApiMixin implements ProjectAtmosphereHooked {

    @ModifyReturnValue(
            method = "getWeatherSnapshot(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;J)Lnet/Gabou/projectatmosphere/api/WeatherSnapshot;",
            at = @At("RETURN"),
            require = 0
    )
    private WeatherSnapshot micc$deepTimeSnapshot(WeatherSnapshot original, ServerLevel level, BlockPos pos, long gameTime) {
        return ProjectAtmosphereBase.snapshot(level, pos, original);
    }
}
