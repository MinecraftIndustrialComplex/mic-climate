package com.minecraftindustrialcomplex.mic_climate;

import com.minecraftindustrialcomplex.mic_climate.atmosphere.PollutionAtmosphereEffect;
import com.minecraftindustrialcomplex.mic_climate.atmosphere.ProjectAtmosphereBase;
import com.minecraftindustrialcomplex.mic_climate.command.ClimateCommands;
import com.minecraftindustrialcomplex.mic_climate.config.ClimateConfig;
import com.minecraftindustrialcomplex.mic_climate.crowns.CrownsBridge;
import com.minecraftindustrialcomplex.mic_climate.gametest.GameTests;
import com.minecraftindustrialcomplex.mic_climate.lso.LsoBridge;
import com.minecraftindustrialcomplex.mic_climate.provider.UnifiedEnvironmentProvider;
import com.minecraftindustrialcomplex.mic_climate.seasons.client.SeasonsClient;
import com.github.thedeathlycow.thermoo.api.ThermooRegistryKeys;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.registries.RegisterEvent;
import org.slf4j.Logger;

/**
 * One ambient temperature for the whole pack.
 *
 * <p>Every temperature-aware mod here has its own idea of "how warm is it
 * outside": Power Grid derives a Celsius figure from the biome's base
 * temperature, Destroy derives a Kelvin one from the biome plus its pollution
 * model, and neither notices Serene Seasons or Project Atmosphere at all. This
 * mod makes them all read a single number.
 *
 * <p>The shared currency is <a href="https://modrinth.com/mod/thermoo">Thermoo</a>'s
 * environment lookup — a neutral, unit-aware library with no opinion about
 * gameplay. This mod is only the connector: it registers one
 * {@code mic_climate:unified} environment provider that folds Project
 * Atmosphere (or a biome+season fallback) and Destroy's pollution into one
 * value, and it mixes into the other mods so they read that value back out
 * through {@link Climate}. No mod's physics change; only their ambient input.
 *
 * <p>Nothing here is a hard dependency except Thermoo itself. Each bridge is
 * gated twice: the mixins by {@code MicClimateMixinPlugin} at class-load time,
 * and the provider's optional sources by {@link Compat#isLoaded(String)} at
 * call time. This class deliberately imports nothing from an optional mod.
 */
@Mod(MicClimate.MODID)
public class MicClimate {
    public static final String MODID = "mic_climate";

    public static final Logger LOGGER = LogUtils.getLogger();

    public MicClimate(IEventBus modBus, ModContainer container) {
        ClimateConfig.register(container);
        modBus.addListener(RegisterEvent.class, MicClimate::onRegister);

        // Legendary Survival Overhaul sums its temperature modifiers straight
        // off its own DeferredRegister, and NeoForge refuses new entries once
        // RegisterEvent has fired -- so this has to happen here, in the
        // constructor, and not in a setup event. LsoBridge is the only class
        // that names LSO's packages; naming it from behind the guard is what
        // keeps this class free of them. ModList is built before mods are
        // constructed, so the guard can already answer.
        if (Compat.isLoaded(Compat.LEGENDARY_SURVIVAL_OVERHAUL))
            LsoBridge.init(modBus);

        // CROWNS' temperature field is seeded once per section and then never
        // recomputed, so the bridge runs a timer that hands slices of it back
        // for a rebuild. Same shape as the LSO guard: the only class naming
        // com.rae.* is the one behind it.
        if (Compat.isLoaded(Compat.CROWNS))
            CrownsBridge.init();

        // pollution.mode = ATMOSPHERE puts Destroy's greenhouse warming into
        // Project Atmosphere's own regional state, so it needs both mods to be
        // there at all. Registering is unconditional on the mode itself: the
        // handler reads the config every tick, so the setting can be changed in
        // a running game and the offset is taken back out again when it is.
        if (Compat.isLoaded(Compat.PROJECT_ATMOSPHERE) && Compat.isLoaded(Compat.DESTROY))
            PollutionAtmosphereEffect.init();

        // Deep Time's climate as Project Atmosphere's own base (mixin.projectatmosphere, applied by
        // ProjectAtmosphereMixinPlugin under the same two conditions). This publishes the season's
        // date once a tick for the hook, which Project Atmosphere also calls from worker threads.
        if (Compat.isLoaded(Compat.PROJECT_ATMOSPHERE)
                && (Compat.isLoaded(Compat.DEEP_TIME) || ClimateConfig.Test.ENABLED))
            ProjectAtmosphereBase.init();

        // Serene Seasons' seasons by latitude on Deep Time planets (mixin.seasons, applied by
        // SeasonsMixinPlugin under the same conditions). The client half is the colour override and
        // its re-mesh trigger; the server half needs no registration.
        if (FMLEnvironment.dist.isClient() && Compat.isLoaded(Compat.SERENE_SEASONS)
                && (Compat.isLoaded(Compat.DEEP_TIME) || ClimateConfig.Test.ENABLED))
            SeasonsClient.init(modBus);

        // /mic_climate probe|invalidate|pollution|mode -- the console's view of
        // what every bridged mod thinks the temperature is. Registered on the
        // game bus rather than the mod bus, and unconditionally: which lines it
        // can print is decided per source, at call time, by Compat.isLoaded.
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class, ClimateCommands::register);

        // Does nothing unless this JVM was started to run gametests, which is
        // the only situation in which the holder classes -- each of which
        // imports the mod it is about -- should be loaded at all.
        GameTests.init(modBus);
    }

    /**
     * Registers our environment provider type into Thermoo's
     * {@code thermoo:environment_provider_type} registry.
     *
     * <p>That registry is built by the Fabric API registry builder but is still
     * filled through NeoForge's {@link RegisterEvent}, which is how Thermoo
     * registers its own types ({@code impl/ThermooCommonRegisters}). Thermoo is
     * a required dependency ordered before us, so the registry object exists by
     * the time this fires.
     */
    private static void onRegister(RegisterEvent event) {
        event.register(
                ThermooRegistryKeys.ENVIRONMENT_PROVIDER_TYPE,
                asResource("unified"),
                () -> UnifiedEnvironmentProvider.TYPE
        );
    }

    public static ResourceLocation asResource(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }
}
