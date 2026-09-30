package com.minecraftindustrialcomplex.mic_climate.seasons;

import sereneseasons.api.season.ISeasonState;
import sereneseasons.api.season.Season;

/**
 * Serene Seasons' season state as seen at one place on a Deep Time planet: the level's own state,
 * shifted half a year south of the equator and, for the discrete decisions, pulled toward Mid
 * Summer by the season's strength there ({@link LatitudeSeasons}).
 *
 * <p>Only a view: durations come from the level's state, and the tick count inside the current
 * sub-season is the level's, so a caller that measures progress through a sub-season or season
 * (the season sensor, Project Atmosphere's progress) sees the same progress as everyone else,
 * just in the local sub-season. The tropical season is shifted but never faded: where the
 * seasons fade, the callers that care (Project Atmosphere's moisture stage) drop it instead.
 */
public final class LocalSeasonState implements ISeasonState {

    private final ISeasonState global;
    private final int subSeason;
    private final int tropicalSeason;

    /**
     * @param global      the level's state
     * @param latitudeDeg the place's latitude, north positive
     * @param strength    the season's strength there; ignored unless {@code discrete}
     * @param discrete    pull the sub-season toward Mid Summer ({@link LatitudeSeasons#damp})
     */
    public LocalSeasonState(ISeasonState global, double latitudeDeg, double strength, boolean discrete) {
        this.global = global;
        boolean south = LatitudeSeasons.south(latitudeDeg);
        int shifted = LatitudeSeasons.shift(global.getSubSeason().ordinal(), south);
        this.subSeason = discrete ? LatitudeSeasons.damp(shifted, strength) : shifted;
        this.tropicalSeason = LatitudeSeasons.shiftTropical(global.getTropicalSeason().ordinal(), south);
    }

    @Override
    public int getDayDuration() {
        return global.getDayDuration();
    }

    @Override
    public int getSubSeasonDuration() {
        return global.getSubSeasonDuration();
    }

    @Override
    public int getSeasonDuration() {
        return global.getSeasonDuration();
    }

    @Override
    public int getCycleDuration() {
        return global.getCycleDuration();
    }

    @Override
    public int getSeasonCycleTicks() {
        int duration = Math.max(1, global.getSubSeasonDuration());
        return subSeason * duration + Math.floorMod(global.getSeasonCycleTicks(), duration);
    }

    @Override
    public int getDay() {
        return getSeasonCycleTicks() / Math.max(1, global.getDayDuration());
    }

    @Override
    public Season.SubSeason getSubSeason() {
        return Season.SubSeason.VALUES[subSeason];
    }

    @Override
    public Season getSeason() {
        return getSubSeason().getSeason();
    }

    @Override
    public Season.TropicalSeason getTropicalSeason() {
        return Season.TropicalSeason.VALUES[tropicalSeason];
    }

    @Override
    public String toString() {
        return "LocalSeasonState[" + getSubSeason() + ", " + getTropicalSeason() + "]";
    }
}
