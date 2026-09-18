package com.minecraftindustrialcomplex.mic_climate.provider;

import com.minecraftindustrialcomplex.mic_climate.config.ClimateConfig;
import net.minecraft.world.level.Level;
import petrolpark.mc.destroy.DestroyAttachmentTypes;
import petrolpark.mc.destroy.core.pollution.LevelPollution;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * How much hotter Destroy's pollution has made the world.
 *
 * <p>The raw figure is Destroy's own outdoor temperature model
 * ({@link LevelPollution#getOutdoorTemperature}) — up to +20&nbsp;K from
 * greenhouse gases and +4&nbsp;K from ozone depletion — minus its clean-world
 * baseline, so only the <em>difference</em> crosses over. Destroy works in
 * kelvin and the unified value in Celsius, but a delta is a delta.
 *
 * <p>Destroy's {@code enablePollution} and {@code temperatureAffected} server
 * configs need no handling here: with either switched off
 * {@code getOutdoorTemperature()} returns the flat baseline, so the delta is
 * zero and the interaction disappears on its own. That is deliberate — this
 * mod should never make pollution matter in a world where the pollution
 * author's own configs say it should not.
 *
 * <p>The shift is one number per level, but the provider that consumes it runs
 * on every uncached chunk lookup, so it is cached per level for a second of
 * game time. Pollution moves over hours; nothing is lost.
 *
 * <p>This class exists only so the {@code petrolpark} imports stay in one
 * lazily-loaded place. It was {@code PollutionTemperature} in
 * {@code mic-destroy-electric} before the unified provider took the job over.
 */
public final class DestroyPollutionShift {

    /** Ticks a level's shift is reused for before being recomputed. */
    private static final int CACHE_TICKS = 20;

    private static final Map<Level, Sample> SAMPLES = Collections.synchronizedMap(new WeakHashMap<>());

    private DestroyPollutionShift() {}

    private record Sample(long tick, float shift) {}

    /**
     * @return the number of degrees to add to the ambient temperature, or 0
     *         when pollution is disabled, the multiplier is 0, or the level's
     *         pollution data is not readable yet
     */
    public static float shift(Level level) {
        if (level == null)
            return 0f;

        long now = level.getGameTime();
        Sample sample = SAMPLES.get(level);
        if (sample != null && now >= sample.tick() && now - sample.tick() < CACHE_TICKS)
            return sample.shift();

        float shift = compute(level);
        SAMPLES.put(level, new Sample(now, shift));
        return shift;
    }

    private static float compute(Level level) {
        float multiplier = ClimateConfig.pollutionMultiplier();
        if (multiplier == 0f)
            return 0f;

        try {
            float delta = level.getData(DestroyAttachmentTypes.LEVEL_POLLUTION).getOutdoorTemperature()
                    - LevelPollution.BASELINE_OUTDOOR_TEMPERATURE_K;
            return delta * multiplier;
        } catch (RuntimeException e) {
            // Pollution data and Destroy's server config are not available on
            // every path that reads a temperature (ponder scenes run from the
            // title screen, early level load). Treat those as a clean world.
            return 0f;
        }
    }
}
