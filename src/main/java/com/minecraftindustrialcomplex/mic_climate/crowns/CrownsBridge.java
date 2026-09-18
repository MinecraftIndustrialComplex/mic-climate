package com.minecraftindustrialcomplex.mic_climate.crowns;

import com.minecraftindustrialcomplex.mic_climate.MicClimate;
import com.minecraftindustrialcomplex.mic_climate.config.ClimateConfig;
import com.rae.crowns.content.fields.util.DataLayerType;
import com.rae.crowns.content.fields.util.PhysicsWorldData;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Create: CROWNS bridge, and the only class outside {@code mixin.crowns}
 * that names a {@code com.rae.*} type.
 *
 * <p>CROWNS keeps a real temperature field: every block in a simulated
 * 16&times;16&times;16 section carries a kelvin value, and its solver relaxes
 * each cell toward a per-block <em>default</em> held in a second layer. For air
 * and for any block with no entry in CROWNS' block/fluid tables that default is
 * the biome's figure from {@code data/crowns/float_map/biomes/temperatures.json}
 * — a constant per biome, blind to weather, time of day and seasons.
 * {@code mixin.crowns.PhysicsSaveManagerMixin} replaces it with the pack's
 * ambient; this class supplies the two things that mixin cannot get on its own.
 *
 * <h2>The level</h2>
 *
 * <p>{@code PhysicsSaveManager.getDefaultTemperature} is handed a
 * {@code LevelChunkSection} and an absolute position, but no {@code Level} —
 * the section is enough for CROWNS, which only wants the noise biome. Both of
 * its callers ({@code PhysicsWorldData.initialise} and, through
 * {@code PhysicsWorldData.set}, {@code updateChangedBlocks}) do have the
 * {@link ServerLevel}, so {@link #CURRENT_LEVEL} carries it down. It is a
 * {@link ThreadLocal} because those callers run on CROWNS' own
 * {@code PhysicThread}, not the server thread, and because a dedicated server
 * ticks several levels through the same physics thread in turn.
 *
 * <h2>The refresh</h2>
 *
 * <p>The default layer is computed once, when a section starts being simulated,
 * saved with the world, and never recomputed — so on its own the ambient would
 * only reach CROWNS at chunk (re)load, and a world that never unloads would be
 * stuck in the season it was generated in. CROWNS' own seam for this is
 * {@code PhysicsWorldData.scheduleInitialisation(sectionPos, layers...)}, which
 * makes the next {@code initialise()} rebuild those layers for that section.
 *
 * <p>Two details of {@code initialise()} shape how that seam has to be used:
 *
 * <ul>
 * <li>It drops any scheduled section that is not in {@code getNearDynamic()},
 *     <em>without</em> putting it back into {@code getLoadedSections()} — so
 *     scheduling a section CROWNS is not simulating would quietly evict it.
 *     Only the intersection of the two sets is ever scheduled here.</li>
 * <li>It re-derives "does this section need solving?" from the layers it just
 *     rebuilt, and the test that usually says yes only runs for the
 *     {@code TEMPERATURE} layer. Rebuilding {@code DEFAULT_TEMPERATURE} alone
 *     would therefore park a section that was being solved. Every refreshed
 *     section is marked as needing ticking again once it comes back — which is
 *     also what makes the refresh mean anything, since the solver is what
 *     carries the cells to their new default.</li>
 * </ul>
 *
 * <p>{@code TEMPERATURE} itself is deliberately never rescheduled: that layer
 * <em>is</em> the simulation, and rebuilding it would erase every machine's
 * accumulated heat once an hour.
 *
 * <h2>Threading</h2>
 *
 * <p>{@code toInitialise} and {@code loadedSections} are plain fastutil
 * collections owned by the physics thread. CROWNS itself writes them from the
 * server thread (its {@code /crowns} commands, and {@code putDynamic} when a
 * temperature-carrying block entity loads), but there is no reason to copy that
 * race: the server tick only raises a flag here, and the scheduling itself
 * happens at the head of {@code initialise()}, on the physics thread, in the
 * one place that is about to consume it.
 */
public final class CrownsBridge {

    /**
     * The level whose physics tick is currently running, or {@code null}
     * outside one. Read by {@code PhysicsSaveManagerMixin}, which has no other
     * way to reach it.
     */
    public static final ThreadLocal<ServerLevel> CURRENT_LEVEL = new ThreadLocal<>();

    private static final Map<ResourceKey<Level>, RefreshState> STATE = new ConcurrentHashMap<>();

    private static volatile boolean refreshFailed;

    private CrownsBridge() {}

    /**
     * Starts the refresh timer. Call once, from the mod constructor, with
     * CROWNS known to be present.
     */
    public static void init() {
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, CrownsBridge::onServerTick);
        MicClimate.LOGGER.debug(
                "Create: CROWNS bridge active; its biome default temperature layer now follows the pack's ambient");
    }

    /** Server thread: mark every level's default layer as due for a refresh. */
    private static void onServerTick(ServerTickEvent.Post event) {
        if (!ClimateConfig.crownsEnabled())
            return;

        MinecraftServer server = event.getServer();
        int interval = ClimateConfig.crownsRefreshIntervalTicks();
        if (interval <= 0 || server.getTickCount() % interval != 0)
            return;

        for (ServerLevel level : server.getAllLevels())
            state(level).due = true;
    }

    /**
     * Physics thread, at the head of {@code PhysicsWorldData.initialise}:
     * publish the level for the initializers about to run, and, if a refresh
     * came due since the last pass, schedule this pass's slice of sections.
     */
    public static void beginInitialise(PhysicsWorldData data, ServerLevel level) {
        CURRENT_LEVEL.set(level);

        if (!ClimateConfig.crownsEnabled())
            return;

        RefreshState state = state(level);
        if (!state.due)
            return;
        state.due = false;

        try {
            scheduleSlice(data, state);
        } catch (Throwable t) {
            // A refresh is a nicety; losing CROWNS' physics thread to it is not.
            if (!refreshFailed) {
                refreshFailed = true;
                MicClimate.LOGGER.warn(
                        "Could not refresh Create: CROWNS' default temperature layer; "
                                + "its field will follow the climate only at chunk (re)load", t);
            }
        }
    }

    /**
     * Physics thread, at the return of {@code PhysicsWorldData.initialise}:
     * drop the level again, and hand back to the solver the sections this
     * pass rebuilt.
     */
    public static void endInitialise(PhysicsWorldData data, ServerLevel level) {
        CURRENT_LEVEL.remove();

        RefreshState state = STATE.get(level.dimension());
        if (state == null || state.pending.isEmpty())
            return;

        // initialise() works to a time and count budget, so a big slice takes
        // several passes; a section is done when it is back in loadedSections.
        LongSet loaded = data.getLoadedSections();
        LongIterator it = state.pending.iterator();
        while (it.hasNext()) {
            long section = it.nextLong();
            if (loaded.contains(section)) {
                data.setNeedTicking(section);
                it.remove();
            }
        }
    }

    /**
     * Physics thread: publish the level for {@code updateChangedBlocks}, whose
     * per-block {@code set} runs the same initializer for a single cell.
     */
    public static void enter(ServerLevel level) {
        CURRENT_LEVEL.set(level);
    }

    /** Physics thread: drop the level again. */
    public static void exit() {
        CURRENT_LEVEL.remove();
    }

    /**
     * Schedules up to {@code crowns.sectionsPerRefresh} simulated sections for
     * a rebuild of their default temperature layer, continuing from where the
     * previous pass stopped so a world larger than one slice comes round in
     * order rather than starving its far half.
     */
    private static void scheduleSlice(PhysicsWorldData data, RefreshState state) {
        LongSet loaded = data.getLoadedSections();
        if (loaded.isEmpty())
            return;

        // scheduleInitialisation removes from loadedSections as it goes, so the
        // pass has to run off a copy -- as CROWNS' own reinitializeAll does.
        // Sorting only makes the rotation order stable between passes.
        long[] sections = loaded.toLongArray();
        Arrays.sort(sections);

        LongSet nearDynamic = data.getNearDynamic();
        int budget = Math.min(ClimateConfig.crownsSectionsPerRefresh(), sections.length);

        // Anything left over from the previous pass has had at least
        // refreshIntervalTicks of physics ticks to come back and did not.
        state.pending.clear();

        int start = (int) Math.floorMod(state.cursor, sections.length);
        int examined = 0;
        int scheduled = 0;
        while (examined < sections.length && scheduled < budget) {
            long section = sections[(start + examined) % sections.length];
            examined++;

            if (!nearDynamic.contains(section))
                continue;

            data.scheduleInitialisation(section, DataLayerType.DEFAULT_TEMPERATURE);
            state.pending.add(section);
            scheduled++;
        }

        state.cursor = start + examined;
    }

    private static RefreshState state(ServerLevel level) {
        return STATE.computeIfAbsent(level.dimension(), unused -> new RefreshState());
    }

    /**
     * One level's refresh bookkeeping. Only {@link #due} crosses threads; the
     * rest is touched exclusively from inside {@code initialise}.
     */
    private static final class RefreshState {
        volatile boolean due;
        long cursor;
        final LongSet pending = new LongOpenHashSet();
    }
}
