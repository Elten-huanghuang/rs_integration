package com.huanghuang.rsintegration.autoeat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/** Server-owned auto-eat choices that survive logout, restart, and player cloning. */
public record AutoEatPreferences(AutoEatMode mode, List<ResourceLocation> selectedItems) {
    public static final int MAX_SELECTED_ITEMS = 64;
    private static final String ROOT_KEY = "RSIntegrationAutoEat";
    private static final String MODE_KEY = "Mode";
    private static final String SELECTED_ITEMS_KEY = "SelectedItems";
    private static final String SELECTED_ITEM_KEY = "SelectedItem";

    public AutoEatPreferences {
        if (mode == null) mode = AutoEatMode.DIVERSITY;
        selectedItems = boundedCopy(selectedItems);
    }

    public static AutoEatPreferences load(ServerPlayer player) {
        CompoundTag tag = root(player);
        AutoEatPreferences stored = read(tag);
        List<ResourceLocation> selected = validFoods(stored.selectedItems);
        if (!tag.contains(SELECTED_ITEMS_KEY, Tag.TAG_LIST)
                || !selected.equals(stored.selectedItems)) {
            write(tag, stored.mode, selected);
        }
        return new AutoEatPreferences(stored.mode, selected);
    }

    public static void save(ServerPlayer player, AutoEatMode mode,
                            Collection<ResourceLocation> selectedItems) {
        write(root(player), mode, validFoods(selectedItems));
    }

    static AutoEatPreferences read(CompoundTag tag) {
        AutoEatMode mode = AutoEatMode.DIVERSITY;
        if (tag.contains(MODE_KEY, Tag.TAG_STRING)) {
            try {
                mode = AutoEatMode.valueOf(tag.getString(MODE_KEY));
            } catch (IllegalArgumentException ignored) {}
        }
        List<ResourceLocation> selected = new ArrayList<>();
        if (tag.contains(SELECTED_ITEMS_KEY, Tag.TAG_LIST)) {
            ListTag list = tag.getList(SELECTED_ITEMS_KEY, Tag.TAG_STRING);
            for (int i = 0; i < list.size() && selected.size() < MAX_SELECTED_ITEMS; i++) {
                ResourceLocation id = ResourceLocation.tryParse(list.getString(i));
                if (id != null && !selected.contains(id)) selected.add(id);
            }
        } else if (tag.contains(SELECTED_ITEM_KEY, Tag.TAG_STRING)) {
            ResourceLocation legacy = ResourceLocation.tryParse(tag.getString(SELECTED_ITEM_KEY));
            if (legacy != null) selected.add(legacy);
        }
        return new AutoEatPreferences(mode, selected);
    }

    static void write(CompoundTag tag, AutoEatMode mode,
                      Collection<ResourceLocation> selectedItems) {
        tag.putString(MODE_KEY, (mode == null ? AutoEatMode.DIVERSITY : mode).name());
        ListTag list = new ListTag();
        for (ResourceLocation selectedItem : boundedCopy(selectedItems)) {
            list.add(StringTag.valueOf(selectedItem.toString()));
        }
        tag.put(SELECTED_ITEMS_KEY, list);
        tag.remove(SELECTED_ITEM_KEY);
    }

    private static CompoundTag root(ServerPlayer player) {
        CompoundTag persisted = player.getPersistentData().getCompound(ServerPlayer.PERSISTED_NBT_TAG);
        player.getPersistentData().put(ServerPlayer.PERSISTED_NBT_TAG, persisted);
        if (!persisted.contains(ROOT_KEY, Tag.TAG_COMPOUND)) persisted.put(ROOT_KEY, new CompoundTag());
        return persisted.getCompound(ROOT_KEY);
    }

    private static List<ResourceLocation> validFoods(Collection<ResourceLocation> ids) {
        List<ResourceLocation> valid = new ArrayList<>();
        for (ResourceLocation id : boundedCopy(ids)) {
            if (!ForgeRegistries.ITEMS.containsKey(id)) continue;
            Item item = ForgeRegistries.ITEMS.getValue(id);
            if (item != null && item.isEdible()) valid.add(id);
        }
        return List.copyOf(valid);
    }

    private static List<ResourceLocation> boundedCopy(Collection<ResourceLocation> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        LinkedHashSet<ResourceLocation> unique = new LinkedHashSet<>();
        for (ResourceLocation id : ids) {
            if (id != null) unique.add(id);
            if (unique.size() >= MAX_SELECTED_ITEMS) break;
        }
        return List.copyOf(unique);
    }
}
