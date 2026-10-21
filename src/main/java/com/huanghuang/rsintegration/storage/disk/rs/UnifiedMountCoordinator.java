package com.huanghuang.rsintegration.storage.disk.rs;

import com.refinedmods.refinedstorage.api.network.node.INetworkNodeProxy;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.apiimpl.network.node.NetworkNode;
import com.refinedmods.refinedstorage.apiimpl.network.node.diskdrive.DiskDriveNetworkNode;
import com.refinedmods.refinedstorage.apiimpl.network.node.diskmanipulator.DiskManipulatorNetworkNode;
import com.refinedmods.refinedstorage.api.storage.cache.InvalidateCause;
import com.refinedmods.refinedstorage.api.storage.disk.IStorageDisk;
import com.huanghuang.rsintegration.storage.disk.core.UnifiedDiskCore;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Comparator;
import java.util.function.Supplier;

/** 整个服务器唯一挂载；验证物理槽位与节点实例，旧 wrapper 自动失效。 */
public final class UnifiedMountCoordinator {
    private static final Logger LOGGER = LogManager.getLogger(UnifiedMountCoordinator.class);
    private static final ThreadLocal<NetworkNode> CALLER = new ThreadLocal<>();
    public static <T> T withCaller(NetworkNode node, Supplier<T> action) {
        NetworkNode old = CALLER.get(); CALLER.set(node);
        try { return action.get(); }
        finally { if (old == null) CALLER.remove(); else CALLER.set(old); }
    }
    public static NetworkNode caller() { return CALLER.get(); }

    private static ServerLevel level(NetworkNode node) {
        return node.getLevel() instanceof ServerLevel level ? level : null;
    }

    private static LevelChunk loadedChunk(ServerLevel level, NetworkNode node) {
        if (level == null) return null;
        return level.getChunkSource().getChunkNow(node.getPos().getX() >> 4, node.getPos().getZ() >> 4);
    }

    private static BlockEntity loadedBlockEntity(ServerLevel level, NetworkNode node) {
        LevelChunk chunk = loadedChunk(level, node);
        // 节点 read() 可能发生在区块恢复中，不能同步请求区块或触发方块实体的 NBT 恢复。
        return chunk == null ? null : chunk.getBlockEntities().get(node.getPos());
    }

    private static boolean attached(ServerLevel level, NetworkNode node) {
        BlockEntity block = loadedBlockEntity(level, node);
        return block instanceof INetworkNodeProxy<?> proxy && proxy.getNode() == node;
    }

