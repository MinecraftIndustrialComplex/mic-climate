package com.minecraftindustrialcomplex.mic_climate.mixin;

import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Every bridge in this mod is optional, and the way that is enforced is here:
 * a mixin in the sub-package {@code mixin.<modid>} is applied only when that
 * mod is actually installed.
 *
 * <p>So {@code mixin.powergrid.*} needs {@code powergrid},
 * {@code mixin.destroy.*} needs {@code destroy}, and a future
 * {@code mixin.crowns.*} will need {@code crowns} with no change here. Mixins
 * sitting directly in {@code mixin} always apply.
 *
 * <p>This class deliberately touches nothing else of the mod's own: it runs
 * during mixin bootstrap, before the mod list is built and before it is safe to
 * pull mod classes through the transforming class loader, so the check goes
 * through FML's loading mod list and the log goes to a logger of its own.
 */
public class MicClimateMixinPlugin implements IMixinConfigPlugin {

    private static final String MIXIN_PACKAGE = "com.minecraftindustrialcomplex.mic_climate.mixin.";

    private static final Logger LOGGER = LoggerFactory.getLogger("mic_climate");

    @Override
    public void onLoad(String mixinPackage) {}

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        String modid = requiredMod(mixinClassName);
        if (modid == null)
            return true;

        LoadingModList mods = LoadingModList.get();
        if (mods == null || mods.getModFileById(modid) != null)
            return true;

        LOGGER.info("Skipping mixin {}: {} is not installed", mixinClassName, modid);
        return false;
    }

    /**
     * @return the mod id named by the mixin's sub-package, or {@code null} if
     *         the mixin is not in one
     */
    private static String requiredMod(String mixinClassName) {
        if (!mixinClassName.startsWith(MIXIN_PACKAGE))
            return null;

        String rest = mixinClassName.substring(MIXIN_PACKAGE.length());
        int dot = rest.indexOf('.');
        return dot <= 0 ? null : rest.substring(0, dot);
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
