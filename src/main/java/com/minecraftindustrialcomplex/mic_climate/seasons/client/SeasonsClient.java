package com.minecraftindustrialcomplex.mic_climate.seasons.client;

import com.minecraftindustrialcomplex.mic_climate.MicClimate;
import com.minecraftindustrialcomplex.mic_climate.config.ClimateConfig;
import com.minecraftindustrialcomplex.mic_climate.seasons.LatitudeSeasons;
import com.minecraftindustrialcomplex.mic_climate.seasons.PlanetLatitude;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import sereneseasons.api.season.ISeasonColorProvider;
import sereneseasons.api.season.ISeasonState;
import sereneseasons.api.season.SeasonHelper;
import sereneseasons.season.SeasonColorHandlers;
import sereneseasons.util.SeasonColorUtil;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The hemisphere seasons on the client: grass and foliage colours by latitude, and the re-mesh
 * that shows them.
 *
 * <p><b>Colours.</b> Serene Seasons replaces the grass and foliage colour resolvers with its own,
 * which colour every block by the level's season, and then hands the result to any overrides other
 * mods registered through {@code SeasonColorHandlers.registerResolverOverride} (added "for other
 * mods" in 2024). That override is used here, with no mixin: on a Deep Time planet it recolours
 * the block with Serene Seasons' own {@code applySeasonal*Colouring} for the hemisphere's
 * sub-season, then blends toward the biome's own colour (Mid Summer's) by the temperate strength.
 * Serene Seasons' tropical biomes use its tropical calendar instead inside the wet/dry band (blended
 * by the tropical strength; the wet season is the hemisphere's summer half) and the temperate seasons
 * beyond it, cross-fading over 20 to 25 degrees ({@link LatitudeSeasons#tropicalBiomeColour}).
 * Off a planet, north of the full-season latitude, or on any error it returns Serene Seasons'
 * colour untouched.
 *
 * <p><b>Re-mesh.</b> Serene Seasons re-meshes the world when the level's sub-season changes; the
 * local sub-seasons change at exactly the same moments (the southern shift is a whole number of
 * sub-seasons, and the strength does not change with time), so that covers the calendar. What it
 * does not cover is the latitude becoming known: Deep Time's planet info can arrive after the
 * first chunks were meshed, and the config switch or full-season latitude can change in a running
 * game. {@link #remeshWhenThePlanetChanges} re-meshes once when any of those changes for the level
 * on screen.
 *
 * <p><b>Distant Horizons</b> asks the resolvers for its LODs with no position ({@code x = z = 0}),
 * which would read as the equator; those calls get Serene Seasons' own colour, so LODs look as they
 * did before (the level's season) rather than seasonless.
 *
 * <p>Registered only on a client with Serene Seasons and Deep Time installed.
 */
public final class SeasonsClient {

    private static final AtomicBoolean LOGGED_FAILURE = new AtomicBoolean();

    /** What the colours were last meshed with; client thread only. */
    private record Planet(ClientLevel level, int circumference, String projection, boolean on, double fullLatitude) {}

    private static Planet meshedWith;

    private SeasonsClient() {}

    public static void init(IEventBus modBus) {
        modBus.addListener(FMLClientSetupEvent.class, e -> e.enqueueWork(SeasonsClient::registerOverrides));
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, e -> remeshWhenThePlanetChanges());
    }

    private static void registerOverrides() {
        SeasonColorHandlers.registerResolverOverride(SeasonColorHandlers.ResolverType.GRASS,
                (original, seasonal, current, biome, x, z) -> colour(true, original, current, biome, x, z));
        SeasonColorHandlers.registerResolverOverride(SeasonColorHandlers.ResolverType.FOLIAGE,
                (original, seasonal, current, biome, x, z) -> colour(false, original, current, biome, x, z));
        MicClimate.LOGGER.info("Hemisphere seasons: grass and foliage colours follow latitude on Deep Time planets");
    }

    /** One grass or foliage colour, as Serene Seasons' override chain hands it over. */
    static int colour(boolean grass, int original, int current, Holder<Biome> biome, double x, double z) {
        if (x == 0.0 && z == 0.0)
            return current; // Distant Horizons: no position
        try {
            ClientLevel level = Minecraft.getInstance().level;
            if (level == null || biome == null)
                return current;
            double lat = PlanetLatitude.latitude(level, x, z);
            if (Double.isNaN(lat))
                return current;
            ISeasonState global = SeasonHelper.getSeasonState(level);
            double w = PlanetLatitude.strength(lat);
            if (!SeasonHelper.usesTropicalSeasons(biome)) {
                if (LatitudeSeasons.unchanged(lat, w))
                    return current;
                return LatitudeSeasons.lerpRgb(original, apply(grass, LatitudeSeasons.shifted(global.getSubSeason(), lat), biome, original), w);
            }
            // Serene Seasons' tropical biomes follow the tropical wet/dry cycle inside its band (its own
            // latitude band; the wet season is the hemisphere's summer half) and the temperate seasons
            // beyond it, cross-fading over 20 to 25 degrees.
            double wetDryStrength = LatitudeSeasons.tropicalStrength(lat);
            double temperateShare = LatitudeSeasons.temperateWeight(lat);
            int wetDry = temperateShare < 1.0 && wetDryStrength > 0.0
                    ? apply(grass, LatitudeSeasons.shifted(global.getTropicalSeason(), lat), biome, original) : original;
            int temperate = temperateShare > 0.0
                    ? apply(grass, LatitudeSeasons.shifted(global.getSubSeason(), lat), biome, original) : original;
            return LatitudeSeasons.tropicalBiomeColour(original, wetDry, temperate, wetDryStrength, w, temperateShare);
        } catch (Throwable t) {
            if (LOGGED_FAILURE.compareAndSet(false, true))
                MicClimate.LOGGER.warn("Hemisphere season colours failed; Serene Seasons' own colours are used", t);
            return current;
        }
    }

    /** Serene Seasons' own grass or foliage colouring of {@code original} for one season. */
    private static int apply(boolean grass, ISeasonColorProvider season, Holder<Biome> biome, int original) {
        return grass ? SeasonColorUtil.applySeasonalGrassColouring(season, biome, original)
                : SeasonColorUtil.applySeasonalFoliageColouring(season, biome, original);
    }

    /** Once a client tick: re-mesh when the level on screen became (or stopped being) a planet, or the switch moved. */
    private static void remeshWhenThePlanetChanges() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            meshedWith = null;
            return;
        }
        Planet now = new Planet(level, PlanetLatitude.circumference(level), PlanetLatitude.projection(level), PlanetLatitude.switchedOn(),
                ClimateConfig.fullSeasonLatitude());
        Planet before = meshedWith;
        meshedWith = now;
        if (before == null || before.level() != level || before.equals(now))
            return;
        if (now.circumference() > 0 || before.circumference() > 0) {
            MicClimate.LOGGER.info("Hemisphere seasons: re-meshing for planet circumference {} (was {}), projection '{}' (was '{}'), {}",
                    now.circumference(), before.circumference(), now.projection(), before.projection(), now.on() ? "on" : "off");
            mc.levelRenderer.allChanged();
        }
    }
}