    public static final class Lease {
        public final UnifiedDiskRoot root;
        public final NetworkNode node;
        public final int slot;
        private final ServerLevel level;
        private final IStorageDisk<?>[] items, fluids;
        private final ItemStack physical;
        private final Runnable retry;
        private boolean active = true;
        /** 区分尚未恢复的方块实体和已挂载后被移除的方块实体。 */
        private boolean observedBlockEntity;
        /** 启动时读到的空视图，需要在节点附着且连接网络后刷新一次。 */
        private boolean pendingCacheRefresh;
        Lease(UnifiedDiskRoot root, NetworkNode node, int slot, IStorageDisk<?>[] items,
              IStorageDisk<?>[] fluids, ItemStack physical, Runnable retry) {
            this(root, level(node), node, slot, items, fluids, physical, retry);
        }
        Lease(UnifiedDiskRoot root, ServerLevel level, NetworkNode node, int slot, IStorageDisk<?>[] items,
              IStorageDisk<?>[] fluids, ItemStack physical, Runnable retry) {
            this.root = root; this.node = node; this.slot = slot;
            this.level = level;
            this.items = items; this.fluids = fluids; this.physical = physical; this.retry = retry;
        }
        public boolean valid() {
            if (!active || !root.manager().enabled()) return false;
            if (!hasPhysicalDisk()) return false;
            BlockEntity block = loadedBlockEntity(level, node);
            boolean same = block instanceof INetworkNodeProxy<?> proxy && proxy.getNode() == node;
            // 节点 read() 会先恢复槽位再进入方块实体；尚未附着期间不可读写。
            if (same) observedBlockEntity = true;
            return same;
        }
        boolean stale() {
            if (!active) return true;
            if (!hasPhysicalDisk()) return true;
            LevelChunk chunk = loadedChunk(level, node);
            // 首次恢复期间区块尚未进入缓存，不代表磁盘仓已经卸载。
            if (chunk == null) return observedBlockEntity;
            BlockEntity block = chunk.getBlockEntities().get(node.getPos());
            if (block == null) {
                // 节点 read() 可能早于方块实体注册；区块仍在加载时保留租约，valid() 会暂时拒绝读写。
                return observedBlockEntity;
            }
            boolean same = block instanceof INetworkNodeProxy<?> proxy && proxy.getNode() == node;
            if (same) observedBlockEntity = true;
            return !same;
        }
        private boolean hasPhysicalDisk() {
            ItemStack current = node instanceof DiskDriveNetworkNode drive ? drive.getDisks().getStackInSlot(slot)
                    : node instanceof DiskManipulatorNetworkNode manipulator ? manipulator.getDisks().getStackInSlot(slot) : ItemStack.EMPTY;
            return sameDisk(current);
        }
        /** 区块/服务器重载会重建 ItemStack 对象，必须按持久化身份比较，不能比较对象引用。 */
        private boolean sameDisk(ItemStack current) {
            if (!(current.getItem() instanceof UnifiedDiskItem item) || current.getCount() != 1) return false;
            return root.id().equals(item.getId(current)) && root.worldId().equals(item.worldId(current));
        }
        void revoke() { active = false; items[slot] = null; fluids[slot] = null; }
        public String location() { return level.dimension().location() + " " + node.getPos() + " 槽=" + slot; }
    }
    private static final class Candidate {
        final UnifiedDiskRoot root;
        final ServerLevel level;
        final NetworkNode node;
        final int slot;
        final Runnable retry;
        boolean observedBlockEntity;
        Candidate(UnifiedDiskRoot root, ServerLevel level, NetworkNode node, int slot, Runnable retry) {
            this.root = root; this.level = level; this.node = node; this.slot = slot; this.retry = retry;
            observedBlockEntity = attached(level, node);
        }
    }
    private static final class Deferred {
        final ServerLevel level;
        final NetworkNode node;
        final int slot;
        final Runnable retry;
        long nextAttempt;
        boolean observedBlockEntity;
        Deferred(ServerLevel level, NetworkNode node, int slot, Runnable retry, long nextAttempt) {
            this.level = level; this.node = node; this.slot = slot; this.retry = retry; this.nextAttempt = nextAttempt;
            observedBlockEntity = attached(level, node);
        }
    }
    private final Map<UUID, Lease> leases = new HashMap<>();
    private final List<Candidate> waiting = new ArrayList<>();
    private final List<Deferred> deferred = new ArrayList<>();
    private long ticks;

    /** RS 只调用一次 createStorages 时，延迟等待节点恢复后再触发一次。 */
    public void defer(NetworkNode node, int slot, Runnable retry) {
        defer(level(node), node, slot, retry);
    }

    public void defer(ServerLevel level, NetworkNode node, int slot, Runnable retry) {
        if (node == null) return;
        deferred.removeIf(candidate -> candidate.node == node && candidate.slot == slot);
        deferred.add(new Deferred(level, node, slot, retry, ticks + 20));
    }

    public Lease acquire(UnifiedDiskRoot root, NetworkNode node, int slot, IStorageDisk<?>[] items,
                         IStorageDisk<?>[] fluids, ItemStack physical, Runnable retry) {
        return acquire(root, level(node), node, slot, items, fluids, physical, retry);
    }

    public Lease acquire(UnifiedDiskRoot root, ServerLevel level, NetworkNode node, int slot,
                         IStorageDisk<?>[] items, IStorageDisk<?>[] fluids, ItemStack physical, Runnable retry) {
        if (node == null) return null;
        releaseSlot(node, slot);
        Lease existing = leases.get(root.id());
        if (existing != null && existing.stale()) { retire(existing); leases.remove(root.id()); existing = null; }
        if (existing != null) {
            waiting.removeIf(candidate -> candidate.root.id().equals(root.id())
                    && candidate.node == node && candidate.slot == slot);
            waiting.add(new Candidate(root, level, node, slot, retry));
            return null;
        }
        Lease lease = new Lease(root, level, node, slot, items, fluids, physical, retry);
        lease.pendingCacheRefresh = !lease.valid();
        leases.put(root.id(), lease);
        return lease;
    }

    public void releaseSlot(NetworkNode node, int slot) {
        if (node == null) return;
        waiting.removeIf(candidate -> candidate.node == node && candidate.slot == slot);
        deferred.removeIf(candidate -> candidate.node == node && candidate.slot == slot);
        leases.values().removeIf(lease -> {
            if (lease.node != node || lease.slot != slot) return false;
            lease.revoke(); return true;
        });
    }

