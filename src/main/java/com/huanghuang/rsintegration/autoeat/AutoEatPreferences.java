package com.huanghuang.rsintegration.autoeat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;

/** Server-owned auto-eat choices that survive logout, restart, and player cloning. */
public record AutoEatPreferences(AutoEatMode mode, @Nullable ResourceLocation selectedItem) {
    private static final String ROOT_KEY = "RSIntegrationAutoEat";
    private static final String MODE_KEY = "Mode";
    private static final String SELECTED_ITEM_KEY = "SelectedItem";

    public AutoEatPreferences {
        if (mode == null) mode = AutoEatMode.DIVERSITY;
    }

    public static AutoEatPreferences load(ServerPlayer player) {
        AutoEatPreferences stored = read(root(player));
        ResourceLocation selected = validFood(stored.selectedItem);
        if (selected != stored.selectedItem) {
            write(root(player), stored.mode, selected);
        }
        return new AutoEatPreferences(stored.mode, selected);
    }

    public static void save(ServerPlayer player, AutoEatMode mode,
                            @Nullable ResourceLocation selectedItem) {
        write(root(player), mode, validFood(selectedItem));
    }

    static AutoEatPreferences read(CompoundTag tag) {
        AutoEatMode mode = AutoEatMode.DIVERSITY;
        if (tag.contains(MODE_KEY, Tag.TAG_STRING)) {
            try {
                mode = AutoEatMode.valueOf(tag.getString(MODE_KEY));
            } catch (IllegalArgumentException ignored) {}
        }
        ResourceLocation selected = tag.contains(SELECTED_ITEM_KEY, Tag.TAG_STRING)
                ? ResourceLocation.tryParse(tag.getString(SELECTED_ITEM_KEY)) : null;
        return new AutoEatPreferences(mode, selected);
    }

    static void write(CompoundTag tag, AutoEatMode mode,
                      @Nullable ResourceLocation selectedItem) {
        tag.putString(MODE_KEY, (mode == null ? AutoEatMode.DIVERSITY : mode).name());
        if (selectedItem == null) tag.remove(SELECTED_ITEM_KEY);
        else tag.putString(SELECTED_ITEM_KEY, selectedItem.toString());
    }

    private static CompoundTag root(ServerPlayer player) {
        CompoundTag persisted = player.getPersistentData().getCompound(ServerPlayer.PERSISTED_NBT_TAG);
        player.getPersistentData().put(ServerPlayer.PERSISTED_NBT_TAG, persisted);
        if (!persisted.contains(ROOT_KEY, Tag.TAG_COMPOUND)) persisted.put(ROOT_KEY, new CompoundTag());
        return persisted.getCompound(ROOT_KEY);
    }

    @Nullable
    private static ResourceLocation validFood(@Nullable ResourceLocation id) {
        if (id == null || !ForgeRegistries.ITEMS.containsKey(id)) return null;
        Item item = ForgeRegistries.ITEMS.getValue(id);
        return item != null && item.isEdible() ? id : null;
    }
}
