package com.minecraftindustrialcomplex.mic_climate.mixin.crowns;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.minecraftindustrialcomplex.mic_climate.Climate;
import com.minecraftindustrialcomplex.mic_climate.config.ClimateConfig;
import com.minecraftindustrialcomplex.mic_climate.crowns.CrownsBridge;
import com.rae.crowns.content.fields.util.PhysicsSaveManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * CROWNS' temperature field now starts from the pack's weather.
 *
 * <p>{@code getDefaultTemperature} decides what a single block's cell should
 * relax toward, and it does it in three steps: the biome table gives an
 * ambient, then the block or fluid table overrides it where it has an entry.
 * Only the first of those is a claim about the weather, and it is a constant
 * per biome — a desert reads 320&nbsp;K at midnight in midwinter. Replacing it
 * with {@link Climate#kelvin} puts every air cell CROWNS simulates on the same
 * number as Power Grid's devices, Destroy's vats and the player's own comfort.
 *
 * <p>The block and fluid tables are deliberately left as CROWNS wrote them:
 * they say lava is hot and ice is cold, which is true in every season.
 *
 * <p><b>Which call.</b> All three lookups are
 * {@code FloatMapDataLoader.getValue(Object, float)} with the same descriptor,
 * so the biome one is picked by {@code ordinal = 0} — it is first in the
 * method, and the block/fluid ones consume its result as their fallback.
 * {@code FloatMapDataLoader} lives in FormicAPI ({@code com.rae.formicapi}),
 * CROWNS' own library dependency, not in CROWNS.
 *
 * <p><b>Which level.</b> The method is static and gets no {@code Level}; the
 * one running is published by {@code PhysicsWorldDataMixin} through
 * {@link CrownsBridge#CURRENT_LEVEL}. When it is absent — a caller this mod
 * does not know about, or the client-side debug renderer — CROWNS' own answer
 * stands. {@link Climate} caches per chunk and never throws, which matters
 * here: a section's initialisation asks this question 4096 times in a row, on
 * CROWNS' physics thread, and answers all but the first from the cache.
 */
@Mixin(PhysicsSaveManager.class)
public abstract class PhysicsSaveManagerMixin {

    @ModifyExpressionValue(
            method = "getDefaultTemperature",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/rae/formicapi/content/data/managers/FloatMapDataLoader;"
                            + "getValue(Ljava/lang/Object;F)F",
                    ordinal = 0
            )
    )
    private static float micc$unifiedBiomeDefault(
            float original, LevelChunkSection section, Vec3i pos, BlockState state) {
        if (!ClimateConfig.crownsEnabled())
            return original;

        ServerLevel level = CrownsBridge.CURRENT_LEVEL.get();
        if (level == null)
            return original;

        // pos is absolute -- CROWNS' callers pass section origin + offset, and
        // mask it down to section coordinates themselves where they need to.
        return Climate.kelvin(level, new BlockPos(pos.getX(), pos.getY(), pos.getZ()));
    }
}
