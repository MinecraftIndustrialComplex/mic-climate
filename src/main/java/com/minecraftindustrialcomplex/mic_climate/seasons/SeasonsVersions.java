package com.minecraftindustrialcomplex.mic_climate.seasons;

/**
 * Which Serene Seasons and Serene Seasons Plus builds the hemisphere-season mixins
 * ({@code mixin.seasons}) were checked against, and the switches that turn them off before any
 * bytecode is touched.
 *
 * <p>Compile-time constants only: {@link SeasonsMixinPlugin} reads them while mixins are being
 * prepared, before it is safe to load this mod's classes, and {@code javac} inlines a
 * {@code static final String} literal, so the plugin never loads this class.
 */
public final class SeasonsVersions {

    /**
     * Serene Seasons versions whose internals the mixins were read against (a Maven range). 10.1.0.3
     * is the pack's pin (CurseForge file 6182596), read from its bytecode on 2026-09-30; the
     * research compared it with the 1.21.1 branch through 10.1.0.9 and found every target unchanged.
     * The plugin still checks each target method and call is there before applying anything.
     */
    public static final String SERENE_SEASONS_RANGE = "[10.1.0.3,10.1.1)";

    /** Serene Seasons Plus versions its one mixin was read against: 5.1.2 is the pack's pin. */
    public static final String SERENE_SEASONS_PLUS_RANGE = "[5.1.2,5.2)";

    /** {@code -Dmic_climate.hemisphereSeasons=false}: never apply the mixins at all. */
    public static final String DISABLE_PROPERTY = "mic_climate.hemisphereSeasons";

    /**
     * {@code -Dmic_climate.hemisphereSeasons.anyVersion=true}: apply the mixins to versions outside
     * the ranges above. The per-method checks still run.
     */
    public static final String ANY_VERSION_PROPERTY = "mic_climate.hemisphereSeasons.anyVersion";

    private SeasonsVersions() {}
}
