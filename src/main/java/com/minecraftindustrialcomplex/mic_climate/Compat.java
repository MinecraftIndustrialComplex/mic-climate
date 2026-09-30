package com.minecraftindustrialcomplex.mic_climate;

import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.LoadingModList;

/**
 * "Is that mod here?", asked at the two different times this mod needs to know.
 *
 * <p>Every class that touches an optional mod's packages is reached only
 * through one of these guards, so the JVM never has to load a class whose
 * imports are missing.
 */
public final class Compat {

    public static final String POWERGRID = "powergrid";
    public static final String DESTROY = "destroy";
    public static final String PROJECT_ATMOSPHERE = "projectatmosphere";
    public static final String THERMOO_PATCHES = "thermoo_patches";
    public static final String LEGENDARY_SURVIVAL_OVERHAUL = "legendarysurvivaloverhaul";
    public static final String CROWNS = "crowns";
    public static final String DEEP_TIME = "deeptime";
    public static final String SERENE_SEASONS = "sereneseasons";
    public static final String SERENE_SEASONS_PLUS = "sereneseasonsplus";

    private Compat() {}

    /**
     * Runtime check, for code running after mod loading has finished.
     *
     * @return {@code true} if the mod is loaded; {@code false} if it is not, or
     *         if the mod list does not exist yet (which can happen on early
     *         datagen and unit-test paths)
     */
    public static boolean isLoaded(String modid) {
        ModList list = ModList.get();
        return list != null && list.isLoaded(modid);
    }

    /**
     * Load-time check, for the mixin config plugin.
     *
     * <p>{@link ModList} is not built when mixins are being applied, so the
     * only thing that can answer at that point is FML's loading mod list.
     */
    public static boolean isLoadedEarly(String modid) {
        LoadingModList list = LoadingModList.get();
        return list != null && list.getModFileById(modid) != null;
    }
}
