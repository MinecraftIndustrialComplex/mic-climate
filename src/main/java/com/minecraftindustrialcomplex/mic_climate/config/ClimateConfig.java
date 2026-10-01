package com.minecraftindustrialcomplex.mic_climate.config;

import com.github.thedeathlycow.thermoo.api.season.ThermooSeason;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * {@code mic_climate-common.toml}.
 *
 * <p>Written straight against NeoForge's {@link ModConfigSpec} rather than
 * Create's catnip {@code ConfigBase}, because catnip would make Create a hard
 * dependency of a mod whose only hard dependency is Thermoo.
 *
 * <p>Every accessor falls back to the hard-coded default when the spec is not
 * loaded. Mixin and provider code runs on paths where load order is not
 * guaranteed (ponder scenes from the title screen, early level load), and a
 * missing config must never be the reason a temperature lookup throws.
 */
public final class ClimateConfig {

    /** Where the ambient temperature comes from. */
    public enum Source {
        /** Project Atmosphere if it is loaded, otherwise the biome+season fallback. */
        AUTO,
        /** Project Atmosphere only; warns once and behaves like {@link #AUTO} if it is absent. */
        PROJECT_ATMOSPHERE,
        /** Never ask Project Atmosphere; always use the biome+season fallback. */
        THERMOO
    }

    /**
     * {@code pollution.mode}, kept so existing config files load. Both values now behave the same:
     * the unified value always adds Destroy's warming itself ({@code MODIFIER}), and Project
     * Atmosphere gets it through {@code pollution.projectAtmosphere} instead of the old eroding push.
     */
    public enum PollutionMode {
        /** The unified value adds Destroy's warming. */
        MODIFIER,
        /**
         * Retired 2026-09-30. Used to push the warming into Project Atmosphere's regional state
         * through its public API, where it eroded; now read as {@link #MODIFIER}, with one warning.
         */
        ATMOSPHERE
    }

    public static final ModConfigSpec SPEC;

    private static ModConfigSpec.EnumValue<Source> SOURCE;
    private static ModConfigSpec.IntValue CACHE_TICKS;
    private static ModConfigSpec.EnumValue<PollutionMode> POLLUTION_MODE;
    private static ModConfigSpec.IntValue POLLUTION_ATMOSPHERE_INTERVAL_TICKS;
    private static ModConfigSpec.DoubleValue POLLUTION_MULTIPLIER;
    private static ModConfigSpec.BooleanValue POLLUTION_PA;
    private static final Map<ThermooSeason, ModConfigSpec.DoubleValue> SEASON_OFFSETS =
            new EnumMap<>(ThermooSeason.class);
    private static ModConfigSpec.BooleanValue DEEP_TIME_ENABLED;
    private static ModConfigSpec.BooleanValue DEEP_TIME_WEATHER;
    private static ModConfigSpec.DoubleValue DEEP_TIME_MAX_ANOMALY;
    private static ModConfigSpec.BooleanValue DEEP_TIME_PA_BASE;
    private static ModConfigSpec.BooleanValue DEEP_TIME_PA_CLIENT;
    private static ModConfigSpec.BooleanValue POWERGRID_ENABLED;
    private static ModConfigSpec.BooleanValue DESTROY_ENABLED;
    private static ModConfigSpec.BooleanValue LSO_ENABLED;
    private static ModConfigSpec.DoubleValue LSO_NEUTRAL_CELSIUS;
    private static ModConfigSpec.DoubleValue LSO_UNITS_PER_DEGREE;
    private static ModConfigSpec.BooleanValue LSO_DEVICE_HEAT_ENABLED;
    private static ModConfigSpec.DoubleValue LSO_DEVICE_HEAT_TEMP_SCALAR;
    private static ModConfigSpec.DoubleValue LSO_DEVICE_HEAT_RANGE_SCALAR;
    private static ModConfigSpec.BooleanValue CROWNS_ENABLED;
    private static ModConfigSpec.IntValue CROWNS_REFRESH_INTERVAL_TICKS;
    private static ModConfigSpec.IntValue CROWNS_SECTIONS_PER_REFRESH;

    private ClimateConfig() {}

