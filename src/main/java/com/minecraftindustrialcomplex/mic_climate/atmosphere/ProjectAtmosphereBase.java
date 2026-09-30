package com.minecraftindustrialcomplex.mic_climate.atmosphere;

import com.minecraftindustrialcomplex.mic_climate.Compat;
import com.minecraftindustrialcomplex.mic_climate.MicClimate;
import com.minecraftindustrialcomplex.mic_climate.config.ClimateConfig;
import com.minecraftindustrialcomplex.mic_climate.provider.DeepTimeSource;
import com.minecraftindustrialcomplex.mic_climate.provider.ProjectAtmosphereSource;
import com.minecraftindustrialcomplex.mic_climate.provider.UnifiedEnvironmentProvider;
import com.minecraftindustrialcomplex.mic_climate.provider.YearClock;
import net.Gabou.projectatmosphere.api.AtmoApi;
import net.Gabou.projectatmosphere.api.WeatherSnapshot;
import net.Gabou.projectatmosphere.manager.CropStressManager;
import net.Gabou.projectatmosphere.manager.ForecastOrchestrator;
import net.Gabou.projectatmosphere.modules.atmosphere.RegionAtmosphereState;
import net.Gabou.projectatmosphere.modules.temperature.util.LocalBiomeTemperatureResolver;
import net.Gabou.projectatmosphere.util.RegionInstanceKey;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Deep Time's climate as Project Atmosphere's own base temperature: the runtime side of the
 * mixins in {@code mixin.projectatmosphere}.
 *
 * <p><b>What changes.</b> Project Atmosphere builds its temperature from biome base temperatures
 * (through a per-biome table), averaged over 2000-block regions at sea level, plus one global
 * season offset. In a Deep Time world that throws away most of what the planet knows (see
 * {@code plans/phase-08-atmosphere-base.md}). With the hook active:
 *
 * <ul>
 *   <li><b>Per region</b> ({@link #seasonOffset}): a region's seasonal base, which its targets,
 *       its day/night band and its relaxation all hang off, is Deep Time's monthly mean over the
 *       region at sea level at today's date. Project Atmosphere's global season offset is replaced
 *       by "that minus the region's own base", so each region gets its own hemisphere's season and
 *       amplitude through the path Project Atmosphere already has. Its clouds and weather sampling
 *       read these regions.</li>
 *   <li><b>Per block</b> ({@link #celsius}): where Project Atmosphere asks about a block (freezing,
 *       snow, rain or snow, crop stress, its public snapshot), the answer is Deep Time's monthly
 *       mean at the block, lapse rate and all, plus the region's weather anomaly: its live
 *       temperature minus its (Deep Time) seasonal base, capped at {@code deepTime.maxAnomaly},
 *       zero for a region Project Atmosphere never simulated. That is exactly the number
 *       mic-climate's own provider builds, minus pollution in {@code MODIFIER} mode.</li>
 * </ul>
 *
 * <p><b>When.</b> Only when {@code deepTime.enabled} and {@code deepTime.projectAtmosphereBase} are
 * on and the level is the overworld of a Deep Time world with a simulated climate. Otherwise every
 * entry point hands Project Atmosphere's own value back unchanged. Any exception does the same,
 * logged once.
 *
 * <p><b>Threads.</b> Project Atmosphere calls its region getters from its worker pool as well as
 * from the server thread. The date is therefore read on the server thread once a tick and
 * published ({@link #refresh}), and Deep Time's climate API is thread-safe; nothing here touches
 * Serene Seasons off the server thread.
 *
 * <p>This class imports Project Atmosphere and is only loaded when it is installed (behind
 * {@code Compat.isLoaded} in {@code MicClimate}, and from the mixins, which exist only then).
 * Deep Time is reached through {@link DeepTimeSource} only.
 */
public final class ProjectAtmosphereBase {

    /** Lattice size for a region's Deep Time mean: 5 x 5 points, 400 blocks apart. */
    private static final int REGION_GRID = 5;

    private static final AtomicBoolean LOGGED_FAILURE = new AtomicBoolean();
    private static final AtomicBoolean LOGGED_ACTIVE = new AtomicBoolean();

    /** What is known about the running overworld; replaced when the server or world changes. */
    private static final class Context {
        final ServerLevel overworld;
        final Map<RegionInstanceKey, double[]> regions = new ConcurrentHashMap<>();
        volatile boolean climate;
        volatile double yearFraction = Double.NaN;
        /** Game time {@link #yearFraction} was read at; MIN_VALUE before the first read. */
        volatile long dateTick = Long.MIN_VALUE;

        Context(ServerLevel overworld) {
            this.overworld = overworld;
            this.climate = Compat.isLoaded(Compat.DEEP_TIME) && DeepTimeSource.hasClimate(overworld);
        }
    }

    @Nullable
    private static volatile Context context;

    private ProjectAtmosphereBase() {}

    /** Registers the per-tick date refresh. Called once, from the mod constructor. */
    public static void init() {
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Pre.class, e -> refresh(e.getServer()));
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class, e -> context = null);
    }

    // ------------------------------------------------------------------
    // Entry points for the mixins.
    // ------------------------------------------------------------------

    /**
     * The season offset a region adds to its base: Deep Time's regional mean at today's date minus
     * the region's own base, or {@code paOffset} (Project Atmosphere's global one) when inactive.
     */
    public static float seasonOffset(Object state, float paOffset) {
        try {
            Context c = activeContext();
            if (c == null)
                return paOffset;
            RegionAtmosphereState region = (RegionAtmosphereState) state;
            float base = regionBase(c, region.getRegionId());
            if (!Float.isFinite(base))
                return paOffset;
            return base - region.getBaseTemperature();
        } catch (Throwable t) {
            logOnce(t);
            return paOffset;
        }
    }

    /** Project Atmosphere's temperature at a block, or {@code original} when inactive. */
    public static float celsius(ServerLevel level, BlockPos pos, float original) {
        Float t = celsius(level, pos);
        return t == null ? original : t;
    }

    /** As {@link #celsius(ServerLevel, BlockPos, float)}, for the resolver that works in doubles. */
    public static double celsius(ServerLevel level, BlockPos pos, double original) {
        Float t = celsius(level, pos);
        return t == null ? original : t;
    }

    /**
     * {@code original} with its temperature replaced by {@link #celsius} and {@code isSnowing}
     * recomputed the way Project Atmosphere computes it (precipitating, and at or below 0 &deg;C or
     * under clouds that snow). Unchanged when inactive.
     */
    @Nullable
    public static WeatherSnapshot snapshot(ServerLevel level, BlockPos pos, @Nullable WeatherSnapshot original) {
        if (original == null)
            return null;
        try {
            Float t = celsius(level, pos);
            if (t == null)
                return original;
            // Project Atmosphere: snowing = rain > 0 && (temperature <= 0 || clouds.snowing()), where
            // the clouds snow when the biome is cold enough to (vanilla's coldEnoughToSnow). When its
            // own temperature was above freezing the flag can only have come from the clouds.
            boolean cloudSnow = original.temperatureC() > 0f
                    ? original.isSnowing()
                    : original.isSnowing() && level.getBiome(pos).value().coldEnoughToSnow(pos);
            boolean snowing = original.rainIntensity() > 0f && (t <= 0f || cloudSnow);
            return new WeatherSnapshot(original.cloudCover(), original.rainIntensity(), t,
                    original.windSpeedMps(), original.windAngleRad(), original.isStorming(), snowing);
        } catch (Throwable e) {
            logOnce(e);
            return original;
        }
    }

    // ------------------------------------------------------------------
    // The model.
    // ------------------------------------------------------------------

    /**
     * Deep Time's monthly mean at the block plus the region's capped weather anomaly, or
     * {@code null} when the hook is not active for this level.
     */
    @Nullable
    public static Float celsius(ServerLevel level, BlockPos pos) {
        try {
            if (level == null || pos == null)
                return null;
            Context c = activeContext(level);
            if (c == null)
                return null;
            float base = baseAt(c, level, pos);
            if (!Float.isFinite(base))
                return null;
            return base + anomaly(level, pos);
        } catch (Throwable t) {
            logOnce(t);
            return null;
        }
    }

    /** Whether the hook changes Project Atmosphere's numbers in {@code level} right now. */
    public static boolean active(Level level) {
        return level instanceof ServerLevel server && activeContext(server) != null;
    }

    /**
     * The region's weather: its live temperature minus its seasonal base, capped; 0 for a region
     * Project Atmosphere never simulated, outside the overworld, or without a region.
     */
    public static float anomaly(ServerLevel level, BlockPos pos) {
        Float a = ProjectAtmosphereSource.weatherAnomaly(level, pos);
        return a == null ? 0f : UnifiedEnvironmentProvider.cap(a, ClimateConfig.deepTimeMaxAnomaly());
    }

    /** The region's Deep Time seasonal base now (sea level), or NaN when inactive; for the probe. */
    public static float regionBase(ServerLevel level, BlockPos pos) {
        Context c = activeContext(level);
        return c == null ? Float.NaN : regionBase(c, RegionInstanceKey.from(pos));
    }

    /** How many of the five Project Atmosphere classes the hook's mixins actually reached. */
    public static int boundTargets() {
        int n = 0;
        for (Class<?> target : new Class<?>[] {RegionAtmosphereState.class, AtmoApi.class, ForecastOrchestrator.class,
                LocalBiomeTemperatureResolver.class, CropStressManager.class}) {
            if (ProjectAtmosphereHooked.class.isAssignableFrom(target))
                n++;
        }
        return n;
    }

    private static float baseAt(Context c, ServerLevel level, BlockPos pos) {
        Float test = ClimateConfig.Test.projectAtmosphereTestClimate();
        if (test != null)
            return test;
        return DeepTimeSource.celsiusAt(level, pos, date(c));
    }

    private static float regionBase(Context c, @Nullable RegionInstanceKey key) {
        Float test = ClimateConfig.Test.projectAtmosphereTestClimate();
        if (test != null)
            return test;
        if (key == null)
            return Float.NaN;
        double[] months = c.regions.get(key);
        if (months == null) {
            int size = key.regionSize();
            months = DeepTimeSource.regionMonthlyC(c.overworld, key.regionX() * size, key.regionZ() * size, size, REGION_GRID);
            if (months == null)
                return Float.NaN;
            c.regions.put(key, months);
        }
        return (float) DeepTimeSource.atYearFraction(months, date(c));
    }

    /**
     * The date published by {@link #refresh}, read afresh on the server thread when this tick has
     * not published one yet (NaN, the annual mean, when there is no season calendar at all).
     */
    private static double date(Context c) {
        long now = c.overworld.getGameTime();
        if (c.dateTick != now && c.overworld.getServer().isSameThread())
            readDate(c, now);
        return c.yearFraction;
    }

    private static void readDate(Context c, long now) {
        c.yearFraction = YearClock.now(c.overworld).yearFraction();
        c.dateTick = now;
    }

    // ------------------------------------------------------------------
    // Gating.
    // ------------------------------------------------------------------

    private static boolean switchedOn() {
        return ClimateConfig.deepTimeEnabled() && ClimateConfig.projectAtmosphereBase();
    }

    /** The context when the hook is active for {@code level}, else null. */
    @Nullable
    private static Context activeContext(ServerLevel level) {
        if (!switchedOn())
            return null;
        MinecraftServer server = level.getServer();
        if (server == null || level != server.overworld())
            return null;
        return active(context(server.overworld()));
    }

    /** The context when the hook is active for the running server's overworld, else null. */
    @Nullable
    private static Context activeContext() {
        if (!switchedOn())
            return null;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null)
            return null;
        ServerLevel overworld = server.overworld();
        return overworld == null ? null : active(context(overworld));
    }

    @Nullable
    private static Context active(Context c) {
        boolean on = c.climate || ClimateConfig.Test.projectAtmosphereTestClimate() != null;
        if (on && LOGGED_ACTIVE.compareAndSet(false, true)) {
            MicClimate.LOGGER.info("Project Atmosphere now takes its base temperature from {} ({} of 5 hook targets bound)",
                    c.climate ? "Deep Time's climate" : "the GameTest stand-in climate", boundTargets());
        }
        return on ? c : null;
    }

    private static Context context(ServerLevel overworld) {
        Context c = context;
        if (c == null || c.overworld != overworld) {
            c = new Context(overworld);
            context = c;
        }
        return c;
    }

    /** Once a server tick, on the server thread: whether this is a Deep Time world, and the date. */
    private static void refresh(MinecraftServer server) {
        try {
            if (!switchedOn())
                return;
            ServerLevel overworld = server.overworld();
            if (overworld == null)
                return;
            Context c = context(overworld);
            c.climate = Compat.isLoaded(Compat.DEEP_TIME) && DeepTimeSource.hasClimate(overworld);
            if (c.climate)
                readDate(c, overworld.getGameTime());
        } catch (Throwable t) {
            logOnce(t);
        }
    }

    private static void logOnce(Throwable t) {
        if (LOGGED_FAILURE.compareAndSet(false, true))
            MicClimate.LOGGER.warn("Project Atmosphere base hook failed; Project Atmosphere keeps its own numbers there", t);
    }
}
