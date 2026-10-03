package com.huanghuang.rsintegration.client;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.compat.emi.EmiClientBridge;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.mixin.jei.BookmarkOverlayAccessor;
import com.huanghuang.rsintegration.network.RSJeiPlugin;
import com.huanghuang.rsintegration.villager.tradelock.client.VillagerTradeLockClient;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.forge.ForgeTypes;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.gui.bookmarks.IngredientBookmark;
import mezz.jei.gui.overlay.bookmarks.BookmarkOverlay;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Shared player-facing recipe-browser operations for JEI + EMI coexistence. */
public final class RecipeBrowserBridge {
    public enum FavoriteResult {
        ADDED,
        EXISTS,
        UNAVAILABLE
    }

    private RecipeBrowserBridge() {}

    public static FavoriteResult addFavorite(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return FavoriteResult.UNAVAILABLE;

        FavoriteResult emiResult = FavoriteResult.UNAVAILABLE;
        if (hasEmi()) {
            try {
                emiResult = EmiClientBridge.addFavorite(stack);
            } catch (LinkageError | RuntimeException exception) {
                RSIntegrationMod.LOGGER.debug("[RSI-EMI] Failed to add favorite", exception);
            }
        }

        FavoriteResult jeiResult = addJeiFavorite(stack);
        FavoriteResult result = emiResult != FavoriteResult.UNAVAILABLE ? emiResult : jeiResult;
        if (result == FavoriteResult.ADDED) VillagerTradeLockClient.markDirty();
        return result;
    }

    public static List<ItemStack> favoriteItems(int limit) {
        if (limit <= 0) return List.of();
        List<ItemStack> result = new ArrayList<>();
        if (hasEmi()) {
            try {
                appendUnique(result, EmiClientBridge.favoriteItems(limit), limit);
            } catch (LinkageError | RuntimeException exception) {
                RSIntegrationMod.LOGGER.debug("[RSI-EMI] Failed to read favorites", exception);
            }
        }
        appendUnique(result, jeiFavoriteItems(limit), limit);
        return List.copyOf(result);
    }

    public static void setSearchText(String text) {
        String value = text == null ? "" : text;
        if (hasEmi()) {
            try {
                EmiClientBridge.setSearchText(value);
            } catch (LinkageError | RuntimeException exception) {
                RSIntegrationMod.LOGGER.debug("[RSI-EMI] Failed to set search", exception);
            }
        }
        var runtime = RSJeiPlugin.getRuntime();
        if (runtime != null) runtime.getIngredientFilter().setFilterText(value);
    }

    @Nullable
    public static String getSearchText() {
        if (hasEmi()) {
            try {
                return EmiClientBridge.getSearchText();
            } catch (LinkageError | RuntimeException exception) {
                RSIntegrationMod.LOGGER.debug("[RSI-EMI] Failed to read search", exception);
            }
        }
        var runtime = RSJeiPlugin.getRuntime();
        return runtime == null ? null : runtime.getIngredientFilter().getFilterText();
    }

    public static boolean showRecipesOrUses(ItemStack stack, boolean uses) {
        if (stack == null || stack.isEmpty() || !hasEmi()) return false;
        try {
            EmiClientBridge.showRecipesOrUses(stack, uses);
            return true;
        } catch (LinkageError | RuntimeException exception) {
            RSIntegrationMod.LOGGER.debug("[RSI-EMI] Failed to open recipe view", exception);
            return false;
        }
    }

    public static Optional<ItemStack> hoveredEmiItem(int mouseX, int mouseY) {
        if (!hasEmi()) return Optional.empty();
        try {
            return Optional.ofNullable(EmiClientBridge.hoveredItem(mouseX, mouseY))
                    .filter(stack -> !stack.isEmpty());
        } catch (LinkageError | RuntimeException exception) {
            return Optional.empty();
        }
    }

    public static boolean drawEmiRecipeCategoryIcon(GuiGraphics graphics,
                                                    ResourceLocation recipeId,
                                                    int x, int y, int size) {
        if (graphics == null || recipeId == null || size <= 0 || !hasEmi()) return false;
        try {
            return EmiClientBridge.drawRecipeCategoryIcon(graphics, recipeId, x, y, size);
        } catch (LinkageError | RuntimeException exception) {
            RSIntegrationMod.LOGGER.debug("[RSI-EMI] Failed to draw recipe category icon", exception);
            return false;
        }
    }

    public static boolean renderEmiRecipePreview(GuiGraphics graphics, ResourceLocation recipeId,
                                                 int anchorX, int anchorY,
                                                 int screenWidth, int screenHeight,
                                                 int mouseX, int mouseY) {
        if (graphics == null || recipeId == null || !hasEmi()) return false;
        try {
            return EmiClientBridge.renderRecipePreview(graphics, recipeId, anchorX, anchorY,
                    screenWidth, screenHeight, mouseX, mouseY);
        } catch (LinkageError | RuntimeException exception) {
            RSIntegrationMod.LOGGER.debug("[RSI-EMI] Failed to render recipe preview", exception);
            return false;
        }
    }

    private static boolean hasEmi() {
        return ModList.get().isLoaded("emi");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static FavoriteResult addJeiFavorite(ItemStack stack) {
        var runtime = RSJeiPlugin.getRuntime();
        if (runtime == null || !(runtime.getBookmarkOverlay() instanceof BookmarkOverlay overlay)) {
            return FavoriteResult.UNAVAILABLE;
        }
        Optional<? extends ITypedIngredient<?>> typed = InkFluidSupport.isToken(stack)
                ? runtime.getIngredientManager().createTypedIngredient(ForgeTypes.FLUID_STACK, InkFluidSupport.fluid(stack))
                : runtime.getIngredientManager().createTypedIngredient(VanillaTypes.ITEM_STACK, stack.copyWithCount(1));
        if (typed.isEmpty()) return FavoriteResult.UNAVAILABLE;
        var bookmark = IngredientBookmark.create(typed.get(), runtime.getIngredientManager());
        boolean added = ((BookmarkOverlayAccessor) overlay)
                .rsIntegration$getBookmarkList().add(bookmark);
        return added ? FavoriteResult.ADDED : FavoriteResult.EXISTS;
    }

    private static List<ItemStack> jeiFavoriteItems(int limit) {
        var runtime = RSJeiPlugin.getRuntime();
        if (runtime == null || !(runtime.getBookmarkOverlay() instanceof BookmarkOverlay overlay)) {
            return List.of();
        }
        List<ItemStack> result = new ArrayList<>();
        for (var element : ((BookmarkOverlayAccessor) overlay)
                .rsIntegration$getBookmarkList().getElements()) {
            element.getTypedIngredient().getIngredient(VanillaTypes.ITEM_STACK)
                    .filter(stack -> !stack.isEmpty())
                    .ifPresent(stack -> appendUnique(result, List.of(stack), limit));
            if (result.size() >= limit) break;
        }
        return result;
    }

    private static void appendUnique(List<ItemStack> target, List<ItemStack> source, int limit) {
        for (ItemStack stack : source) {
            if (stack == null || stack.isEmpty()) continue;
            boolean duplicate = target.stream().anyMatch(existing ->
                    ItemStack.isSameItemSameTags(existing, stack));
            if (!duplicate) target.add(stack.copyWithCount(1));
            if (target.size() >= limit) return;
        }
    }
}
