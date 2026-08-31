package com.huanghuang.rsintegration.compat.emi;

import com.huanghuang.rsintegration.client.RecipeBrowserBridge;
import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.config.SidebarType;
import dev.emi.emi.runtime.EmiFavorite;
import dev.emi.emi.runtime.EmiFavorites;
import dev.emi.emi.screen.EmiScreenManager;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** Isolates optional EMI runtime references from the shared client code. */
public final class EmiClientBridge {
    private EmiClientBridge() {}

    public static RecipeBrowserBridge.FavoriteResult addFavorite(ItemStack stack) {
        EmiStack ingredient = EmiStack.of(stack.copyWithCount(1));
        for (EmiFavorite favorite : EmiFavorites.favorites) {
            if (favorite.strictEquals(ingredient)) {
                return RecipeBrowserBridge.FavoriteResult.EXISTS;
            }
        }
        EmiFavorites.addFavorite(ingredient);
        EmiScreenManager.repopulatePanels(SidebarType.FAVORITES);
        return RecipeBrowserBridge.FavoriteResult.ADDED;
    }

    public static List<ItemStack> favoriteItems(int limit) {
        List<ItemStack> result = new ArrayList<>();
        for (EmiFavorite favorite : List.copyOf(EmiFavorites.favorites)) {
            for (EmiStack stack : favorite.getEmiStacks()) {
                ItemStack item = stack.getItemStack();
                if (!item.isEmpty()) result.add(item.copyWithCount(1));
                if (result.size() >= limit) return result;
            }
        }
        return result;
    }

    public static void setSearchText(String text) {
        EmiApi.setSearchText(text);
    }

    public static String getSearchText() {
        return EmiApi.getSearchText();
    }

    public static void showRecipesOrUses(ItemStack stack, boolean uses) {
        EmiStack ingredient = EmiStack.of(stack.copyWithCount(1));
        if (uses) EmiApi.displayUses(ingredient); else EmiApi.displayRecipes(ingredient);
    }

    @Nullable
    public static ItemStack hoveredItem(int mouseX, int mouseY) {
        var interaction = EmiApi.getHoveredStack(mouseX, mouseY, true);
        if (interaction == null || interaction.isEmpty()) return null;
        List<EmiStack> stacks = interaction.getStack().getEmiStacks();
        if (stacks.isEmpty()) return null;
        ItemStack item = stacks.get(0).getItemStack();
        return item.isEmpty() ? null : item.copyWithCount(1);
    }
}
