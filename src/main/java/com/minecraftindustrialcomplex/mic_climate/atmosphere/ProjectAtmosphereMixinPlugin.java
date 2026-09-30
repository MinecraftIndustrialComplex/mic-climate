package com.minecraftindustrialcomplex.mic_climate.atmosphere;

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
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The gate in front of {@code mic_climate.projectatmosphere.mixins.json}: the optional hooks into
 * Project Atmosphere (Deep Time's climate as its base, Destroy's pollution inside its temperature,
 * and the per-player Deep Time client table with the rain or snow it decides).
 *
 * <p>Project Atmosphere's jar says "All Rights Reserved" and has no API for its base temperature,
 * so the hooks mix into its internals (Ben's calls, 2026-09-30: "Mixin anyway"; pollution "in all
 * worlds"). Internals change without notice, so each mixin is applied only when every one of these
 * holds, and otherwise is skipped with one log line and Project Atmosphere runs untouched:
 *
 * <ol>
 *   <li>Project Atmosphere is installed, at a version inside
 *       {@link ProjectAtmosphereVersions#KNOWN_RANGE} (or
 *       {@code -Dmic_climate.projectAtmosphereBase.anyVersion=true});</li>
 *   <li>a mod the mixin serves is installed: Deep Time (the base, the client table) or Destroy (the
 *       pollution part, which shares the temperature hooks), or this JVM is a GameTest run;</li>
 *   <li>the mod whose class it targets is installed (Serene Seasons for the rain-or-snow mixin);</li>
 *   <li>{@code -Dmic_climate.projectAtmosphereBase=false} is not set;</li>
 *   <li>every method the mixin injects into, and every call it wraps, is present in that mod's
 *       bytecode with the expected descriptor.</li>
 * </ol>
 *
 * <p>Behind this the config itself is {@code "required": false} with {@code defaultRequire: 0},
 * every mixin is {@code @Pseudo}, and the runtime side ({@code atmosphere.ProjectAtmosphereBase},
 * {@code atmosphere.ProjectAtmosphereClientCache}) falls back to the original value on any
 * exception. Which part is active in a given world is decided at runtime.
 *
 * <p>Like {@code mixin.MicClimateMixinPlugin}, this runs during mixin preparation and touches none
 * of the mod's other classes (the constants it reads are inlined by {@code javac}). It lives outside
 * every mixin package on purpose: a class inside one may not be loaded once that package's config is
 * registered, and the order in which the two configs are prepared is not guaranteed.
 */
public class ProjectAtmosphereMixinPlugin implements IMixinConfigPlugin {

    private static final Logger LOGGER = LoggerFactory.getLogger("mic_climate");

    private static final String PA_MOD = "projectatmosphere";
    private static final String DEEP_TIME_MOD = "deeptime";
    private static final String DESTROY_MOD = "destroy";
    private static final String SERENE_SEASONS_MOD = "sereneseasons";
    /** Mixins that serve Deep Time only, and the ones that serve Deep Time or Destroy. */
    private static final List<String> FOR_DEEP_TIME = List.of(DEEP_TIME_MOD);
    private static final List<String> FOR_DEEP_TIME_OR_DESTROY = List.of(DEEP_TIME_MOD, DESTROY_MOD);
    private static final String MIXIN_PACKAGE = "com.minecraftindustrialcomplex.mic_climate.mixin.projectatmosphere.";

    private static final String PA = "net/Gabou/projectatmosphere/";
    private static final String SERVER_LEVEL = "Lnet/minecraft/server/level/ServerLevel;";
    private static final String BLOCK_POS = "Lnet/minecraft/core/BlockPos;";
    private static final String REGION_KEY = "L" + PA + "util/RegionInstanceKey;";

    /** A method that must exist in the target: name and descriptor. */
    private record Method(String name, String desc) {}

    /** A call that must appear inside a target method. */
    private record Call(Method in, String owner, String name, String desc) {}

    /**
     * What one mixin needs: the mod whose jar holds its target, the mods it serves (any one of
     * them), and the methods and calls it hooks.
     */
    private record Needs(String jarMod, List<String> serves, List<Method> methods, List<Call> calls) {}

    private static final Method GET_TARGET = new Method("getTargetTemperature", "(J)F");
    private static final Method GET_EFFECTIVE_BASE = new Method("getEffectiveBaseTemperature", "()F");
    private static final Method GET_BASELINE_MIN = new Method("getBaselineMinTemperature", "()F");
    private static final Method GET_BASELINE_MAX = new Method("getBaselineMaxTemperature", "()F");
    private static final String DRIFT = PA + "modules/atmosphere/SeasonalAtmosphericDrift";
    private static final Method CROP_EVALUATE = new Method("evaluate", "(" + SERVER_LEVEL + BLOCK_POS + ")Ljava/util/EnumSet;");
    private static final Method SEND_TO_PLAYER = new Method("sendDailyForecastsToPlayer",
            "(Lnet/minecraft/server/level/ServerPlayer;Ljava/util/Map;)V");
    private static final Method COMPUTE_AVERAGES = new Method("computeAverageForecastsByBiomeType", "()V");
    private static final String PAYLOAD = "Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;";

    private static final Map<String, Needs> NEEDS = Map.of(
            "RegionAtmosphereStateMixin", new Needs(PA_MOD, FOR_DEEP_TIME_OR_DESTROY,
                    List.of(GET_TARGET, GET_EFFECTIVE_BASE, GET_BASELINE_MIN, GET_BASELINE_MAX,
                            new Method("getBaseTemperature", "()F"),
                            new Method("getTemperature", "()F"),
                            new Method("getRegionId", "()" + REGION_KEY)),
                    List.of(new Call(GET_TARGET, DRIFT, "currentTemperatureOffsetC", "()F"),
                            new Call(GET_EFFECTIVE_BASE, DRIFT, "currentTemperatureOffsetC", "()F"),
                            new Call(GET_BASELINE_MIN, DRIFT, "currentTemperatureOffsetC", "()F"),
                            new Call(GET_BASELINE_MAX, DRIFT, "currentTemperatureOffsetC", "()F"))),
            "AtmoApiMixin", new Needs(PA_MOD, FOR_DEEP_TIME_OR_DESTROY,
                    List.of(new Method("getWeatherSnapshot",
                            "(" + SERVER_LEVEL + BLOCK_POS + "J)L" + PA + "api/WeatherSnapshot;")),
                    List.of()),
            "ForecastOrchestratorMixin", new Needs(PA_MOD, FOR_DEEP_TIME_OR_DESTROY,
                    List.of(new Method("getCurrentTemperature", "(" + SERVER_LEVEL + BLOCK_POS + "J)F")),
                    List.of()),
            "LocalBiomeTemperatureResolverMixin", new Needs(PA_MOD, FOR_DEEP_TIME_OR_DESTROY,
                    List.of(new Method("getLocalBiomeTemperature",
                            "(" + SERVER_LEVEL + BLOCK_POS + REGION_KEY + "L" + PA + "modules/region/ForecastRegion;)D")),
                    List.of()),
            "CropStressManagerMixin", new Needs(PA_MOD, FOR_DEEP_TIME_OR_DESTROY,
                    List.of(CROP_EVALUATE),
                    List.of(new Call(CROP_EVALUATE, PA + "manager/ForecastOrchestrator", "getCurrentTemperature",
                            "(" + REGION_KEY + "J)F"))),
            "ForecastGeneratorMixin", new Needs(PA_MOD, FOR_DEEP_TIME,
                    List.of(SEND_TO_PLAYER, COMPUTE_AVERAGES),
                    List.of(new Call(COMPUTE_AVERAGES, "net/neoforged/neoforge/network/PacketDistributor", "sendToAllPlayers",
                            "(" + PAYLOAD + "[" + PAYLOAD + ")V"))),
            "SeasonHooksPrecipitationMixin", new Needs(SERENE_SEASONS_MOD, FOR_DEEP_TIME,
                    List.of(new Method("getPrecipitationAtSeasonal",
                            "(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/Holder;" + BLOCK_POS
                                    + ")Lnet/minecraft/world/level/biome/Biome$Precipitation;")),
                    List.of())
    );

    /** Why the whole config is off, or null when the per-mixin checks decide. Computed once. */
    private String disabledReason;
    private String paVersion = "?";

    @Override
    public void onLoad(String mixinPackage) {
        try {
            disabledReason = globalCheck();
        } catch (Throwable t) {
            disabledReason = "the pre-check itself failed (" + t + ")";
        }
        if (disabledReason == null) {
            LOGGER.info("Project Atmosphere {} is inside the range its hooks were checked against ({}); "
                    + "checking their targets", paVersion, ProjectAtmosphereVersions.KNOWN_RANGE);
        } else {
            LOGGER.info("Project Atmosphere hooks (Deep Time base, Destroy pollution) not applied: {}. Project "
                    + "Atmosphere keeps its own temperature.", disabledReason);
        }
    }

    private String globalCheck() throws Exception {
        if ("false".equalsIgnoreCase(System.getProperty(ProjectAtmosphereVersions.DISABLE_PROPERTY)))
            return "-D" + ProjectAtmosphereVersions.DISABLE_PROPERTY + "=false";
        LoadingModList mods = LoadingModList.get();
        if (mods == null)
            return "the mod list is not available";
        ModFileInfo pa = mods.getModFileById(PA_MOD);
        if (pa == null)
            return "Project Atmosphere is not installed";
        boolean gametest = Boolean.getBoolean("mic_climate.gametest");
        if (mods.getModFileById(DEEP_TIME_MOD) == null && mods.getModFileById(DESTROY_MOD) == null && !gametest)
            return "neither Deep Time nor Destroy is installed, so there is nothing to add to it";
        ArtifactVersion version = pa.getMods().stream()
                .filter(m -> PA_MOD.equals(m.getModId()))
                .findFirst()
                .map(m -> m.getVersion())
                .orElse(null);
        paVersion = String.valueOf(version);
        if (version == null)
            return "Project Atmosphere's version could not be read";
        boolean known = VersionRange.createFromVersionSpec(ProjectAtmosphereVersions.KNOWN_RANGE).containsVersion(version);
        if (!known) {
            if (Boolean.getBoolean(ProjectAtmosphereVersions.ANY_VERSION_PROPERTY)) {
                LOGGER.warn("Project Atmosphere {} is outside {}, applying the Deep Time base hook anyway "
                        + "(-D{}=true)", version, ProjectAtmosphereVersions.KNOWN_RANGE,
                        ProjectAtmosphereVersions.ANY_VERSION_PROPERTY);
                return null;
            }
            return "Project Atmosphere " + version + " is outside the checked range " + ProjectAtmosphereVersions.KNOWN_RANGE
                    + " (-D" + ProjectAtmosphereVersions.ANY_VERSION_PROPERTY + "=true to try it anyway)";
        }
        return null;
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
        LoadingModList mods = LoadingModList.get();
        boolean gametest = Boolean.getBoolean("mic_climate.gametest");
        if (!gametest && needs.serves().stream().noneMatch(mod -> mods.getModFileById(mod) != null)) {
            LOGGER.info("Skipping {}: none of {} is installed", simple, needs.serves());
            return false;
        }
        ModFileInfo jar = mods.getModFileById(needs.jarMod());
        if (jar == null) {
            LOGGER.info("Skipping {}: {} is not installed", simple, needs.jarMod());
            return false;
        }
        String missing;
        try {
            missing = missing(jar, targetClassName, needs);
        } catch (Throwable t) {
            missing = "could not read " + targetClassName + " (" + t + ")";
        }
        if (missing != null) {
            LOGGER.warn("Skipping {}: {}'s internals differ from the checked build ({}). That part keeps its own "
                    + "behaviour.", simple, needs.jarMod(), missing);
            return false;
        }
        LOGGER.info("Applying {} to {} (Project Atmosphere {})", simple, targetClassName, paVersion);
        return true;
    }

    /**
     * The first thing {@code needs} asks for that the target's bytecode lacks, or null.
     *
     * <p>The class is read straight out of the target mod's jar, untransformed: ModLauncher's
     * mixin service cannot hand out untransformed bytecode, and asking it for the transformed class
     * while mixins are still being prepared would load the target early.
     */
    private static String missing(ModFileInfo jar, String targetClassName, Needs needs) throws Exception {
        Path file = jar.getFile().findResource(targetClassName.replace('.', '/') + ".class");
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
