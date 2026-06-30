package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.network.AltarBindingRegistry;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;

/**
 * Finds and ranks candidate recipes for a given ingredient.
 */
final class CandidateEngine {

    private CandidateEngine() {}

    private static final int INGREDIENT_SCAN_LIMIT = 64;
    private static final int PREFERRED_RECIPE_BONUS = 10000;

    public record CandidateDiagnostic(ResourceLocation recipeId, int score, ModType modType,
                                       boolean skipped, String skipReason) {}

    /**
     * Returns recipes whose output matches the ingredient, sorted by score (highest first).
     */
    static List<RecipeIndex.Entry> findCandidates(Ingredient ingredient, ResolutionContext ctx) {
        return findCandidates(ingredient, ctx, null);
    }

    /**
     * Same as above, but fills {@code diag} with per-candidate scoring/skip info.
     */
    static List<RecipeIndex.Entry> findCandidates(Ingredient ingredient, ResolutionContext ctx,
                                                   @javax.annotation.Nullable List<CandidateDiagnostic> diag) {
        // Phase 1: collect unique entries by recipe ID (cheap — no getResultItem calls)
        Map<ResourceLocation, RecipeIndex.Entry> byId = new LinkedHashMap<>();
        ItemStack[] items = ingredient.getItems();
        int limit = Math.min(items.length, INGREDIENT_SCAN_LIMIT);
        for (int idx = 0; idx < limit; idx++) {
            ItemStack stack = items[idx];
            if (stack.isEmpty()) continue;
            Item item = stack.getItem();

            List<RecipeIndex.Entry> recipes = ctx.index.get(item);
            if (recipes == null) {
                if (diag != null) logDiag(diag, item, null, 0, null, true, "No recipes indexed for this item");
                continue;
            }

            for (RecipeIndex.Entry entry : recipes) {
                ResourceLocation rid = entry.recipe().getId();
                if (byId.containsKey(rid)) continue; // already collected
                if (entry.modType() != ModType.GENERIC) {
                    if (!isMachineAvailable(entry, ctx)) {
                        if (diag != null) logDiag(diag, item, entry, 0, entry.modType(), true, "Machine not available");
                        continue;
                    }
                    var allowlist = RSIntegrationConfig.MULTIBLOCK_RECIPE_ALLOWLIST.get();
                    if (!allowlist.isEmpty() && !allowlist.contains(rid.toString())) {
                        if (diag != null) logDiag(diag, item, entry, 0, entry.modType(), true, "Not in allowlist");
                        continue;
                    }
                    if (RSIntegrationConfig.MULTIBLOCK_RECIPE_BLACKLIST.get().contains(rid.toString())) {
                        if (diag != null) logDiag(diag, item, entry, 0, entry.modType(), true, "Blocklisted");
                        continue;
                    }
                }
                byId.put(rid, entry);
            }
        }

        // Phase 2: check output matching for each unique recipe (expensive calls once per recipe)
        Map<ResourceLocation, RecipeIndex.Entry> dedup = new LinkedHashMap<>();
        for (RecipeIndex.Entry entry : byId.values()) {
            ItemStack output;
            if (entry.recipe() instanceof CraftingRecipe cr) {
                output = cr.getResultItem(ctx.level.registryAccess());
            } else {
                output = ModRecipeHandlers.tryGetResultItem(entry.recipe(), ctx.level.registryAccess());
            }
            if (output.isEmpty() || !ingredient.test(output)) {
                if (diag != null) logDiag(diag, null, entry, 0, entry.modType(), true, "Output does not match ingredient");
                continue;
            }
            dedup.put(entry.recipe().getId(), entry);
        }

        List<RecipeIndex.Entry> result = new ArrayList<>(dedup.values());
        result.sort((a, b) -> {
            int cmp = Integer.compare(scoreEntry(b, ctx), scoreEntry(a, ctx));
            if (cmp != 0) return cmp;
            // Tiebreak: prefer recipes whose ingredients are available
            cmp = Integer.compare(countAvailableIngredients(b, ctx),
                    countAvailableIngredients(a, ctx));
            if (cmp != 0) return cmp;
            // Final tiebreak: prefer vanilla/minecraft recipes
            boolean aMc = "minecraft".equals(a.recipe().getId().getNamespace());
            boolean bMc = "minecraft".equals(b.recipe().getId().getNamespace());
            if (aMc && !bMc) return -1;
            if (bMc && !aMc) return 1;
            return 0;
        });

        if (diag != null) {
            for (RecipeIndex.Entry e : result) {
                int s = scoreEntry(e, ctx);
                String pref = isPreferred(e, ctx) ? " (PREFERRED)" : "";
                logDiag(diag, null, e, s, e.modType(), false, "Score=" + s + pref);
            }
        }

        return result;
    }