    static {
        Pair<Void, ModConfigSpec> pair = new ModConfigSpec.Builder().configure(builder -> {
            builder.comment(
                    "Where the one ambient temperature everything reads comes from.",
                    "AUTO uses Project Atmosphere when it is installed and a biome+season estimate otherwise;",
                    "PROJECT_ATMOSPHERE insists on Project Atmosphere and warns once if it is missing;",
                    "THERMOO never asks Project Atmosphere, so seasons and biomes are the only inputs."
            );
            SOURCE = builder.defineEnum("source", Source.AUTO);

            builder.comment(
                    "How long, in ticks, a chunk's temperature is reused before it is looked up again.",
                    "Power Grid wires ask for the ambient temperature every tick, so this cache is what keeps",
                    "the Thermoo lookup off the hot path. Note that Power Grid devices additionally re-read the",
                    "ambient only every 100 ticks, so lowering this below 20 changes very little."
            );
            CACHE_TICKS = builder.defineInRange("cacheTicks", 20, 1, 200);

            builder.comment("How Destroy's pollution reaches the rest of the pack, and how strongly.")
                    .push("pollution");

            builder.comment(
                    "Put Destroy's greenhouse/ozone warming inside Project Atmosphere's own temperature, in every",
                    "world: its regions' seasonal base (so their targets, day/night band and live temperature), its",
                    "snow and freeze temperature, its rain-or-snow temperature and its readings all warm with the",
                    "sky, and nothing erodes it, because it is part of the base Project Atmosphere relaxes toward.",
                    "This mixes into Project Atmosphere's internals; the mixins are only applied to versions they were",
                    "checked against and skip themselves with a log line otherwise. Needs Project Atmosphere and",
                    "Destroy. The unified value (machines, players, chemistry) adds the warming once either way."
            );
            POLLUTION_PA = builder.define("projectAtmosphere", true);

            builder.comment(
                    "RETIRED 2026-09-30, kept so existing files load. MODIFIER and ATMOSPHERE now behave the same:",
                    "the unified value adds Destroy's warming itself, and Project Atmosphere gets it through",
                    "pollution.projectAtmosphere above. ATMOSPHERE used to push the warming into Project Atmosphere",
                    "through its public API, where Project Atmosphere eroded it; setting it now logs one warning."
            );
            POLLUTION_MODE = builder.defineEnum("mode", PollutionMode.MODIFIER);

            builder.comment("RETIRED 2026-09-30 with pollution.mode = ATMOSPHERE; read by nothing, kept so existing files load.");
            POLLUTION_ATMOSPHERE_INTERVAL_TICKS = builder.defineInRange("atmosphereIntervalTicks", 100, 20, 1200);

            builder.comment(
                    "Multiplier on the ambient temperature rise Destroy's pollution causes. Destroy's own model",
                    "gives up to +20 degrees from Greenhouse gases and +4 from Ozone Depletion, so at 1.0 a",
                    "maximally polluted world runs 24 degrees hotter: Power Grid devices sit closer to overheating,",
                    "wires carry less current, Solar Panels lose efficiency and Destroy's Vats cool toward a hotter",
                    "ambient. Set to 0 to disable the interaction. Destroy's own enablePollution and",
                    "temperatureAffected server configs are respected regardless of this value."
            );
            POLLUTION_MULTIPLIER = builder.defineInRange("multiplier", 1.0, 0.0, 100.0);

            builder.pop();

            builder.comment(
                    "The biome+season estimate used when Project Atmosphere is not answering.",
                    "The biome part maps Minecraft's -0.5..2.0 base temperature onto -20..56 degrees Celsius,",
                    "the same mapping Project Atmosphere uses, and these offsets are then added on top.",
                    "Seasons come from Thermoo, which gets them from Thermoo Patches (Serene Seasons); with no",
                    "seasons mod installed no offset applies at all."
            ).push("fallback");

            for (ThermooSeason season : ThermooSeason.values()) {
                SEASON_OFFSETS.put(season, builder.defineInRange(
                        offsetKey(season), defaultSeasonOffset(season), -50.0, 50.0));
            }

            builder.pop();

            builder.comment(
                    "Deep Time worlds (the deeptime mod): the planet's simulated climate as the base temperature.",
                    "In a Deep Time world the biome is only a coarse band chosen from a simulated climate; Deep Time",
                    "publishes the climate itself (monthly means per block, the lapse rate at the block's height).",
                    "With this on, the unified temperature there is Deep Time's monthly mean at the Serene Seasons",
                    "date (opposite seasons north and south of the equator), plus Project Atmosphere's weather and",
                    "time-of-day swing (its live regional temperature minus its own regional base), plus pollution.",
                    "Worlds Deep Time did not generate are not affected."
            ).push("deepTime");
            DEEP_TIME_ENABLED = builder.define("enabled", true);

            builder.comment(
                    "Add Project Atmosphere's weather and day/night swing on top of Deep Time's climate. Off gives",
                    "the bare monthly mean. Needs Project Atmosphere; without it nothing is added."
            );
            DEEP_TIME_WEATHER = builder.define("weatherAnomaly", true);

            builder.comment(
                    "The largest weather swing, in degrees Celsius either way, taken from Project Atmosphere. Its",
                    "regional state can drift far from its own base in edge cases; this keeps a glitch from turning",
                    "an ice cap into a desert."
            );
            DEEP_TIME_MAX_ANOMALY = builder.defineInRange("maxAnomaly", 20.0, 0.0, 100.0);

            builder.comment(
                    "Give Project Atmosphere itself Deep Time's climate as its base temperature, so that its own snow,",
                    "ice, rain-or-snow, clouds, crop stress, thermometer and HUD follow the planet too, not only the",
                    "machines and players that read mic-climate. Its base is otherwise built from biome base",
                    "temperatures over 2000-block regions with one northern season for the whole world. With this on,",
                    "in a Deep Time world, each region's seasonal base is Deep Time's monthly mean over that region",
                    "at the season's date, and its per-block readings are Deep Time's value at the block plus the",
                    "region's weather; Project Atmosphere's weather, day/night swing and dynamics stay on top.",
                    "This mixes into Project Atmosphere's internals (it has no API for its base). The mixins are only",
                    "applied to Project Atmosphere versions they were checked against and skip themselves with a log",
                    "line otherwise. Needs deepTime.enabled. Worlds Deep Time did not generate are not affected."
            );
            DEEP_TIME_PA_BASE = builder.define("projectAtmosphereBase", true);

            builder.comment(
                    "In a Deep Time world, send each player a Deep Time version of Project Atmosphere's client",
                    "temperature cache (its per-biome table) for the place they are, refreshed as they move and as",
                    "the day goes on, and let the client decide rain or snow on screen from it, so what falls follows",
                    "the planet's climate at that place and its hemisphere's season. Needs projectAtmosphereBase. On a",
                    "client, turning this off makes it ignore such a cache and decide rain or snow as before."
            );
            DEEP_TIME_PA_CLIENT = builder.define("projectAtmosphereClient", true);
            builder.pop();

            builder.comment(
                    "Make Power Grid's ThermalBehaviour.getAmbientTemperature return the unified value.",
                    "Turning this off leaves Power Grid on its own biome-only formula."
            ).push("powergrid");
            POWERGRID_ENABLED = builder.define("enabled", true);
            builder.pop();

            builder.comment(
                    "Make Destroy's PollutionHelper.getLocalTemperature return the unified value.",
                    "Turning this off leaves Destroy on its own biome+pollution formula."
            ).push("destroy");
            DESTROY_ENABLED = builder.define("enabled", true);
            builder.pop();

            builder.comment(
                    "Feed the unified temperature to Legendary Survival Overhaul, as two temperature",
                    "modifiers registered on LSO's own registry.",
                    "This replaces LSO's biome, season, time-of-day and weather terms, so the pack also",
                    "switches those off in legendarysurvivaloverhaul-common.toml; turning this off without",
                    "turning those back on leaves LSO with almost no world temperature at all."
            ).push("lso");
            LSO_ENABLED = builder.define("enabled", true);

            builder.comment(
                    "The ambient temperature, in degrees Celsius, that LSO should consider neutral.",
                    "LSO's comfortable band is 20 plus or minus 10 of its own units, so this is the",
                    "temperature at which mic_climate contributes nothing in either direction."
            );
            LSO_NEUTRAL_CELSIUS = builder.defineInRange("neutralCelsius", 20.0, -100.0, 100.0);

            builder.comment(
                    "How many LSO units one degree Celsius away from neutral is worth.",
                    "The default reproduces the envelope LSO's own biome modifier had: its Biome Temperature",
                    "Multiplier of 18 spans 0..18 units over the same -20..56 degree range Project Atmosphere",
                    "maps vanilla biomes onto, and 18/76 is about 0.24. Raise it for a harsher climate."
            );
            LSO_UNITS_PER_DEGREE = builder.defineInRange("unitsPerDegree", 0.24, 0.0, 10.0);

            builder.comment(
                    "Heat radiating from nearby machines: anything carrying a Power Grid ThermalBehaviour,",
                    "which is every electrical device and, with mic-destroy-electric installed, Destroy's Vats.",
                    "This is Power Grid's own Cold Sweat formula, applied to LSO instead. Needs Power Grid;",
                    "without it nothing here does anything."
            ).push("deviceHeat");
            LSO_DEVICE_HEAT_ENABLED = builder.define("enabled", true);

            builder.comment(
                    "LSO units contributed per 100 degrees Celsius a device sits above 22.",
                    "At the default a 1600 degree basin heater adds 0.63 units next to the player: noticeable,",
                    "not dangerous. Raise it to make hot machinery a real hazard."
            );
            LSO_DEVICE_HEAT_TEMP_SCALAR = builder.defineInRange("tempScalar", 0.04, 0.0, 100.0);

            builder.comment(
                    "How far that heat reaches, in blocks per 100 degrees Celsius above 22. The influence is",
                    "full within half a block and fades linearly to nothing at this range. The scan is also",
                    "capped by LSO's own Temperature Influence Maximum Distance."
            );
            LSO_DEVICE_HEAT_RANGE_SCALAR = builder.defineInRange("rangeScalar", 0.5, 0.0, 100.0);

            builder.pop(2);

            builder.comment(
                    "Seed Create: CROWNS' per-block temperature field from the unified ambient.",
                    "CROWNS gives every block in a simulated section a temperature in kelvin and relaxes it",
                    "toward a per-block default; for air and most blocks that default comes from CROWNS' own",
                    "biome table, which knows nothing about weather, time of day or seasons. With this on, the",
                    "default becomes the pack's ambient instead. Block and fluid defaults are left alone, so",
                    "lava is still lava."
            ).push("crowns");
            CROWNS_ENABLED = builder.define("enabled", true);

            builder.comment(
                    "How often, in ticks, the default-temperature layer of already-simulated sections is",
                    "recomputed, so that a change of season or weather reaches sections nobody has touched.",
                    "CROWNS computes that layer once when a section starts being simulated and then never",
                    "again, so without this the field would follow the climate only at chunk (re)load.",
                    "1200 ticks is one in-game hour. Raise it if the physics thread is struggling."
            );
            CROWNS_REFRESH_INTERVAL_TICKS = builder.defineInRange("refreshIntervalTicks", 1200, 200, 72000);

            builder.comment(
                    "How many 16x16x16 sections one refresh pass may recompute. A world with more simulated",
                    "sections than this refreshes them in rotation over several passes rather than stalling",
                    "CROWNS' physics thread on one of them. Only sections CROWNS is already simulating (those",
                    "near a temperature-carrying block entity) are ever counted."
            );
            CROWNS_SECTIONS_PER_REFRESH = builder.defineInRange("sectionsPerRefresh", 256, 1, 4096);

            builder.pop();

            return null;
        });

        SPEC = pair.getRight();
    }

