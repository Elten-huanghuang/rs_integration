package com.huanghuang.rsintegration.mods.arsnouveau;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import javax.annotation.Nullable;

/**
 * Source (magical energy) availability probe for Ars Nouveau machines.
 *
 * <p>Both Imbuement Chamber and Enchanting Apparatus consume Source during
 * crafting. The machines inherit {@code getSource()} and {@code getMaxSource()}
 * from {@code AbstractSourceMachine}, and can pull more Source from nearby
 * providers via {@code SourceUtil.takeSourceNearby}.</p>
 *
 * <p><strong>Source is NOT a material ingredient</strong> — it is a per-tile
 * integer resource. Source scarcity is a throughput limiter (soft throttle),
 * not a binary gate. The Imbuement Chamber will continue crafting even with
 * zero nearby Source (falling back to +10 per tick), just more slowly.</p>
 *
 * <p>This probe reads the machine's current Source level for informational
 * purposes (warnings, preview hints), but does NOT attempt to reserve or
 * atomically decrement Source — Ars itself handles Source consumption during
 * the craft tick.</p>
 */
public final class ArsSourceProbe {

    private ArsSourceProbe() {}

    /**
     * Snapshot of a machine's Source state at a given moment.
     */
    public static final class SourceSnapshot {
        public final int current;
        public final int max;

        public SourceSnapshot(int current, int max) {
            this.current = current;
            this.max = max;
        }

        public boolean isAvailable() {
            return current >= 0 && max >= 0;
        }

        public boolean isEmpty() {
            return current <= 0;
        }

        public boolean isFull() {
            return current >= max;
        }

        public int remaining() {
            return Math.max(0, max - current);
        }

        @Override
        public String toString() {
            return current + " / " + max + " Source";
        }
    }

    /**
     * Reads the Source state of the machine at the given position.
     *
     * @param level the world
     * @param pos the machine position
     * @return a snapshot, or {@code null} if the position has no Source machine
     */
    @Nullable
    public static SourceSnapshot probe(Level level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) return null;

        int current = ArsTileAccess.getSource(be);
        int max = ArsTileAccess.getMaxSource(be);

        if (current < 0 || max < 0) {
            // Not a Source machine, or reflection failed
            return null;
        }

        return new SourceSnapshot(current, max);
    }

    /**
     * Checks if the machine has at least {@code required} Source available
     * right now. This is a best-effort check; Source is not reserved atomically,
     * and another operation may consume it before this one starts.
     *
     * @param level the world
     * @param pos the machine position
     * @param required the Source cost
     * @return true if current Source >= required, false otherwise
     */
    public static boolean hasEnoughSource(Level level, BlockPos pos, int required) {
        SourceSnapshot snap = probe(level, pos);
        return snap != null && snap.current >= required;
    }
}
