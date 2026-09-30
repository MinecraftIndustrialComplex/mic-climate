package com.minecraftindustrialcomplex.mic_climate.atmosphere;

/**
 * Marker added to each Project Atmosphere class the Deep Time base hook mixes into.
 *
 * <p>Every mixin in {@code mixin.projectatmosphere} declares {@code implements} this, so
 * {@code ProjectAtmosphereHooked.class.isAssignableFrom(target)} answers "did that mixin actually
 * apply?" at runtime: for the probe, and for the GameTest that must fail when Project Atmosphere's
 * known version stops binding rather than pass by testing nothing. It has no methods.
 */
public interface ProjectAtmosphereHooked {}
