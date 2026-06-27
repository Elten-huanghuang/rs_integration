package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes a single recipe step: material planning and result registration.
 * Delegates sub-ingredient resolution back to {@link CraftingResolver#ensureIngredient}.
 */
final class StepExecutor {

    private StepExecutor() {}

    static final int MAX_DEPTH = 8;
    static final int MAX_STEPS = 384;

    static boolean craftOnce(CraftingRecipe recipe, ResolutionContext ctx, int depth,
                             List<ResourceLocation> altIds, List<String> altModTypes) {
        if (ctx.timedOut()) return false;
        if (depth > MAX_DEPTH) return false;
        if (ctx.steps.size() >= MAX_STEPS) return false;

        ctx.beginUndo();

        if (!planRecipeIngredients(recipe, ctx, depth + 1)) {
            ctx.rollback();
            return false;
        }

        for (ItemStack remainder : CraftPacketUtils.getRecipeRemainders(recipe)) {
            ctx.add(remainder);
        }

        ItemStack result = recipe.getResultItem(ctx.level.registryAccess());
        ctx.add(result);

        for (ItemStack secondary : ModRecipeHandlers.tryGetSecondaryOutputs(recipe, ctx.level.registryAccess())) {
            ctx.add(secondary);
        }

        ctx.steps.add(new CraftingResolver.ResolutionStep(recipe.getId(), ModType.GENERIC,
                new ResourceLocation("minecraft:crafting"), altIds, altModTypes));

        ctx.commitUndo();
        return true;
    }

    static boolean craftOnce(RecipeIndex.Entry entry, ResolutionContext ctx, int depth,
                             List<ResourceLocation> altIds, List<String> altModTypes) {
        if (ctx.timedOut()) return false;
        if (depth > MAX_DEPTH) return false;
        if (ctx.steps.size() >= MAX_STEPS) return false;

        if (entry.modType() == ModType.GENERIC && entry.recipe() instanceof CraftingRecipe cr) {
            return craftOnce(cr, ctx, depth, altIds, altModTypes);
        }

        ctx.beginUndo();
        List<IngredientSpec> specs = CraftPacketUtils.extractIngredientSpecs(entry.recipe());
        if (specs == null || specs.isEmpty()) {
            ctx.rollback();
            return false;
        }

        if (!planRecipeIngredients(specs, ctx, depth + 1)) {
            ctx.rollback();
            return false;
        }

        if (entry.recipe() instanceof CraftingRecipe cr) {
            for (ItemStack remainder : CraftPacketUtils.getRecipeRemainders(cr)) {
                ctx.add(remainder);
            }
        } else {
            for (IngredientSpec spec : specs) {
                if (spec.isEmpty()) continue;
                addCraftingRemainder(spec.ingredient(), ctx);
            }
        }

        var handler = ModRecipeHandlers.handlerFor(entry.recipe());
        ItemStack result = handler != null
                ? handler.getResultItem(entry.recipe(), ctx.level.registryAccess())
                : ModRecipeHandlers.tryGetResultItem(entry.recipe(), ctx.level.registryAccess());
        ctx.add(result);

        if (handler != null) {
            for (ItemStack secondary : handler.getSecondaryOutputs(entry.recipe(), ctx.level.registryAccess())) {
                ctx.add(secondary);
            }
        } else {
            for (ItemStack secondary : ModRecipeHandlers.tryGetSecondaryOutputs(entry.recipe(), ctx.level.registryAccess())) {
                ctx.add(secondary);
            }
        }

        ctx.steps.add(new CraftingResolver.ResolutionStep(entry.recipe().getId(), entry.modType(),
                entry.recipeTypeId(), altIds, altModTypes));

        ctx.commitUndo();
        return true;
    }

    static boolean planRecipeIngredients(CraftingRecipe recipe, ResolutionContext ctx, int depth) {
        if (depth > MAX_DEPTH) return false;
        if (ctx.steps.size() >= MAX_STEPS) return false;

        ctx.beginUndo();

        Map<Item, Integer> grouped = new LinkedHashMap<>();
        Map<Item, Ingredient> representatives = new HashMap<>();

        for (Ingredient ing : recipe.getIngredients()) {
            if (ing.isEmpty()) continue;
            ItemStack[] items = ing.getItems();
            if (items.length == 0) continue;
            Item item = items[0].getItem();
            grouped.merge(item, 1, Integer::sum);
            // Prefer broader (tag-based) Ingredient as representative
            // so ensureIngredient doesn't fail on exact-match mismatch
            // when multiple Ingredient types share the same first item.
            representatives.merge(item, ing, (prev, next) ->
                    next.getItems().length >= prev.getItems().length ? next : prev);
        }

        for (Map.Entry<Item, Integer> entry : grouped.entrySet()) {
            Ingredient ing = representatives.get(entry.getKey());
            if (!CraftingResolver.ensureIngredient(ing, entry.getValue(), ctx, depth + 1)) {
                ctx.rollback();
                return false;
            }
        }
        ctx.commitUndo();
        return true;
    }

    static boolean planRecipeIngredients(List<IngredientSpec> specs, ResolutionContext ctx, int depth) {
        if (depth > MAX_DEPTH) return false;
        if (ctx.steps.size() >= MAX_STEPS) return false;

        ctx.beginUndo();

        Map<Item, Integer> grouped = new LinkedHashMap<>();
        Map<Item, Ingredient> representatives = new HashMap<>();

        for (IngredientSpec spec : specs) {
            if (spec.isEmpty()) continue;
            ItemStack[] items = spec.ingredient().getItems();
            if (items.length == 0) continue;
            Item item = items[0].getItem();
            grouped.merge(item, spec.count(), Integer::sum);
            representatives.merge(item, spec.ingredient(), (prev, next) ->
                    next.getItems().length >= prev.getItems().length ? next : prev);
        }

        for (Map.Entry<Item, Integer> entry : grouped.entrySet()) {
            Ingredient ing = representatives.get(entry.getKey());
            if (!CraftingResolver.ensureIngredient(ing, entry.getValue(), ctx, depth + 1)) {
                ctx.rollback();
                return false;
            }
        }
        ctx.commitUndo();
        return true;
    }

    static void addCraftingRemainder(Ingredient ingredient, ResolutionContext ctx) {
        for (ItemStack stack : ingredient.getItems()) {
            if (stack.isEmpty()) continue;
            try {
                ItemStack remainder = stack.getCraftingRemainingItem();
                if (!remainder.isEmpty()) {
                    ctx.add(remainder.copyWithCount(1));
                    return;
                }
            } catch (Throwable e) {
                RSIntegrationMod.LOGGER.debug("[RSI] getCraftingRemainingItem probe failed", e);
            }
        }
    }
}
