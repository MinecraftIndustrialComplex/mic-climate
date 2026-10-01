package com.minecraftindustrialcomplex.mic_climate.mixin.projectatmosphere;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.minecraftindustrialcomplex.mic_climate.atmosphere.ProjectAtmosphereClientCache;
import com.minecraftindustrialcomplex.mic_climate.atmosphere.ProjectAtmosphereHooked;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * Where Project Atmosphere sends its client temperature table, in a Deep Time world each player gets
 * their own Deep Time table instead ({@code atmosphere.ProjectAtmosphereClientCache}).
 *
 * <p>Two sends: {@code sendDailyForecastsToPlayer}, one player at login, and the broadcast inside
 * {@code computeAverageForecastsByBiomeType}, everyone when the forecast is rebuilt. Anywhere the
 * hook does not apply, or on any error, Project Atmosphere's own send goes ahead unchanged.
 */
@Pseudo
@Mixin(targets = "net.Gabou.projectatmosphere.manager.ForecastGenerator", remap = false)
public abstract class ForecastGeneratorMixin implements ProjectAtmosphereHooked {

    @Inject(
            method = "sendDailyForecastsToPlayer(Lnet/minecraft/server/level/ServerPlayer;Ljava/util/Map;)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private static void micc$deepTimeTable(ServerPlayer player, Map<ResourceLocation, float[]> snapshot, CallbackInfo ci) {
        if (player != null && ProjectAtmosphereClientCache.sendInstead(player, snapshot))
            ci.cancel();
    }

    @WrapOperation(
            method = "computeAverageForecastsByBiomeType()V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/neoforge/network/PacketDistributor;sendToAllPlayers(Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;[Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;)V"
            ),
            require = 0
    )
    private static void micc$deepTimeTables(CustomPacketPayload payload, CustomPacketPayload[] others, Operation<Void> original) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || !ProjectAtmosphereClientCache.sendAllInstead(server))
            original.call(payload, others);
    }
}
