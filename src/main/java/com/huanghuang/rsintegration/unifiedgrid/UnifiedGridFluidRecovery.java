package com.huanghuang.rsintegration.unifiedgrid;

import com.huanghuang.rsintegration.mods.ironsspellbooks.FluidRefundItem;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.fluids.FluidStack;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 玩家离线或背包满时暂存退款；只有领取物品时才读取该玩家的记录。 */
public final class UnifiedGridFluidRecovery extends SavedData {
    private static final int MAX_CLAIM_PER_ACTION = 32;
    private static final int MAX_VISITS_PER_ACTION = 64;
    /** 按玩家和原网络索引，领取时无需扫描其他玩家的退款。 */
    private final Map<UUID, Map<String, Bucket>> pending = new LinkedHashMap<>();
    private final ListTag unowned = new ListTag();

    private static final class Bucket {
        final Deque<CompoundTag> entries = new ArrayDeque<>();
        final Map<CompoundTag, CompoundTag> latest = new HashMap<>();

        void add(CompoundTag entry) {
            entries.addLast(entry);
            latest.put(identity(entry.getCompound("fluid")), entry);
        }

        void removeIndex(CompoundTag entry) {
            CompoundTag key = identity(entry.getCompound("fluid"));
            if (latest.get(key) == entry) latest.remove(key);
        }

        boolean isEmpty() { return entries.isEmpty(); }
    }

    UnifiedGridFluidRecovery() { }
    UnifiedGridFluidRecovery(CompoundTag tag) {
        for (Tag value : tag.getList("pending", Tag.TAG_COMPOUND)) {
            CompoundTag entry = ((CompoundTag) value).copy();
            if (!entry.hasUUID("owner")) {
                unowned.add(entry);
                continue;
            }
            String networkId = entry.contains("network", Tag.TAG_STRING)
                    ? entry.getString("network") : null;
            queue(entry.getUUID("owner"), networkId).add(entry);
        }
    }

    static UnifiedGridFluidRecovery get(ServerPlayer player) {
        return get(player.server);
    }

    public static UnifiedGridFluidRecovery get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(UnifiedGridFluidRecovery::new,
                UnifiedGridFluidRecovery::new, "rs_integration_unified_grid_fluid_recovery");
    }

    void retain(UUID player, FluidStack fluid) {
        if (fluid.isEmpty()) return;
        retainRaw(player, fluid.writeToNBT(new CompoundTag()), null);
    }

    /** 原始 NBT 也能暂存；流体模组暂时缺失时不丢掉注册名和标签。 */
    public boolean retainRaw(UUID player, CompoundTag fluid, @Nullable String networkId) {
        if (!fluid.contains("FluidName", Tag.TAG_STRING) || fluid.getInt("Amount") <= 0) {
            throw new IllegalArgumentException("无法暂存没有有效身份或数量的流体");
        }
        boolean firstForNetwork = !pending.containsKey(player)
                || !pending.get(player).containsKey(networkId);
        Bucket bucket = queue(player, networkId);
        CompoundTag identity = identity(fluid);
        int remaining = fluid.getInt("Amount");
        // 相同流体身份直接定位；大量不同 NBT 的退款也无需遍历整个队列。
        CompoundTag previous = bucket.latest.get(identity);
        if (previous != null) {
            CompoundTag stored = previous.getCompound("fluid");
            int capacity = Integer.MAX_VALUE - stored.getInt("Amount");
            if (capacity > 0) {
                int merged = Math.min(capacity, remaining);
                stored.putInt("Amount", stored.getInt("Amount") + merged);
                remaining -= merged;
            }
        }
        if (remaining > 0) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("owner", player);
            CompoundTag remainder = fluid.copy();
            remainder.putInt("Amount", remaining);
            entry.put("fluid", remainder);
            if (networkId != null) entry.putString("network", networkId);
            bucket.add(entry);
        }
        setDirty();
        return firstForNetwork;
    }

    /** 登录或执行领取命令时最多发放 32 个物品；未发出的记录保留在世界存档。 */
    public int claim(ServerPlayer player) {
        Map<String, Bucket> byNetwork = pending.get(player.getUUID());
        if (byNetwork == null || player.getInventory() == null) return 0;
        int claimed = 0;
        int visited = 0;
        Iterator<Map.Entry<String, Bucket>> networks = byNetwork.entrySet().iterator();
        while (networks.hasNext() && claimed < MAX_CLAIM_PER_ACTION
                && visited < MAX_VISITS_PER_ACTION
                && player.getInventory().getFreeSlot() >= 0) {
            Map.Entry<String, Bucket> group = networks.next();
            Bucket bucket = group.getValue();
            int visits = Math.min(bucket.entries.size(), MAX_VISITS_PER_ACTION - visited);
            for (int index = 0; index < visits && claimed < MAX_CLAIM_PER_ACTION
                    && player.getInventory().getFreeSlot() >= 0; index++, visited++) {
                CompoundTag entry = bucket.entries.peekFirst();
                try {
                    if (!player.getInventory().add(FluidRefundItem.create(
                            entry.getCompound("fluid"), group.getKey()))) {
                        break;
                    }
                } catch (IllegalArgumentException invalid) {
                    bucket.entries.removeFirst();
                    bucket.entries.addLast(entry);
                    continue;
                }
                bucket.entries.removeFirst();
                bucket.removeIndex(entry);
                claimed++;
                setDirty();
            }
            if (bucket.isEmpty()) networks.remove();
        }
        if (byNetwork.isEmpty()) pending.remove(player.getUUID());
        if (claimed > 0) {
            player.getInventory().setChanged();
            if (player.inventoryMenu != null) player.inventoryMenu.broadcastChanges();
            if (player.containerMenu != null && player.containerMenu != player.inventoryMenu) {
                player.containerMenu.broadcastChanges();
            }
        }
        return claimed;
    }

    private Bucket queue(UUID player, @Nullable String networkId) {
        return pending.computeIfAbsent(player, ignored -> new LinkedHashMap<>())
                .computeIfAbsent(networkId, ignored -> new Bucket());
    }

    private static CompoundTag identity(CompoundTag fluid) {
        CompoundTag identity = fluid.copy();
        identity.remove("Amount");
        return identity;
    }

    @Override public CompoundTag save(CompoundTag tag) {
        ListTag serialized = unowned.copy();
        for (Map<String, Bucket> byNetwork : pending.values()) {
            for (Bucket bucket : byNetwork.values()) {
                for (CompoundTag entry : bucket.entries) serialized.add(entry.copy());
            }
        }
        tag.put("pending", serialized);
        return tag;
    }
}
