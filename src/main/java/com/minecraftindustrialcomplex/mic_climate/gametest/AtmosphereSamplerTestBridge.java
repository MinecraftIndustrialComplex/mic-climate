package com.minecraftindustrialcomplex.mic_climate.gametest;

import com.minecraftindustrialcomplex.mic_climate.atmosphere.ProjectAtmosphereHooked;
import com.minecraftindustrialcomplex.mic_climate.atmosphere.ProjectAtmosphereRandomStates;
import net.Gabou.projectatmosphere.ProjectAtmosphere;
import net.Gabou.projectatmosphere.api.AtmoApi;
import net.Gabou.projectatmosphere.async.BiomeSampler;
import net.Gabou.projectatmosphere.manager.ForecastGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Project Atmosphere's side of {@link AtmosphereSamplerGameTests}: its biome sampler built the way
 * its forecast builds it, its private {@code sampleSquare} run as the forecast runs it, and the
 * {@code RandomState} it would have built on its own, for comparison. Names {@code net.Gabou.*},
 * so the holder only calls in here.
 */
final class AtmosphereSamplerTestBridge {

    private AtmosphereSamplerTestBridge() {}

    /** Whether {@code mixin.projectatmosphere.BiomeSamplerMixin} reached Project Atmosphere's BiomeSampler. */
    static boolean bound() {
        return ProjectAtmosphereHooked.class.isAssignableFrom(BiomeSampler.class);
    }

    static long seed() {
        return ProjectAtmosphere.seed;
    }

    /** What two samplers built in a row, as {@code sampleSquare} builds them, did to the cache. */
    record Sharing(long builtByFirst, long builtBySecond, long reusedBySecond, boolean sameState) {}

    static Sharing twoSamplers(ServerLevel level) throws ReflectiveOperationException {
        BiomeSource source = level.getChunkSource().getGenerator().getBiomeSource();
        long b0 = ProjectAtmosphereRandomStates.built();
        Climate.Sampler first = climateOf(new BiomeSampler(ProjectAtmosphere.seed, level.registryAccess(), source));
        long b1 = ProjectAtmosphereRandomStates.built();
        long r1 = ProjectAtmosphereRandomStates.reused();
        Climate.Sampler second = climateOf(new BiomeSampler(ProjectAtmosphere.seed, level.registryAccess(), source));
        long b2 = ProjectAtmosphereRandomStates.built();
        long r2 = ProjectAtmosphereRandomStates.reused();
        return new Sharing(b1 - b0, b2 - b1, r2 - r1, first == second);
    }

    /**
     * What the cached state samples against the state Project Atmosphere builds on its own
     * ({@code BiomeSampler.java:27-35}, called here outside the sampler, so not through the cache),
     * over a grid: the six climate parameters and, through the overworld's multi-noise biome source
     * (the one a normal world's forecast samples), the biome. A state with the next seed is compared
     * too, to show the grid tells states apart.
     */
    record Equivalence(int points, int climateDiffers, int biomeDiffers, String firstDiff, int otherSeedDiffers,
                       Set<String> biomes, long builtByFresh, boolean freshIsAnotherState) {}

    static Equivalence compareWithOwnState(ServerLevel level) throws ReflectiveOperationException {
        RegistryAccess access = level.registryAccess();
        long seed = ProjectAtmosphere.seed;
        Holder<MultiNoiseBiomeSourceParameterList> preset = access
                .registryOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                .getHolderOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD);
        MultiNoiseBiomeSource overworld = MultiNoiseBiomeSource.createFromPreset(preset);

        BiomeSampler cached = new BiomeSampler(seed, access, overworld);
        Climate.Sampler cachedClimate = climateOf(cached);

        long b0 = ProjectAtmosphereRandomStates.built();
        NoiseGeneratorSettings settings = access.registryOrThrow(Registries.NOISE_SETTINGS)
                .getHolderOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
        Climate.Sampler own = RandomState.create(settings, access.lookupOrThrow(Registries.NOISE), seed).sampler();
        Climate.Sampler otherSeed = RandomState.create(settings, access.lookupOrThrow(Registries.NOISE), seed + 1).sampler();
        long builtByFresh = ProjectAtmosphereRandomStates.built() - b0;

