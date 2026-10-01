package com.minecraftindustrialcomplex.mic_climate.atmosphere;

import com.minecraftindustrialcomplex.mic_climate.Compat;
import com.minecraftindustrialcomplex.mic_climate.MicClimate;
import com.minecraftindustrialcomplex.mic_climate.config.ClimateConfig;
import com.minecraftindustrialcomplex.mic_climate.provider.DeepTimeSource;
import com.minecraftindustrialcomplex.mic_climate.provider.DestroyPollutionShift;
import com.minecraftindustrialcomplex.mic_climate.provider.ProjectAtmosphereSource;
import com.minecraftindustrialcomplex.mic_climate.provider.UnifiedEnvironmentProvider;
import com.minecraftindustrialcomplex.mic_climate.provider.YearClock;
import net.Gabou.projectatmosphere.api.AtmoApi;
import net.Gabou.projectatmosphere.api.WeatherSnapshot;
import net.Gabou.projectatmosphere.manager.CropStressManager;
import net.Gabou.projectatmosphere.manager.ForecastOrchestrator;
import net.Gabou.projectatmosphere.modules.atmosphere.AtmosphericStateRegistry;
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
 * The runtime side of the mixins in {@code mixin.projectatmosphere}: what Project Atmosphere's own
 * temperature becomes when mic-climate knows better. Two independent parts share the same hook
 * sites.
 *
 * <p><b>Deep Time's climate as the base</b> (Deep Time worlds only; {@code
 * plans/phase-08-atmosphere-base.md}). Project Atmosphere builds its temperature from biome base
 * temperatures, averaged over 2000-block regions at sea level, plus one global season offset.
 *
 * <ul>
 *   <li><b>Per region</b> ({@link #seasonOffset}): a region's seasonal base, which its targets,
 *       its day/night band and its relaxation all hang off, is Deep Time's monthly mean over the
 *       region at sea level at today's date. Project Atmosphere's global season offset is replaced
 *       by "that minus the region's own base".</li>
 *   <li><b>Per block</b> ({@link #celsius}): where Project Atmosphere asks about a block (freezing,
 *       snow, rain or snow, crop stress, its public snapshot), the answer is Deep Time's monthly
 *       mean at the block plus the region's weather anomaly (live minus seasonal base, capped at
 *       {@code deepTime.maxAnomaly}, zero for a region never simulated), plus pollution.</li>
 * </ul>
 *
 * <p><b>Destroy's pollution inside Project Atmosphere</b> (every world; {@code
 * plans/phase-09-atmosphere-client-and-pollution.md}). Destroy's greenhouse warming
 * ({@link DestroyPollutionShift}) is added to the same season offset, so every region's seasonal
 * base, targets and band carry it and its live temperature settles on it: nothing erodes it,
 * because it is part of what Project Atmosphere relaxes toward. The per-block readings that are
 * built from the forecast rather than the live region (rain or snow, snow and freeze) get it added
 * directly; the ones built from the live region (the snapshot, crop stress) already carry it once
 * the region has simulated. For the unified value (machines) the warming is counted once, the hybrid
 * way ({@link #pollutionCorrection}): at Project Atmosphere's pace where it actively simulates the
 * region, at once everywhere else, which needs an estimate of how much of the warming each region's
 * live temperature holds ({@link #onScheduledUpdate}, fed by the scheduler mixin).
 *
 * <p><b>When.</b> The Deep Time part: {@code deepTime.enabled} and {@code
 * deepTime.projectAtmosphereBase}, in the overworld of a Deep Time world with a climate. The
 * pollution part: Destroy installed and {@code pollution.projectAtmosphere}, in the overworld of any
 * world. Otherwise every entry point hands Project Atmosphere's own value back unchanged; any
 * exception does the same, logged once.
 *
 * <p><b>Threads.</b> Project Atmosphere calls its region getters from its worker pool as well as
 * from the server thread, so the date and the pollution shift are read on the server thread once a
 * tick and published ({@link #refresh}); Deep Time's climate API is thread-safe.
 *
 * <p>This class imports Project Atmosphere and is only loaded when it is installed. Deep Time and
 * Destroy are reached only through {@link DeepTimeSource} and {@link DestroyPollutionShift}, behind
 * {@code Compat.isLoaded}.
 */
public final class ProjectAtmosphereBase {

    /** Which kind of Project Atmosphere reading a per-block hook replaces. */
    public enum Reading {
        /** Built from the region's live temperature (the snapshot, crop stress): carries pollution already. */
        LIVE,
        /** Built from the forecast and the global offset (rain or snow, snow and freeze): does not. */
        FORECAST
    }

    /** Lattice size for a region's Deep Time mean: 5 x 5 points, 400 blocks apart. */
    private static final int REGION_GRID = 5;

    /**
     * A region counts as simulated by Project Atmosphere when its scheduler gave it an ACTIVE update
     * (the pass for regions within 1000 blocks of a player, every 20 ticks) within this many ticks:
     * three active passes, so one missed callback does not flip it.
     */
    public static final int SIMULATING_TICKS = 60;

    /**
     * How far one scheduler update moves a region's live temperature toward a step in its targets,
     * from Project Atmosphere's own constants: scale x (sunlight blend + forecast restore 0.04) +
     * relax factor. ACTIVE: 1 x (0.6 + 0.04) + 0.0012; PASSIVE: 0.35 x (0.45 + 0.04) + 0.00035.
     */
    private static final float ABSORB_ACTIVE = 0.6412f;
    private static final float ABSORB_PASSIVE = 0.1719f;

    private static final AtomicBoolean LOGGED_FAILURE = new AtomicBoolean();
    private static final AtomicBoolean LOGGED_ACTIVE = new AtomicBoolean();
    private static final AtomicBoolean LOGGED_POLLUTION = new AtomicBoolean();

    /** What is known about the running overworld; replaced when the server or world changes. */
    private static final class Context {
        final ServerLevel overworld;
        final Map<RegionInstanceKey, double[]> regions = new ConcurrentHashMap<>();
        volatile boolean climate;
        volatile double yearFraction = Double.NaN;
        /** Game time {@link #yearFraction} was read at; MIN_VALUE before the first read. */
        volatile long dateTick = Long.MIN_VALUE;
        /** Destroy's warming in the overworld, degrees, as published on the server thread. */
        volatile float pollution;
        /** Per region: how much of the warming its live temperature holds, and its last active update. */
        final Map<RegionInstanceKey, Absorbed> absorbed = new ConcurrentHashMap<>();

        Context(ServerLevel overworld) {
            this.overworld = overworld;
            this.climate = Compat.isLoaded(Compat.DEEP_TIME) && DeepTimeSource.hasClimate(overworld);
        }
    }

    /**
     * How much of Destroy's warming one region's live temperature has taken up, as Project
     * Atmosphere's scheduler moved it ({@link #onScheduledUpdate}); {@code state} is the identity of
     * the {@code RegionAtmosphereState} it describes (Project Atmosphere replaces states), and
     * {@code lastActive} the game time of its last ACTIVE update, or MIN_VALUE.
     */
    private record Absorbed(int state, float amount, long lastActive) {}

    @Nullable
    private static volatile Context context;

    private ProjectAtmosphereBase() {}

    /** Registers the per-tick refresh and the client cache. Called once, from the mod constructor. */
    public static void init() {
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Pre.class, e -> refresh(e.getServer()));
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class, e -> context = null);
        ProjectAtmosphereClientCache.init();
    }

    // ------------------------------------------------------------------
    // Entry points for the mixins.
    // ------------------------------------------------------------------

    /**
     * The season offset a region adds to its base: in a Deep Time world Deep Time's regional mean at
     * today's date minus the region's own base, otherwise {@code paOffset} (Project Atmosphere's
     * global one); plus Destroy's warming when the pollution part is on.
     */
    public static float seasonOffset(Object state, float paOffset) {
        try {
            Context c = context();
            if (c == null)
                return paOffset;
            float offset = paOffset;
            if (deepTime(c)) {
                RegionAtmosphereState region = (RegionAtmosphereState) state;
                float base = regionBase(c, region.getRegionId());
                if (Float.isFinite(base))
                    offset = base - region.getBaseTemperature();
            }
            if (pollution(c))
                offset += c.pollution;
            return offset;
        } catch (Throwable t) {
            logOnce(t);
            return paOffset;
        }
    }

    /** Project Atmosphere's temperature at a block, or {@code original} when nothing applies. */
    public static float celsius(ServerLevel level, BlockPos pos, float original, Reading reading) {
        Float t = celsius(level, pos, reading, original);
        return t == null ? original : t;
    }

    /** As {@link #celsius(ServerLevel, BlockPos, float, Reading)}, for the resolver that works in doubles. */
    public static double celsius(ServerLevel level, BlockPos pos, double original, Reading reading) {
        Float t = celsius(level, pos, reading, (float) original);
        return t == null ? original : t;
    }

    /**
     * {@code original} with its temperature replaced as a {@link Reading#LIVE} reading and {@code
     * isSnowing} recomputed the way Project Atmosphere computes it (precipitating, and at or below
     * 0 &deg;C or under clouds that snow). Unchanged when nothing applies.
     */
    @Nullable
    public static WeatherSnapshot snapshot(ServerLevel level, BlockPos pos, @Nullable WeatherSnapshot original) {
        if (original == null)
            return null;
        try {
            Float t = celsius(level, pos, Reading.LIVE, original.temperatureC());
            if (t == null || t == original.temperatureC())
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
     * Project Atmosphere's temperature at a block as the hooks make it, or {@code null} when neither
     * part applies to this level (then the original stands).
     *
     * <ul>
     *   <li>Deep Time world: Deep Time's monthly mean at the block + the region's capped weather
     *       anomaly + pollution, whatever the reading.</li>
     *   <li>Otherwise, pollution part on: {@code original} + pollution for a {@link Reading#FORECAST}
     *       reading; {@code original} for a {@link Reading#LIVE} one, whose region carries it.</li>
     * </ul>
     */
    @Nullable
    public static Float celsius(ServerLevel level, BlockPos pos, Reading reading, float original) {
        try {
            if (level == null || pos == null)
                return null;
            Context c = contextFor(level);
            if (c == null)
                return null;
            if (deepTime(c)) {
                float base = baseAt(c, level, pos);
                if (Float.isFinite(base))
                    return base + anomaly(level, pos) + (pollution(c) ? c.pollution : 0f);
            }
            if (pollution(c) && reading == Reading.FORECAST)
                return original + c.pollution;
            return null;
        } catch (Throwable t) {
            logOnce(t);
            return null;
        }
    }

    /** Deep Time's monthly mean at the block plus the region's weather and pollution; null outside the Deep Time part. */
    @Nullable
    public static Float deepTimeCelsius(ServerLevel level, BlockPos pos) {
        Context c = level == null ? null : contextFor(level);
        return c != null && deepTime(c) ? celsius(level, pos, Reading.FORECAST, Float.NaN) : null;
    }

    /** Whether the Deep Time part changes Project Atmosphere's numbers in {@code level} right now. */
    public static boolean active(Level level) {
        Context c = level instanceof ServerLevel server ? contextFor(server) : null;
        return c != null && deepTime(c);
    }

    /** Whether the pollution part is on for {@code level} (the overworld, Destroy installed, switch on). */
    public static boolean pollutionActive(Level level) {
        Context c = level instanceof ServerLevel server ? contextFor(server) : null;
        return c != null && pollution(c);
    }

    /** Destroy's warming the pollution part is putting into Project Atmosphere now, degrees (0 when off). */
    public static float pollutionShift(Level level) {
        Context c = level instanceof ServerLevel server ? contextFor(server) : null;
        return c != null && pollution(c) ? c.pollution : 0f;
    }

    /**
     * Project Atmosphere's scheduler has just moved {@code state}'s live temperature (one update in
     * {@code AtmosphericUpdateScheduler.applyDeltas}): advance the estimate of how much of Destroy's
     * warming the region holds by the same fraction the update moved it toward its targets (ACTIVE or
     * PASSIVE, plus Project Atmosphere's guard for a deviation over 6 &deg;C), and note an ACTIVE update.
     * A region Project Atmosphere replaced starts again from nothing.
     */
    public static void onScheduledUpdate(Object state) {
        try {
            Context c = context();
            if (c == null || !pollution(c))
                return;
            RegionAtmosphereState region = (RegionAtmosphereState) state;
            RegionInstanceKey key = region.getRegionId();
            if (key == null)
                return;
            boolean active = AtmosphericStateRegistry.getActiveStates().contains(key);
            long now = c.overworld.getGameTime();
            float target = c.pollution;
            int identity = System.identityHashCode(region);
            c.absorbed.compute(key, (k, old) -> {
                float amount = old == null || old.state() != identity ? 0f : old.amount();
                float deviation = target - amount;
                float scale = active ? 1f : 0.35f;
                float step = (active ? ABSORB_ACTIVE : ABSORB_PASSIVE) * deviation;
                float excess = Math.abs(deviation) - 6f;
                if (excess > 0f)
                    step += Math.signum(deviation) * Math.min(3f, 0.15f * excess) * scale;
                if (Math.abs(step) > Math.abs(deviation))
                    step = deviation;
                long lastActive = active ? now : old == null || old.state() != identity ? Long.MIN_VALUE : old.lastActive();
                return new Absorbed(identity, amount + step, lastActive);
            });
        } catch (Throwable t) {
            logOnce(t);
        }
    }

    /**
     * What the unified value adds to Project Atmosphere's reading at {@code pos} for Destroy's warming,
     * so that the warming is counted once and reaches machines the hybrid way (Ben, 2026-09-30):
     *
     * <ul>
     *   <li>no region yet: 0, the reading comes from the forecast, to which the hook adds the warming;</li>
     *   <li>a region Project Atmosphere never simulated (live still exactly its base): the full shift;</li>
     *   <li>a region it is simulating (an ACTIVE update within {@link #SIMULATING_TICKS} ticks): 0, the
     *       warming arrives at Project Atmosphere's own pace through its live temperature;</li>
     *   <li>any other region (only PASSIVE updates, or none since the last player left): the shift less
     *       what the live temperature already holds, so a change applies at once and the total is the
     *       same as once Project Atmosphere has caught up.</li>
     * </ul>
     *
     * <p>{@code null} when the pollution part is off: the provider then adds the whole shift itself, as
     * Project Atmosphere carries none of it. A region first seen after a restart, already simulated, is
     * taken to hold the current shift (its saved live temperature settled on it).
     */
    @Nullable
    public static Float pollutionCorrection(ServerLevel level, BlockPos pos) {
        try {
            Context c = contextFor(level);
            if (c == null || !pollution(c))
                return null;
            RegionInstanceKey key = RegionInstanceKey.from(pos);
            RegionAtmosphereState state = AtmosphericStateRegistry.getState(key);
            if (state == null)
                return 0f;
            float shift = c.pollution;
            int identity = System.identityHashCode(state);
            if (state.getTemperature() == state.getBaseTemperature()) {
                c.absorbed.remove(key);
                return shift;
            }
            Absorbed a = c.absorbed.compute(key, (k, old) ->
                    old != null && old.state() == identity ? old : new Absorbed(identity, shift, Long.MIN_VALUE));
            if (simulating(c, a))
                return 0f;
            return shift - a.amount();
        } catch (Throwable t) {
            logOnce(t);
            return null;
        }
    }

    /** Drops what is known about the region at {@code pos} (GameTests that put a region back as they found it). */
    public static void forgetRegion(ServerLevel level, BlockPos pos) {
        Context c = contextFor(level);
        if (c != null)
            c.absorbed.remove(RegionInstanceKey.from(pos));
    }

    /** Whether Project Atmosphere is simulating the region at {@code pos} in the hybrid's sense; for tests and the probe. */
    public static boolean simulating(ServerLevel level, BlockPos pos) {
        Context c = contextFor(level);
        if (c == null)
            return false;
        RegionAtmosphereState state = AtmosphericStateRegistry.getState(RegionInstanceKey.from(pos));
        Absorbed a = c.absorbed.get(RegionInstanceKey.from(pos));
        return state != null && a != null && a.state() == System.identityHashCode(state) && simulating(c, a);
    }

    private static boolean simulating(Context c, Absorbed a) {
        return a.lastActive() != Long.MIN_VALUE && c.overworld.getGameTime() - a.lastActive() <= SIMULATING_TICKS;
    }

    /**
     * The region's weather: its live temperature minus its seasonal base, capped; 0 for a region
     * Project Atmosphere never simulated, outside the overworld, or without a region. Both terms carry
     * the pollution part equally, so it cancels here.
     */
    public static float anomaly(ServerLevel level, BlockPos pos) {
        Float a = ProjectAtmosphereSource.weatherAnomaly(level, pos);
        return a == null ? 0f : UnifiedEnvironmentProvider.cap(a, ClimateConfig.deepTimeMaxAnomaly());
    }

    /** The region's Deep Time seasonal base now (sea level), or NaN outside the Deep Time part; for the probe. */
    public static float regionBase(ServerLevel level, BlockPos pos) {
        Context c = contextFor(level);
        return c == null || !deepTime(c) ? Float.NaN : regionBase(c, RegionInstanceKey.from(pos));
    }

    /** How many of the five server-side Project Atmosphere classes the hook's mixins actually reached. */
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

    private static boolean deepTimeSwitchedOn() {
        return ClimateConfig.deepTimeEnabled() && ClimateConfig.projectAtmosphereBase();
    }

    private static boolean pollutionSwitchedOn() {
        return Compat.isLoaded(Compat.DESTROY) && ClimateConfig.pollutionProjectAtmosphere();
    }

    /** The Deep Time part is on for this context. */
    private static boolean deepTime(Context c) {
        boolean on = deepTimeSwitchedOn() && (c.climate || ClimateConfig.Test.projectAtmosphereTestClimate() != null);
        if (on && LOGGED_ACTIVE.compareAndSet(false, true)) {
            MicClimate.LOGGER.info("Project Atmosphere now takes its base temperature from {} ({} of 5 hook targets bound)",
                    c.climate ? "Deep Time's climate" : "the GameTest stand-in climate", boundTargets());
        }
        return on;
    }

    /** The pollution part is on for this context. */
    private static boolean pollution(Context c) {
        boolean on = pollutionSwitchedOn();
        if (on && LOGGED_POLLUTION.compareAndSet(false, true)) {
            MicClimate.LOGGER.info("Destroy's pollution warming now goes into Project Atmosphere's own temperature "
                    + "({} of 5 hook targets bound)", boundTargets());
        }
        return on;
    }

    /** The context for {@code level} when it is the running server's overworld, else null. */
    @Nullable
    private static Context contextFor(ServerLevel level) {
        MinecraftServer server = level.getServer();
        if (server == null || level != server.overworld())
            return null;
        return context(level);
    }

    /** The context for the running server's overworld, or null without one. */
    @Nullable
    private static Context context() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        ServerLevel overworld = server == null ? null : server.overworld();
        return overworld == null ? null : context(overworld);
    }

    private static Context context(ServerLevel overworld) {
        Context c = context;
        if (c == null || c.overworld != overworld) {
            c = new Context(overworld);
            context = c;
        }
        return c;
    }

    /** Once a server tick, on the server thread: Deep Time world or not, the date, the pollution. */
    private static void refresh(MinecraftServer server) {
        try {
            ServerLevel overworld = server.overworld();
            if (overworld == null)
                return;
            Context c = context(overworld);
            if (deepTimeSwitchedOn()) {
                c.climate = Compat.isLoaded(Compat.DEEP_TIME) && DeepTimeSource.hasClimate(overworld);
                if (c.climate)
                    readDate(c, overworld.getGameTime());
            }
            c.pollution = pollutionSwitchedOn() ? DestroyPollutionShift.shift(overworld) : 0f;
        } catch (Throwable t) {
            logOnce(t);
        }
    }

    static void logOnce(Throwable t) {
        if (LOGGED_FAILURE.compareAndSet(false, true))
            MicClimate.LOGGER.warn("Project Atmosphere hook failed; Project Atmosphere keeps its own numbers there", t);
    }
}
