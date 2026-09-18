package com.minecraftindustrialcomplex.mic_climate.gametest;

import com.minecraftindustrialcomplex.mic_climate.Compat;
import com.minecraftindustrialcomplex.mic_climate.MicClimate;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The Create: CROWNS bridge.
 *
 * <p>What is asserted is the seam, not the simulation. CROWNS gives every block
 * in a simulated section a kelvin temperature and relaxes it toward a per-block
 * default; for air that default comes from CROWNS' biome table, and the bridge
 * replaces it with the pack's ambient inside
 * {@code PhysicsSaveManager.getDefaultTemperature}. That is a static method
 * with no {@code Level} argument, reached on CROWNS' own physics thread with
 * the level published through {@code CrownsBridge.CURRENT_LEVEL} — so the test
 * calls it the way CROWNS does and checks the number that comes back.
 *
 * <p><b>What this deliberately does not test:</b> that a section CROWNS is
 * actually simulating ends up carrying that value, and that the periodic
 * refresh moves an already-initialised section. Both need CROWNS to have
 * elected a section "near dynamic" and run its physics thread over it, which
 * needs a CROWNS machine, a settling period and a poll — several seconds of
 * wall time whose outcome depends on CROWNS' own scheduling rather than on
 * anything this mod does. The mixin-application evidence in the launch log
 * covers "the injection is live"; this covers "the injection computes the right
 * value"; the middle claim is left to a real world.
 */
@GameTestHolder(MicClimate.MODID)
@PrefixGameTestTemplate(false)
public final class CrownsGameTests {

    private CrownsGameTests() {}

    /**
     * CROWNS' default temperature for an air cell is the pack's ambient, in
     * kelvin, rather than its biome table's 300 K.
     */
    @GameTest(template = GameTests.TEMPLATE, timeoutTicks = 400, batch = "mic_climate_crowns")
    public static void crownsDefaultTemperatureIsTheUnifiedValue(GameTestHelper helper) {
        if (GameTests.skipWithout(helper, Compat.CROWNS))
            return;
        CrownsTestBridge.assertDefaultTemperatureIsUnified(helper);
    }
}
