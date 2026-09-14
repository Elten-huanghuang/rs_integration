package com.huanghuang.rsintegration.mods.arsnouveau;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Stable pedestal layout for Ars Nouveau machines (both Imbuement Chamber and
 * Enchanting Apparatus).
 *
 * <p>Both machines scan nearby {@code ArcanePedestalTile}s at a fixed radius
 * (Imbuement: 1, Apparatus: 3), and many recipes require specific items on
 * those pedestals. This layout captures the pedestal positions at validation
 * time and holds them for the duration of the batch, ensuring the same
 * pedestals are used for every craft and freed when the batch ends.</p>
 *
 * <p>The layout is immutable once built. All positions are absolute world
 * coordinates so they remain valid across tick boundaries.</p>
 */
public final class ArsPedestalLayout {

    private final BlockPos machinePos;
    private final List<BlockPos> pedestalPositions;

    private ArsPedestalLayout(BlockPos machinePos, List<BlockPos> pedestalPositions) {
        this.machinePos = machinePos;
        this.pedestalPositions = List.copyOf(pedestalPositions);
    }

    /**
     * Scans and captures the pedestal layout for the given machine. Returns
     * {@code null} if the machine is not an Ars Nouveau machine with pedestal
     * support, or if reflection failed.
     *
     * @param level the world
     * @param machinePos the machine's position
     * @return the layout, or {@code null} if unavailable
     */
    @Nullable
    public static ArsPedestalLayout capture(Level level, BlockPos machinePos) {
        ArsPedestalLayout layout = captureAllowEmpty(level, machinePos);
        return layout == null || layout.pedestalPositions.isEmpty() ? null : layout;
    }

    /**
     * Captures a layout while preserving a valid empty scan. Apparatus recipes
     * created by data packs may require only the central reagent and therefore
     * do not need an Arcane Pedestal.
     */
    @Nullable
    public static ArsPedestalLayout captureAllowEmpty(Level level, BlockPos machinePos) {
        BlockEntity be = level.getBlockEntity(machinePos);
        if (be == null) return null;

        List<BlockPos> positions = ArsTileAccess.pedestalPositions(be);
        return new ArsPedestalLayout(machinePos, positions);
    }

    /**
     * The machine position this layout is anchored to.
     */
    public BlockPos machinePos() {
        return machinePos;
    }

    /**
     * All pedestal positions, in the order Ars itself enumerates them.
     * Immutable.
     */
    public List<BlockPos> pedestalPositions() {
        return pedestalPositions;
    }

    /**
     * The number of pedestals in this layout.
     */
    public int pedestalCount() {
        return pedestalPositions.size();
    }

    /**
     * Checks if all pedestals are still present and accessible.
     *
     * @param level the world
     * @return true if every pedestal position still has a block entity
     */
    public boolean isValid(Level level) {
        for (BlockPos pos : pedestalPositions) {
            if (level.getBlockEntity(pos) == null) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ArsPedestalLayout)) return false;
        ArsPedestalLayout that = (ArsPedestalLayout) o;
        return machinePos.equals(that.machinePos) &&
                pedestalPositions.equals(that.pedestalPositions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(machinePos, pedestalPositions);
    }

    @Override
    public String toString() {
        return "ArsPedestalLayout{machine=" + machinePos +
                ", pedestals=" + pedestalCount() + "}";
    }
}