    private static void logDiag(List<CandidateDiagnostic> diag, @javax.annotation.Nullable Item item,
                                 @javax.annotation.Nullable RecipeIndex.Entry entry, int score,
                                 @javax.annotation.Nullable ModType mt, boolean skipped, String reason) {
        ResourceLocation id = entry != null ? entry.recipe().getId() :
                (item != null ? ForgeRegistries.ITEMS.getKey(item) : null);
        if (id == null) id = new ResourceLocation("unknown", "unknown");
        diag.add(new CandidateDiagnostic(id, score, mt != null ? mt : ModType.GENERIC, skipped, reason));
    }

    private static boolean isMachineAvailable(RecipeIndex.Entry entry, ResolutionContext ctx) {
        if (entry.modType() == ModType.GENERIC) return true;
        if (ctx.player == null) return false;
        return AltarBindingRegistry.hasBindingForRecipe(ctx.player, entry.recipe());
    }

    private static boolean isPreferred(RecipeIndex.Entry entry, ResolutionContext ctx) {
        if (ctx.preferredRecipes == null) return false;
        ItemStack output = ModRecipeHandlers.tryGetResultItem(entry.recipe(), ctx.level.registryAccess());
        if (output.isEmpty()) return false;
        ResourceLocation itemKey = ForgeRegistries.ITEMS.getKey(output.getItem());
        if (itemKey == null) return false;
        ResourceLocation pref = ctx.preferredRecipes.get(itemKey);
        return pref != null && pref.equals(entry.recipe().getId());
    }

    private static int scoreEntry(RecipeIndex.Entry entry, ResolutionContext ctx) {
        if (entry.recipe() instanceof CraftingRecipe cr) {
            return scoreRecipe(cr, ctx) + (entry.modType() == ModType.GENERIC ? 10 : 0);
        }
        int score = 0;
        if (entry.modType() == ModType.GENERIC) score += 10;
        List<IngredientSpec> specs = CraftPacketUtils.extractIngredientSpecs(entry.recipe());
        if (specs != null) {
            int nonEmpty = 0;
            for (IngredientSpec spec : specs) {
                if (spec.isEmpty()) continue;
                nonEmpty++;
                if (ctx.countMatching(spec.ingredient()) > 0) {
                    score += 10;
                }
            }
            score -= nonEmpty;
        }
        ItemStack output = ModRecipeHandlers.tryGetResultItem(entry.recipe(), ctx.level.registryAccess());
        if (!output.isEmpty()) {
            if (isPreferred(entry, ctx)) {
                score += PREFERRED_RECIPE_BONUS;
            }
            if (output.getCount() > 1) {
                score += cappedOutputBonus(output.getCount());
            }
        }
        return score;
    }

    private static int scoreRecipe(CraftingRecipe recipe, ResolutionContext ctx) {
        int score = 0;
        ResourceLocation outputKey = ForgeRegistries.ITEMS.getKey(
                recipe.getResultItem(ctx.level.registryAccess()).getItem());
        if (outputKey != null && ctx.preferredRecipes != null) {
            ResourceLocation preferred = ctx.preferredRecipes.get(outputKey);
            if (preferred != null && preferred.equals(recipe.getId())) {
                score += PREFERRED_RECIPE_BONUS;
            }
        }

        for (Ingredient ing : recipe.getIngredients()) {
            if (ing.isEmpty()) continue;
            if (ctx.countMatching(ing) > 0) {
                score += 10;
            }
        }

        score -= recipe.getIngredients().size();

        ItemStack output = recipe.getResultItem(ctx.level.registryAccess());
        if (output.getCount() > 1) {
            score += cappedOutputBonus(output.getCount());
        }

        return score;
    }

    /** Cap the per-batch output bonus so decompression recipes
     *  (e.g. 1 block → 9 items) don't dominate direct recipes
     *  (e.g. 1 calx + 1 dust → 2 calx).  Max +15 keeps the
     *  bonus smaller than two ingredient-availability checks. */
    private static int cappedOutputBonus(int outputCount) {
        return Math.min((outputCount - 1) * 5, 15);
    }

    /** Count how many distinct ingredients of a recipe have matching items available. */
    private static int countAvailableIngredients(RecipeIndex.Entry entry, ResolutionContext ctx) {
        if (entry.recipe() instanceof CraftingRecipe cr) {
            int c = 0;
            for (Ingredient ing : cr.getIngredients()) {
                if (!ing.isEmpty() && ctx.countMatching(ing) > 0) c++;
            }
            return c;
        }
        List<IngredientSpec> specs = CraftPacketUtils.extractIngredientSpecs(entry.recipe());
        if (specs == null) return 0;
        int c = 0;
        for (IngredientSpec spec : specs) {
            if (!spec.isEmpty() && ctx.countMatching(spec.ingredient()) > 0) c++;
        }
        return c;
    }
}
