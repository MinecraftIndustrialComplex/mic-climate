package com.minecraftindustrialcomplex.mic_climate.provider;

import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import sereneseasons.api.season.ISeasonState;
import sereneseasons.api.season.SeasonHelper;

/**
 * The only class in this mod that imports Serene Seasons, and only its public API package. Reached
 * from {@link YearClock} behind a {@code Compat.isLoaded("sereneseasons")} guard.
 */
final class SereneSeasonsClock {

    private SereneSeasonsClock() {}

    /** Serene Seasons' position in its cycle as Deep Time's year fraction, or null. */
    @Nullable
    static Double yearFraction(Level level) {
        ISeasonState state = SeasonHelper.getSeasonState(level);
        if (state == null)
            return null;
        double f = YearClock.fromSeasonCycle(state.getSeasonCycleTicks(), state.getCycleDuration());
        return Double.isNaN(f) ? null : f;
    }
}
