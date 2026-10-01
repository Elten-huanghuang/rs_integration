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
    public static final class Lease {
        public final UnifiedDiskRoot root;
        public final NetworkNode node;
        public final int slot;
        private final IStorageDisk<?>[] items, fluids;
        private final ItemStack physical;
        private boolean active = true;
        private boolean attached;
        Lease(UnifiedDiskRoot root, NetworkNode node, int slot, IStorageDisk<?>[] items,
              IStorageDisk<?>[] fluids, ItemStack physical) {
            this.root = root; this.node = node; this.slot = slot;
            this.items = items; this.fluids = fluids; this.physical = physical;
        }
        public boolean valid() {
            if (!active || !root.manager().enabled() || !(node.getLevel() instanceof ServerLevel level)
                    || !level.hasChunkAt(node.getPos())) return false;
            if (!(physical.getItem() instanceof UnifiedDiskItem item) || physical.getCount() != 1
                    || !root.id().equals(item.getId(physical)) || !root.worldId().equals(item.worldId(physical))) return false;
            ItemStack current = node instanceof DiskDriveNetworkNode drive ? drive.getDisks().getStackInSlot(slot)
                    : node instanceof DiskManipulatorNetworkNode manipulator ? manipulator.getDisks().getStackInSlot(slot) : ItemStack.EMPTY;
            if (current != physical) return false;
            BlockEntity block = level.getBlockEntity(node.getPos());
            boolean same = block instanceof INetworkNodeProxy<?> proxy && proxy.getNode() == node;
            if (same) attached = true;
            // 节点 read() 会先恢复槽位再进入方块实体；尚未附着期间不可读写。
            return same;
        }
        boolean stale() {
            if (!active || !(node.getLevel() instanceof ServerLevel level) || !level.hasChunkAt(node.getPos())) return true;
            ItemStack current = node instanceof DiskDriveNetworkNode drive ? drive.getDisks().getStackInSlot(slot)
                    : node instanceof DiskManipulatorNetworkNode manipulator ? manipulator.getDisks().getStackInSlot(slot) : ItemStack.EMPTY;
            if (current != physical) return true;
            BlockEntity block = level.getBlockEntity(node.getPos());
            return !(block instanceof INetworkNodeProxy<?> proxy) || proxy.getNode() != node;
        }
        void revoke() { active = false; items[slot] = null; fluids[slot] = null; }
        public String location() { return node.getLevel().dimension().location() + " " + node.getPos() + " 槽=" + slot; }
    }
    private record Candidate(UnifiedDiskRoot root, NetworkNode node, int slot, Runnable retry) {}
    private final Map<UUID, Lease> leases = new HashMap<>();
    private final List<Candidate> waiting = new ArrayList<>();

    public Lease acquire(UnifiedDiskRoot root, NetworkNode node, int slot, IStorageDisk<?>[] items,
                         IStorageDisk<?>[] fluids, ItemStack physical, Runnable retry) {
        if (node == null) return null;
        releaseSlot(node, slot);
        Lease existing = leases.get(root.id());
        if (existing != null && existing.stale()) { existing.revoke(); leases.remove(root.id()); existing = null; }
        if (existing != null) { waiting.add(new Candidate(root, node, slot, retry)); return null; }
        Lease lease = new Lease(root, node, slot, items, fluids, physical);
        leases.put(root.id(), lease);
        return lease;
    }

    public void releaseSlot(NetworkNode node, int slot) {
        if (node == null) return;
        waiting.removeIf(candidate -> candidate.node == node && candidate.slot == slot);
        leases.values().removeIf(lease -> {
            if (lease.node != node || lease.slot != slot) return false;
            lease.revoke(); return true;
        });
    }

    public boolean mounted(UUID id) { return leases.containsKey(id); }
    public String location(UUID id) { Lease lease = leases.get(id); return lease == null ? "无" : lease.location(); }
    public void clear() { leases.values().forEach(Lease::revoke); leases.clear(); waiting.clear(); }

    public void sweep() {
        Map<NetworkNode, Boolean> changed = new IdentityHashMap<>();
        leases.values().removeIf(lease -> {
            if (!lease.stale()) return false;
            lease.revoke(); changed.put(lease.node, true); return true;
        });
        for (Candidate candidate : List.copyOf(waiting)) {
            if (!(candidate.node.getLevel() instanceof ServerLevel level) || !level.hasChunkAt(candidate.node.getPos())) {
                waiting.remove(candidate); continue;
            }
            BlockEntity block = level.getBlockEntity(candidate.node.getPos());
            if (!(block instanceof INetworkNodeProxy<?> proxy) || proxy.getNode() != candidate.node) {
                waiting.remove(candidate); continue;
            }
            if (!leases.containsKey(candidate.root.id())) {
                waiting.remove(candidate);
                candidate.retry.run();
                changed.put(candidate.node, true);
            }
        }
        for (NetworkNode node : changed.keySet()) {
            if (node.getNetwork() != null) {
                node.getNetwork().getItemStorageCache().invalidate(InvalidateCause.DISK_INVENTORY_CHANGED);
                node.getNetwork().getFluidStorageCache().invalidate(InvalidateCause.DISK_INVENTORY_CHANGED);
            }
        }
    }
}
