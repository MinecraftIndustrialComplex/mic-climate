package com.minecraftindustrialcomplex.mic_climate.seasons;

import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.fml.loading.moddiscovery.ModFileInfo;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.VersionRange;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The gate in front of {@code mic_climate.seasons.mixins.json}: the hemisphere seasons' mixins into
 * Serene Seasons and Serene Seasons Plus.
 *
 * <p>Both mods are All Rights Reserved and neither has a per-position season API, so the mixins
 * reach into their internals (Ben's decisions, 2026-09-30). Internals change without notice, so
 * each mixin is applied only when every one of these holds, and otherwise is skipped with one log
 * line and that part of the mod keeps its own global season:
 *
 * <ol>
 *   <li>{@code -Dmic_climate.hemisphereSeasons=false} is not set;</li>
 *   <li>Deep Time is installed (the only source of a latitude), or this JVM is a GameTest run, which
 *       uses a stand-in planet;</li>
 *   <li>the mod the mixin targets is installed at a version inside its range in
 *       {@link SeasonsVersions} (or {@code -Dmic_climate.hemisphereSeasons.anyVersion=true});</li>
 *   <li>every method the mixin injects into, and every call it wraps, is in that mod's bytecode with
 *       the expected descriptor.</li>
 * </ol>
 *
 * <p>Behind this the config is {@code "required": false} with {@code defaultRequire: 0}, every mixin
 * is {@code @Pseudo} with {@code require = 0}, and the runtime side ({@link SereneSeasonsHemispheres})
 * hands back the mod's own answer off a Deep Time planet and on any exception.
 *
 * <p>Like the other plugins here it runs during mixin preparation and touches none of the mod's
 * other classes ({@link SeasonsVersions}' constants are inlined by {@code javac}), and it lives
 * outside every mixin package.
 */
public class SeasonsMixinPlugin implements IMixinConfigPlugin {

    private static final Logger LOGGER = LoggerFactory.getLogger("mic_climate");

    private static final String DEEP_TIME_MOD = "deeptime";
    private static final String SS_MOD = "sereneseasons";
    private static final String SSP_MOD = "sereneseasonsplus";
    private static final String MIXIN_PACKAGE = "com.minecraftindustrialcomplex.mic_climate.mixin.seasons.";

    private static final String LEVEL = "Lnet/minecraft/world/level/Level;";
    private static final String BLOCK_POS = "Lnet/minecraft/core/BlockPos;";
    private static final String HOLDER = "Lnet/minecraft/core/Holder;";
    private static final String SUB_SEASON = "Lsereneseasons/api/season/Season$SubSeason;";
    private static final String GET_SEASON_STATE_OWNER = "sereneseasons/api/season/SeasonHelper";
    private static final String GET_SEASON_STATE_DESC = "(" + LEVEL + ")Lsereneseasons/api/season/ISeasonState;";

    /** A method that must exist in the target: name and descriptor. */
    private record Method(String name, String desc) {}

    /** A call that must appear inside a target method. */
    private record Call(Method in, String owner, String name, String desc) {}

    /** What one mixin needs: the mod it targets, and from its target class. */
    private record Needs(String mod, List<Method> methods, List<Call> calls) {}

    private static final Method GET_BIOME_TEMPERATURE = new Method("getBiomeTemperature",
            "(" + LEVEL + HOLDER + BLOCK_POS + ")F");
    private static final String SEASON_HOOKS = "sereneseasons/season/SeasonHooks";
    private static final String HOLDER_OWNER = "net/minecraft/core/Holder";
    private static final String TAG_TEST_DESC = "(Lnet/minecraft/tags/TagKey;)Z";
    private static final Method IN_SEASON = new Method("getBiomeTemperatureInSeason",
            "(" + SUB_SEASON + HOLDER + BLOCK_POS + ")F");
    private static final Method HAS_PRECIPITATION = new Method("hasPrecipitationSeasonal", "(" + LEVEL + HOLDER + ")Z");
    private static final Method PRECIPITATION_AT = new Method("getPrecipitationAtSeasonal",
            "(" + LEVEL + HOLDER + BLOCK_POS + ")Lnet/minecraft/world/level/biome/Biome$Precipitation;");
    private static final Method IS_CROP_FERTILE = new Method("isCropFertile",
            "(Ljava/lang/String;" + LEVEL + BLOCK_POS + ")Z");
    private static final Method ON_WORLD_TICK = new Method("onWorldTick", "(Lglitchcore/event/TickEvent$Level;)V");
    private static final Method UPDATE_POWER = new Method("updatePower", "(" + LEVEL + BLOCK_POS + ")V");
    private static final Method BIRCH_COLOUR = new Method("lambda$registerBlockColors$0",
            "(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/BlockAndTintGetter;" + BLOCK_POS + "I)I");
    private static final String PROPERTIES = "sereneseasons/config/SeasonsConfig$SeasonProperties";

    private static final Map<String, Needs> NEEDS = Map.of(
            "sereneseasons.SeasonHooksMixin", new Needs(SS_MOD,
                    List.of(GET_BIOME_TEMPERATURE, IN_SEASON, HAS_PRECIPITATION, PRECIPITATION_AT),
                    List.of(new Call(GET_BIOME_TEMPERATURE, SEASON_HOOKS, "getBiomeTemperatureInSeason", IN_SEASON.desc()),
                            new Call(IN_SEASON, HOLDER_OWNER, "is", TAG_TEST_DESC),
                            new Call(PRECIPITATION_AT, SEASON_HOOKS, "hasPrecipitationSeasonal", HAS_PRECIPITATION.desc()),
                            new Call(HAS_PRECIPITATION, HOLDER_OWNER, "is", TAG_TEST_DESC),
                            new Call(HAS_PRECIPITATION, GET_SEASON_STATE_OWNER, "getSeasonState", GET_SEASON_STATE_DESC))),
            "sereneseasons.ModFertilityMixin", new Needs(SS_MOD,
                    List.of(IS_CROP_FERTILE),
                    List.of(new Call(IS_CROP_FERTILE, GET_SEASON_STATE_OWNER, "getSeasonState", GET_SEASON_STATE_DESC),
                            new Call(IS_CROP_FERTILE, "net/minecraft/core/Holder", "is", "(Lnet/minecraft/tags/TagKey;)Z"))),
            "sereneseasons.RandomUpdateHandlerMixin", new Needs(SS_MOD,
                    List.of(ON_WORLD_TICK),
                    List.of(new Call(ON_WORLD_TICK, PROPERTIES, "meltChance", "()F"),
                            new Call(ON_WORLD_TICK, PROPERTIES, "meltRolls", "()I"),
                            new Call(ON_WORLD_TICK, "sereneseasons/season/RandomUpdateHandler", "meltInChunk",
                                    "(Lnet/minecraft/server/level/ChunkMap;Lnet/minecraft/world/level/chunk/LevelChunk;F)V"))),
            "sereneseasons.SeasonSensorBlockMixin", new Needs(SS_MOD,
                    List.of(UPDATE_POWER),
                    List.of(new Call(UPDATE_POWER, GET_SEASON_STATE_OWNER, "getSeasonState", GET_SEASON_STATE_DESC))),
            "sereneseasons.ModClientMixin", new Needs(SS_MOD,
                    List.of(BIRCH_COLOUR),
                    List.of(new Call(BIRCH_COLOUR, GET_SEASON_STATE_OWNER, "getSeasonState", GET_SEASON_STATE_DESC),
                            new Call(BIRCH_COLOUR, "net/minecraft/world/level/FoliageColor", "getBirchColor", "()I"))),
            "sereneseasonsplus.SnowAccumulationPolicyMixin", new Needs(SSP_MOD,
                    List.of(new Method("evaluateChunk", "(Lnet/minecraft/server/level/ServerLevel;" + SUB_SEASON
                            + "Lcom/Gabou/sereneseasonsplus/access/ISnowTrackedChunk;Lnet/minecraft/world/level/ChunkPos;ZIZ)"
                            + "Lcom/Gabou/sereneseasonsplus/features/logic/SnowAccumulationPolicy$ChunkDecision;")),
                    List.of())
    );

    /** Why the whole config is off, or null when the per-mixin checks decide. Computed once. */
    private String disabledReason;
    /** Per mod: its file, or why its mixins are off. */
    private final Map<String, ModFileInfo> files = new HashMap<>();
    private final Map<String, String> modOff = new HashMap<>();
    private final Map<String, String> versions = new HashMap<>();

    @Override
    public void onLoad(String mixinPackage) {
        try {
            disabledReason = globalCheck();
            if (disabledReason == null) {
                checkMod(SS_MOD, "Serene Seasons", SeasonsVersions.SERENE_SEASONS_RANGE);
                checkMod(SSP_MOD, "Serene Seasons Plus", SeasonsVersions.SERENE_SEASONS_PLUS_RANGE);
            }
        } catch (Throwable t) {
            disabledReason = "the pre-check itself failed (" + t + ")";
        }
        if (disabledReason != null) {
            LOGGER.info("Hemisphere seasons not applied: {}. Serene Seasons keeps one season for the whole world.",
                    disabledReason);
        }
    }

    private String globalCheck() {
        if ("false".equalsIgnoreCase(System.getProperty(SeasonsVersions.DISABLE_PROPERTY)))
            return "-D" + SeasonsVersions.DISABLE_PROPERTY + "=false";
        LoadingModList mods = LoadingModList.get();
        if (mods == null)
            return "the mod list is not available";
        if (mods.getModFileById(DEEP_TIME_MOD) == null && !Boolean.getBoolean("mic_climate.gametest"))
            return "Deep Time is not installed, and without it there is no latitude";
        if (mods.getModFileById(SS_MOD) == null)
            return "Serene Seasons is not installed";
        return null;
    }

    private void checkMod(String modId, String name, String range) throws Exception {
        ModFileInfo file = LoadingModList.get().getModFileById(modId);
        if (file == null) {
            modOff.put(modId, name + " is not installed");
            return;
        }
        ArtifactVersion version = file.getMods().stream()
                .filter(m -> modId.equals(m.getModId()))
                .findFirst()
                .map(m -> m.getVersion())
                .orElse(null);
        versions.put(modId, String.valueOf(version));
        if (version == null) {
            modOff.put(modId, name + "'s version could not be read");
        } else if (!VersionRange.createFromVersionSpec(range).containsVersion(version)) {
            if (Boolean.getBoolean(SeasonsVersions.ANY_VERSION_PROPERTY)) {
                LOGGER.warn("{} {} is outside {}, applying the hemisphere seasons anyway (-D{}=true)",
                        name, version, range, SeasonsVersions.ANY_VERSION_PROPERTY);
            } else {
                modOff.put(modId, name + " " + version + " is outside the checked range " + range
                        + " (-D" + SeasonsVersions.ANY_VERSION_PROPERTY + "=true to try it anyway)");
            }
        }
        if (!modOff.containsKey(modId)) {
            files.put(modId, file);
            LOGGER.info("{} {} is inside the range the hemisphere seasons were checked against ({}); checking its targets",
                    name, version, range);
        } else {
            LOGGER.info("Hemisphere seasons: {}; that mod keeps one season for the whole world.", modOff.get(modId));
        }
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (disabledReason != null)
            return false;
        String simple = mixinClassName.startsWith(MIXIN_PACKAGE) ? mixinClassName.substring(MIXIN_PACKAGE.length()) : mixinClassName;
        Needs needs = NEEDS.get(simple);
        if (needs == null) {
            LOGGER.warn("Skipping {}: the plugin has no target checks for it", mixinClassName);
            return false;
        }
        ModFileInfo file = files.get(needs.mod());
        if (file == null)
            return false; // logged once in onLoad
        String missing;
        try {
            missing = missing(file, targetClassName, needs);
        } catch (Throwable t) {
            missing = "could not read " + targetClassName + " (" + t + ")";
        }
        if (missing != null) {
            LOGGER.warn("Skipping {}: {} {}'s internals differ from the checked build ({}). That part keeps its own "
                    + "global season.", simple, needs.mod(), versions.get(needs.mod()), missing);
            return false;
        }
        LOGGER.info("Applying {} to {} ({} {})", simple, targetClassName, needs.mod(), versions.get(needs.mod()));
        return true;
    }

    /**
     * The first thing {@code needs} asks for that the target's bytecode lacks, or null. The class is
     * read straight out of the mod's jar, untransformed (see {@code ProjectAtmosphereMixinPlugin}).
     */
    private static String missing(ModFileInfo mod, String targetClassName, Needs needs) throws Exception {
        Path file = mod.getFile().findResource(targetClassName.replace('.', '/') + ".class");
        if (file == null || !Files.exists(file))
            return "no class " + targetClassName;
        ClassNode node = new ClassNode();
        new ClassReader(Files.readAllBytes(file)).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        for (Method m : needs.methods()) {
            if (find(node, m) == null)
                return "no method " + m.name() + m.desc();
        }
        for (Call c : needs.calls()) {
            MethodNode in = find(node, c.in());
            if (in == null || !calls(in, c))
                return "no call to " + c.owner().substring(c.owner().lastIndexOf('/') + 1) + "." + c.name()
                        + " in " + c.in().name();
        }
        return null;
    }

    private static MethodNode find(ClassNode node, Method m) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(m.name()) && method.desc.equals(m.desc()))
                return method;
        }
        return null;
    }

    private static boolean calls(MethodNode method, Call c) {
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(c.owner()) && call.name.equals(c.name())
                    && call.desc.equals(c.desc()))
                return true;
        }
        return false;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
