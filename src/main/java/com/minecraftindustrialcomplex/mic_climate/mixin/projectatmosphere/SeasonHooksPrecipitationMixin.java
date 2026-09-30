package com.minecraftindustrialcomplex.mic_climate.mixin.projectatmosphere;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.minecraftindustrialcomplex.mic_climate.atmosphere.ProjectAtmosphereClientCache;
import com.minecraftindustrialcomplex.mic_climate.atmosphere.ProjectAtmosphereHooked;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Rain or snow on screen, from the player's Deep Time table.
 *
 * <p>What falls on a client is decided by {@code Biome.getPrecipitationAt}: Simple Clouds' weather
 * renderer (which replaces vanilla's) and vanilla's rain ticks both ask it, and Serene Seasons'
 * client mixin answers it with {@code SeasonHooks.getPrecipitationAtSeasonal}, from the biome's
 * vanilla temperature and one global season. Project Atmosphere's own client temperature table is
 * not consulted there at all. This takes Serene Seasons' answer and, on a client holding a Deep Time
 * table ({@code atmosphere.ProjectAtmosphereClientCache}), turns a biome that precipitates into snow
 * below 0 &deg;C of its table value and rain above: the climate and season of the place the player
 * is, on its own side of the equator. Everywhere else (the server, no Deep Time table, a biome
 * without a value) Serene Seasons' answer stands, so a biome that does not precipitate in this
 * season still does not.
 *
 * <p>This is the one mixin here into Serene Seasons, and it touches only the client's final answer,
 * after whatever Serene Seasons (or a per-position season layer on top of it) decided.
 */
@Pseudo
@Mixin(targets = "sereneseasons.season.SeasonHooks", remap = false)
public abstract class SeasonHooksPrecipitationMixin implements ProjectAtmosphereHooked {

    @ModifyReturnValue(
            method = "getPrecipitationAtSeasonal(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/Holder;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/biome/Biome$Precipitation;",
            at = @At("RETURN"),
            require = 0
    )
    private static Biome.Precipitation micc$deepTimePrecipitation(Biome.Precipitation original, Level level,
                                                                  Holder<Biome> biome, BlockPos pos) {
        return ProjectAtmosphereClientCache.precipitation(level, biome, original);
    }
}
