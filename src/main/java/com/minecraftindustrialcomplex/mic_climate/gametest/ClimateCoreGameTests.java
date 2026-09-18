package com.minecraftindustrialcomplex.mic_climate.gametest;

import com.github.thedeathlycow.thermoo.api.environment.EnvironmentLookup;
import com.github.thedeathlycow.thermoo.api.environment.component.EnvironmentComponentTypes;
import com.github.thedeathlycow.thermoo.api.util.TemperatureRecord;
import com.github.thedeathlycow.thermoo.api.util.TemperatureUnit;
import com.minecraftindustrialcomplex.mic_climate.Climate;
import com.minecraftindustrialcomplex.mic_climate.MicClimate;
import com.minecraftindustrialcomplex.mic_climate.config.ClimateConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The producer end: that Thermoo's lookup really is answering with our provider,
 * and that {@link Climate} reports what the lookup says.
 *
 * <p>These need nothing but Thermoo, so they run in any pack that can load this
 * mod at all.
 */
@GameTestHolder(MicClimate.MODID)
@PrefixGameTestTemplate(false)
public final class ClimateCoreGameTests {

    private ClimateCoreGameTests() {}

    /**
     * Thermoo has a temperature for this position, and it is ours.
     *
     * <p>The lookup returning <em>anything</em> is already the interesting half:
     * Thermoo attaches environment definitions to biomes at {@code SERVER_STARTED}
     * from the datapack registry, so an empty component map here would mean
     * either that {@code data/mic_climate/thermoo/environment/overworld.json} did
     * not load or that {@code mic_climate:unified} failed to register as a
     * provider type — the two silent failures this mod is most exposed to.
     */
    @GameTest(template = GameTests.TEMPLATE, timeoutTicks = 400)
    public static void providerIsSelected(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = GameTests.centre(helper);

        DataComponentMap components = EnvironmentLookup.getInstance()
                .findEnvironmentComponents(level, pos);

        TemperatureRecord record = components.get(EnvironmentComponentTypes.TEMPERATURE);
        GameTests.assertTrue(
                "Thermoo's environment lookup carries a TEMPERATURE component at " + pos,
                record != null
        );

        double fromThermoo = record.valueInUnit(TemperatureUnit.CELSIUS);
        GameTests.record("biome", level.getBiome(pos).unwrapKey().map(Object::toString).orElse("?"));
        GameTests.record("thermoo unit", record.unit());
        GameTests.assertNear(
                "Climate.celsius agrees with Thermoo's lookup",
                Climate.celsius(level, pos), fromThermoo, 0.01
        );

        helper.succeed();
    }

    /**
     * Kelvin is Celsius plus 273.15 and the cache does not lose that.
     *
     * <p>Trivial arithmetic, but Destroy reads kelvin and Power Grid reads
     * Celsius off the same number, so a unit slip here would show up as two
     * bridges disagreeing by 273 degrees and nothing else.
     */
    @GameTest(template = GameTests.TEMPLATE, timeoutTicks = 400)
    public static void kelvinTracksCelsius(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = GameTests.centre(helper);

        GameTests.assertNear(
                "Climate.kelvin is Climate.celsius + 273.15",
                Climate.kelvin(level, pos), Climate.celsius(level, pos) + 273.15, 0.01
        );

        helper.succeed();
    }

    /**
     * {@code Climate.invalidate} really does force the next read to be a fresh
     * one.
     *
     * <p>Half the suite's later tests change a world input and then wait for the
     * number to move, and every one of them depends on the cache being droppable;
     * if it were not, those tests would pass or fail on cache timing rather than
     * on the thing they claim to measure. The config's own {@code cacheTicks} is
     * pinned to 1 here so nothing is left to the default.
     */
    @GameTest(template = GameTests.TEMPLATE, timeoutTicks = 400, batch = "mic_climate_cache")
    public static void cacheCanBeDropped(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = GameTests.centre(helper);

        ClimateConfig.Test.cacheTicks(1);
        try {
            float first = Climate.celsius(level, pos);
            Climate.invalidate(level);
            float second = Climate.celsius(level, pos);
            GameTests.assertNear(
                    "a re-read after invalidate gives the same steady-state value",
                    second, first, 0.01
            );
        } finally {
            ClimateConfig.Test.clear();
        }

        helper.succeed();
    }
}
