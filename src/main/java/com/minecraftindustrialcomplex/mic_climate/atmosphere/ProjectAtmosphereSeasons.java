package com.minecraftindustrialcomplex.mic_climate.atmosphere;

import com.minecraftindustrialcomplex.mic_climate.MicClimate;
import com.minecraftindustrialcomplex.mic_climate.seasons.LatitudeSeasons;
import com.minecraftindustrialcomplex.mic_climate.seasons.PlanetLatitude;
import com.minecraftindustrialcomplex.mic_climate.seasons.SereneSeasonsHemispheres;
import net.Gabou.projectatmosphere.modules.atmosphere.AtmosphericUpdateScheduler;
import net.Gabou.projectatmosphere.modules.atmosphere.RegionAtmosphereState;
import net.Gabou.projectatmosphere.modules.atmosphere.SeasonalAtmosphericDrift;
import net.Gabou.projectatmosphere.seasons.SeasonSnapshot;
import net.Gabou.projectatmosphere.seasons.SeasonStage;
import net.Gabou.projectatmosphere.seasons.SeasonTimeHelper;
import net.Gabou.projectatmosphere.seasons.SereneSeasonsSeasonDelegate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.Nullable;
import sereneseasons.api.season.ISeasonState;
import sereneseasons.api.season.Season;
import sereneseasons.api.season.SeasonHelper;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Project Atmosphere's seasons by latitude on a Deep Time planet: the runtime side of the
 * hemisphere-season mixins into Project Atmosphere ({@code mixin.projectatmosphere}, gated by
 * {@link ProjectAtmosphereMixinPlugin} like the base hook).
 *
 * <p>Project Atmosphere takes its season from Serene Seasons through one delegate
 * ({@code SereneSeasonsSeasonDelegate}) and uses it level-wide, with one exception: its seasonal
 * drift asks the delegate once per region, with the region's position, for the humidity,
 * pressure and cloud-water targets it drifts the region toward. The delegate ignores that
 * position. With the hooks:
 *
 * <ul>
 *   <li><b>Regional season</b> ({@link #delegateState}): asked with a position on a planet, the
 *       delegate reads the local season state (hemisphere-shifted, pulled toward Mid Summer where
 *       the seasons fade), the same one Serene Seasons' crop and melt decisions use there. Its
 *       tropical wet/dry stage keeps its own band, 7.5 to 22.5 degrees, inverted in the south
 *       ({@link #tropical}). Level-wide calls stay global.</li>
 *   <li><b>Sunlight</b> ({@link #sunlight}): the season's sunlight multiplier, which scales every
 *       region's day heating, is level-wide. Each region's heating is rescaled by its own season's
 *       multiplier over the level's.</li>
 *   <li><b>Falling leaves</b> ({@link #leafStage}, client): the wind's leaf particles (orange in
 *       autumn, none in winter) follow the season at the player's position.</li>
 * </ul>
 *
 * <p>Temperature is not touched here: the base hook ({@link ProjectAtmosphereBase}) already
 * replaces Project Atmosphere's global season offset per region with Deep Time's monthly means,
 * which carry each hemisphere's season and the tropics' small one, so doing it again would count
 * the season twice. Everything else of Project Atmosphere that reads the season level-wide
 * (forecast generation, its local resolver's biome table, season-change triggers, commands) either
 * feeds a temperature the base hook replaces or is a trigger at a boundary the hemispheres share.
 *
 * <p>Every entry point hands back Project Atmosphere's own value off a Deep Time planet, with
 * {@code deepTime.hemisphereSeasons} off, and on any exception (logged once).
 */
public final class ProjectAtmosphereSeasons {

    private static final AtomicBoolean LOGGED_FAILURE = new AtomicBoolean();

    /** Project Atmosphere's private {@code SeasonalModifier.forSnapshot} and its sunlight accessor. */
    private static volatile MethodHandle forSnapshot;
    private static volatile MethodHandle sunlightOf;
    private static volatile boolean noModifierAccess;

    private ProjectAtmosphereSeasons() {}

    // ------------------------------------------------------------------
    // Entry points for the mixins.
    // ------------------------------------------------------------------

    /**
     * The Serene Seasons state Project Atmosphere's delegate builds a snapshot from:
     * {@code global} for level-wide calls ({@code pos} null) and off a planet, the local discrete
     * state otherwise.
     */
    public static ISeasonState delegateState(Level level, @Nullable BlockPos pos, ISeasonState global) {
        return pos == null ? global : SereneSeasonsHemispheres.discreteState(level, pos, global);
    }

    /**
     * Whether Project Atmosphere's tropical wet/dry stage applies at {@code pos}: its own answer
     * (Serene Seasons' tropical biomes), inside the tropical band only (7.5 to 22.5 degrees, where the
     * wet/dry cycle's strength is at least {@link LatitudeSeasons#TROPICAL_CUTOFF}), shifted half a year
     * in the south by the local state. The wet/dry cycle does not fade with the temperate seasons
     * (Ben, 2026-09-30: "Exempt wet/dry").
     */
    public static boolean tropical(Level level, @Nullable BlockPos pos, boolean original) {
        if (!original || pos == null)
            return original;
        try {
            double lat = PlanetLatitude.latitude(level, pos.getZ());
            return Double.isNaN(lat) || LatitudeSeasons.tropicalStrength(lat) >= LatitudeSeasons.TROPICAL_CUTOFF;
        } catch (Throwable t) {
            logOnce(t);
            return original;
        }
    }

    /**
     * A region's sunlight multiplier as the scheduler reads it (its biome's), rescaled so that the
     * level-wide seasonal multiplier the scheduler also applies becomes the region's own season's.
     */
    public static float sunlight(RegionAtmosphereState state, float biomeMultiplier) {
        try {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server == null || !server.isSameThread() || state == null)
                return biomeMultiplier;
            ServerLevel overworld = server.overworld();
            BlockPos pos = state.getPosition();
            if (overworld == null || pos == null)
                return biomeMultiplier;
            double lat = PlanetLatitude.latitude(overworld, pos.getZ());
            if (Double.isNaN(lat) || LatitudeSeasons.unchanged(lat, PlanetLatitude.strength(lat)))
                return biomeMultiplier;
            float global = SeasonalAtmosphericDrift.sunlightMultiplier();
            float local = sunlightMultiplier(SeasonTimeHelper.snapshot(overworld, pos));
            if (!(global > 0f) || !Float.isFinite(local))
                return biomeMultiplier;
            return biomeMultiplier * local / global;
        } catch (Throwable t) {
            logOnce(t);
            return biomeMultiplier;
        }
    }

    /** The season stage Project Atmosphere's falling leaves use at the player's position (client). */
    public static SeasonStage leafStage(Level level, BlockPos pos, SeasonStage original) {
        if (original == null || original == SeasonStage.NEUTRAL || pos == null)
            return original;
        try {
            double lat = PlanetLatitude.latitude(level, pos.getZ());
            if (Double.isNaN(lat))
                return original;
            ISeasonState local = SereneSeasonsHemispheres.discreteState(level, pos, SeasonHelper.getSeasonState(level));
            return stage(local.getSeason());
        } catch (Throwable t) {
            logOnce(t);
            return original;
        }
    }

    // ------------------------------------------------------------------
    // Diagnostics.
    // ------------------------------------------------------------------

    /** Project Atmosphere's regional season at {@code pos} (what its drift uses) and its level-wide one. */
    public static String describe(ServerLevel level, BlockPos pos) {
        SeasonSnapshot local = SeasonTimeHelper.snapshot(level, pos);
        SeasonSnapshot global = SeasonTimeHelper.snapshot(level);
        float sun = Float.NaN;
        try {
            sun = sunlightMultiplier(local);
        } catch (Throwable ignored) {
            // reported as NaN
        }
        return String.format(java.util.Locale.ROOT, "region %s/%s (sunlight x%.2f) vs level %s/%s (sunlight x%.2f); hooks bound %d/3",
                local.stage(), local.moistureStage(), sun, global.stage(), global.moistureStage(),
                SeasonalAtmosphericDrift.sunlightMultiplier(), boundTargets());
    }

    /** How many of the hemisphere-season mixins' three Project Atmosphere classes they reached (one is client-only). */
    public static int boundTargets() {
        int n = 0;
        for (Class<?> target : new Class<?>[] {SereneSeasonsSeasonDelegate.class, AtmosphericUpdateScheduler.class})
            if (ProjectAtmosphereHooked.class.isAssignableFrom(target))
                n++;
        try {
            Class<?> client = Class.forName("net.Gabou.projectatmosphere.client.ClientTickHandler", false,
                    ProjectAtmosphereSeasons.class.getClassLoader());
            if (ProjectAtmosphereHooked.class.isAssignableFrom(client))
                n++;
        } catch (Throwable ignored) {
            // a dedicated server has no client classes
        }
        return n;
    }

    /** Project Atmosphere's sunlight multiplier for a snapshot, through its own (private) modifier. */
    public static float sunlightMultiplier(SeasonSnapshot snapshot) throws Throwable {
        if (noModifierAccess)
            return Float.NaN;
        MethodHandle make = forSnapshot, sun = sunlightOf;
        if (make == null || sun == null) {
            try {
                Class<?> modifier = Class.forName(SeasonalAtmosphericDrift.class.getName() + "$SeasonalModifier", true,
                        SeasonalAtmosphericDrift.class.getClassLoader());
                Method m = modifier.getDeclaredMethod("forSnapshot", SeasonSnapshot.class);
                m.setAccessible(true);
                Method s = modifier.getDeclaredMethod("sunlightMultiplier");
                s.setAccessible(true);
                MethodHandles.Lookup lookup = MethodHandles.lookup();
                make = lookup.unreflect(m).asType(MethodType.methodType(Object.class, SeasonSnapshot.class));
                sun = lookup.unreflect(s).asType(MethodType.methodType(float.class, Object.class));
                forSnapshot = make;
                sunlightOf = sun;
            } catch (Throwable t) {
                noModifierAccess = true;
                MicClimate.LOGGER.warn("Project Atmosphere's seasonal modifier is not reachable; its sunlight stays level-wide", t);
                return Float.NaN;
            }
        }
        return (float) sun.invokeExact((Object) make.invokeExact(snapshot));
    }

    private static SeasonStage stage(Season season) {
        return switch (season) {
            case SPRING -> SeasonStage.SPRING;
            case SUMMER -> SeasonStage.SUMMER;
            case AUTUMN -> SeasonStage.AUTUMN;
            case WINTER -> SeasonStage.WINTER;
        };
    }

    private static void logOnce(Throwable t) {
        if (LOGGED_FAILURE.compareAndSet(false, true))
            MicClimate.LOGGER.warn("Project Atmosphere hemisphere seasons failed; it keeps its own season there", t);
    }
}
