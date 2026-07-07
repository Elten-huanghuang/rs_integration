package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.command.PerformanceMonitor;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes recipe steps: material planning and result registration.
 * Delegates sub-ingredient resolution back to {@link CraftingResolver#ensureIngredient}.
 */
final class StepExecutor {

    private StepExecutor() {}

    static int maxDepth() { return RSIntegrationConfig.CRAFTING_MAX_DEPTH.get(); }
    static int maxSteps() { return RSIntegrationConfig.CRAFTING_MAX_STEPS.get(); }

    static boolean craftBatched(CraftingRecipe recipe, ResolutionContext ctx, int depth,
                                List<ResourceLocation> altIds, List<String> altModTypes,
                                CraftingResolver.EdgeTracker edges, int batches) {
        if (ctx.timedOut()) {
            PerformanceMonitor.recordResolveTimeout();
            return false;
        }
        if (depth > maxDepth()) return false;
        if (ctx.steps.size() + 1 > maxSteps()) return false;

        ctx.beginUndo();
        edges.beginUndo();

        if (!planRecipeIngredients(recipe, ctx, depth + 1, edges, batches)) {
            ctx.rollback();
            edges.rollback();
            return false;
        }

        for (ItemStack remainder : CraftPacketUtils.getRecipeRemainders(recipe)) {
            ctx.add(remainder.copyWithCount(remainder.getCount() * batches));
        }

        ItemStack result = recipe.getResultItem(ctx.level.registryAccess());
        ctx.add(result.copyWithCount(result.getCount() * batches));

        for (ItemStack secondary : ModRecipeHandlers.tryGetSecondaryOutputs(recipe, ctx.level.registryAccess())) {
            ctx.add(secondary.copyWithCount(secondary.getCount() * batches));
        }

        ctx.steps.add(new CraftingResolver.ResolutionStep(recipe.getId(), ModType.GENERIC,
                new ResourceLocation("minecraft:crafting"), altIds, altModTypes, false, batches));

        ctx.commitUndo();
        edges.commitUndo();
        return true;
    }

    static boolean craftBatched(RecipeIndex.Entry entry, ResolutionContext ctx, int depth,
                                List<ResourceLocation> altIds, List<String> altModTypes,
                                CraftingResolver.EdgeTracker edges, int batches) {
        if (ctx.timedOut()) {
            PerformanceMonitor.recordResolveTimeout();
            return false;
        }
        if (depth > maxDepth()) return false;
        if (ctx.steps.size() + 1 > maxSteps()) return false;

        if (entry.modType() == ModType.GENERIC && entry.recipe() instanceof CraftingRecipe cr) {
            return craftBatched(cr, ctx, depth, altIds, altModTypes, edges, batches);
        }

        ctx.beginUndo();
        edges.beginUndo();

        List<IngredientSpec> specs = CraftPacketUtils.extractIngredientSpecs(entry.recipe());

        // If the standard ingredient path returned garbage (AIR, bare items
        // without NBT from TACZ/Applied Armorer), repair via reflection.
        boolean broken = specs == null || specs.isEmpty();
        if (!broken) {
            for (IngredientSpec spec : specs) {
                if (spec.isEmpty()) continue;
                for (ItemStack stack : spec.ingredient().getItems()) {
                    if (stack.isEmpty()) continue;
                    if (stack.getItem() == net.minecraft.world.item.Items.AIR) { broken = true; break; }
                    ResourceLocation rl = ForgeRegistries.ITEMS.getKey(stack.getItem());
                    if (rl != null && !stack.hasTag()) {
                        String ns = rl.getNamespace();
                        if ("tacz".equals(ns) || "applied_armorer".equals(ns)) { broken = true; break; }
                    }
                }
                if (broken) break;
            }
        }
        if (broken) {
            List<ItemStack> repaired = CraftingResolver.getRepairedInputStacks(
                    entry.recipe(), ctx.level.registryAccess());
            if (!repaired.isEmpty()) {
                specs = new ArrayList<>();
                for (ItemStack stack : repaired) {
                    specs.add(new IngredientSpec(
                            CraftingResolver.ingredientOf(stack, stack.hasTag()), stack.getCount()));
                }
            }
        }

        if (specs == null || specs.isEmpty()) {
            ctx.rollback();
            edges.rollback();
            return false;
        }

        if (!planRecipeIngredients(specs, ctx, depth + 1, edges, batches)) {
            ctx.rollback();
            edges.rollback();
            return false;
        }

        if (entry.recipe() instanceof CraftingRecipe cr) {
            for (ItemStack remainder : CraftPacketUtils.getRecipeRemainders(cr)) {
                ctx.add(remainder.copyWithCount(remainder.getCount() * batches));
            }
        } else {
            for (IngredientSpec spec : specs) {
                if (spec.isEmpty()) continue;
                addCraftingRemainder(spec.ingredient(), spec.count() * batches, ctx);
            }
        }

        var handler = ModRecipeHandlers.handlerFor(entry.recipe());
        ItemStack result = ItemStack.EMPTY;
        if (handler != null) {
            result = handler.getResultItem(entry.recipe(), ctx.level.registryAccess());
        }
        if (result.isEmpty()) {
            result = ModRecipeHandlers.tryGetResultItem(entry.recipe(), ctx.level.registryAccess());
        }
        if (result.isEmpty()) {
            RSIntegrationMod.LOGGER.warn("[RSI] craftBatched: empty result for recipe {} (handler={})",
                    entry.recipe().getId(), handler != null ? handler.getClass().getSimpleName() : "null");
        }
        // If the result is bare (no NBT), scan fields for the real
        // NBT-carrying output hidden by the mod author.
        if (!result.isEmpty() && !result.hasTag()) {
            ItemStack hidden = CraftingResolver.extractHiddenOutput(entry.recipe());
            if (!hidden.isEmpty()) {
                result = hidden;
            }
        }
        ctx.add(result.copyWithCount(result.getCount() * batches));

        if (handler != null) {
            for (ItemStack secondary : handler.getSecondaryOutputs(entry.recipe(), ctx.level.registryAccess())) {
                ctx.add(secondary.copyWithCount(secondary.getCount() * batches));
            }
        } else {
            for (ItemStack secondary : ModRecipeHandlers.tryGetSecondaryOutputs(entry.recipe(), ctx.level.registryAccess())) {
                ctx.add(secondary.copyWithCount(secondary.getCount() * batches));
            }
        }

        ctx.steps.add(new CraftingResolver.ResolutionStep(entry.recipe().getId(), entry.modType(),
                entry.recipeTypeId(), altIds, altModTypes, false, batches));

        ctx.commitUndo();
        edges.commitUndo();
        return true;
    }

    static boolean planRecipeIngredients(CraftingRecipe recipe, ResolutionContext ctx, int depth,
                                         CraftingResolver.EdgeTracker edges, int batches) {
        if (depth > maxDepth()) return false;
        if (ctx.steps.size() + batches > maxSteps()) return false;

        ctx.beginUndo();
        edges.beginUndo();

        Map<CraftingResolver.StackKey, Integer> grouped = new LinkedHashMap<>();
        Map<CraftingResolver.StackKey, Ingredient> representatives = new HashMap<>();

        for (Ingredient ing : recipe.getIngredients()) {
            if (ing.isEmpty()) continue;
            ItemStack[] items = ing.getItems();
            if (items.length == 0) continue;

            CraftingResolver.StackKey key = CraftingResolver.StackKey.of(items[0], items[0].hasTag());
            grouped.merge(key, 1, Integer::sum);
            representatives.merge(key, ing, (prev, next) ->
                    next.getItems().length >= prev.getItems().length ? next : prev);
        }

        for (Map.Entry<CraftingResolver.StackKey, Integer> entry : grouped.entrySet()) {
            Ingredient ing = representatives.get(entry.getKey());
            if (!CraftingResolver.ensureIngredient(ing, entry.getValue() * batches, ctx, depth + 1, edges)) {
                ctx.rollback();
                edges.rollback();
                return false;
            }
        }
        ctx.commitUndo();
        edges.commitUndo();
        return true;
    }

    static boolean planRecipeIngredients(List<IngredientSpec> specs, ResolutionContext ctx, int depth,
                                         CraftingResolver.EdgeTracker edges, int batches) {
        if (depth > maxDepth()) return false;
        if (ctx.steps.size() + batches > maxSteps()) return false;

        ctx.beginUndo();
        edges.beginUndo();

        Map<CraftingResolver.StackKey, Integer> grouped = new LinkedHashMap<>();
        Map<CraftingResolver.StackKey, Ingredient> representatives = new HashMap<>();

        for (IngredientSpec spec : specs) {
            if (spec.isEmpty()) continue;
            ItemStack[] items = spec.ingredient().getItems();
            if (items.length == 0) continue;

            CraftingResolver.StackKey key = CraftingResolver.StackKey.of(items[0], items[0].hasTag());
            grouped.merge(key, spec.count(), Integer::sum);
            representatives.merge(key, spec.ingredient(), (prev, next) ->
                    next.getItems().length >= prev.getItems().length ? next : prev);
        }

        for (Map.Entry<CraftingResolver.StackKey, Integer> entry : grouped.entrySet()) {
            Ingredient ing = representatives.get(entry.getKey());
            if (!CraftingResolver.ensureIngredient(ing, entry.getValue() * batches, ctx, depth + 1, edges)) {
                ctx.rollback();
                edges.rollback();
                return false;
            }
        }
        ctx.commitUndo();
        edges.commitUndo();
        return true;
    }

    static void addCraftingRemainder(Ingredient ingredient, int count, ResolutionContext ctx) {
        for (ItemStack stack : ingredient.getItems()) {
            if (stack.isEmpty()) continue;
            try {
                ItemStack remainder = stack.getCraftingRemainingItem();
                if (!remainder.isEmpty()) {
                    ctx.add(remainder.copyWithCount(count));
                    return;
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.debug("[RSI] getCraftingRemainingItem probe failed", e);
            }
        }
    }
}