    public boolean mounted(UUID id) { return leases.containsKey(id); }

    /** 返回指定 RS 网络上的归墟盘；网络之间严格隔离。 */
    public List<UnifiedDiskCore> disksForNetwork(INetwork network) {
        if (network == null) return List.of();
        List<UnifiedDiskCore> result = new ArrayList<>();
        for (Lease lease : leases.values()) {
            if (!lease.active || lease.node.getNetwork() != network || !lease.valid()) continue;
            UnifiedDiskManager.Entry entry = lease.root.manager().entry(lease.root.id());
            if (entry.core != null) result.add(entry.core);
        }
        result.sort(Comparator.comparing(disk -> disk.diskId));
        return result;
    }
    public String location(UUID id) { Lease lease = leases.get(id); return lease == null ? "无" : lease.location(); }
    public void clear() { leases.values().forEach(Lease::revoke); leases.clear(); waiting.clear(); deferred.clear(); }

    private void retire(Lease lease) {
        lease.revoke();
        if (!lease.hasPhysicalDisk() || loadedChunk(lease.level, lease.node) != null) return;
        // RS 在区块重载后复用原节点，不会重新读取槽位；旧视图撤销后必须保留重挂载入口。
        LOGGER.info("[RSI] 统一盘 {} 所在区块卸载，等待区块恢复后重新挂载: dim={} pos={} slot={}",
                lease.root.id(), lease.level.dimension(), lease.node.getPos(), lease.slot);
        defer(lease.level, lease.node, lease.slot, () -> {
            // 等待期间可能发生插拔；只恢复原盘，不能用捕获的旧 ItemStack 覆盖新槽位。
            if (!lease.hasPhysicalDisk()) return;
            LOGGER.info("[RSI] 统一盘 {} 所在区块已恢复，尝试自动重建物品与流体视图: dim={} pos={} slot={}",
                    lease.root.id(), lease.level.dimension(), lease.node.getPos(), lease.slot);
            lease.retry.run();
        });
    }

    public void sweep() {
        ticks++;
        Map<NetworkNode, Boolean> changed = new IdentityHashMap<>();
        leases.values().removeIf(lease -> {
            if (!lease.stale()) {
                // valid() 的其他调用可能先观察到附着，因此缓存刷新要独立记账。
                if (lease.pendingCacheRefresh && lease.valid() && lease.node.getNetwork() != null) {
                    lease.pendingCacheRefresh = false;
                    changed.put(lease.node, true);
                }
                return false;
            }
            retire(lease); changed.put(lease.node, true); return true;
        });
        for (Candidate candidate : List.copyOf(waiting)) {
            LevelChunk chunk = loadedChunk(candidate.level, candidate.node);
            BlockEntity block = chunk == null ? null : chunk.getBlockEntities().get(candidate.node.getPos());
            if (block == null) {
                if (candidate.observedBlockEntity) waiting.remove(candidate);
                continue;
            }
            if (!(block instanceof INetworkNodeProxy<?> proxy) || proxy.getNode() != candidate.node) {
                waiting.remove(candidate); continue;
            }
            candidate.observedBlockEntity = true;
            if (!leases.containsKey(candidate.root.id())) {
                waiting.remove(candidate);
                candidate.retry.run();
                changed.put(candidate.node, true);
            }
        }
        for (Deferred candidate : List.copyOf(deferred)) {
            LevelChunk chunk = loadedChunk(candidate.level, candidate.node);
            BlockEntity block = chunk == null ? null : chunk.getBlockEntities().get(candidate.node.getPos());
            if (block == null) {
                if (candidate.observedBlockEntity) deferred.remove(candidate);
                continue;
            }
            if (!(block instanceof INetworkNodeProxy<?> proxy) || proxy.getNode() != candidate.node) {
                deferred.remove(candidate);
                continue;
            }
            candidate.observedBlockEntity = true;
            if (ticks < candidate.nextAttempt) continue;
            deferred.remove(candidate);
            candidate.retry.run();
            changed.put(candidate.node, true);
        }
        for (NetworkNode node : changed.keySet()) {
            if (node.getNetwork() != null) {
                node.getNetwork().getItemStorageCache().invalidate(InvalidateCause.DISK_INVENTORY_CHANGED);
                node.getNetwork().getFluidStorageCache().invalidate(InvalidateCause.DISK_INVENTORY_CHANGED);
            }
        }
    }
}
