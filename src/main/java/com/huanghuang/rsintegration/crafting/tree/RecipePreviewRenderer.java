package com.huanghuang.rsintegration.crafting.tree;

import com.huanghuang.rsintegration.network.RSJeiPlugin;
import com.huanghuang.rsintegration.util.UIRenderer;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nullable;
import java.util.*;

/**
 * JEI recipe preview rendering for tree-node tooltips (ported from JEICT).
 * <p>
 * Caches {@link IRecipeLayoutDrawable} per recipeId. Follows the JEICT pattern:
 * vanilla recipe → output focus → category lookup → match → drawable.
 */
public final class RecipePreviewRenderer {

    private final Map<ResourceLocation, Optional<IRecipeLayoutDrawable<?>>> cache = new HashMap<>();
    private final Map<ResourceLocation, Optional<IDrawable>> iconCache = new HashMap<>();
    private final Map<ResourceLocation, Optional<Component>> titleCache = new HashMap<>();
    private final Minecraft mc;

    public RecipePreviewRenderer() {
        this.mc = Minecraft.getInstance();
    }

    public void clear() {
        cache.clear();
        iconCache.clear();
        titleCache.clear();
    }

    /**
     * Render a recipe preview tooltip near the given screen position.
     * Returns true when something was drawn (caller should suppress plain item tooltip).
     */
    public boolean renderRecipeTooltip(GuiGraphics gfx, Font font,
                                       ResourceLocation recipeId,
                                       int anchorX, int anchorY, int screenW, int screenH,
                                       int mouseX, int mouseY) {
        Optional<IRecipeLayoutDrawable<?>> opt = getDrawable(recipeId);
        if (opt.isPresent()) {
            renderJeiTooltip(gfx, font, opt.get(), anchorX, anchorY, screenW, screenH, mouseX, mouseY);
            return true;
        }

        Recipe<?> recipe = mc.level != null
                ? mc.level.getRecipeManager().byKey(recipeId).orElse(null) : null;
        if (recipe != null) {
            renderManualTooltip(gfx, font, recipe, anchorX, anchorY, screenW, screenH);
            return true;
        }

        return false;
    }

    // ── Layer 1: JEI drawable (JEICT pattern) ──────────────────────

    @Nullable
    private Optional<IRecipeLayoutDrawable<?>> getDrawable(ResourceLocation recipeId) {
        return cache.computeIfAbsent(recipeId, this::createDrawable);
    }

