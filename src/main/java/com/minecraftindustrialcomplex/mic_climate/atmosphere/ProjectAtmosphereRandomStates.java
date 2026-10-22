package com.minecraftindustrialcomplex.mic_climate.atmosphere;

import net.minecraft.core.HolderGetter;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * The one {@link RandomState} Project Atmosphere's biome sampler needs, built once per server
 * instead of once per forecast square.
 *
 * <p>Project Atmosphere 0.9.1.2 builds a new {@code BiomeSampler} in every
 * {@code ForecastGenerator.sampleSquare} call (line 228), and each one calls
 * {@code RandomState.create(OVERWORLD settings, the noise registry, ProjectAtmosphere.seed)}
 * ({@code BiomeSampler.java:35}). That is the whole overworld noise router wired again, and with
 * C2ME every density function in it compiled again: about a second each time, on whichever thread
 * asked. It happens for the start-up forecast and again for every region no save holds yet, often on
 * the server thread (a temperature query through Thermoo reaches it), where it stalls the tick.
 *
 * <p>The inputs are the same every time within one server: the same settings object, the same noise
 * lookup ({@code MappedRegistry.asLookup()} hands out one instance) and the same seed. So the state
 * is built on the first call and handed back for later calls with the same inputs. The inputs are
 * compared by identity, not by {@code equals}: {@link NoiseGeneratorSettings} is a record, whose
 * {@code equals} walks every density function, and a mod that swaps the router in place (Lithostitched
 * does, for its router wrappers) changes {@link NoiseGeneratorSettings#noiseRouter()}'s identity, so
 * that is compared too. Any change of input builds a new state, exactly as Project Atmosphere would
 * have, and replaces this one. What Project Atmosphere samples is therefore unchanged: the state it
 * would have built from these inputs, built once.
 *
 * <p>Sharing is safe because a {@code RandomState}'s climate sampler keeps no per-call state in its
 * functions: a single-point sample's caches live in the context passed in (vanilla and C2ME's
 * compiled functions alike), and vanilla itself shares one state across every worldgen thread.
 *
 * <p>One slot, not a map: one server has one seed and one set of registries. Cleared when the server
 * stops ({@code MicClimate}), so a finished single-player world's registries are not kept alive.
 * Only vanilla types here: {@code mixin.projectatmosphere.BiomeSamplerMixin} is the caller.
 */
public final class ProjectAtmosphereRandomStates {

    private ProjectAtmosphereRandomStates() {}

    /** The inputs one state was built from, all compared by identity. */
    private record Key(NoiseGeneratorSettings settings, NoiseRouter router,
                       HolderGetter<NormalNoise.NoiseParameters> noises, long seed) {
        boolean same(NoiseGeneratorSettings settings, HolderGetter<NormalNoise.NoiseParameters> noises, long seed) {
            return this.settings == settings && this.router == settings.noiseRouter()
                    && this.noises == noises && this.seed == seed;
        }
    }

    private static final Object LOCK = new Object();
    private static Key key;
    private static RandomState state;

    private static final AtomicLong BUILT = new AtomicLong();
    private static final AtomicLong REUSED = new AtomicLong();

    /**
     * The state for these inputs: the cached one when they match the last build, otherwise
     * {@code build}'s, which then becomes the cached one. Built under a lock, so threads that ask at
     * the same moment wait for one build instead of each compiling their own. {@code build} must not
     * wait on another thread (Project Atmosphere's {@code RandomState.create} does not).
     */
    public static RandomState get(NoiseGeneratorSettings settings, HolderGetter<NormalNoise.NoiseParameters> noises,
                                  long seed, Supplier<RandomState> build) {
        synchronized (LOCK) {
            if (state != null && key.same(settings, noises, seed)) {
                REUSED.incrementAndGet();
                return state;
            }
            RandomState built = build.get();
            key = new Key(settings, settings.noiseRouter(), noises, seed);
            state = built;
            BUILT.incrementAndGet();
            return built;
        }
    }

    /** Drops the cached state (the server stopped). */
    public static void clear() {
        synchronized (LOCK) {
            key = null;
            state = null;
        }
    }

    /** States built since start-up, for the GameTests and the probe. */
    public static long built() {
        return BUILT.get();
    }

    /** Times a cached state was handed back instead of building one. */
    public static long reused() {
        return REUSED.get();
    }
}
