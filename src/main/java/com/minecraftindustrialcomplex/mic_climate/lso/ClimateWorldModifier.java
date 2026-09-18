package com.minecraftindustrialcomplex.mic_climate.lso;

import com.minecraftindustrialcomplex.mic_climate.Climate;
import com.minecraftindustrialcomplex.mic_climate.config.ClimateConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import sfiomn.legendarysurvivaloverhaul.api.temperature.ModifierBase;

/**
 * The pack's climate, expressed in LSO's units.
 *
 * <p>Registered as {@code legendarysurvivaloverhaul:mic_climate_world} (see
 * {@link LsoBridge} for why the namespace is LSO's). It replaces LSO's own
 * biome, season, time-of-day and weather terms, which the pack config switches
 * off, with the single number every other mod here reads.
 *
 * <p><b>The mapping.</b> LSO's world temperature is a unitless figure whose
 * body-temperature bands sit at 5 / 10 / 20 / 30 / 35 — frostbite, cold,
 * normal, hot, heat stroke — so a comfortable world is 20 &plusmn; 10. The
 * default {@code lso.neutralCelsius = 20} puts room temperature at the middle
 * of that band and {@code lso.unitsPerDegree = 0.24} reproduces the envelope
 * LSO's biome term had: its {@code "Biome Temperature Multiplier"} of 18 spans
 * 0&hellip;18 units across the same &minus;20&hellip;56&nbsp;&deg;C that Project
 * Atmosphere maps vanilla biomes onto, and 18/76 &asymp; 0.24. At the defaults
 * &minus;20&nbsp;&deg;C gives &minus;9.6 and 56&nbsp;&deg;C gives +8.6.
 *
 * <p>{@code getPlayerInfluence} stays at zero: this modifier is about the
 * world, and everything about the player's body — clothing, wetness, huddling,
 * sprinting — is still LSO's own business.
 *
 * <p>Note that LSO calls {@code getWorldInfluence} with a {@code null} player
 * from {@code TemperatureUtil.getWorldTemperature(Level, BlockPos)}, so nothing
 * here may touch the player argument.
 */
public class ClimateWorldModifier extends ModifierBase {

    @Override
    public float getPlayerInfluence(Player player) {
        return 0f;
    }

    @Override
    public float getWorldInfluence(Player player, Level level, BlockPos pos) {
        if (!ClimateConfig.lsoEnabled())
            return 0f;

        float celsius = Climate.celsius(level, pos);
        return (celsius - ClimateConfig.lsoNeutralCelsius()) * ClimateConfig.lsoUnitsPerDegree();
    }
}
