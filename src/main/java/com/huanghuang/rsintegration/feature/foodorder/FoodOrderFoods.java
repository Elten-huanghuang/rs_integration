package com.huanghuang.rsintegration.feature.foodorder;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 食物枚举和已吃状态的统一入口。 */
public final class FoodOrderFoods {

    private static List<Item> edibleCache;

    private FoodOrderFoods() {}

    /** 所有可食用物品，按 registry id 排序，保证列表稳定。 */
    public static List<Item> edibleItems() {
        List<Item> cached = edibleCache;
        if (cached != null) {
            return cached;
        }
        List<Item> items = new ArrayList<>();
        for (Item item : ForgeRegistries.ITEMS.getValues()) {
            if (item == null || item == Items.AIR) {
                continue;
            }
            try {
                if (item.isEdible()) {
                    items.add(item);
                }
            } catch (Throwable throwable) {
                RSIntegrationMod.LOGGER.debug(
                        "[RSI-FoodOrder] 跳过无法判定可食用的物品 {}", item, throwable);
            }
        }
        items.sort(Comparator.comparing(item -> {
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
            return id == null ? "" : id.toString();
        }));
        edibleCache = List.copyOf(items);
        return edibleCache;
    }

    public static Set<String> eaten(ServerPlayer player) {
        Set<String> eaten = new HashSet<>(FoodOrderEatenData.get(player.server)
                .snapshot(player.getUUID()));
        eaten.addAll(FoodOrderSolCarrot.eatenIds(player));
        return eaten;
    }

    /** 当前玩家未吃过的食物；关闭“跳过已吃”时返回全部食物。 */
    public static List<Item> uneaten(ServerPlayer player) {
        List<Item> all = edibleItems();
        if (!RSIntegrationConfig.FOOD_ORDER_SKIP_EATEN.get()) {
            return all;
        }
        Set<String> eaten = eaten(player);
        List<Item> result = new ArrayList<>(all.size());
        for (Item item : all) {
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
            if (id == null || !eaten.contains(id.toString())) {
                result.add(item);
            }
        }
        return result;
    }

    /** 记录一次进食；同时用于旧附属存档语义的迁移。 */
    public static void markEaten(ServerPlayer player, ItemStack stack) {
        if (player == null || stack == null || stack.isEmpty()) {
            return;
        }
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id != null) {
            FoodOrderEatenData.get(player.server).mark(player.getUUID(), id.toString());
        }
    }

    public static void invalidate() {
        edibleCache = null;
    }
}
