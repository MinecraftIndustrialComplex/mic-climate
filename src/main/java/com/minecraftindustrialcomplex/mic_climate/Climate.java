package com.minecraftindustrialcomplex.mic_climate;

import com.github.thedeathlycow.thermoo.api.environment.EnvironmentLookup;
import com.github.thedeathlycow.thermoo.api.environment.component.EnvironmentComponentTypes;
import com.github.thedeathlycow.thermoo.api.environment.component.TemperatureRecordComponent;
import com.github.thedeathlycow.thermoo.api.util.TemperatureRecord;
import com.github.thedeathlycow.thermoo.api.util.TemperatureUnit;
import com.minecraftindustrialcomplex.mic_climate.config.ClimateConfig;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The one read path: "how warm is it at this block?".
 *
 * <p>Everything downstream — Power Grid's devices, Destroy's vats, later LSO
 * and CROWNS — goes through here, and here goes through Thermoo's
 * {@link EnvironmentLookup}, which in turn runs whichever environment providers
 * a datapack has attached to the biome. In this pack that is
 * {@code mic_climate:unified}.
 *
 * <p><b>Why the cache.</b> Power Grid asks for the ambient temperature from
 * wire updates, which happen every tick on every wire in the world, and the
 * lookup behind it walks a provider list and builds a component map. The value
 * is region-and-biome resolution anyway, so it is cached per chunk section (16 blocks cubed) for
 * {@link ClimateConfig#cacheTicks()} ticks and nothing is lost by it.
 *
 * <p><b>Why it never throws.</b> Ambient temperature gets asked for from
 * places that are not really worlds: Create's ponder scenes run a virtual level
 * from the title screen, schematic rendering does something similar, and block
 * entities can tick before a level's biome source is ready. Any failure here is
 * answered with a plain 20&nbsp;&deg;C, cached like any other sample so a
 * broken path cannot become a per-tick exception storm.
 */
public final class Climate {

    /** What a lookup answers with when it cannot answer: Thermoo's own default. */
    public static final float FALLBACK_CELSIUS = 20f;

    private static final float KELVIN_OFFSET = 273.15f;

    /**
     * Cap on remembered chunks per level. A player flying at elytra speed sees
     * a few thousand chunks in a couple of minutes, and none of the ones behind
     * them matter any more.
     */
    private static final int MAX_CHUNKS_PER_LEVEL = 4096;

    private static final Map<Level, LevelSamples> LEVELS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private Climate() {}

    /**
     * The unified ambient temperature at a position, in degrees Celsius.
     *
     * <p>Safe to call from either logical side and from any thread; the
     * per-level sample map is guarded by its own lock.
     */
    public static float celsius(Level level, BlockPos pos) {
        if (level == null || pos == null)
            return FALLBACK_CELSIUS;

        // Constant-folded away outside a gametest run: ENABLED is a static final
        // boolean read from a system property this JVM was started with.
        if (ClimateConfig.Test.ENABLED) {
            Float forced = ClimateConfig.Test.forcedCelsius();
            if (forced != null)
                return forced;
        }

        LevelSamples samples = LEVELS.computeIfAbsent(level, unused -> new LevelSamples());
        long chunk = cacheKey(pos);

        long now;
        try {
            now = level.getGameTime();
        } catch (Throwable t) {
            return FALLBACK_CELSIUS;
        }

        Float cached = samples.get(chunk, now, ClimateConfig.cacheTicks());
        if (cached != null)
            return cached;

        float value = lookup(level, pos);
        samples.put(chunk, now, value);
        return value;
    }

    /** The same value in kelvin, for the mods that work in absolute units. */
    public static float kelvin(Level level, BlockPos pos) {
        return celsius(level, pos) + KELVIN_OFFSET;
    }

    /**
     * Drops a level's cached samples. Nothing in the mod needs this; it exists
     * so a test or a debug command can force the next read to be a real one.
     */
    public static void invalidate(Level level) {
        if (level == null)
            LEVELS.clear();
        else
            LEVELS.remove(level);
    }

    /**
     * The Thermoo lookup with the cache stepped around, for
     * {@code /mic_climate probe}: the probe wants to show what the environment
     * provider says <em>now</em> next to what the cache is still handing out,
     * and reading both through {@link #celsius} would only ever show one of
     * them. Does not populate the cache either, so probing cannot change what
     * the next real reader sees.
     */
    public static float uncachedCelsius(Level level, BlockPos pos) {
        if (level == null || pos == null)
            return FALLBACK_CELSIUS;
        return lookup(level, pos);
    }

    /**
     * How many ticks ago the cached sample covering {@code pos} was taken, or
     * {@code -1} when there is none (or it has expired). Diagnostic only.
     */
    public static int cacheAge(Level level, BlockPos pos) {
        if (level == null || pos == null)
            return -1;

        LevelSamples samples = LEVELS.get(level);
        if (samples == null)
            return -1;

        long now;
        try {
            now = level.getGameTime();
        } catch (Throwable t) {
            return -1;
        }

        Long tick = samples.tick(cacheKey(pos));
        if (tick == null || now < tick)
            return -1;

        long age = now - tick;
        return age >= ClimateConfig.cacheTicks() ? -1 : (int) age;
    }

    /**
     * The cache cell of a position: its 16-block chunk section. Per section rather than per chunk
     * column because in a Deep Time world the temperature falls with height (the planet's lapse rate,
     * about 0.13 degrees per block at its default scale), so a machine on a mountain top and one in
     * the valley below must not share a sample.
     */
    private static long cacheKey(BlockPos pos) {
        return SectionPos.asLong(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
    }

    private static float lookup(Level level, BlockPos pos) {
        try {
            DataComponentMap components = EnvironmentLookup.getInstance()
                    .findEnvironmentComponents(level, pos);

            // A biome with no matching EnvironmentDefinition yields an EMPTY
            // map rather than a defaulted one, so the default belongs here.
            TemperatureRecord record = components.getOrDefault(
                    EnvironmentComponentTypes.TEMPERATURE,
                    TemperatureRecordComponent.DEFAULT
            );

            float celsius = (float) record.valueInUnit(TemperatureUnit.CELSIUS);
            return Float.isFinite(celsius) ? celsius : FALLBACK_CELSIUS;
        } catch (Throwable t) {
            return FALLBACK_CELSIUS;
        }
    }

    /**
     * One level's chunk samples. Small enough that a plain lock beats anything
     * cleverer, and it is held only for a map read or write.
     */
    private static final class LevelSamples {
        private final Long2ObjectMap<Sample> byChunk = new Long2ObjectOpenHashMap<>();

        synchronized Float get(long chunk, long now, int ttl) {
            Sample sample = this.byChunk.get(chunk);
            if (sample == null)
                return null;
            // now < tick means the level's clock went backwards (a rewind, or a
            // fresh world reusing the object); treat the sample as stale.
            if (now < sample.tick || now - sample.tick >= ttl)
                return null;
            return sample.celsius;
        }

        /** When the sample covering this chunk was taken, or {@code null}. */
        synchronized Long tick(long chunk) {
            Sample sample = this.byChunk.get(chunk);
            return sample == null ? null : sample.tick;
        }

        synchronized void put(long chunk, long now, float celsius) {
            if (this.byChunk.size() >= MAX_CHUNKS_PER_LEVEL) {
                // Nothing here is worth an LRU: every entry expires within a
                // second of game time anyway, so drop the lot and re-sample.
                this.byChunk.clear();
            }
            this.byChunk.put(chunk, new Sample(now, celsius));
        }
    }

    private record Sample(long tick, float celsius) {}
}
