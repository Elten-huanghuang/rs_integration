package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.util.PlayerUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 玩家离线且没有可用存储端点时，按玩家身份暂存普通物品退款。 */
public final class CraftItemRecovery extends SavedData {
    private static final String DATA_NAME = "rs_integration_craft_item_recovery";
    private static final int MAX_CLAIM_STACKS = 64;
    private final Map<UUID, Deque<ItemStack>> pending = new LinkedHashMap<>();
    private final Set<UUID> processedRefundIds = new LinkedHashSet<>();

    public CraftItemRecovery() {
    }

    CraftItemRecovery(CompoundTag tag) {
        ListTag entries = tag.getList("pending", Tag.TAG_COMPOUND);
        for (Tag value : entries) {
            CompoundTag entry = (CompoundTag) value;
            if (!entry.hasUUID("owner")) continue;
            ItemStack stack = ItemStack.of(entry.getCompound("stack"));
            if (entry.contains("amount", Tag.TAG_INT) && !stack.isEmpty()) {
                stack.setCount(entry.getInt("amount"));
            }
            if (!stack.isEmpty()) retain(entry.getUUID("owner"), stack);
        }
        for (Tag value : tag.getList("processedRefundIds", Tag.TAG_INT_ARRAY)) {
            int[] parts = ((IntArrayTag) value).getAsIntArray();
            if (parts.length != 4) continue;
            processedRefundIds.add(new UUID(
                    ((long) parts[0] << 32) | (parts[1] & 0xffffffffL),
                    ((long) parts[2] << 32) | (parts[3] & 0xffffffffL)));
        }
    }

    public static CraftItemRecovery get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                CraftItemRecovery::new, CraftItemRecovery::new, DATA_NAME);
    }

    public void retain(UUID owner, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        Deque<ItemStack> queue = pending.computeIfAbsent(
                owner, ignored -> new ArrayDeque<>());
        ItemStack remainder = stack.copy();
        for (ItemStack existing : queue) {
            if (!ItemStack.isSameItemSameTags(existing, remainder)) continue;
            int room = Integer.MAX_VALUE - existing.getCount();
            if (room <= 0) continue;
            int moved = Math.min(room, remainder.getCount());
            existing.grow(moved);
            remainder.shrink(moved);
            if (remainder.isEmpty()) break;
        }
        if (!remainder.isEmpty()) queue.addLast(remainder.copy());
        setDirty();
    }

    /** 以稳定退款 ID 入队；重放同一核对命令不会重复生成凭证。 */
    public boolean retainOnce(UUID owner, ItemStack stack, UUID refundId) {
        if (owner == null || stack == null || stack.isEmpty()
                || refundId == null || processedRefundIds.contains(refundId)) return false;
        retain(owner, stack);
        processedRefundIds.add(refundId);
        setDirty();
        return true;
    }

    /** 登录或手动领取时最多处理 64 个堆叠，背包不足的部分继续保留。 */
    public int claim(ServerPlayer player) {
        Deque<ItemStack> queue = pending.get(player.getUUID());
        if (queue == null || player.getInventory() == null) return 0;
        int claimed = 0;
        while (!queue.isEmpty() && claimed < MAX_CLAIM_STACKS) {
            ItemStack stored = queue.peekFirst();
            ItemStack remainder;
            try {
                remainder = PlayerUtils.insertIntoPlayerInventory(player, stored.copy());
            } catch (RuntimeException ignored) {
                break;
            }
            if (remainder.isEmpty()) {
                queue.removeFirst();
                claimed++;
                setDirty();
                continue;
            }
            if (remainder.getCount() < stored.getCount()) {
                stored.setCount(remainder.getCount());
                setDirty();
            }
            break;
        }
        if (queue.isEmpty()) pending.remove(player.getUUID());
        return claimed;
    }

    public boolean hasPending(UUID owner) {
        Deque<ItemStack> queue = pending.get(owner);
        return queue != null && !queue.isEmpty();
    }

    List<ItemStack> pendingStacksForTests(UUID owner) {
        Deque<ItemStack> queue = pending.get(owner);
        return queue == null ? List.of()
                : queue.stream().map(ItemStack::copy).toList();
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag entries = new ListTag();
        for (Map.Entry<UUID, Deque<ItemStack>> owner : pending.entrySet()) {
            for (ItemStack stack : owner.getValue()) {
                if (stack.isEmpty()) continue;
                CompoundTag entry = new CompoundTag();
                entry.putUUID("owner", owner.getKey());
                // 原版 Count 只保存 byte，大额恢复数量需要单独保存。
                entry.put("stack", stack.copyWithCount(1).save(new CompoundTag()));
                entry.putInt("amount", stack.getCount());
                entries.add(entry);
            }
        }
        tag.put("pending", entries);
        ListTag refundIds = new ListTag();
        for (UUID id : processedRefundIds) {
            refundIds.add(new IntArrayTag(new int[]{
                    (int) (id.getMostSignificantBits() >>> 32),
                    (int) id.getMostSignificantBits(),
                    (int) (id.getLeastSignificantBits() >>> 32),
                    (int) id.getLeastSignificantBits()}));
        }
        tag.put("processedRefundIds", refundIds);
        return tag;
    }
}
