package com.minecraftindustrialcomplex.mic_climate.atmosphere;

import com.minecraftindustrialcomplex.mic_climate.MicClimate;
import com.minecraftindustrialcomplex.mic_climate.config.ClimateConfig;
import net.Gabou.projectatmosphere.client.BiomeClientTemperatureCache;
import net.Gabou.projectatmosphere.manager.ForecastGenerator;
import net.Gabou.projectatmosphere.network.BiomeDayTemperaturePacket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Each player's own Project Atmosphere client temperature cache, from Deep Time's climate, in Deep
 * Time worlds ({@code deepTime.projectAtmosphereClient}).
 *
 * <p>Project Atmosphere's client knows temperature only through {@code BiomeClientTemperatureCache}:
 * one flat daily curve per biome, averaged over every region the world has, sent to everyone at
 * login. On a Deep Time planet that table mixes both hemispheres and knows nothing of the place a
 * player is in. This sends each player a table for where they are instead, over Project
 * Atmosphere's own packet ({@code BiomeDayTemperaturePacket}), which replaces the client's table
 * whole:
 *
 * <ul>
 *   <li>for every biome on the loaded surface around the player (every 32 blocks out to the view
 *       distance, at most 192 blocks), the mean of Project Atmosphere's hooked temperature there:
 *       Deep Time's monthly mean at that block plus the region's weather anomaly and pollution, the
 *       same number the server decides rain or snow by;</li>
 *   <li>Project Atmosphere's own values for every other biome;</li>
 *   <li>a marker entry, {@link #MARKER}, so the client knows the table is Deep Time's
 *       ({@link #deepTimeCacheReceived()}): {@code ProjectAtmosphereClientPrecipitation} then decides
 *       rain or snow on screen from it.</li>
 * </ul>
 *
 * <p>It is sent where Project Atmosphere sends its own table (at login, and to everyone when its
 * forecast is rebuilt, both intercepted by {@code mixin.projectatmosphere.ForecastGeneratorMixin}),
 * and again whenever a player has moved 48 blocks or a minute has passed, so it follows travel, the
 * seasons and the day's weather. When the hook stops applying (switched off, or the player leaves
 * for another dimension and comes back to a world without it), the player gets Project Atmosphere's
 * own table back, without the marker.
 */
public final class ProjectAtmosphereClientCache {

    /** The marker biome key; its value is {@link #MARKER_VALUE} everywhere in the curve. */
    public static final ResourceLocation MARKER = MicClimate.asResource("deep_time_cache");
    public static final float MARKER_VALUE = 1.25f;

    private static final int STEP = 32;
    private static final int MAX_RADIUS = 192;
    private static final int CHECK_TICKS = 20;
    private static final int MAX_AGE_TICKS = 1200;
    private static final int MOVE_BLOCKS = 48;
    /** Project Atmosphere's curves have 24 slots, one per hour of the day. */
    private static final int SLOTS = 24;

    private record Sent(long tick, int x, int z, boolean deepTime) {}

    private static final Map<UUID, Sent> SENT = new ConcurrentHashMap<>();

    private ProjectAtmosphereClientCache() {}

    static void init() {
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, e -> tick(e.getServer()));
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent.class, e -> SENT.remove(e.getEntity().getUUID()));
    }

    // ------------------------------------------------------------------
    // Server side.
    // ------------------------------------------------------------------

    /**
     * Project Atmosphere is about to send {@code snapshot} to {@code player}: send the player's own
     * Deep Time table instead and return true, or return false to let it send its own.
     */
    public static boolean sendInstead(ServerPlayer player, @Nullable Map<ResourceLocation, float[]> snapshot) {
        try {
            if (!applies(player))
                return false;
            if (!player.server.isSameThread()) {
                // Chunks are only read on the server thread.
                player.server.execute(() -> sendInstead(player, snapshot));
                return true;
            }
            send(player, snapshot);
            // Chunks around a player who has just logged in may not be loaded yet: look again soon.
            SENT.computeIfPresent(player.getUUID(), (k, s) -> new Sent(s.tick() - MAX_AGE_TICKS + 100, s.x(), s.z(), true));
            return true;
        } catch (Throwable t) {
            ProjectAtmosphereBase.logOnce(t);
            return false;
        }
    }

    /** Project Atmosphere is about to send its table to everyone: send each player their own instead; false if it should. */
    public static boolean sendAllInstead(MinecraftServer server) {
        try {
            if (server.getPlayerList().getPlayers().stream().noneMatch(ProjectAtmosphereClientCache::applies))
                return false;
            if (!server.isSameThread()) {
                server.execute(() -> sendAllInstead(server));
                return true;
            }
            boolean any = false;
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (applies(player)) {
                    send(player, ForecastGenerator.createDailyTemperatureSnapshotForSync());
                    any = true;
                }
            }
            if (!any)
                return false;
            // Players outside the hook get Project Atmosphere's own table.
            Map<ResourceLocation, float[]> own = ForecastGenerator.createDailyTemperatureSnapshotForSync();
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (!applies(player))
                    PacketDistributor.sendToPlayer(player, new BiomeDayTemperaturePacket(own));
            }
            return true;
        } catch (Throwable t) {
            ProjectAtmosphereBase.logOnce(t);
            return false;
        }
    }

    private static boolean applies(ServerPlayer player) {
        return ClimateConfig.projectAtmosphereClient() && ProjectAtmosphereBase.active(player.serverLevel());
    }

    private static void send(ServerPlayer player, @Nullable Map<ResourceLocation, float[]> snapshot) {
        Map<ResourceLocation, float[]> table = new HashMap<>(snapshot == null ? Map.of() : snapshot);
        table.putAll(local(player.serverLevel(), player.blockPosition(), radius(player)));
        table.put(MARKER, flat(MARKER_VALUE));
        PacketDistributor.sendToPlayer(player, new BiomeDayTemperaturePacket(table));
        SENT.put(player.getUUID(), new Sent(player.serverLevel().getGameTime(), player.getBlockX(), player.getBlockZ(), true));
    }

    private static int radius(ServerPlayer player) {
        int view = player.server.getPlayerList().getViewDistance() * 16;
        return Math.max(STEP, Math.min(MAX_RADIUS, view));
    }

    /** Refreshes tables that have gone stale, and hands Project Atmosphere's back where the hook stopped. */
    private static void tick(MinecraftServer server) {
        try {
            if (server.getTickCount() % CHECK_TICKS != 0)
                return;
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                Sent sent = SENT.get(player.getUUID());
                if (applies(player)) {
                    long now = player.serverLevel().getGameTime();
                    boolean stale = sent == null || !sent.deepTime() || now - sent.tick() >= MAX_AGE_TICKS
                            || Math.abs(player.getBlockX() - sent.x()) > MOVE_BLOCKS
                            || Math.abs(player.getBlockZ() - sent.z()) > MOVE_BLOCKS;
                    if (stale)
                        send(player, ForecastGenerator.createDailyTemperatureSnapshotForSync());
                } else if (sent != null && sent.deepTime()) {
                    PacketDistributor.sendToPlayer(player,
                            new BiomeDayTemperaturePacket(ForecastGenerator.createDailyTemperatureSnapshotForSync()));
                    SENT.remove(player.getUUID());
                }
            }
        } catch (Throwable t) {
            ProjectAtmosphereBase.logOnce(t);
        }
    }

    /**
     * Per biome on the loaded surface within {@code radius} of {@code centre}, the mean of Project
     * Atmosphere's hooked temperature there (Deep Time + weather + pollution), as flat curves. Empty
     * outside the Deep Time part. Only loaded chunks are sampled: nothing is loaded or generated.
     */
    public static Map<ResourceLocation, float[]> local(ServerLevel level, BlockPos centre, int radius) {
        Map<ResourceLocation, double[]> sums = new HashMap<>();
        for (int dx = -radius; dx <= radius; dx += STEP) {
            for (int dz = -radius; dz <= radius; dz += STEP) {
                int x = centre.getX() + dx, z = centre.getZ() + dz;
                LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                if (chunk == null)
                    continue;
                int y = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x & 15, z & 15) + 1;
                BlockPos pos = new BlockPos(x, y, z);
                ResourceLocation biome = level.getBiome(pos).unwrapKey().map(ResourceKey::location).orElse(null);
                if (biome == null)
                    continue;
                Float t = ProjectAtmosphereBase.deepTimeCelsius(level, pos);
                if (t == null || !Float.isFinite(t))
                    continue;
                double[] s = sums.computeIfAbsent(biome, b -> new double[2]);
                s[0] += t;
                s[1]++;
            }
        }
        Map<ResourceLocation, float[]> out = new HashMap<>();
        sums.forEach((biome, s) -> out.put(biome, flat((float) (s[0] / s[1]))));
        return out;
    }

    private static float[] flat(float value) {
        float[] curve = new float[SLOTS];
        Arrays.fill(curve, value);
        return curve;
    }

    // ------------------------------------------------------------------
    // Client side.
    // ------------------------------------------------------------------

    /** Whether this client's Project Atmosphere cache is a Deep Time table (it carries {@link #MARKER}). */
    public static boolean deepTimeCacheReceived() {
        return BiomeClientTemperatureCache.getTemperature(MARKER, null) == MARKER_VALUE;
    }

    /**
     * Rain or snow on screen, for {@code mixin.projectatmosphere.SeasonHooksPrecipitationMixin}: on a
     * client holding a Deep Time table, a biome that precipitates at all gets snow below 0 &deg;C of
     * its table value and rain above; anywhere else (the server, no table, a biome without a value,
     * {@code deepTime.projectAtmosphereClient} off on this client) {@code original} stands.
     */
    public static Biome.Precipitation precipitation(@Nullable Level level, @Nullable Holder<Biome> biome,
                                                    Biome.Precipitation original) {
        try {
            if (original == Biome.Precipitation.NONE || level == null || !level.isClientSide() || biome == null)
                return original;
            if (!ClimateConfig.projectAtmosphereClient() || !deepTimeCacheReceived())
                return original;
            ResourceKey<Biome> key = biome.unwrapKey().orElse(null);
            float t = key == null ? Float.NaN : cached(key);
            if (Float.isNaN(t))
                return original;
            return decide(t);
        } catch (Throwable e) {
            ProjectAtmosphereBase.logOnce(e);
            return original;
        }
    }

    /** Snow below 0 &deg;C, rain otherwise: Project Atmosphere's own freezing line. */
    public static Biome.Precipitation decide(float celsius) {
        return celsius < 0f ? Biome.Precipitation.SNOW : Biome.Precipitation.RAIN;
    }

    /** The client cache's value for {@code biome}, or NaN when it has none. */
    public static float cached(ResourceKey<Biome> biome) {
        float t = BiomeClientTemperatureCache.getTemperature(biome.location(), null);
        // Project Atmosphere answers 0.5 for a biome it has no curve for; a real 0.5 reads as a miss.
        return t == 0.5f ? Float.NaN : t;
    }
}
