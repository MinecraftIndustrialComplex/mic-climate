package com.minecraftindustrialcomplex.mic_climate.atmosphere;

import com.minecraftindustrialcomplex.mic_climate.MicClimate;
import com.minecraftindustrialcomplex.mic_climate.config.ClimateConfig;
import com.minecraftindustrialcomplex.mic_climate.provider.DestroyPollutionShift;
import net.Gabou.projectatmosphere.modules.atmosphere.AtmosphericStateRegistry;
import net.Gabou.projectatmosphere.modules.atmosphere.RegionAtmosphereState;
import net.Gabou.projectatmosphere.util.RegionInstanceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * {@code pollution.mode = ATMOSPHERE}: Destroy's greenhouse warming pushed into
 * Project Atmosphere's own regional state.
 *
 * <p>In {@code MODIFIER} mode the warming is added inside
 * {@code UnifiedEnvironmentProvider}, which is exact but invisible to Project
 * Atmosphere itself — its forecasts, its HUD and everything reading
 * {@code AtmoApi} still describe a clean world. This mode instead makes the
 * warming part of the weather: the offset goes into
 * {@link RegionAtmosphereState}, so every consumer of Project Atmosphere sees
 * it, and the pack's own unified value picks it back up through
 * {@code ProjectAtmosphereSource} rather than adding it a second time.
 *
 * <p>Only Project Atmosphere's public API is called here. (The one place this
 * mod mixes into Project Atmosphere is the optional Deep Time base hook,
 * {@code ProjectAtmosphereBase} and {@code mixin.projectatmosphere}; with it
 * active the region's seasonal base, which this controller's erosion
 * estimate is measured against, is Deep Time's.)
 *
 * <h2>Why this is a controller and not a one-line write</h2>
 *
 * <p>Project Atmosphere does not rewrite a region's temperature from the
 * forecast each tick — {@code fromForecast}/{@code initializeState} run only at
 * region (re)generation. What it does every update is move the live value:
 * {@code AtmosphericUpdateScheduler} computes a daylight/rain target from the
 * baselines captured at forecast init and applies
 * {@code adjustTemperature(blend * (target - temperature) * scale)}, then
 * {@code relaxTowardBase(relaxFactor)}. So an external offset is not
 * overwritten, it is <em>eroded</em>: whatever we add is pulled back toward
 * where the simulation thinks the region belongs.
 *
 * <p>A one-shot {@code adjustTemperature(shift)} therefore decays, and calling
 * it repeatedly with the same shift would stack without bound. Instead each
 * pass re-adds only what erosion took away:
 *
 * <pre>
 * desired = DestroyPollutionShift.shift(overworld)   // the value MODIFIER mode would add
 * erosion = record.lastSeen - state.getTemperature() // how far PA moved it since our last write
 * applied = clamp(record.applied - erosion,          // assume erosion hit our contribution first
 *                 0, max(record.applied, desired))
 * delta   = desired - applied
 * if (|delta| &gt; 0.05) applied += actual movement of adjustTemperature(delta)
 * </pre>
 *
 * <p>The ceiling is the belief we already held rather than {@code desired}
 * alone, so that a {@code desired} which has fallen to zero is spent on the
 * negative delta that removes the offset instead of quietly erasing the belief
 * that there is one to remove.</p>
 *
 * <p>The offset therefore never stacks, and it unwinds on its own when
 * pollution clears (delta goes negative until {@code desired} is reached).
 * {@code applied} is credited with the temperature the state <em>actually</em>
 * moved rather than with {@code delta}, so Project Atmosphere's own
 * {@code clampTemperature} ceiling cannot leave us believing in an offset that
 * was never applied.
 *
 * <h2>The limitation</h2>
 *
 * <p>The observed-erosion estimate cannot distinguish Project Atmosphere's
 * natural weather drift from erosion of our offset: both show up as the region
 * having moved since we last looked, and everything that moved is charged to
 * our contribution first. On a short cadence the controller keeps the region
 * near {@code natural + desired}, but it is a heuristic, and it is why this
 * mode is experimental and off by default.
 *
 * <p>How well it holds depends directly on
 * {@code pollution.atmosphereIntervalTicks} against Project Atmosphere's own
 * 20-tick active cadence, whose blend pulls 60% of the remaining gap toward the
 * target on every update. At an interval of 20 the offset oscillates between
 * the full amount and about 40% of it; at the default 100 it is largely gone
 * before the next pass restores it, so the region runs warm in a repeating
 * sawtooth rather than at a steady {@code natural + desired}. Lower the
 * interval if the offset should actually hold.
 *
 * <p>The clean fix is not available from outside: {@code baseTemperature} is
 * what the simulation relaxes toward and Project Atmosphere 0.8.1.0 has no
 * public setter for it. A base-temperature offset hook is worth asking its
 * author for — that is a request for a public setter on a mod whose licence
 * already welcomes addons, not a modification we would ship.
 *
 * <h2>Scope: one global map, no dimension</h2>
 *
 * <p>{@code AtmosphericStateRegistry} is static and its {@link RegionInstanceKey}
 * is {@code (regionX, regionZ, regionSize)} with no level in it, so there is one
 * atmosphere for the whole server, not one per dimension; Project Atmosphere's
 * own {@code EventHandler} matches that by ticking nothing outside
 * {@code Level.OVERWORLD}. The records here are keyed the same way, and the
 * level handed to {@link DestroyPollutionShift} is always
 * {@link MinecraftServer#overworld()} — running the pass once per
 * {@code ServerLevel} would apply the overworld's pollution to the same regions
 * once per dimension.
 */
public final class PollutionAtmosphereEffect {

    /** Below this many degrees, a correction is not worth a write. */
    private static final float EPSILON = 0.05f;

    /** Consecutive failed passes before this bridge switches itself off. */
    private static final int MAX_CONSECUTIVE_FAILURES = 10;

    /**
     * What we believe we have added to each region. Server thread only: the
     * tick handler and the shutdown listener are the only writers.
     */
    private static final Map<RegionInstanceKey, Offset> OFFSETS = new HashMap<>();

    private static int consecutiveFailures;

    private static boolean disabled;

    private PollutionAtmosphereEffect() {}

    /**
     * Starts the controller. Call once, from the mod constructor, with both
     * Project Atmosphere and Destroy known to be present.
     *
     * <p>Registration is unconditional on {@code pollution.mode} so that the
     * setting can be flipped in a running game; the tick handler is what reads
     * the mode, and what undoes the offset when it changes back.
     */
    public static void init() {
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, PollutionAtmosphereEffect::onServerTick);
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class, PollutionAtmosphereEffect::onServerStopped);
        MicClimate.LOGGER.debug(
                "Project Atmosphere pollution bridge registered; it acts only while pollution.mode = ATMOSPHERE");
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        if (disabled)
            return;

        MinecraftServer server = event.getServer();

        if (ClimateConfig.pollutionMode() != ClimateConfig.PollutionMode.ATMOSPHERE) {
            // Back to MODIFIER: hand the regions their own weather back once,
            // then go idle. The map being empty is what makes this happen once.
            if (!OFFSETS.isEmpty())
                guarded(PollutionAtmosphereEffect::withdrawAll);
            return;
        }

        int interval = ClimateConfig.pollutionAtmosphereIntervalTicks();
        if (interval <= 0 || server.getTickCount() % interval != 0)
            return;

        guarded(() -> pass(server.overworld()));
    }

    /** A server that stops takes its atmosphere with it; forget the bookkeeping. */
    private static void onServerStopped(ServerStoppedEvent event) {
        OFFSETS.clear();
        consecutiveFailures = 0;
    }

    /**
     * One controller pass over the regions Project Atmosphere is actively
     * simulating. Passive regions are left alone; they are updated a fifth as
     * strongly and nobody is standing in them, and they get their correction
     * when a player brings them back into the active set.
     */
    private static void pass(ServerLevel overworld) {
        float desired = DestroyPollutionShift.shift(overworld);

        // getActiveStates() hands back the registry's live set, which Project
        // Atmosphere replaces wholesale from its own async callback; copy it so
        // one pass works to one membership.
        List<RegionInstanceKey> active = new ArrayList<>(AtmosphericStateRegistry.getActiveStates());

        forget(key -> AtmosphericStateRegistry.getState(key) == null);

        for (RegionInstanceKey key : active) {
            RegionAtmosphereState state = AtmosphericStateRegistry.getState(key);
            if (state == null)
                continue;
            apply(key, state, desired);
        }
    }

    private static void apply(RegionInstanceKey key, RegionAtmosphereState state, float desired) {
        float current = state.getTemperature();

        Offset offset = OFFSETS.get(key);
        float previous = offset == null ? 0f : offset.applied;
        float applied = previous;
        if (offset != null) {
            // Positive when Project Atmosphere pulled the region back down
            // since our last write. Charged to our contribution first -- that
            // is the heuristic this whole class rests on.
            float erosion = offset.lastSeen - current;
            applied = previous - erosion;
        }

        // Destroy's outdoor temperature is its baseline plus non-negative
        // greenhouse and ozone terms, so the shift is never negative: the
        // offset only ever needs clamping up from zero. The ceiling is what we
        // already believed we had, or the new target if that is higher -- not
        // the target alone, because when pollution clears (or Destroy's
        // enablePollution/temperatureAffected go off) the target is zero, and
        // clamping to it would erase the belief instead of spending it on the
        // negative delta that takes the offset back out.
        applied = Mth.clamp(applied, 0f, Math.max(previous, desired));

        float delta = desired - applied;
        if (Math.abs(delta) > EPSILON) {
            state.adjustTemperature(delta);
            float after = state.getTemperature();
            // Credit what the state really moved, not what we asked for:
            // adjustTemperature clamps against the region's own ceiling.
            applied += after - current;
            current = after;
        }

        if (applied <= EPSILON && desired <= EPSILON) {
            OFFSETS.remove(key);
            return;
        }

        if (offset == null) {
            OFFSETS.put(key, new Offset(current, applied));
        } else {
            offset.lastSeen = current;
            offset.applied = applied;
        }
    }

    /**
     * Takes our whole contribution back out of every region we have written to.
     * Anything Project Atmosphere already eroded is not subtracted twice: only
     * what we still believe is present is removed.
     */
    private static void withdrawAll() {
        for (Map.Entry<RegionInstanceKey, Offset> entry : OFFSETS.entrySet()) {
            RegionAtmosphereState state = AtmosphericStateRegistry.getState(entry.getKey());
            if (state == null)
                continue;

            float applied = entry.getValue().applied;
            if (applied > EPSILON)
                state.adjustTemperature(-applied);
        }
        OFFSETS.clear();
        MicClimate.LOGGER.debug(
                "pollution.mode is no longer ATMOSPHERE; removed the pollution offset from Project Atmosphere");
    }

    /**
     * How much of the pollution offset this controller believes it is still
     * holding in a region. Diagnostic: {@code /mic_climate probe} prints it, so
     * that "the atmosphere line moved" can be told apart from "we pushed it".
     *
     * <p>Zero for a region we have never written to, which is also the answer
     * in {@code MODIFIER} mode and on a server where this bridge never started.
     */
    public static float appliedOffset(RegionInstanceKey key) {
        Offset offset = OFFSETS.get(key);
        return offset == null ? 0f : offset.applied;
    }

    /** Drops the records for regions Project Atmosphere no longer has a state for. */
    private static void forget(java.util.function.Predicate<RegionInstanceKey> gone) {
        Iterator<RegionInstanceKey> keys = OFFSETS.keySet().iterator();
        while (keys.hasNext()) {
            if (gone.test(keys.next()))
                keys.remove();
        }
    }

    /**
     * Runs a pass, and stops running them for good if Project Atmosphere keeps
     * throwing. A bridge that is broken should say so once and get out of the
     * way, not fill the log at 5 Hz.
     */
    private static void guarded(Runnable work) {
        try {
            work.run();
            consecutiveFailures = 0;
        } catch (Throwable t) {
            consecutiveFailures++;
            MicClimate.LOGGER.debug("Pollution -> Project Atmosphere pass failed ({})", consecutiveFailures, t);

            if (consecutiveFailures > MAX_CONSECUTIVE_FAILURES) {
                disabled = true;
                OFFSETS.clear();
                MicClimate.LOGGER.error(
                        "Pushing Destroy's pollution into Project Atmosphere failed {} times in a row; "
                                + "switching that off for this session. Set pollution.mode = MODIFIER in "
                                + "mic_climate-common.toml to get the pollution warming back through the "
                                + "environment provider instead.",
                        consecutiveFailures, t);
            }
        }
    }

    /** One region's bookkeeping. */
    private static final class Offset {

        /** The temperature we left the region at, to measure erosion against. */
        private float lastSeen;

        /** How much of {@code desired} we believe is still present. */
        private float applied;

        private Offset(float lastSeen, float applied) {
            this.lastSeen = lastSeen;
            this.applied = applied;
        }
    }
}
