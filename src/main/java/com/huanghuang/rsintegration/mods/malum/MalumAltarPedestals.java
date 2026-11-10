package com.huanghuang.rsintegration.mods.malum;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import javax.annotation.Nullable;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Predicate;

/** 仅服务端线程使用；批量任务独占自己投料的物品台，直到整个预载批次结束。 */
public final class MalumAltarPedestals {
    private static final Map<Level, Reservations> WORLDS = new WeakHashMap<>();

    private MalumAltarPedestals() {}

    private static final class Reservations {
        final Map<BlockPos, Lease> altars = new HashMap<>();
        final Map<BlockPos, Lease> pedestals = new HashMap<>();
    }

    public static final class Lease implements AutoCloseable {
        private final WeakReference<Level> level;
        private final WeakReference<BlockEntity> altar;
        private final BlockPos altarPos;
        private final Map<BlockPos, WeakReference<BlockEntity>> pedestals;
        private boolean closed;

        private Lease(Level level, BlockEntity altar, List<BlockEntity> pedestals) {
            this.level = new WeakReference<>(level);
            this.altar = new WeakReference<>(altar);
            this.altarPos = altar.getBlockPos().immutable();
            this.pedestals = new LinkedHashMap<>();
            for (BlockEntity pedestal : pedestals) {
                this.pedestals.put(pedestal.getBlockPos().immutable(), new WeakReference<>(pedestal));
            }
        }

        private boolean active(Level level) {
            return !closed && present(level, altarPos, altar.get());
        }

        public List<Object> captured() {
            Level currentLevel = level.get();
            if (currentLevel == null || !active(currentLevel)) return List.of();
            List<Object> result = new ArrayList<>(pedestals.size());
            pedestals.forEach((pos, reference) -> {
                BlockEntity pedestal = reference.get();
                if (present(currentLevel, pos, pedestal)) result.add(pedestal);
            });
            return result;
        }

        @Override
        public void close() {
            closed = true;
            Level currentLevel = level.get();
            Reservations reservations = WORLDS.get(currentLevel);
            if (reservations == null) return;
            reservations.altars.remove(altarPos, this);
            for (BlockPos pos : pedestals.keySet()) reservations.pedestals.remove(pos, this);
            if (reservations.altars.isEmpty()) WORLDS.remove(currentLevel);
        }
    }

    @Nullable
    public static Lease tryReserve(Level level, BlockEntity altar, List<?> candidates,
                                   int required, Predicate<Object> empty) {
        if (level.isClientSide || required < 0 || !present(level, altar.getBlockPos(), altar)) return null;
        Reservations reservations = WORLDS.get(level);
        if (reservations != null && live(reservations.altars.get(altar.getBlockPos()), level) != null) {
            return null;
        }
        List<BlockEntity> selected = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        for (Object candidate : candidates) {
            if (selected.size() == required) break;
            if (!(candidate instanceof BlockEntity pedestal)
                    || !seen.add(pedestal.getBlockPos())
                    || !present(level, pedestal.getBlockPos(), pedestal)
                    || !available(level, pedestal) || !empty.test(pedestal)) continue;
            selected.add(pedestal);
        }
        // 空间不足时不留下部分占用，避免四台祭坛各自占一部分后互相等待。
        if (selected.size() != required) return null;
        Lease lease = new Lease(level, altar, selected);
        reservations = WORLDS.computeIfAbsent(level, ignored -> new Reservations());
        reservations.altars.put(lease.altarPos, lease);
        for (BlockPos pos : lease.pedestals.keySet()) reservations.pedestals.put(pos, lease);
        return lease;
    }

    /** 有任务时直接读取已选物品台，不再每次遍历整个祭坛周边。 */
    @Nullable
    public static List<?> captureReserved(Level level, BlockPos altarPos) {
        if (level.isClientSide) return null;
        Reservations reservations = WORLDS.get(level);
        Lease lease = reservations == null ? null : live(reservations.altars.get(altarPos), level);
        return lease == null ? null : lease.captured();
    }

    public static boolean available(Level level, Object pedestal) {
        if (level == null || level.isClientSide) return true;
        if (!(pedestal instanceof BlockEntity blockEntity)) return true;
        Reservations reservations = WORLDS.get(level);
        return reservations == null
                || live(reservations.pedestals.get(blockEntity.getBlockPos()), level) == null;
    }

    /** 未托管的祭坛也不能取走相邻批量任务的材料。 */
    public static List<?> filterCaptured(Level level, BlockPos altarPos, List<?> captured) {
        if (level.isClientSide) return captured;
        Reservations reservations = WORLDS.get(level);
        if (reservations == null) return captured;
        List<Object> result = new ArrayList<>(captured.size());
        for (Object pedestal : captured) {
            Lease lease = pedestal instanceof BlockEntity blockEntity
                    ? live(reservations.pedestals.get(blockEntity.getBlockPos()), level) : null;
            if (lease == null || lease.altarPos.equals(altarPos)) result.add(pedestal);
        }
        return result;
    }

    @Nullable
    private static Lease live(@Nullable Lease lease, Level level) {
        if (lease == null || lease.active(level)) return lease;
        lease.close();
        return null;
    }

    private static boolean present(Level level, BlockPos pos, @Nullable BlockEntity blockEntity) {
        return blockEntity != null && !blockEntity.isRemoved() && level.isLoaded(pos)
                && level.getBlockEntity(pos) == blockEntity;
    }
}
