package com.minecraftindustrialcomplex.mic_climate.lso;

import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.patryk3211.powergrid.electricity.base.ThermalBehaviour;

/**
 * "How hot is this block entity?", asked of Power Grid.
 *
 * <p>A class of its own for one reason: it is the only thing in the LSO bridge
 * that names {@code org.patryk3211.*}, so on a pack with LSO but without Power
 * Grid it is simply never resolved. {@link DeviceHeatModifier} mentions it only
 * after checking {@code Compat.isLoaded("powergrid")}.
 *
 * <p>{@code ThermalBehaviour} is Power Grid's per-block-entity thermal model —
 * the same field its overheating logic and its thermometer read — and
 * mic-destroy-electric attaches one to Destroy's Vats, so this covers both.
 */
final class PowerGridThermalReader {

    private PowerGridThermalReader() {}

    /**
     * @return the block entity's temperature in degrees Celsius, or
     *         {@code null} if it has no thermal behaviour (which is most block
     *         entities in the world)
     */
    static Float temperature(BlockEntity blockEntity) {
        if (blockEntity == null)
            return null;

        ThermalBehaviour behaviour = BlockEntityBehaviour.get(blockEntity, ThermalBehaviour.TYPE);
        if (behaviour == null)
            return null;

        float temperature = behaviour.getTemperature();
        return Float.isFinite(temperature) ? temperature : null;
    }
}
