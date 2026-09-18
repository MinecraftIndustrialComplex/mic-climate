package com.minecraftindustrialcomplex.mic_climate.lso;

import com.minecraftindustrialcomplex.mic_climate.Compat;
import com.minecraftindustrialcomplex.mic_climate.config.ClimateConfig;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.entity.BlockEntity;
import sfiomn.legendarysurvivaloverhaul.api.temperature.ModifierBase;
import sfiomn.legendarysurvivaloverhaul.config.Config;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Standing next to something hot should be felt.
 *
 * <p>Registered as {@code legendarysurvivaloverhaul:mic_climate_device_heat}.
 * Power Grid already wrote this feature once — {@code compat.cold_sweat.ElectricBlockTemp},
 * dormant because the pack ships LSO rather than Cold Sweat — and this is the
 * same formula against the same source of truth, so a transformer that is about
 * to overheat feels the same whichever survival mod is installed:
 *
 * <pre>
 *   excess   = max(0, deviceTemperature - 22)
 *   units    = excess * deviceHeat.tempScalar  / 100     // 0.04 per 100 &deg;C
 *   reach    = excess * deviceHeat.rangeScalar / 100     // 0.5 blocks per 100 &deg;C
 *   influence= units, fading linearly to 0 from 0.5 blocks out to reach
 * </pre>
 *
 * <p>A basin heater seething at 1600&nbsp;&deg;C therefore adds 0.63 units at
 * arm's length and reaches about 7.9 blocks: warm, not dangerous. Turning it
 * dangerous is a config change, not a code one.
 *
 * <p><b>What counts as a device.</b> Anything carrying Power Grid's
 * {@code ThermalBehaviour} — every electrical machine, and (through
 * mic-destroy-electric) Destroy's Vats. Reading it needs Power Grid's classes,
 * so that read lives in {@link PowerGridThermalReader}, which this class
 * mentions only inside a {@link Compat#isLoaded(String)} guard: LSO without
 * Power Grid must not be a {@code NoClassDefFoundError}.
 *
 * <p><b>Cost.</b> The scan visits the block entities of the few chunks
 * overlapping LSO's {@code "Temperature Influence Maximum Distance"} rather
 * than the ~68 000 blocks in that cube, takes only chunks that are already
 * loaded, and the answer is cached per position for {@link #CACHE_TICKS} ticks.
 * LSO re-evaluates modifiers every {@code "Temperature Tick Time"} (20) ticks,
 * so in practice this runs a handful of times a second across all players.
 *
 * <p>As with {@link ClimateWorldModifier}, the player argument may be
 * {@code null} and is not used.
 */
public class DeviceHeatModifier extends ModifierBase {

    /**
     * Power Grid's own idea of "not hot": {@code ElectricBlockTemp} subtracts
     * this before scaling, and it is also {@code ThermalBehaviour.STANDARD_TEMPERATURE},
     * the temperature devices are rated at. A device sitting at ambient
     * contributes nothing.
     */
    private static final float DEVICE_NEUTRAL_CELSIUS = 22f;

    /** Inside this radius the full influence applies, as in Power Grid's blend. */
    private static final double FULL_INFLUENCE_DISTANCE = 0.5;

    /** How long an answer is reused. Below LSO's 20-tick evaluation period. */
    private static final int CACHE_TICKS = 10;

    /** Used only if LSO's config has not been baked yet; LSO's own default. */
    private static final int DEFAULT_MAX_DISTANCE = 20;

    /** Sanity bound on the scan, whatever the LSO config says. */
    private static final int MAX_SCAN_DISTANCE = 64;

    private static final int MAX_CACHED_POSITIONS = 1024;

    private static final Map<Level, LevelSamples> LEVELS =
            Collections.synchronizedMap(new WeakHashMap<>());

    @Override
    public float getPlayerInfluence(Player player) {
        return 0f;
    }

    @Override
    public float getWorldInfluence(Player player, Level level, BlockPos pos) {
        if (!ClimateConfig.lsoEnabled() || !ClimateConfig.lsoDeviceHeatEnabled())
            return 0f;
        if (level == null || pos == null)
            return 0f;
        if (!Compat.isLoaded(Compat.POWERGRID))
            return 0f;

        long now;
        try {
            now = level.getGameTime();
        } catch (Throwable t) {
            return 0f;
        }

        LevelSamples samples = LEVELS.computeIfAbsent(level, unused -> new LevelSamples());
        long key = pos.asLong();

        Float cached = samples.get(key, now);
        if (cached != null)
            return cached;

        float value;
        try {
            value = scan(level, pos);
        } catch (Throwable t) {
            // A device's temperature is never worth an exception on LSO's
            // temperature tick; a bad block entity just contributes nothing.
            value = 0f;
        }
        samples.put(key, now, value);
        return value;
    }

    private static float scan(Level level, BlockPos pos) {
        int maxDistance = maxDistance();
        double maxDistanceSq = (double) maxDistance * maxDistance;

        int minChunkX = (pos.getX() - maxDistance) >> 4;
        int maxChunkX = (pos.getX() + maxDistance) >> 4;
        int minChunkZ = (pos.getZ() - maxDistance) >> 4;
        int maxChunkZ = (pos.getZ() + maxDistance) >> 4;

        float total = 0f;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                // getChunkNow, not getChunk: a temperature reading must never
                // be the thing that generates or loads a chunk.
                LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (chunk == null)
                    continue;

                for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
                    BlockPos devicePos = entry.getKey();
                    double distanceSq = devicePos.distToCenterSqr(
                            pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                    if (distanceSq > maxDistanceSq)
                        continue;

                    Float temperature = PowerGridThermalReader.temperature(entry.getValue());
                    if (temperature == null)
                        continue;

                    total += influence(temperature, Math.sqrt(distanceSq));
                }
            }
        }
        return total;
    }

    /**
     * Power Grid's {@code ElectricBlockTemp} blend, with one deliberate
     * difference: where Cold Sweat's {@code CSMath.blend} returns the full
     * influence when the reach has shrunk below the 0.5-block plateau, this
     * returns nothing there. That only happens for a device barely above
     * ambient, where the influence is a rounding error either way, and "a
     * lukewarm device warms you at any distance" is not a behaviour worth
     * reproducing.
     */
    private static float influence(float deviceCelsius, double distance) {
        float excess = deviceCelsius - DEVICE_NEUTRAL_CELSIUS;
        if (excess <= 0f)
            return 0f;

        float units = excess * ClimateConfig.lsoDeviceHeatTempScalar() / 100f;
        double reach = (double) excess * ClimateConfig.lsoDeviceHeatRangeScalar() / 100.0;
        if (units <= 0f || distance >= reach)
            return 0f;

        if (distance <= FULL_INFLUENCE_DISTANCE)
            return units;

        double fade = 1.0 - (distance - FULL_INFLUENCE_DISTANCE)
                / (reach - FULL_INFLUENCE_DISTANCE);
        return (float) (units * fade);
    }

    /**
     * LSO's {@code "Temperature Influence Maximum Distance"}, so that device
     * heat reaches exactly as far as the block heat LSO already models.
     * {@code Config.Baked} is filled when LSO's config loads and is zero before
     * that.
     */
    private static int maxDistance() {
        int configured = Config.Baked.tempInfluenceMaximumDist;
        if (configured <= 0)
            configured = DEFAULT_MAX_DISTANCE;
        return Math.min(configured, MAX_SCAN_DISTANCE);
    }

    /** Same shape as {@code Climate}'s chunk cache, keyed by position instead. */
    private static final class LevelSamples {
        private final Long2ObjectMap<Sample> byPos = new Long2ObjectOpenHashMap<>();

        synchronized Float get(long key, long now) {
            Sample sample = this.byPos.get(key);
            if (sample == null)
                return null;
            if (now < sample.tick || now - sample.tick >= CACHE_TICKS)
                return null;
            return sample.influence;
        }

        synchronized void put(long key, long now, float influence) {
            if (this.byPos.size() >= MAX_CACHED_POSITIONS)
                this.byPos.clear();
            this.byPos.put(key, new Sample(now, influence));
        }
    }

    private record Sample(long tick, float influence) {}
}
