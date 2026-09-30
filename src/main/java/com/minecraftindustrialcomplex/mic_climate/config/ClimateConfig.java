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

    /** How Destroy's pollution-driven warming enters the world. */
    public enum PollutionMode {
        /** Added to the unified value inside our environment provider. */
        MODIFIER,
        /**
         * Pushed into Project Atmosphere's own regional state, so that its
         * forecasts and every other consumer of it see the warming too.
         * Experimental: see {@code atmosphere.PollutionAtmosphereEffect}.
         */
        ATMOSPHERE
    }

    public static final ModConfigSpec SPEC;

    private static ModConfigSpec.EnumValue<Source> SOURCE;
    private static ModConfigSpec.IntValue CACHE_TICKS;
    private static ModConfigSpec.EnumValue<PollutionMode> POLLUTION_MODE;
    private static ModConfigSpec.IntValue POLLUTION_ATMOSPHERE_INTERVAL_TICKS;
    private static ModConfigSpec.DoubleValue POLLUTION_MULTIPLIER;
    private static final Map<ThermooSeason, ModConfigSpec.DoubleValue> SEASON_OFFSETS =
            new EnumMap<>(ThermooSeason.class);
    private static ModConfigSpec.BooleanValue DEEP_TIME_ENABLED;
    private static ModConfigSpec.BooleanValue DEEP_TIME_WEATHER;
    private static ModConfigSpec.DoubleValue DEEP_TIME_MAX_ANOMALY;
    private static ModConfigSpec.BooleanValue DEEP_TIME_PA_BASE;
    private static ModConfigSpec.BooleanValue DEEP_TIME_HEMISPHERE_SEASONS;
    private static ModConfigSpec.DoubleValue DEEP_TIME_FULL_SEASON_LATITUDE;
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
                    "MODIFIER adds Destroy's greenhouse/ozone warming to the unified temperature at its source,",
                    "so every consumer sees it exactly once.",
                    "ATMOSPHERE instead pushes the warming into Project Atmosphere's own regional state, so that",
                    "its forecasts, its displays and everything else reading it are warmed too, and the pack's",
                    "temperature picks the warming back up from there instead of adding it separately. Needs both",
                    "Project Atmosphere and Destroy; without Project Atmosphere it falls back to MODIFIER.",
                    "This mode is experimental. Project Atmosphere erodes any temperature written from outside,",
                    "pulling the region back toward where its own simulation says it belongs, so the offset has to",
                    "be topped up on a timer, and the top-up cannot tell natural weather drift apart from erosion",
                    "of the offset: it charges everything the region moved to the offset first. It never stacks and",
                    "it unwinds when pollution clears, but the value it holds is an estimate, not an exact figure."
            );
            POLLUTION_MODE = builder.defineEnum("mode", PollutionMode.MODIFIER);

            builder.comment(
                    "How often, in ticks, ATMOSPHERE mode tops the pollution offset back up in each region",
                    "Project Atmosphere is actively simulating. Does nothing in MODIFIER mode.",
                    "Project Atmosphere moves active regions 60 percent of the way toward their own target every",
                    "20 ticks, which is also how fast it erodes the offset, so this interval decides what the",
                    "warming actually feels like: at 20 the region stays between the full offset and about 40",
                    "percent of it, while at the default 100 the offset has mostly decayed again before the next",
                    "pass restores it, giving a repeating rise and fall rather than a steady warmer world.",
                    "Lower it toward 20 for a warming that holds; raise it to make the push cheaper and gentler."
            );
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
                    "Serene Seasons' seasons by latitude on a Deep Time planet (latitude = -z * 360 / circumference).",
                    "Serene Seasons has one season per world, the northern one. With this on, south of the equator",
                    "its calendar runs half a year out, and the seasons fade smoothly toward the equator, where",
                    "there are none (Mid Summer, Serene Seasons' neutral season, all year). It covers Serene",
                    "Seasons' grass, foliage and birch colours, its biome temperature (snow, ice, rain or snow), crop",
                    "fertility, melting and the season sensor; Serene Seasons Plus's snow policy; and Project",
                    "Atmosphere's regional season (humidity, pressure, cloud water, sunlight) and falling leaves.",
                    "Serene Seasons' own season, events and weather frequency stay the world's. This mixes into those",
                    "mods (they have no per-position season API); each mixin is only applied to versions it was",
                    "checked against. Needs deepTime.enabled. Worlds Deep Time did not generate are not affected.",
                    "Clients read their own copy of this file for the colours, so keep it the same on both sides."
            );
            DEEP_TIME_HEMISPHERE_SEASONS = builder.define("hemisphereSeasons", true);

            builder.comment(
                    "The latitude, in degrees, at and beyond which the seasons have their full strength. Toward the",
                    "equator they fade smoothly (a smoothstep: half strength at half this latitude) to none at the",
                    "equator. Keep it the same on the server and on clients."
            );
            DEEP_TIME_FULL_SEASON_LATITUDE = builder.defineInRange("fullSeasonLatitude", 45.0, 1.0, 90.0);
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
        private static volatile Boolean hemisphereSeasons;
        private static volatile TestPlanet seasonTestPlanet;

        private Test() {}

        /**
         * A stand-in Deep Time planet for the hemisphere seasons: every server level of the overworld
         * counts as a planet of this circumference, with its equator at block {@code equatorZ}.
         *
         * @param circumference C in blocks; latitude is {@code -(z - equatorZ) * 360 / C}
         * @param equatorZ      the z of the equator (0 on a real planet); a test moves it to put its
         *                      own blocks in the north, on the equator or in the south
         */
        public record TestPlanet(int circumference, int equatorZ) {}

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

        public static void hemisphereSeasons(Boolean value) {
            check();
            hemisphereSeasons = value;
        }

        /**
         * Treat the overworld as a Deep Time planet of circumference {@code circumference} with its
         * equator at {@code equatorZ} (see {@link TestPlanet}); {@code null} for no stand-in.
         */
        public static void seasonTestPlanet(TestPlanet planet) {
            check();
            seasonTestPlanet = planet;
        }

        /** @return the stand-in planet, or {@code null} for Deep Time's own */
        public static TestPlanet seasonTestPlanet() {
            return ENABLED ? seasonTestPlanet : null;
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
            hemisphereSeasons = null;
            seasonTestPlanet = null;
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

    public static int pollutionAtmosphereIntervalTicks() {
        return loaded() ? POLLUTION_ATMOSPHERE_INTERVAL_TICKS.get() : 100;
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

    /**
     * A {@code deepTime.hemisphereSeasons} set by {@code /mic_climate seasons} for this session, or
     * {@code null} to use the file's value. Reachable in a normal game on purpose: comparing Serene
     * Seasons' own season with the latitude's, in one running world, is what the switch is for.
     */
    private static volatile Boolean hemisphereSeasonsOverride;

    /** @param value on/off for this session, or {@code null} to follow the config file again */
    public static void hemisphereSeasonsOverride(Boolean value) {
        hemisphereSeasonsOverride = value;
    }

    /** @return the session override, or {@code null} when the file is in charge */
    public static Boolean hemisphereSeasonsOverride() {
        return hemisphereSeasonsOverride;
    }

    /**
     * {@code deepTime.hemisphereSeasons}: Serene Seasons' seasons by latitude on Deep Time planets.
     * Also needs {@link #deepTimeEnabled()}.
     */
    public static boolean hemisphereSeasons() {
        if (Test.hemisphereSeasons != null)
            return Test.hemisphereSeasons;
        if (hemisphereSeasonsOverride != null)
            return hemisphereSeasonsOverride;
        return !loaded() || DEEP_TIME_HEMISPHERE_SEASONS.get();
    }

    /** {@code deepTime.fullSeasonLatitude}, degrees. */
    public static double fullSeasonLatitude() {
        return loaded() ? DEEP_TIME_FULL_SEASON_LATITUDE.get() : 45.0;
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