    public static void register(ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, SPEC, "mic_climate-common.toml");
    }

    /** {@code fallback.springOffset}, {@code fallback.dryOffset}, ... */
    private static String offsetKey(ThermooSeason season) {
        String name = season.getSerializedName();
        return name.substring(0, 1).toLowerCase(Locale.ROOT) + name.substring(1) + "Offset";
    }

    private static double defaultSeasonOffset(ThermooSeason season) {
        return switch (season) {
            case WINTER -> -8.0;
            case SPRING -> -2.0;
            case SUMMER -> 4.0;
            case AUTUMN -> -1.0;
            // The tropics do not have a temperature season; the wet/dry split is
            // about rainfall, so it moves humidity rather than degrees.
            case TROPICAL_DRY, TROPICAL_WET -> 0.0;
        };
    }

    private static boolean loaded() {
        return SPEC.isLoaded();
    }

    /**
     * Config values a GameTest may replace for the duration of one test.
     *
     * <p>Several assertions are of the form "with the bridge on the number moves,
     * with it off it does not", and the honest way to make that comparison is to
     * flip the switch the pack ships rather than to reimplement the formula in
     * the test. Rewriting {@code mic_climate-common.toml} at runtime would mean
     * a config reload event mid-tick, so these overrides sit in front of the
     * spec instead: a set value wins, {@code null} means "ask the config".
     *
     * <p>They exist only when the JVM was started with
     * {@code -Dmic_climate.gametest=true}, which is set by the {@code
     * gameTestServer} run and by nothing else, so a normal game cannot reach
     * them even by accident.
     */
    public static final class Test {

        /** Whether this JVM was launched as a gametest run. */
        public static final boolean ENABLED = Boolean.getBoolean("mic_climate.gametest");

        private static volatile Float forcedCelsius;
        private static volatile Source source;
        private static volatile Integer cacheTicks;
        private static volatile PollutionMode pollutionMode;
        private static volatile Double pollutionMultiplier;
        private static volatile Boolean powergridEnabled;
        private static volatile Boolean destroyEnabled;
        private static volatile Boolean lsoEnabled;
        private static volatile Boolean lsoDeviceHeatEnabled;
        private static volatile Boolean crownsEnabled;
        private static volatile Boolean deepTimeEnabled;
        private static volatile Boolean deepTimeWeather;
        private static volatile Boolean projectAtmosphereBase;
        private static volatile Float projectAtmosphereTestClimate;
        private static volatile Boolean pollutionProjectAtmosphere;

        private Test() {}

        /**
         * Pins the unified ambient to a fixed value, bypassing the provider.
         *
         * <p>Several bridges are only interesting when the ambient <em>moves</em>
         * -- a Power Grid device is supposed to re-sample it, CROWNS is supposed
         * to reseed from it. Moving it by turning a real world input (pollution,
         * the season) works but couples those tests to whichever optional mod
         * supplies the input and to how fast that mod's own caches settle. This
         * is the stimulus with nothing else attached.
         */
        public static void forcedCelsius(Float value) {
            check();
            forcedCelsius = value;
        }

        /** @return the pinned ambient, or {@code null} to compute one normally */
        public static Float forcedCelsius() {
            return ENABLED ? forcedCelsius : null;
        }

        public static void source(Source value) {
            check();
            source = value;
        }

        public static void cacheTicks(Integer value) {
            check();
            cacheTicks = value;
        }

        public static void pollutionMode(PollutionMode value) {
            check();
            pollutionMode = value;
        }

        public static void pollutionMultiplier(Double value) {
            check();
            pollutionMultiplier = value;
        }

        public static void powergridEnabled(Boolean value) {
            check();
            powergridEnabled = value;
        }

        public static void destroyEnabled(Boolean value) {
            check();
            destroyEnabled = value;
        }

        public static void lsoEnabled(Boolean value) {
            check();
            lsoEnabled = value;
        }

        public static void lsoDeviceHeatEnabled(Boolean value) {
            check();
            lsoDeviceHeatEnabled = value;
        }

        public static void crownsEnabled(Boolean value) {
            check();
            crownsEnabled = value;
        }

        public static void deepTimeEnabled(Boolean value) {
            check();
            deepTimeEnabled = value;
        }

        public static void deepTimeWeather(Boolean value) {
            check();
            deepTimeWeather = value;
        }

        public static void projectAtmosphereBase(Boolean value) {
            check();
            projectAtmosphereBase = value;
        }

        public static void pollutionProjectAtmosphere(Boolean value) {
            check();
            pollutionProjectAtmosphere = value;
        }

        /**
         * A stand-in climate for the Project Atmosphere base hook: a constant temperature, in
         * &deg;C, that the hook treats as Deep Time's reading everywhere and in any level.
         *
         * <p>The GameTest server's world is not a Deep Time world, and Deep Time is not on its
         * classpath, so this is the only way a test can watch the hook's mixins change what Project
         * Atmosphere decides (its snow, ice, snapshot and crop stress) and change back.
         */
        public static void projectAtmosphereTestClimate(Float celsius) {
            check();
            projectAtmosphereTestClimate = celsius;
        }

        /** @return the stand-in climate, or {@code null} for the real one */
        public static Float projectAtmosphereTestClimate() {
            return ENABLED ? projectAtmosphereTestClimate : null;
        }

        /** Hands every setting back to the config file. Call from a test's finally. */
        public static void clear() {
            check();
            forcedCelsius = null;
            source = null;
            cacheTicks = null;
            pollutionMode = null;
            pollutionMultiplier = null;
            powergridEnabled = null;
            destroyEnabled = null;
            lsoEnabled = null;
            lsoDeviceHeatEnabled = null;
            crownsEnabled = null;
            deepTimeEnabled = null;
            deepTimeWeather = null;
            projectAtmosphereBase = null;
            projectAtmosphereTestClimate = null;
            pollutionProjectAtmosphere = null;
        }

        private static void check() {
            if (!ENABLED) {
                throw new IllegalStateException(
                        "ClimateConfig.Test is only usable in a gametest run "
                                + "(-Dmic_climate.gametest=true)");
            }
        }
    }

    public static Source source() {
        if (Test.source != null)
            return Test.source;
        return loaded() ? SOURCE.get() : Source.AUTO;
    }

    public static int cacheTicks() {
        if (Test.cacheTicks != null)
            return Test.cacheTicks;
        return loaded() ? CACHE_TICKS.get() : 20;
    }

    /**
     * A {@code pollution.mode} set by {@code /mic_climate mode} for this
     * session, or {@code null} to use the file's value.
     *
     * <p>Not a {@link Test} hook: this one is reachable in a normal game, on
     * purpose. Switching between {@code MODIFIER} and {@code ATMOSPHERE} is the
     * one config change whose effect is worth watching happen — the offset is
     * pushed into Project Atmosphere and taken back out again over the next few
     * seconds — and the alternative is editing {@code mic_climate-common.toml}
     * and reloading, which is not something an operator can do from a console.
     * It deliberately does not touch the file, so a restart is back to what the
     * pack ships.
     */
    private static volatile PollutionMode pollutionModeOverride;

    /** @param mode the mode to run in, or {@code null} to follow the config file again */
    public static void pollutionModeOverride(PollutionMode mode) {
        pollutionModeOverride = mode;
    }

    /** @return the session override, or {@code null} when the file is in charge */
    public static PollutionMode pollutionModeOverride() {
        return pollutionModeOverride;
    }

    public static PollutionMode pollutionMode() {
        if (Test.pollutionMode != null)
            return Test.pollutionMode;
        if (pollutionModeOverride != null)
            return pollutionModeOverride;
        return loaded() ? POLLUTION_MODE.get() : PollutionMode.MODIFIER;
    }

    /**
     * {@code pollution.projectAtmosphere}: Destroy's warming inside Project Atmosphere's own
     * temperature (the pollution part of {@code atmosphere.ProjectAtmosphereBase}).
     */
    public static boolean pollutionProjectAtmosphere() {
        if (Test.pollutionProjectAtmosphere != null)
            return Test.pollutionProjectAtmosphere;
        return !loaded() || POLLUTION_PA.get();
    }

    public static float pollutionMultiplier() {
        if (Test.pollutionMultiplier != null)
            return Test.pollutionMultiplier.floatValue();
        return loaded() ? POLLUTION_MULTIPLIER.get().floatValue() : 1f;
    }

    public static float seasonOffset(ThermooSeason season) {
        ModConfigSpec.DoubleValue value = SEASON_OFFSETS.get(season);
        if (value == null)
            return 0f;
        return loaded() ? value.get().floatValue() : (float) defaultSeasonOffset(season);
    }

    /** {@code deepTime.enabled}: Deep Time's simulated climate as the base in Deep Time worlds. */
    public static boolean deepTimeEnabled() {
        if (Test.deepTimeEnabled != null)
            return Test.deepTimeEnabled;
        return !loaded() || DEEP_TIME_ENABLED.get();
    }

    /** {@code deepTime.weatherAnomaly}: add Project Atmosphere's weather swing on top. */
    public static boolean deepTimeWeather() {
        if (Test.deepTimeWeather != null)
            return Test.deepTimeWeather;
        return !loaded() || DEEP_TIME_WEATHER.get();
    }

    /**
     * A {@code deepTime.projectAtmosphereBase} set by {@code /mic_climate atmosphere base} for this
     * session, or {@code null} to use the file's value. Reachable in a normal game on purpose, like
     * {@link #pollutionModeOverride()}: comparing Project Atmosphere's own reading with and without
     * the hook, in one running world, is what the switch is for.
     */
    private static volatile Boolean projectAtmosphereBaseOverride;

    /** @param value on/off for this session, or {@code null} to follow the config file again */
    public static void projectAtmosphereBaseOverride(Boolean value) {
        projectAtmosphereBaseOverride = value;
    }

    /** @return the session override, or {@code null} when the file is in charge */
    public static Boolean projectAtmosphereBaseOverride() {
        return projectAtmosphereBaseOverride;
    }

    /**
     * {@code deepTime.projectAtmosphereBase}: Deep Time's climate as Project Atmosphere's own base in
     * Deep Time worlds. The hook also needs {@link #deepTimeEnabled()}.
     */
    public static boolean projectAtmosphereBase() {
        if (Test.projectAtmosphereBase != null)
            return Test.projectAtmosphereBase;
        if (projectAtmosphereBaseOverride != null)
            return projectAtmosphereBaseOverride;
        return !loaded() || DEEP_TIME_PA_BASE.get();
    }

    /** {@code deepTime.projectAtmosphereClient}: the per-player Deep Time client cache and its rain/snow. */
    public static boolean projectAtmosphereClient() {
        return !loaded() || DEEP_TIME_PA_CLIENT.get();
    }

    /** {@code deepTime.maxAnomaly}, degrees Celsius. */
    public static float deepTimeMaxAnomaly() {
        return loaded() ? DEEP_TIME_MAX_ANOMALY.get().floatValue() : 20f;
    }

    public static boolean powergridEnabled() {
        if (Test.powergridEnabled != null)
            return Test.powergridEnabled;
        return !loaded() || POWERGRID_ENABLED.get();
    }

    public static boolean destroyEnabled() {
        if (Test.destroyEnabled != null)
            return Test.destroyEnabled;
        return !loaded() || DESTROY_ENABLED.get();
    }

    public static boolean lsoEnabled() {
        if (Test.lsoEnabled != null)
            return Test.lsoEnabled;
        return !loaded() || LSO_ENABLED.get();
    }

    public static float lsoNeutralCelsius() {
        return loaded() ? LSO_NEUTRAL_CELSIUS.get().floatValue() : 20f;
    }

    public static float lsoUnitsPerDegree() {
        return loaded() ? LSO_UNITS_PER_DEGREE.get().floatValue() : 0.24f;
    }

    public static boolean lsoDeviceHeatEnabled() {
        if (Test.lsoDeviceHeatEnabled != null)
            return Test.lsoDeviceHeatEnabled;
        return !loaded() || LSO_DEVICE_HEAT_ENABLED.get();
    }

    public static float lsoDeviceHeatTempScalar() {
        return loaded() ? LSO_DEVICE_HEAT_TEMP_SCALAR.get().floatValue() : 0.04f;
    }

    public static float lsoDeviceHeatRangeScalar() {
        return loaded() ? LSO_DEVICE_HEAT_RANGE_SCALAR.get().floatValue() : 0.5f;
    }

    public static boolean crownsEnabled() {
        if (Test.crownsEnabled != null)
            return Test.crownsEnabled;
        return !loaded() || CROWNS_ENABLED.get();
    }

    public static int crownsRefreshIntervalTicks() {
        return loaded() ? CROWNS_REFRESH_INTERVAL_TICKS.get() : 1200;
    }

    public static int crownsSectionsPerRefresh() {
        return loaded() ? CROWNS_SECTIONS_PER_REFRESH.get() : 256;
    }
}
