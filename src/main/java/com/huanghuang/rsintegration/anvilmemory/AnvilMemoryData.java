package com.huanghuang.rsintegration.anvilmemory;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

public final class AnvilMemoryData {
    public static final int LIMIT = 6;
    private static final String ROOT = "RSIntegrationAnvilMemory";

    private AnvilMemoryData() {}

    public static List<ItemStack> get(ServerPlayer player, String adapterId) {
        ListTag stored = root(player).getList(adapterId, Tag.TAG_COMPOUND);
        List<ItemStack> result = new ArrayList<>(Math.min(stored.size(), LIMIT));
        for (int i = 0; i < stored.size() && result.size() < LIMIT; i++) {
            ItemStack stack = ItemStack.of(stored.getCompound(i));
            if (!stack.isEmpty()) result.add(stack.copyWithCount(1));
        }
        return List.copyOf(result);
    }

    public static void remember(ServerPlayer player, String adapterId, ItemStack material, boolean rememberNbt) {
        if (material.isEmpty()) return;
        ItemStack remembered = material.copyWithCount(1);
        if (!rememberNbt) remembered.setTag(null);
        List<ItemStack> values = update(get(player, adapterId), remembered, rememberNbt);

        ListTag list = new ListTag();
        for (ItemStack stack : values) list.add(stack.save(new CompoundTag()));
        root(player).put(adapterId, list);
    }

    static List<ItemStack> update(List<ItemStack> existing, ItemStack material, boolean rememberNbt) {
        if (material.isEmpty()) return List.copyOf(existing);
        ItemStack remembered = material.copyWithCount(1);
        if (!rememberNbt) remembered.setTag(null);
        List<ItemStack> values = new ArrayList<>(existing);
        values.removeIf(stack -> rememberNbt
                ? ItemStack.isSameItemSameTags(stack, remembered)
                : ItemStack.isSameItem(stack, remembered));
        values.add(0, remembered);
        if (values.size() > LIMIT) values.subList(LIMIT, values.size()).clear();
        return List.copyOf(values);
    }

    private static CompoundTag root(ServerPlayer player) {
        CompoundTag persisted = player.getPersistentData().getCompound(ServerPlayer.PERSISTED_NBT_TAG);
        player.getPersistentData().put(ServerPlayer.PERSISTED_NBT_TAG, persisted);
        if (!persisted.contains(ROOT, Tag.TAG_COMPOUND)) persisted.put(ROOT, new CompoundTag());
        return persisted.getCompound(ROOT);
    }
}