    private Optional<IRecipeLayoutDrawable<?>> createDrawable(ResourceLocation recipeId) {
        IJeiRuntime jei = RSJeiPlugin.getRuntime();
        if (jei == null || mc.level == null) return Optional.empty();

        Recipe<?> vanilla = mc.level.getRecipeManager().byKey(recipeId).orElse(null);
        if (vanilla == null) return Optional.empty();

        try {
            return findHandlingCategory(jei, vanilla)
                    .flatMap(cat -> createDrawableForCategory(jei, cat, vanilla));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * First JEI category whose recipe type actually <em>handles</em> {@code vanilla} — not merely
     * one that lists the output. Resolving purely by output focus + {@code findFirst()} picks an
     * arbitrary producer (usually the vanilla crafting category), so a multiblock recipe would show
     * the wrong machine icon. Output focus narrows the search; {@link #categoryHandles} verifies it.
     * <p>
     * Two robustness details, both needed for machine recipes:
     * <ul>
     *   <li><b>Empty result.</b> Many mod recipes (Malum/Lodestone {@code ILodestoneRecipe}, Eidolon,
     *       …) return an EMPTY {@code getResultItem} — their real output lives in a custom field the
     *       JEI category reads directly. JEI's focus factory throws on an empty ingredient, so we skip
     *       the focus lookup when the result is empty and treat any focus failure as "fall through to
     *       the unfocused scan below".</li>
     *   <li><b>Hidden recipes.</b> Some mods (e.g. Malum) hide their own recipes from the JEI browser
     *       at runtime via {@code IRecipeManager.hideRecipes}. {@code includeHidden()} keeps their
     *       category visible to the reverse lookup so the tree still gets an icon/title/preview.</li>
     * </ul>
     */
    private Optional<IRecipeCategory<?>> findHandlingCategory(IJeiRuntime jei, Recipe<?> vanilla) {
        ItemStack output = vanilla.getResultItem(mc.level.registryAccess());
        if (!output.isEmpty()) {
            try {
                IFocus<?> focus = jei.getJeiHelpers().getFocusFactory()
                        .createFocus(RecipeIngredientRole.OUTPUT, VanillaTypes.ITEM_STACK, output);
                Optional<IRecipeCategory<?>> byOutput = jei.getRecipeManager().createRecipeCategoryLookup()
                        .limitFocus(List.of(focus))
                        .includeHidden()
                        .get()
                        .filter(cat -> categoryHandles(cat, vanilla))
                        .findFirst();
                if (byOutput.isPresent()) return byOutput;
            } catch (Exception ignored) {
                // Empty/invalid focus — fall through to the unfocused scan.
            }
        }
        // Unfocused scan: iterate every category (including hidden) and pick the one whose recipe
        // type actually handles this recipe. Covers empty-result machine recipes that the focus
        // lookup can't reach.
        return jei.getRecipeManager().createRecipeCategoryLookup()
                .includeHidden()
                .get()
                .filter(cat -> categoryHandles(cat, vanilla))
                .findFirst();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean categoryHandles(IRecipeCategory<?> cat, Recipe<?> vanilla) {
        try {
            Class<?> recipeClass = cat.getRecipeType().getRecipeClass();
            return recipeClass.isInstance(vanilla) && ((IRecipeCategory) cat).isHandled(vanilla);
        } catch (Exception e) {
            return false;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Optional<IRecipeLayoutDrawable<?>> createDrawableForCategory(
            IJeiRuntime jei, IRecipeCategory cat, Recipe<?> vanilla) {
        try {
            Class<?> recipeClass = cat.getRecipeType().getRecipeClass();
            if (!recipeClass.isInstance(vanilla)) return Optional.empty();
            if (!cat.isHandled(vanilla)) return Optional.empty();
            return jei.getRecipeManager().createRecipeLayoutDrawable(
                    cat,
                    recipeClass.cast(vanilla),
                    jei.getJeiHelpers().getFocusFactory().getEmptyFocusGroup()
            ).map(d -> d);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private void renderJeiTooltip(GuiGraphics gfx, Font font,
                                  IRecipeLayoutDrawable<?> drawable,
                                  int anchorX, int anchorY, int screenW, int screenH,
                                  int mouseX, int mouseY) {
        int w = drawable.getRect().getWidth();
        int h = drawable.getRect().getHeight();
        int pad = 6;
        int panelW = w + pad * 2;
        int panelH = h + pad * 2;

        int px = anchorX + 12;
        int py = anchorY + 4;
        if (px + panelW > screenW) px = anchorX - panelW - 12;
        if (py + panelH > screenH) py = anchorY - panelH - 4;
        px = Math.max(2, Math.min(px, screenW - panelW - 2));
        py = Math.max(2, Math.min(py, screenH - panelH - 2));

        // Custom dark rounded panel: 1px emerald border (drawn as a slightly larger rounded
        // rect underlay) + a 0xF0151515 body on top, leaving the border as a thin ring. Elevated
        // to z=390 (just under the recipe's z=400) so it sits above the tree drawn afterwards.
        gfx.pose().pushPose();
        gfx.pose().translate(0, 0, 390);
        UIRenderer.rounded(gfx, px - 1, py - 1, panelW + 2, panelH + 2, 3f, 0xFF44AA66);
        UIRenderer.rounded(gfx, px, py, panelW, panelH, 2f, 0xF0151515);
        gfx.pose().popPose();

        int recipeX = px + pad;
        int recipeY = py + pad;
        drawable.setPosition(recipeX, recipeY);
        gfx.pose().pushPose();
        gfx.pose().translate(0, 0, 400);
        drawable.drawRecipe(gfx, mouseX, mouseY);
        drawable.drawOverlays(gfx, mouseX, mouseY);
        gfx.pose().popPose();
    }

    // ── Layer 2: manual grid fallback ──────────────────────────────

    private void renderManualTooltip(GuiGraphics gfx, Font font,
                                     Recipe<?> recipe,
                                     int anchorX, int anchorY, int screenW, int screenH) {
        List<Ingredient> inputs = recipe.getIngredients();
        ItemStack output = recipe.getResultItem(mc.level.registryAccess());
        if (inputs.isEmpty() && output.isEmpty()) return;

        int cell = 24;
        int cols = Math.min(inputs.size(), 4);
        int rows = inputs.isEmpty() ? 1 : (inputs.size() + cols - 1) / cols;
        int gridW = cols * cell;
        int arrowW = 24;
        int outW = cell;
        int totalW = gridW + (inputs.isEmpty() ? 0 : arrowW) + outW;

        int pad = 6;
        int panelW = totalW + pad * 2;
        int titleH = font.lineHeight + 4;
        int panelH = titleH + rows * cell + pad * 2;

        int px = anchorX + 12;
        int py = anchorY + 4;
        if (px + panelW > screenW) px = anchorX - panelW - 12;
        if (py + panelH > screenH) py = anchorY - panelH - 4;
        px = Math.max(2, Math.min(px, screenW - panelW - 2));
        py = Math.max(2, Math.min(py, screenH - panelH - 2));

        // Elevate the whole fallback (bg + grid + items + text) to z=390 so it sits above the
        // tree drawn afterwards; relative depth is preserved, so item icons still render on top.
        gfx.pose().pushPose();
        gfx.pose().translate(0, 0, 390);
        UIRenderer.rounded(gfx, px - 1, py - 1, panelW + 2, panelH + 2, 3f, 0xFF44AA66);
        UIRenderer.rounded(gfx, px, py, panelW, panelH, 2f, 0xF0151515);

        String title = recipe.getId().getPath();
        title = font.plainSubstrByWidth(title, panelW - pad * 2);
        gfx.drawString(font, title, px + pad + 2, py + pad, 0xFF889988, false);

        int gridTop = py + pad + titleH;
        for (int i = 0; i < inputs.size(); i++) {
            int cx = px + pad + (i % cols) * cell + 4;
            int cy = gridTop + (i / cols) * cell + 4;
            gfx.fill(cx - 1, cy - 1, cx + cell - 2, cy, 0xFF3A6A3A);
            gfx.fill(cx - 1, cy + cell - 3, cx + cell - 2, cy + cell - 2, 0xFF3A6A3A);
            gfx.fill(cx - 1, cy - 1, cx, cy + cell - 2, 0xFF3A6A3A);
            gfx.fill(cx + cell - 3, cy - 1, cx + cell - 2, cy + cell - 2, 0xFF3A6A3A);
            ItemStack[] items = inputs.get(i).getItems();
            if (items.length > 0) {
                gfx.renderItem(items[0], cx, cy);
            }
        }

        int arrowX = px + pad + gridW;
        int arrowY = gridTop + (rows * cell) / 2 - 4;
        if (!inputs.isEmpty()) {
            gfx.fill(arrowX, arrowY, arrowX + arrowW - 4, arrowY + 1, 0xFF44AA66);
            gfx.fill(arrowX + arrowW - 8, arrowY - 3, arrowX + arrowW - 4, arrowY + 4, 0xFF44AA66);
        }

        int outX = inputs.isEmpty() ? px + pad : arrowX + arrowW;
        int outY = gridTop + (rows * cell) / 2 - cell / 2;
        if (!output.isEmpty()) {
            gfx.renderItem(output, outX + 4, outY + 4);
            String cnt = "x" + output.getCount();
            gfx.drawString(font, cnt, outX + cell + 2, outY + (cell - font.lineHeight) / 2,
                    0xFFBBCCBB, false);
        }
        gfx.pose().popPose();
    }

    // ── Recipe-category icon (for tree nodes) ──────────────────────

    /**
     * Draw the JEI recipe-category icon for {@code recipeId} at ({@code x},{@code y}), scaled to
     * fit {@code size}. Returns false when JEI is unavailable or the category has no icon — the
     * caller then falls back to its placeholder. Fully guarded; never throws.
     */
    public boolean drawCategoryIcon(GuiGraphics gfx, ResourceLocation recipeId,
                                    int x, int y, int size) {
        IDrawable icon = iconCache.computeIfAbsent(recipeId, this::lookupCategoryIcon).orElse(null);
        if (icon == null) return false;
        try {
            int iw = icon.getWidth();
            int ih = icon.getHeight();
            if (iw <= 0 || ih <= 0) return false;
            gfx.pose().pushPose();
            gfx.pose().translate(x, y, 0);
            float s = Math.min(size / (float) iw, size / (float) ih);
            if (s != 1f) gfx.pose().scale(s, s, 1f);
            icon.draw(gfx);
            gfx.pose().popPose();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private Optional<IDrawable> lookupCategoryIcon(ResourceLocation recipeId) {
        IJeiRuntime jei = RSJeiPlugin.getRuntime();
        if (jei == null || mc.level == null) return Optional.empty();
        Recipe<?> vanilla = mc.level.getRecipeManager().byKey(recipeId).orElse(null);
        if (vanilla == null) return Optional.empty();
        try {
            return findHandlingCategory(jei, vanilla)
                    .map(IRecipeCategory::getIcon)
                    .filter(Objects::nonNull);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Human-readable JEI category title for {@code recipeId} (e.g. "Spirit Altar", "Crafting"),
     * resolved from the category that actually handles the recipe. Cached; empty when JEI is off.
     */
    public Optional<Component> categoryTitle(ResourceLocation recipeId) {
        return titleCache.computeIfAbsent(recipeId, this::lookupCategoryTitle);
    }

    private Optional<Component> lookupCategoryTitle(ResourceLocation recipeId) {
        IJeiRuntime jei = RSJeiPlugin.getRuntime();
        if (jei == null || mc.level == null) return Optional.empty();
        Recipe<?> vanilla = mc.level.getRecipeManager().byKey(recipeId).orElse(null);
        if (vanilla == null) return Optional.empty();
        try {
            return findHandlingCategory(jei, vanilla).map(IRecipeCategory::getTitle);
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