        int points = 0;
        int climateDiffers = 0;
        int biomeDiffers = 0;
        int otherSeedDiffers = 0;
        String firstDiff = "";
        Set<String> biomes = new TreeSet<>();
        for (int x = -24000; x <= 24000; x += 2000) {
            for (int z = -24000; z <= 24000; z += 2000) {
                for (int y : new int[] {-40, 63, 140}) {
                    points++;
                    int qx = x >> 2;
                    int qy = y >> 2;
                    int qz = z >> 2;
                    Climate.TargetPoint a = cachedClimate.sample(qx, qy, qz);
                    Climate.TargetPoint b = own.sample(qx, qy, qz);
                    if (!a.equals(b)) {
                        climateDiffers++;
                        if (firstDiff.isEmpty())
                            firstDiff = "climate at " + x + "," + y + "," + z + ": " + a + " vs " + b;
                    }
                    if (!b.equals(otherSeed.sample(qx, qy, qz)))
                        otherSeedDiffers++;
                    ResourceLocation viaPa = cached.getBiomeId(x, y, z);
                    Holder<Biome> ownBiome = overworld.getNoiseBiome(qx, qy, qz, own);
                    String expected = ownBiome.unwrapKey().map(ResourceKey::location).map(ResourceLocation::toString)
                            .orElse("minecraft:plains");
                    biomes.add(expected);
                    if (!viaPa.toString().equals(expected)) {
                        biomeDiffers++;
                        if (firstDiff.isEmpty())
                            firstDiff = "biome at " + x + "," + y + "," + z + ": " + viaPa + " vs " + expected;
                    }
                }
            }
        }
        return new Equivalence(points, climateDiffers, biomeDiffers, firstDiff, otherSeedDiffers, biomes,
                builtByFresh, own != cachedClimate);
    }

    /** One run of Project Atmosphere's private {@code sampleSquare}, and what it did to the cache. */
    record Square(float seaLevel, int samples, long built, long reused) {}

    /**
     * Runs {@code ForecastGenerator.sampleSquare} at {@code x, z}, one block above its sea-level cut
     * (below the cut it returns without building a sampler).
     */
    static Square sampleSquare(ServerLevel level, int x, int z, int radius) throws ReflectiveOperationException {
        Field sea = ForecastGenerator.class.getDeclaredField("SEA_LEVEL");
        sea.setAccessible(true);
        float seaLevel = sea.getFloat(null);
        Method square = ForecastGenerator.class.getDeclaredMethod("sampleSquare", BlockPos.class, ServerLevel.class, int.class);
        square.setAccessible(true);
        long b0 = ProjectAtmosphereRandomStates.built();
        long r0 = ProjectAtmosphereRandomStates.reused();
        List<?> samples = (List<?>) square.invoke(null, new BlockPos(x, (int) Math.ceil(seaLevel) + 1, z), level, radius);
        return new Square(seaLevel, samples.size(), ProjectAtmosphereRandomStates.built() - b0,
                ProjectAtmosphereRandomStates.reused() - r0);
    }

    /** A temperature query through Project Atmosphere's API at {@code pos}, and what it did to the cache. */
    record Query(float temperatureC, long built, long reused) {}

    static Query temperature(ServerLevel level, BlockPos pos) {
        long b0 = ProjectAtmosphereRandomStates.built();
        long r0 = ProjectAtmosphereRandomStates.reused();
        float t = AtmoApi.getInstance().getCurrentWeather(level, pos).temperatureC();
        return new Query(t, ProjectAtmosphereRandomStates.built() - b0, ProjectAtmosphereRandomStates.reused() - r0);
    }

    private static Climate.Sampler climateOf(BiomeSampler sampler) throws ReflectiveOperationException {
        Field field = BiomeSampler.class.getDeclaredField("sampler");
        field.setAccessible(true);
        return (Climate.Sampler) field.get(sampler);
    }
}
