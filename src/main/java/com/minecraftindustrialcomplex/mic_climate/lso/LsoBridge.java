package com.minecraftindustrialcomplex.mic_climate.lso;

import com.minecraftindustrialcomplex.mic_climate.Compat;
import com.minecraftindustrialcomplex.mic_climate.MicClimate;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import sfiomn.legendarysurvivaloverhaul.api.temperature.ModifierBase;
import sfiomn.legendarysurvivaloverhaul.registry.TemperatureModifierRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * The Legendary Survival Overhaul bridge, and the only place in this mod that
 * names an {@code sfiomn.*} class outside the {@code lso} package.
 *
 * <p>LSO's world temperature is the sum of {@code getWorldInfluence} over the
 * entries of <b>its own</b> {@code TemperatureModifierRegistry.MODIFIERS}
 * {@link DeferredRegister} — {@code TemperatureUtilInternal.getWorldTemperature}
 * iterates that field directly rather than the backing {@link net.minecraft.core.Registry},
 * so a second {@code DeferredRegister} created on the same registry key would be
 * built and then never read. There is therefore exactly one way in: put our
 * suppliers on LSO's instance.
 *
 * <p><b>Namespace.</b> That instance was created with LSO's own mod id, and
 * {@link DeferredRegister#register(String, java.util.function.Supplier)} pastes
 * that namespace onto the name we give. Our two entries are consequently called
 * {@code legendarysurvivaloverhaul:mic_climate_world} and
 * {@code legendarysurvivaloverhaul:mic_climate_device_heat}. That is cosmetic —
 * the registry is never keyed by namespace — but it is the id that shows up in
 * {@code /neoforge registries} and in the startup line logged below.
 *
 * <p><b>Timing.</b> {@code register(name, supplier)} only records an entry;
 * NeoForge commits the lot when {@code RegisterEvent} fires, and it throws
 * {@code IllegalStateException} if anything is added after that. Mod
 * constructors all run before {@code RegisterEvent}, so registering from
 * {@link MicClimate}'s constructor is in time; the {@code AFTER} ordering on
 * the {@code legendarysurvivaloverhaul} dependency in our {@code mods.toml} is
 * what guarantees LSO's class (and hence the field) is initialised first.
 *
 * <p>Loaded only from behind {@code Compat.isLoaded("legendarysurvivaloverhaul")},
 * so a pack without LSO never resolves any of these names.
 */
public final class LsoBridge {

    /** Registry path of {@link ClimateWorldModifier}, under LSO's namespace. */
    public static final String WORLD_MODIFIER = "mic_climate_world";

    /** Registry path of {@link DeviceHeatModifier}, under LSO's namespace. */
    public static final String DEVICE_HEAT_MODIFIER = "mic_climate_device_heat";

    private LsoBridge() {}

    /**
     * Adds our two modifiers to LSO's registry. Call once, from the mod
     * constructor, with LSO known to be present.
     */
    public static void init(IEventBus modBus) {
        TemperatureModifierRegistry.MODIFIERS.register(WORLD_MODIFIER, ClimateWorldModifier::new);
        TemperatureModifierRegistry.MODIFIERS.register(DEVICE_HEAT_MODIFIER, DeviceHeatModifier::new);
        modBus.addListener(FMLCommonSetupEvent.class, LsoBridge::onCommonSetup);
    }

    /**
     * Proof, once per launch, that the entries survived registration.
     *
     * <p>Registering onto another mod's {@code DeferredRegister} is exactly the
     * kind of thing that fails silently — a load-order change, or LSO freezing
     * its register earlier in some future version, and our modifiers simply
     * stop being summed with no error anywhere. One line at startup turns that
     * into something a log can answer.
     */
    private static void onCommonSetup(FMLCommonSetupEvent event) {
        List<ResourceLocation> ids = new ArrayList<>();
        for (DeferredHolder<ModifierBase, ? extends ModifierBase> holder
                : TemperatureModifierRegistry.MODIFIERS.getEntries()) {
            ids.add(holder.getId());
        }

        boolean ours = ids.contains(id(WORLD_MODIFIER)) && ids.contains(id(DEVICE_HEAT_MODIFIER));
        if (ours) {
            MicClimate.LOGGER.info(
                    "Registered {} and {} on Legendary Survival Overhaul's temperature modifier registry",
                    id(WORLD_MODIFIER), id(DEVICE_HEAT_MODIFIER)
            );
        } else {
            MicClimate.LOGGER.warn(
                    "mic_climate's LSO temperature modifiers are missing from "
                            + "TemperatureModifierRegistry.MODIFIERS; LSO will not see the pack's climate. "
                            + "Registered modifiers: {}",
                    ids
            );
        }
        MicClimate.LOGGER.debug("LSO temperature modifiers: {}", ids);

        if (!Compat.isLoaded(Compat.POWERGRID)) {
            MicClimate.LOGGER.debug(
                    "Power Grid is absent, so {} will always contribute 0", id(DEVICE_HEAT_MODIFIER));
        }
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(
                TemperatureModifierRegistry.MODIFIERS.getNamespace(), path);
    }
}
