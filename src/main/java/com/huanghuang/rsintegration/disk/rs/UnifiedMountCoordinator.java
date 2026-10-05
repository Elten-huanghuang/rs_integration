package com.huanghuang.rsintegration.disk.rs;

import com.refinedmods.refinedstorage.api.network.node.INetworkNodeProxy;
import com.refinedmods.refinedstorage.apiimpl.network.node.NetworkNode;
import com.refinedmods.refinedstorage.apiimpl.network.node.diskdrive.DiskDriveNetworkNode;
import com.refinedmods.refinedstorage.apiimpl.network.node.diskmanipulator.DiskManipulatorNetworkNode;
import com.refinedmods.refinedstorage.api.storage.cache.InvalidateCause;
import com.refinedmods.refinedstorage.api.storage.disk.IStorageDisk;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** 整个服务器唯一挂载；验证物理槽位与节点实例，旧 wrapper 自动失效。 */
public final class UnifiedMountCoordinator {
    private static final ThreadLocal<NetworkNode> CALLER = new ThreadLocal<>();
    public static <T> T withCaller(NetworkNode node, Supplier<T> action) {
        NetworkNode old = CALLER.get(); CALLER.set(node);
        try { return action.get(); }
        finally { if (old == null) CALLER.remove(); else CALLER.set(old); }
    }
    public static NetworkNode caller() { return CALLER.get(); }

    private static LevelChunk loadedChunk(NetworkNode node) {
        if (!(node.getLevel() instanceof ServerLevel level)) return null;
        return level.getChunkSource().getChunkNow(node.getPos().getX() >> 4, node.getPos().getZ() >> 4);
    }

    private static BlockEntity loadedBlockEntity(NetworkNode node) {
        LevelChunk chunk = loadedChunk(node);
        // 节点 read() 可能发生在区块恢复中，不能同步请求区块或触发方块实体的 NBT 恢复。
        return chunk == null ? null : chunk.getBlockEntities().get(node.getPos());
    }

    public static final class Lease {
        public final UnifiedDiskRoot root;
        public final NetworkNode node;
        public final int slot;
        private final IStorageDisk<?>[] items, fluids;
        private final ItemStack physical;
        private boolean active = true;
        Lease(UnifiedDiskRoot root, NetworkNode node, int slot, IStorageDisk<?>[] items,
              IStorageDisk<?>[] fluids, ItemStack physical) {
            this.root = root; this.node = node; this.slot = slot;
            this.items = items; this.fluids = fluids; this.physical = physical;
        }
        public boolean valid() {
            if (!active || !root.manager().enabled()) return false;
            ItemStack current = node instanceof DiskDriveNetworkNode drive ? drive.getDisks().getStackInSlot(slot)
                    : node instanceof DiskManipulatorNetworkNode manipulator ? manipulator.getDisks().getStackInSlot(slot) : ItemStack.EMPTY;
            if (!sameDisk(current)) return false;
            BlockEntity block = loadedBlockEntity(node);
            boolean same = block instanceof INetworkNodeProxy<?> proxy && proxy.getNode() == node;
            // 节点 read() 会先恢复槽位再进入方块实体；尚未附着期间不可读写。
            return same;
        }
        boolean stale() {
            if (!active) return true;
            ItemStack current = node instanceof DiskDriveNetworkNode drive ? drive.getDisks().getStackInSlot(slot)
                    : node instanceof DiskManipulatorNetworkNode manipulator ? manipulator.getDisks().getStackInSlot(slot) : ItemStack.EMPTY;
            if (!sameDisk(current)) return true;
            LevelChunk chunk = loadedChunk(node);
            if (chunk == null) return true;
            BlockEntity block = chunk.getBlockEntities().get(node.getPos());
            if (block == null) {
                // 节点 read() 可能早于方块实体注册；区块仍在加载时保留租约，valid() 会暂时拒绝读写。
                return false;
            }
            return !(block instanceof INetworkNodeProxy<?> proxy) || proxy.getNode() != node;
        }
        /** 区块/服务器重载会重建 ItemStack 对象，必须按持久化身份比较，不能比较对象引用。 */
        private boolean sameDisk(ItemStack current) {
            if (!(current.getItem() instanceof UnifiedDiskItem item) || current.getCount() != 1) return false;
            return root.id().equals(item.getId(current)) && root.worldId().equals(item.worldId(current));
        }
        void revoke() { active = false; items[slot] = null; fluids[slot] = null; }
        public String location() { return node.getLevel().dimension().location() + " " + node.getPos() + " 槽=" + slot; }
    }
    private record Candidate(UnifiedDiskRoot root, NetworkNode node, int slot, Runnable retry) {}
    private static final class Deferred {
        final NetworkNode node;
        final int slot;
        final Runnable retry;
        long nextAttempt;
        Deferred(NetworkNode node, int slot, Runnable retry, long nextAttempt) {
            this.node = node; this.slot = slot; this.retry = retry; this.nextAttempt = nextAttempt;
        }
    }
    private final Map<UUID, Lease> leases = new HashMap<>();
    private final List<Candidate> waiting = new ArrayList<>();
    private final List<Deferred> deferred = new ArrayList<>();
    private long ticks;

