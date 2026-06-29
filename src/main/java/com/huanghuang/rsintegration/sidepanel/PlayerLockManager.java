package com.huanghuang.rsintegration.sidepanel;

import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Manages per-player locked item lists via player persistent NBT.
 * Data is stored at {@code player.persistentData.rs_integration.locked_items}
 * and auto-persists with the player entity.
 */
public final class PlayerLockManager {

    private static final String ROOT_KEY = "rs_integration";
    private static final String LOCK_KEY = "locked_items";

    private PlayerLockManager() {}

    public static Set<ResourceLocation> getLockedItems(ServerPlayer player) {
        var pd = player.getPersistentData();
        if (!pd.contains(ROOT_KEY, Tag.TAG_COMPOUND)) return Collections.emptySet();
        var root = pd.getCompound(ROOT_KEY);
        if (!root.contains(LOCK_KEY, Tag.TAG_LIST)) return Collections.emptySet();
        ListTag list = root.getList(LOCK_KEY, Tag.TAG_STRING);
        Set<ResourceLocation> result = new LinkedHashSet<>();
        for (int i = 0; i < list.size(); i++) {
            ResourceLocation rl = ResourceLocation.tryParse(list.getString(i));
            if (rl != null) result.add(rl);
        }
        return result;
    }

    public static void setLockedItems(ServerPlayer player, Set<ResourceLocation> items) {
        var pd = player.getPersistentData();
        var root = pd.getCompound(ROOT_KEY);
        ListTag list = new ListTag();
        for (ResourceLocation rl : items) {
            list.add(StringTag.valueOf(rl.toString()));
        }
        root.put(LOCK_KEY, list);
        pd.put(ROOT_KEY, root);
    }

    public static Set<ResourceLocation> toggleLock(ServerPlayer player, ResourceLocation itemId) {
        Set<ResourceLocation> current = getLockedItems(player);
        if (!current.remove(itemId)) {
            current.add(itemId);
        }
        setLockedItems(player, current);
        return current;
    }
}
