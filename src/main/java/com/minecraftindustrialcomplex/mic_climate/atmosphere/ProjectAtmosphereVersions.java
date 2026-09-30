package com.minecraftindustrialcomplex.mic_climate.atmosphere;

/**
 * Which Project Atmosphere builds the Deep Time base hook ({@code mixin.projectatmosphere}) was
 * checked against, and the switches that turn it off before any bytecode is touched.
 *
 * <p>Compile-time constants only: {@link ProjectAtmosphereMixinPlugin} reads them while
 * mixins are being prepared, before it is safe to load this mod's classes, and {@code javac}
 * inlines a {@code static final String} literal, so the plugin never loads this class.
 */
public final class ProjectAtmosphereVersions {

    /**
     * The Project Atmosphere versions whose internals the hook was read against (a Maven range).
     * 0.9.1.2 is the pack's pin (CurseForge file 8541023), decompiled on 2026-09-30; later 0.9.1.x
     * patch builds are accepted on the assumption that a patch release keeps those internals, and
     * the plugin still checks every target method is there before applying anything.
     */
    public static final String KNOWN_RANGE = "[0.9.1.2,0.9.2)";

    /** {@code -Dmic_climate.projectAtmosphereBase=false}: never apply the hook's mixins at all. */
    public static final String DISABLE_PROPERTY = "mic_climate.projectAtmosphereBase";

    /**
     * {@code -Dmic_climate.projectAtmosphereBase.anyVersion=true}: apply the hook to a Project
     * Atmosphere outside {@link #KNOWN_RANGE}. The per-method checks still run.
     */
    public static final String ANY_VERSION_PROPERTY = "mic_climate.projectAtmosphereBase.anyVersion";

    private ProjectAtmosphereVersions() {}
}
