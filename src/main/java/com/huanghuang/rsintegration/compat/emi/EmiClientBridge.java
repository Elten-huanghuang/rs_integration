package com.huanghuang.rsintegration.compat.emi;

import com.huanghuang.rsintegration.util.UIRenderer;
import com.mojang.blaze3d.systems.RenderSystem;
import com.huanghuang.rsintegration.client.RecipeBrowserBridge;
import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.recipe.EmiRecipeManager;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.widget.Widget;
import dev.emi.emi.api.widget.WidgetHolder;
import dev.emi.emi.config.SidebarType;
import dev.emi.emi.runtime.EmiFavorite;
import dev.emi.emi.runtime.EmiFavorites;
import dev.emi.emi.screen.EmiScreenManager;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Isolates optional EMI runtime references from the shared client code. */
public final class EmiClientBridge {
    private static EmiRecipeManager indexedRecipeManager;
    private static int indexedRecipeCount = -1;
    private static Map<ResourceLocation, EmiRecipeCategory> categoriesByRecipeId = Map.of();
    private static Map<ResourceLocation, EmiRecipe> recipesByRecipeId = Map.of();
    private static final Map<ResourceLocation, RecipePreviewWidgets> previewWidgets = new HashMap<>();

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

    public static boolean drawRecipeCategoryIcon(GuiGraphics graphics, ResourceLocation recipeId,
                                                 int x, int y, int size) {
        EmiRecipeManager manager = EmiApi.getRecipeManager();
        if (manager == null) return false;
        List<EmiRecipe> recipes = manager.getRecipes();
        if (manager != indexedRecipeManager || recipes.size() != indexedRecipeCount) {
            rebuildRecipeCategoryIndex(manager, recipes);
        }
        EmiRecipeCategory category = categoriesByRecipeId.get(recipeId);
        if (category == null) return false;

        graphics.pose().pushPose();
        try {
            graphics.pose().translate(x, y, 0);
            float scale = size / 16.0f;
            graphics.pose().scale(scale, scale, 1.0f);
            category.render(graphics, 0, 0, 0);
            return true;
        } finally {
            graphics.pose().popPose();
        }
    }

    public static boolean renderRecipePreview(GuiGraphics graphics, ResourceLocation recipeId,
                                              int anchorX, int anchorY,
                                              int screenWidth, int screenHeight,
                                              int mouseX, int mouseY) {
        EmiRecipe recipe = findRecipe(recipeId);
        if (recipe == null) return false;
        RecipePreviewWidgets preview = previewWidgets.computeIfAbsent(recipeId,
                ignored -> createPreviewWidgets(recipe));
        if (preview == RecipePreviewWidgets.EMPTY) return false;

        int padding = 6;
        int panelWidth = preview.width() + padding * 2;
        int panelHeight = preview.height() + padding * 2;
        int panelX = anchorX + 12;
        int panelY = anchorY + 4;
        if (panelX + panelWidth > screenWidth) panelX = anchorX - panelWidth - 12;
        if (panelY + panelHeight > screenHeight) panelY = anchorY - panelHeight - 4;
        panelX = Math.max(2, Math.min(panelX, screenWidth - panelWidth - 2));
        panelY = Math.max(2, Math.min(panelY, screenHeight - panelHeight - 2));

        graphics.pose().pushPose();
        try {
            graphics.pose().translate(0, 0, 390);
            UIRenderer.rounded(graphics, panelX - 1, panelY - 1,
                    panelWidth + 2, panelHeight + 2, 3f, 0xFF44AA66);
            UIRenderer.rounded(graphics, panelX, panelY,
                    panelWidth, panelHeight, 2f, 0xF0151515);
            graphics.pose().translate(panelX + padding, panelY + padding, 10);
            int localMouseX = mouseX - panelX - padding;
            int localMouseY = mouseY - panelY - padding;
            for (Widget widget : preview.widgets()) {
                widget.render(graphics, localMouseX, localMouseY, 0);
            }
            return true;
        } finally {
            RenderSystem.enableDepthTest();
            RenderSystem.setShaderColor(1, 1, 1, 1);
            graphics.pose().popPose();
        }
    }