    /** RS 只调用一次 createStorages 时，延迟等待节点恢复后再触发一次。 */
    public void defer(NetworkNode node, int slot, Runnable retry) {
        if (node == null) return;
        deferred.removeIf(candidate -> candidate.node == node && candidate.slot == slot);
        deferred.add(new Deferred(node, slot, retry, ticks + 20));
    }

    public Lease acquire(UnifiedDiskRoot root, NetworkNode node, int slot, IStorageDisk<?>[] items,
                         IStorageDisk<?>[] fluids, ItemStack physical, Runnable retry) {
        if (node == null) return null;
        releaseSlot(node, slot);
        Lease existing = leases.get(root.id());
        if (existing != null && existing.stale()) { existing.revoke(); leases.remove(root.id()); existing = null; }
        if (existing != null) {
            waiting.removeIf(candidate -> candidate.root.id().equals(root.id())
                    && candidate.node == node && candidate.slot == slot);
            waiting.add(new Candidate(root, node, slot, retry));
            return null;
        }
        Lease lease = new Lease(root, node, slot, items, fluids, physical);
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
    public String location(UUID id) { Lease lease = leases.get(id); return lease == null ? "无" : lease.location(); }
    public void clear() { leases.values().forEach(Lease::revoke); leases.clear(); waiting.clear(); deferred.clear(); }

    public void sweep() {
        ticks++;
        Map<NetworkNode, Boolean> changed = new IdentityHashMap<>();
        leases.values().removeIf(lease -> {
            if (!lease.stale()) return false;
            lease.revoke(); changed.put(lease.node, true); return true;
        });
        for (Candidate candidate : List.copyOf(waiting)) {
            LevelChunk chunk = loadedChunk(candidate.node);
            if (chunk == null) {
                waiting.remove(candidate); continue;
            }
            BlockEntity block = chunk.getBlockEntities().get(candidate.node.getPos());
            if (block != null && (!(block instanceof INetworkNodeProxy<?> proxy) || proxy.getNode() != candidate.node)) {
                waiting.remove(candidate); continue;
            }
            if (block == null) continue;
            if (!leases.containsKey(candidate.root.id())) {
                waiting.remove(candidate);
                candidate.retry.run();
                changed.put(candidate.node, true);
            }
        }
        for (Deferred candidate : List.copyOf(deferred)) {
            LevelChunk chunk = loadedChunk(candidate.node);
            if (chunk == null) {
                deferred.remove(candidate);
                continue;
            }
            BlockEntity block = chunk.getBlockEntities().get(candidate.node.getPos());
            if (block != null && (!(block instanceof INetworkNodeProxy<?> proxy) || proxy.getNode() != candidate.node)) {
                deferred.remove(candidate);
                continue;
            }
            if (block == null || ticks < candidate.nextAttempt) continue;
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
