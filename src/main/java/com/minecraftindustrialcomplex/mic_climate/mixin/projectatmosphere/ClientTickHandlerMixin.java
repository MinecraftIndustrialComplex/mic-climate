package com.minecraftindustrialcomplex.mic_climate.mixin.projectatmosphere;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.minecraftindustrialcomplex.mic_climate.atmosphere.ProjectAtmosphereHooked;
import com.minecraftindustrialcomplex.mic_climate.atmosphere.ProjectAtmosphereSeasons;
import net.Gabou.projectatmosphere.seasons.SeasonStage;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Project Atmosphere's falling leaves, by latitude (client). The wind's leaf particles pick their
 * colour from {@code ClientTickHandler.getCurrentSeason(ClientLevel, BlockPos)} (orange in autumn,
 * green in spring and summer, none in winter), which ignores its position; on a Deep Time planet it
 * answers the local season there.
 */
@Pseudo
@Mixin(targets = "net.Gabou.projectatmosphere.client.ClientTickHandler", remap = false)
public abstract class ClientTickHandlerMixin implements ProjectAtmosphereHooked {

    @ModifyReturnValue(
            method = "getCurrentSeason(Lnet/minecraft/client/multiplayer/ClientLevel;Lnet/minecraft/core/BlockPos;)Lnet/Gabou/projectatmosphere/seasons/SeasonStage;",
            at = @At("RETURN"),
            require = 0
    )
    private static SeasonStage micc$localLeafSeason(SeasonStage original, ClientLevel level, BlockPos pos) {
        return ProjectAtmosphereSeasons.leafStage(level, pos, original);
    }
}
