package com.huanghuang.rsintegration.mods.jei;

import com.huanghuang.rsintegration.network.RSJeiPlugin;
import com.huanghuang.rsintegration.network.packet.JeiNetworkInventoryPacket;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.ingredients.subtypes.UidContext;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** Adds only missing, real item stacks and removes only stacks added by this bridge. */
public final class TetraJeiItemBridge {
    @Nullable private static IIngredientManager manager;
    private static final Map<String, ItemStack> injected = new LinkedHashMap<>();

    private TetraJeiItemBridge() {}

    public static void sync(Collection<ItemStack> desiredStacks) {
        IJeiRuntime runtime = RSJeiPlugin.getRuntime();
        if (runtime == null) return;
        IIngredientManager nextManager = runtime.getIngredientManager();
        if (manager != nextManager) {
            manager = nextManager;
            injected.clear();
        }

        Map<String, ItemStack> desired = new LinkedHashMap<>();
        Function<ItemStack, String> ingredientKey = stack -> nextManager
                .getIngredientHelper(VanillaTypes.ITEM_STACK)
                .getUniqueId(stack, UidContext.Ingredient);
        for (ItemStack stack : desiredStacks) {
            if (stack != null && !stack.isEmpty()) {
                ItemStack copy = stack.copyWithCount(1);
                desired.putIfAbsent(ingredientKey.apply(copy), copy);
            }
        }

        Set<String> removedKeys = new LinkedHashSet<>(injected.keySet());
        removedKeys.removeAll(desired.keySet());
        if (!removedKeys.isEmpty()) {
            List<ItemStack> removed = removedKeys.stream()
                    .map(injected::remove).filter(Objects::nonNull).toList();
            if (!removed.isEmpty()) manager.removeIngredientsAtRuntime(VanillaTypes.ITEM_STACK, removed);
        }

        Set<String> existing = new LinkedHashSet<>();
        for (ItemStack stack : manager.getAllItemStacks()) {
            existing.add(ingredientKey.apply(stack));
        }
        Map<String, ItemStack> additions = new LinkedHashMap<>();
        for (Map.Entry<String, ItemStack> entry : desired.entrySet()) {
            String ingredientUid = ingredientKey.apply(entry.getValue());
            if (existing.contains(ingredientUid)) continue;
            additions.put(ingredientUid, entry.getValue());
        }
        if (!additions.isEmpty()) {
            List<ItemStack> values = List.copyOf(additions.values());
            manager.addIngredientsAtRuntime(VanillaTypes.ITEM_STACK, values);
            for (Map.Entry<String, ItemStack> entry : additions.entrySet()) {
                injected.put(entry.getKey(), entry.getValue());
            }
        }
    }

    public static void clear() {
        injected.clear();
        manager = null;
    }

}