    @Nullable
    private static EmiRecipe findRecipe(ResourceLocation recipeId) {
        EmiRecipeManager manager = EmiApi.getRecipeManager();
        if (manager == null) return null;
        List<EmiRecipe> recipes = manager.getRecipes();
        if (manager != indexedRecipeManager || recipes.size() != indexedRecipeCount) {
            rebuildRecipeCategoryIndex(manager, recipes);
        }
        return recipesByRecipeId.get(recipeId);
    }

    private static RecipePreviewWidgets createPreviewWidgets(EmiRecipe recipe) {
        int width = recipe.getDisplayWidth();
        int height = recipe.getDisplayHeight();
        if (width <= 0 || height <= 0) return RecipePreviewWidgets.EMPTY;
        PreviewWidgetHolder holder = new PreviewWidgetHolder(width, height);
        try {
            recipe.addWidgets(holder);
        } catch (RuntimeException exception) {
            return RecipePreviewWidgets.EMPTY;
        }
        if (holder.widgets.isEmpty()) return RecipePreviewWidgets.EMPTY;
        return new RecipePreviewWidgets(width, height, List.copyOf(holder.widgets));
    }

    private static void rebuildRecipeCategoryIndex(EmiRecipeManager manager, List<EmiRecipe> recipes) {
        Map<ResourceLocation, EmiRecipeCategory> rebuilt = new HashMap<>();
        Map<ResourceLocation, EmiRecipe> rebuiltRecipes = new HashMap<>();
        for (EmiRecipe recipe : recipes) {
            EmiRecipeCategory category = recipe.getCategory();
            if (category == null) continue;
            ResourceLocation emiId = recipe.getId();
            if (emiId != null) {
                rebuilt.putIfAbsent(emiId, category);
                putPreferredRecipe(rebuiltRecipes, emiId, recipe);
            }
            try {
                Recipe<?> backing = recipe.getBackingRecipe();
                if (backing != null && backing.getId() != null) {
                    rebuilt.putIfAbsent(backing.getId(), category);
                    putPreferredRecipe(rebuiltRecipes, backing.getId(), recipe);
                }
            } catch (RuntimeException ignored) {
                // Some synthetic EMI recipes have no Minecraft recipe backing.
            }
        }
        indexedRecipeManager = manager;
        indexedRecipeCount = recipes.size();
        categoriesByRecipeId = Map.copyOf(rebuilt);
        recipesByRecipeId = Map.copyOf(rebuiltRecipes);
        previewWidgets.clear();
    }

    private static void putPreferredRecipe(Map<ResourceLocation, EmiRecipe> target,
                                           ResourceLocation id, EmiRecipe candidate) {
        EmiRecipe existing = target.get(id);
        if (existing == null || (isJemiRecipe(existing) && !isJemiRecipe(candidate))) {
            target.put(id, candidate);
        }
    }

    private static boolean isJemiRecipe(EmiRecipe recipe) {
        return recipe.getClass().getName().startsWith("dev.emi.emi.jemi.");
    }

    private static final class PreviewWidgetHolder implements WidgetHolder {
        private final int width;
        private final int height;
        private final List<Widget> widgets = new ArrayList<>();

        private PreviewWidgetHolder(int width, int height) {
            this.width = width;
            this.height = height;
        }

        @Override
        public int getWidth() {
            return width;
        }

        @Override
        public int getHeight() {
            return height;
        }

        @Override
        public <T extends Widget> T add(T widget) {
            widgets.add(widget);
            return widget;
        }
    }

    private record RecipePreviewWidgets(int width, int height, List<Widget> widgets) {
        private static final RecipePreviewWidgets EMPTY = new RecipePreviewWidgets(0, 0, List.of());
    }
}
