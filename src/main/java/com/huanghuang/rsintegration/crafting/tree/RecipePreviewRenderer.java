package com.huanghuang.rsintegration.crafting.tree;

import com.huanghuang.rsintegration.mods.pmmo.client.PmmoSalvageAccess;
import java.lang.reflect.Field;

import com.huanghuang.rsintegration.client.RecipeBrowserBridge;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.mods.ironsspellbooks.IronSpellBooksRecipe;
import com.huanghuang.rsintegration.mods.ironsspellbooks.IronSpellBooksRecipeCatalog;
import com.huanghuang.rsintegration.mods.ironsspellbooks.client.InkFluidRenderer;
import com.huanghuang.rsintegration.mods.pmmo.PmmoSalvageCatalog;
import com.huanghuang.rsintegration.mods.vanilla.brewing.VanillaBrewingCatalog;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.network.RSJeiPlugin;
import com.huanghuang.rsintegration.util.UIRenderer;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.forge.ForgeTypes;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.registries.ForgeRegistries;

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
    private final Map<ResourceLocation, CandidateDetails> candidateCache = new HashMap<>();
    private final Minecraft mc;
    @Nullable
    private IJeiRuntime cachedJeiRuntime;

    // Cached items for synthetic recipe icons
    private Item gemCuttingTableItem;
    private Item marketItem;
    private Item scrollForgeItem;
    private Item arcaneAnvilItem;
    private Item goetyDarkAltarItem;

    public RecipePreviewRenderer() {
        this.mc = Minecraft.getInstance();
    }

    public void clear() {
        clearJeiCaches();
        cachedJeiRuntime = RSJeiPlugin.getRuntime();
        gemCuttingTableItem = null;  // Clear cached item on reset
        marketItem = null;
        scrollForgeItem = null;
        arcaneAnvilItem = null;
        goetyDarkAltarItem = null;
    }

    private void clearJeiCaches() {
        cache.clear();
        iconCache.clear();
        titleCache.clear();
        candidateCache.clear();
    }

    @Nullable
    private IJeiRuntime currentJeiRuntime() {
        IJeiRuntime current = RSJeiPlugin.getRuntime();
        if (current != cachedJeiRuntime) {
            clearJeiCaches();
            cachedJeiRuntime = current;
        }
        return current;
    }

    /**
     * Render a recipe preview tooltip near the given screen position.
     * Returns true when something was drawn (caller should suppress plain item tooltip).
     */
    public boolean renderRecipeTooltip(GuiGraphics gfx, Font font,
                                       ResourceLocation recipeId,
                                       int anchorX, int anchorY, int screenW, int screenH,
                                       int mouseX, int mouseY) {
        if (RecipeBrowserBridge.renderEmiRecipePreview(gfx, recipeId,
                anchorX, anchorY, screenW, screenH, mouseX, mouseY)) {
            return true;
        }
        Optional<IRecipeLayoutDrawable<?>> opt = getDrawable(recipeId);
        if (opt.isPresent()) {
            renderJeiTooltip(gfx, font, opt.get(), anchorX, anchorY, screenW, screenH, mouseX, mouseY);
            return true;
        }

        Recipe<?> recipe = resolveRecipe(recipeId);
        if (recipe != null) {
            renderManualTooltip(gfx, font, recipe, anchorX, anchorY, screenW, screenH);
            return true;
        }

        return false;
    }

    // ── Layer 1: JEI drawable (JEICT pattern) ──────────────────────

    @Nullable
    private Optional<IRecipeLayoutDrawable<?>> getDrawable(ResourceLocation recipeId) {
        if (currentJeiRuntime() == null || mc.level == null) return Optional.empty();
        return cache.computeIfAbsent(recipeId, this::createDrawable);
    }

    private Optional<IRecipeLayoutDrawable<?>> createDrawable(ResourceLocation recipeId) {
        IJeiRuntime jei = RSJeiPlugin.getRuntime();
        if (jei == null || mc.level == null) return Optional.empty();

        Recipe<?> vanilla = resolveRecipe(recipeId);
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
                InkFluidRenderer.render(gfx, items[0], cx, cy);
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
            InkFluidRenderer.render(gfx, output, outX + 4, outY + 4);
            String cnt = InkFluidRenderer.quantity(output, output.getCount());
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
        if (isIronSpellBooksRecipe(recipeId)) {
            if (recipeId.getPath().startsWith("irons_spellbooks/recycle/")
                    || recipeId.getPath().startsWith("irons_spellbooks/bottle/")) {
                return renderItemIcon(gfx, ForgeRegistries.ITEMS.getValue(
                        new ResourceLocation("irons_spellbooks", "alchemist_cauldron")), x, y, size);
            }
            boolean scrollForge = recipeId.getPath().startsWith("irons_spellbooks/scroll_forge/");
            Item item = scrollForge ? scrollForgeItem : arcaneAnvilItem;
            if (item == null) {
                item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(
                        "irons_spellbooks", scrollForge ? "scroll_forge" : "arcane_anvil"));
                if (scrollForge) scrollForgeItem = item;
                else arcaneAnvilItem = item;
            }
            if (renderItemIcon(gfx, item, x, y, size)) return true;
        }
        if (isGoetyRitualRecipe(recipeId)) {
            if (goetyDarkAltarItem == null) {
                goetyDarkAltarItem = ForgeRegistries.ITEMS
                        .getValue(new ResourceLocation("goety", "dark_altar"));
            }
            if (renderItemIcon(gfx, goetyDarkAltarItem, x, y, size)) return true;
        }
        if (isPmmoSalvageRecipe(recipeId)) {
            ItemStack salvageBlock = PmmoSalvageAccess.salvageBlock();
            if (!salvageBlock.isEmpty()) {
                gfx.renderItem(salvageBlock, x, y);
                return true;
            }
        }
        if (isSyntheticBrewingRecipe(recipeId)) {
            gfx.renderItem(new ItemStack(Items.BREWING_STAND), x, y);
            return true;
        }
        if (isSyntheticGemCuttingRecipe(recipeId)) {
            if (gemCuttingTableItem == null) {
                gemCuttingTableItem = ForgeRegistries.ITEMS
                        .getValue(new ResourceLocation("apotheosis", "gem_cutting_table"));
            }
            if (gemCuttingTableItem != null && gemCuttingTableItem != Items.AIR) {
                gfx.renderItem(new ItemStack(gemCuttingTableItem), x, y);
                return true;
            }
        }
        if (isVirtualMarketRecipe(recipeId)) {
            try {
                if (marketItem == null) {
                    marketItem = ForgeRegistries.ITEMS
                            .getValue(new ResourceLocation("farmingforblockheads", "market"));
                }
                if (marketItem != null && marketItem != Items.AIR) {
                    gfx.pose().pushPose();
                    try {
                        gfx.pose().translate(x, y, 0);
                        float scale = size / 16.0f;
                        gfx.pose().scale(scale, scale, 1.0f);
                        gfx.renderItem(new ItemStack(marketItem), 0, 0);
                    } finally {
                        gfx.pose().popPose();
                    }
                    return true;
                }
            } catch (RuntimeException ignored) {
                // Registry lookup/rendering must not prevent the recipe tree from drawing.
            }
        }
        IDrawable icon = null;
        if (currentJeiRuntime() != null && mc.level != null) {
            icon = iconCache.computeIfAbsent(recipeId, this::lookupCategoryIcon).orElse(null);
        }
        if (icon == null) {
            return RecipeBrowserBridge.drawEmiRecipeCategoryIcon(gfx, recipeId, x, y, size);
        }
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
        Recipe<?> vanilla = resolveRecipe(recipeId);
        if (vanilla == null) return Optional.empty();
        try {
            return findHandlingCategory(jei, vanilla)
                    .map(IRecipeCategory::getIcon)
                    .filter(Objects::nonNull);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static boolean isSyntheticBrewingRecipe(ResourceLocation recipeId) {
        return "rs_integration".equals(recipeId.getNamespace())
                && recipeId.getPath().startsWith("vanilla_brewing/");
    }

    private static boolean isIronSpellBooksRecipe(ResourceLocation recipeId) {
        if (recipeId == null || !"rs_integration".equals(recipeId.getNamespace())) return false;
        String path = recipeId.getPath();
        return path.startsWith("irons_spellbooks/scroll_forge/")
                || path.startsWith("irons_spellbooks/arcane_anvil/")
                || path.startsWith("irons_spellbooks/recycle/")
                || path.startsWith("irons_spellbooks/bottle/");
    }

    private boolean isGoetyRitualRecipe(ResourceLocation recipeId) {
        if (recipeId == null || !"goety".equals(recipeId.getNamespace()) || mc.level == null) {
            return false;
        }
        Recipe<?> recipe = mc.level.getRecipeManager().byKey(recipeId).orElse(null);
        return recipe != null && recipe.getClass().getName()
                .equals("com.Polarice3.Goety.common.crafting.RitualRecipe");
    }

    private static boolean renderItemIcon(GuiGraphics gfx, @Nullable Item item,
                                          int x, int y, int size) {
        if (item == null || item == Items.AIR) return false;
        gfx.pose().pushPose();
        try {
            gfx.pose().translate(x, y, 0);
            float scale = size / 16.0f;
            gfx.pose().scale(scale, scale, 1.0f);
            gfx.renderItem(new ItemStack(item), 0, 0);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        } finally {
            gfx.pose().popPose();
        }
    }

    private static boolean isSyntheticGemCuttingRecipe(ResourceLocation recipeId) {
        return "rs_integration".equals(recipeId.getNamespace())
                && recipeId.getPath().startsWith("gem_cutting/");
    }

    static boolean isVirtualMarketRecipe(ResourceLocation recipeId) {
        return recipeId != null
                && "farmingforblockheads".equals(recipeId.getNamespace())
                && recipeId.getPath().startsWith("market/");
    }

    private static boolean isPmmoSalvageRecipe(ResourceLocation recipeId) {
        return recipeId != null && "rs_integration".equals(recipeId.getNamespace())
                && recipeId.getPath().startsWith("pmmo_salvage/");
    }

    /**
     * Human-readable JEI category title for {@code recipeId} (e.g. "Spirit Altar", "Crafting"),
     * resolved from the category that actually handles the recipe. Cached; empty when JEI is off.
     */
    public Optional<Component> categoryTitle(ResourceLocation recipeId) {
        if (isIronSpellBooksRecipe(recipeId)) {
            String path = recipeId.getPath();
            String key = path.startsWith("irons_spellbooks/scroll_forge/")
                    ? "rsi.batch.mod.irons_spellbooks_scroll_forge"
                    : path.startsWith("irons_spellbooks/recycle/") || path.startsWith("irons_spellbooks/bottle/")
                    ? "gui.rs_integration.jei.irons_spellbooks_alchemist_cauldron"
                    : "rsi.batch.mod.irons_spellbooks_arcane_anvil";
            return Optional.of(Component.translatable(key));
        }
        if (isPmmoSalvageRecipe(recipeId)) {
            return Optional.of(Component.translatable("rsi.jei.pmmo_salvage"));
        }
        if (isVirtualMarketRecipe(recipeId)) {
            return Optional.of(Component.translatable("block.farmingforblockheads.market"));
        }
        if (currentJeiRuntime() == null || mc.level == null) return Optional.empty();
        return titleCache.computeIfAbsent(recipeId, this::lookupCategoryTitle);
    }

    private Optional<Component> lookupCategoryTitle(ResourceLocation recipeId) {
        IJeiRuntime jei = RSJeiPlugin.getRuntime();
        if (jei == null || mc.level == null) return Optional.empty();
        Recipe<?> vanilla = resolveRecipe(recipeId);
        if (vanilla == null) return Optional.empty();
        try {
            return findHandlingCategory(jei, vanilla).map(IRecipeCategory::getTitle);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** 原生配方管理器之外的内部配方，也必须能展示具体卷轴与墨水身份。 */
    @Nullable
    private Recipe<?> resolveRecipe(ResourceLocation recipeId) {
        if (mc.level == null) return null;
        Recipe<?> recipe = mc.level.getRecipeManager().byKey(recipeId).orElse(null);
        if (recipe != null) return recipe;
        try {
            if (isIronSpellBooksRecipe(recipeId)) return IronSpellBooksRecipeCatalog.byId(mc.level, recipeId);
            if (isPmmoSalvageRecipe(recipeId)) return PmmoSalvageCatalog.byId(recipeId);
            if (isSyntheticBrewingRecipe(recipeId)) return VanillaBrewingCatalog.byId(recipeId);
        } catch (RuntimeException | LinkageError ignored) {
            // 可选模组或客户端目录尚未就绪时，仍保留候选和配方 ID。
        }
        return null;
    }

    public record CandidateDetails(String name, String searchText) {}

    /** 搜索索引只收集文字；不为屏幕外的数百个候选创建 JEI 布局。 */
    public CandidateDetails candidateDetails(ResourceLocation id, ItemStack fallback) {
        return candidateCache.computeIfAbsent(id, ignored -> {
            Recipe<?> recipe = resolveRecipe(id);
            if (recipe == null) return new CandidateDetails(id.getPath(), id.toString());
            List<ItemStack> inputs = previewInputs(recipe);
            ItemStack output = previewOutput(recipe, fallback);
            String name = inputs.isEmpty() ? output.getHoverName().getString()
                    : inputs.get(0).getHoverName().getString() + " → " + output.getHoverName().getString();
            if (recipe instanceof IronSpellBooksRecipe iron && iron.isScrollRecycling()) {
                ResourceLocation spell = ResourceLocation.tryParse(iron.spellId());
                if (spell != null) {
                    String key = "spell." + spell.getNamespace() + "." + spell.getPath();
                    String spellName = I18n.exists(key) ? I18n.get(key) : spell.getPath();
                    String level = id.getPath().substring(id.getPath().lastIndexOf('/') + 1);
                    name = Component.translatable("rsi.plan.recipe_picker.spell_level", spellName, level).getString();
                }
            }
            StringBuilder search = new StringBuilder(id.toString()).append(' ').append(name);
            for (ItemStack input : inputs) {
                search.append(' ').append(input.getHoverName().getString());
                try {
                    for (Component line : input.getTooltipLines(mc.player, TooltipFlag.Default.NORMAL)) {
                        search.append(' ').append(line.getString());
                    }
                } catch (RuntimeException | LinkageError ignoredTooltip) {
                    // 个别第三方物品的提示依赖菜单状态，不影响整个候选面板。
                }
            }
            search.append(' ').append(output.getHoverName().getString());
            return new CandidateDetails(name, search.toString());
        });
    }

    /** 在候选行的固定区域中展示 JEI 布局，返回鼠标下的材料用于未缩放的提示。 */
    public ItemStack renderRecipeInArea(GuiGraphics graphics, Font font, ResourceLocation id,
                                       int x, int y, int width, int height, int mouseX, int mouseY,
                                       ItemStack fallback) {
        Optional<IRecipeLayoutDrawable<?>> layout = getDrawable(id);
        if (layout.isPresent()) {
            var drawable = layout.get();
            int recipeW = Math.max(1, drawable.getRect().getWidth());
            int recipeH = Math.max(1, drawable.getRect().getHeight());
            float scale = Math.min(1f, Math.min(width / (float) recipeW, height / (float) recipeH));
            int left = x + (int) ((width - recipeW * scale) / 2);
            int top = y + (int) ((height - recipeH * scale) / 2);
            int localX = (int) Math.floor((mouseX - left) / scale);
            int localY = (int) Math.floor((mouseY - top) / scale);
            drawable.setPosition(0, 0);
            graphics.pose().pushPose();
            try {
                graphics.pose().translate(left, top, 0);
                graphics.pose().scale(scale, scale, 1);
                drawable.drawRecipe(graphics, localX, localY);
            } finally {
                graphics.pose().popPose();
            }
            if (mouseX < x || mouseX >= x + width || mouseY < y || mouseY >= y + height) return ItemStack.EMPTY;
            ItemStack item = drawable.getIngredientUnderMouse(localX, localY, VanillaTypes.ITEM_STACK)
                    .orElse(ItemStack.EMPTY);
            if (!item.isEmpty()) return item;
            return drawable.getIngredientUnderMouse(localX, localY, ForgeTypes.FLUID_STACK)
                    .map(InkFluidSupport::token).orElse(ItemStack.EMPTY);
        }

        Recipe<?> recipe = resolveRecipe(id);
        if (recipe == null) {
            graphics.drawString(font, Component.translatable("rsi.plan.recipe_picker.no_preview"),
                    x + 2, y + 4, 0xFF8CA898, false);
            return ItemStack.EMPTY;
        }
        List<ItemStack> inputs = previewInputs(recipe);
        int columns = Math.max(1, Math.min(4, inputs.size()));
        int rows = Math.max(1, (inputs.size() + columns - 1) / columns);
        int naturalW = columns * 22 + 42;
        int naturalH = rows * 22;
        float scale = Math.min(1f, Math.min(width / (float) naturalW, height / (float) naturalH));
        int left = x + (int) ((width - naturalW * scale) / 2);
        int top = y + (int) ((height - naturalH * scale) / 2);
        double localX = (mouseX - left) / scale, localY = (mouseY - top) / scale;
        ItemStack hovered = ItemStack.EMPTY;
        graphics.pose().pushPose();
        try {
            graphics.pose().translate(left, top, 0);
            graphics.pose().scale(scale, scale, 1);
            for (int i = 0; i < inputs.size(); i++) {
                int slotX = (i % columns) * 22, slotY = (i / columns) * 22;
                renderPreviewSlot(graphics, font, inputs.get(i), slotX, slotY);
                if (localX >= slotX && localX < slotX + 18 && localY >= slotY && localY < slotY + 18) hovered = inputs.get(i);
            }
            int outputX = columns * 22 + 24, outputY = (naturalH - 18) / 2;
            graphics.drawString(font, "→", columns * 22 + 4, outputY + 4, 0xFFB8D1C0, false);
            ItemStack output = previewOutput(recipe, fallback);
            renderPreviewSlot(graphics, font, output, outputX, outputY);
            if (localX >= outputX && localX < outputX + 18 && localY >= outputY && localY < outputY + 18) hovered = output;
        } finally {
            graphics.pose().popPose();
        }
        return hovered;
    }

    private static void renderPreviewSlot(GuiGraphics graphics, Font font, ItemStack stack, int x, int y) {
        graphics.fill(x, y, x + 18, y + 18, 0xFF4C8060);
        graphics.fill(x + 1, y + 1, x + 17, y + 17, 0xFF14251B);
        InkFluidRenderer.render(graphics, stack, x + 1, y + 1);
        if (stack.getCount() > 1) {
            String count = Integer.toString(stack.getCount());
            graphics.drawString(font, count, x + 18 - font.width(count), y + 10, 0xFFFFFFFF, true);
        }
    }

    private static List<ItemStack> previewInputs(Recipe<?> recipe) {
        if (recipe instanceof IronSpellBooksRecipe iron) {
            List<ItemStack> inputs = new ArrayList<>(iron.inputs());
            if (iron.isScrollRecycling()) inputs.add(InkFluidSupport.token(new FluidStack(Fluids.WATER, InkFluidSupport.BOTTLE_AMOUNT)));
            return inputs;
        }
        List<ItemStack> inputs = new ArrayList<>();
        for (Ingredient ingredient : recipe.getIngredients()) {
            ItemStack[] items = ingredient.getItems();
            if (items.length > 0) inputs.add(items[0]);
        }
        return inputs;
    }

    private ItemStack previewOutput(Recipe<?> recipe, ItemStack fallback) {
        ItemStack output = recipe.getResultItem(mc.level.registryAccess());
        if (output.isEmpty()) output = ModRecipeHandlers.tryGetResultItem(recipe, mc.level.registryAccess());
        return output.isEmpty() ? fallback : output;
    }
}
