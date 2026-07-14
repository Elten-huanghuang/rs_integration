package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.recipe.SlashBladeRecipeHandler;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.common.crafting.StrictNBTIngredient;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;

/**
 * Finds and ranks candidate recipes for a given ingredient.
 */
final class CandidateEngine {

    private CandidateEngine() {}

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
        long p1Start = System.nanoTime();
        ItemStack[] items = ingredient.getItems();
        long itemsMs = (System.nanoTime() - p1Start) / 1_000_000;

        // Diagnostic: Tag ingredient audit
        boolean isTag = items.length > 1;
        int itemsWithRecipes = 0;
        Set<ResourceLocation> firstItems = new LinkedHashSet<>();
        for (int s = 0; s < Math.min(items.length, 10); s++) {
            if (!items[s].isEmpty()) firstItems.add(ForgeRegistries.ITEMS.getKey(items[s].getItem()));
        }

        long loopStart = System.nanoTime();
        for (int idx = 0; idx < items.length; idx++) {
            ItemStack stack = items[idx];
            if (stack.isEmpty()) continue;
            Item item = stack.getItem();

            List<RecipeIndex.Entry> recipes = ctx.index.get(item);
            if (recipes == null) {
                if (diag != null) logDiag(diag, item, null, 0, null, true, "No recipes indexed for this item");
                continue;
            }
            itemsWithRecipes++;

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
        long loopMs = (System.nanoTime() - loopStart) / 1_000_000;

        // Diagnostic: log Tag ingredient candidate search summary
        if (isTag) {
            RSIntegrationMod.LOGGER.debug(
                    "[RSI-Candidate] Phase1: getItems={}ms, loop={}ms, {} items, {} recipes, {} unique candidates, first items: {}",
                    itemsMs, loopMs, items.length, items.length, byId.size(), firstItems);
        }

        boolean nbtStrict = ingredient instanceof StrictNBTIngredient;
        boolean ingredientAllNbt = !nbtStrict && allItemsHaveNbt(ingredient);
        Map<ResourceLocation, RecipeIndex.Entry> dedup = new LinkedHashMap<>();

        // Phase 2: validate outputs.  Vanilla CraftingRecipe entries are
        // instant (getResultItem is pre-computed); mod recipes go through
        // ModRecipeHandlers.tryGetResultItem() which uses a global cache.
        // Both contribute to the candidate pool so players with mod-only
        // ingredients (e.g. Botany glass but no vanilla glass) can still
        // resolve recipes like glass → glass_pane.
        long phase2Start = System.nanoTime();
        int vanillaCount = 0;
        for (RecipeIndex.Entry entry : byId.values()) {
            if (ctx.timedOut()) break;
            if (!(entry.recipe() instanceof CraftingRecipe cr)) continue;
            vanillaCount++;
            ItemStack output = cr.getResultItem(ctx.level.registryAccess());
            if (passesOutputCheck(entry, output, ingredient, ingredientAllNbt, nbtStrict, diag)) {
                dedup.put(entry.recipe().getId(), entry);
            }
        }
        int modCount = 0;
        for (RecipeIndex.Entry entry : byId.values()) {
            if (ctx.timedOut()) break;
            if (entry.recipe() instanceof CraftingRecipe) continue;
            modCount++;
            ItemStack output = ModRecipeHandlers.tryGetResultItem(entry.recipe(), ctx.level.registryAccess());
            if (passesOutputCheck(entry, output, ingredient, ingredientAllNbt, nbtStrict, diag)) {
                dedup.put(entry.recipe().getId(), entry);
            }
        }
        long phase2Elapsed = System.nanoTime() - phase2Start;

        if (isTag) {
            RSIntegrationMod.LOGGER.debug(
                    "[RSI-Candidate] Phase2: {} vanilla + {} mod recipes in {}ms, dedup={}",
                    vanillaCount, modCount, phase2Elapsed / 1_000_000, dedup.size());
        }

        List<RecipeIndex.Entry> result = new ArrayList<>(dedup.values());

        // Pre-compute score and availability before sorting so each entry is
        // evaluated exactly once — NOT O(N log N) times inside the comparator.
        // Use an ingredient→count cache so countMatching (which iterates all
        // 546+ inventory item types) is called at most once per unique ingredient.
        Map<Ingredient, Integer> matchCache = new HashMap<>();
        Map<ResourceLocation, Integer> scoreCache = new HashMap<>();
        Map<ResourceLocation, Integer> availCache = new HashMap<>();
        for (RecipeIndex.Entry entry : result) {
            if (ctx.timedOut()) break; // don't burn remaining budget on scoring
            scoreCache.put(entry.recipe().getId(), scoreEntry(entry, ctx, nbtStrict, matchCache));
            availCache.put(entry.recipe().getId(), countAvailableIngredients(entry, ctx, matchCache));
        }

        long sortStart = System.nanoTime();
        result.sort((a, b) -> {
            ResourceLocation idA = a.recipe().getId();
            ResourceLocation idB = b.recipe().getId();

            int cmp = Integer.compare(scoreCache.get(idB), scoreCache.get(idA));
            if (cmp != 0) return cmp;

            // Tiebreak: prefer recipes whose ingredients are available
            cmp = Integer.compare(availCache.get(idB), availCache.get(idA));
            if (cmp != 0) return cmp;

            // Final tiebreak: prefer vanilla/minecraft recipes
            boolean aMc = "minecraft".equals(idA.getNamespace());
            boolean bMc = "minecraft".equals(idB.getNamespace());
            if (aMc && !bMc) return -1;
            if (bMc && !aMc) return 1;
            return 0;
        });

        if (isTag) {
            long sortElapsed = System.nanoTime() - sortStart;
            RSIntegrationMod.LOGGER.debug(
                    "[RSI-Candidate] Phase2 sort: {} entries in {}ms",
                    result.size(), sortElapsed / 1_000_000);
        }

        if (diag != null) {
            for (RecipeIndex.Entry e : result) {
                int s = scoreEntry(e, ctx, nbtStrict, null);
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

    private static int scoreEntry(RecipeIndex.Entry entry, ResolutionContext ctx, boolean nbtStrict,
                                    @javax.annotation.Nullable Map<Ingredient, Integer> matchCache) {
        if (entry.recipe() instanceof CraftingRecipe cr) {
            return scoreRecipe(cr, ctx, nbtStrict, matchCache) + (entry.modType() == ModType.GENERIC ? 10 : 0);
        }
        int score = 0;
        if (entry.modType() == ModType.GENERIC) score += 10;
        if (nbtStrict && entry.nbtSensitive()) score += 5;
        List<IngredientSpec> specs = CraftPacketUtils.extractIngredientSpecs(entry.recipe());
        if (specs != null) {
            int nonEmpty = 0;
            for (IngredientSpec spec : specs) {
                if (spec.isEmpty()) continue;
                nonEmpty++;
                if (cachedCountMatching(ctx, spec.ingredient(), matchCache) > 0) {
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
            // Only award the output-count bonus when this recipe's ingredients
            // are actually present — otherwise decompression recipes (block→4 bars)
            // outscore direct recipes (beeswax_block→1 bar) even when their
            // input must itself be crafted from the output, creating a circular
            // dependency that forces the resolver into a sub-optimal path.
            if (output.getCount() > 1 && allIngredientsAvailable(entry, ctx, matchCache)) {
                score += cappedOutputBonus(output.getCount());
            }
        }
        return score;
    }

    private static int scoreRecipe(CraftingRecipe recipe, ResolutionContext ctx, boolean nbtStrict,
                                     @javax.annotation.Nullable Map<Ingredient, Integer> matchCache) {
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
            if (cachedCountMatching(ctx, ing, matchCache) > 0) {
                score += 10;
            }
        }

        score -= recipe.getIngredients().size();

        ItemStack output = recipe.getResultItem(ctx.level.registryAccess());
        if (output.getCount() > 1) {
            // Gate output bonus behind ingredient availability for the same
            // reason as the mod-recipe path: avoid over-ranking decompression
            // recipes when their inputs are themselves craftable only via the
            // output they produce.
            boolean allAvail = true;
            for (Ingredient ing : recipe.getIngredients()) {
                if (!ing.isEmpty() && cachedCountMatching(ctx, ing, matchCache) <= 0) {
                    allAvail = false;
                    break;
                }
            }
            if (allAvail) {
                score += cappedOutputBonus(output.getCount());
            }
        }

        return score;
    }

    private static int cachedCountMatching(ResolutionContext ctx, Ingredient ing,
                                           @javax.annotation.Nullable Map<Ingredient, Integer> cache) {
        if (cache == null) return ctx.countMatching(ing);
        return cache.computeIfAbsent(ing, ctx::countMatching);
    }

    /** Cap the per-batch output bonus so decompression recipes
     *  (e.g. 1 block → 9 items) don't dominate direct recipes
     *  (e.g. 1 calx + 1 dust → 2 calx).  Max +15 keeps the
     *  bonus smaller than two ingredient-availability checks. */
    private static int cappedOutputBonus(int outputCount) {
        return Math.min((outputCount - 1) * 5, 15);
    }

    /** Returns true when every non-empty ingredient has at least one matching item available. */
    private static boolean allIngredientsAvailable(RecipeIndex.Entry entry, ResolutionContext ctx,
                                                    @javax.annotation.Nullable Map<Ingredient, Integer> matchCache) {
        if (entry.recipe() instanceof CraftingRecipe cr) {
            for (Ingredient ing : cr.getIngredients()) {
                if (!ing.isEmpty() && cachedCountMatching(ctx, ing, matchCache) <= 0) return false;
            }
            return true;
        }
        List<IngredientSpec> specs = CraftPacketUtils.extractIngredientSpecs(entry.recipe());
        if (specs == null) return false;
        for (IngredientSpec spec : specs) {
            if (!spec.isEmpty() && cachedCountMatching(ctx, spec.ingredient(), matchCache) <= 0) return false;
        }
        return true;
    }

    /** Count how many distinct ingredients of a recipe have matching items available. */
    private static int countAvailableIngredients(RecipeIndex.Entry entry, ResolutionContext ctx,
                                                   @javax.annotation.Nullable Map<Ingredient, Integer> matchCache) {
        if (entry.recipe() instanceof CraftingRecipe cr) {
            int c = 0;
            for (Ingredient ing : cr.getIngredients()) {
                if (!ing.isEmpty() && cachedCountMatching(ctx, ing, matchCache) > 0) c++;
            }
            return c;
        }
        List<IngredientSpec> specs = CraftPacketUtils.extractIngredientSpecs(entry.recipe());
        if (specs == null) return 0;
        int c = 0;
        for (IngredientSpec spec : specs) {
            if (!spec.isEmpty() && cachedCountMatching(ctx, spec.ingredient(), matchCache) > 0) c++;
        }
        return c;
    }

    private static boolean passesOutputCheck(RecipeIndex.Entry entry, ItemStack output,
                                              Ingredient ingredient, boolean ingredientAllNbt,
                                              boolean nbtStrict,
                                              @javax.annotation.Nullable List<CandidateDiagnostic> diag) {
        if (output.isEmpty() || !ingredient.test(output)) {
            boolean slashBladeChain = false;
            if (SlashBladeRecipeHandler.isSlashBladeIngredient(ingredient) && !output.isEmpty()) {
                for (ItemStack ingItem : ingredient.getItems()) {
                    if (!ingItem.isEmpty() && ingItem.getItem() == output.getItem()) {
                        slashBladeChain = true;
                        break;
                    }
                }
            }
            if (!slashBladeChain) {
                if (diag != null) logDiag(diag, null, entry, 0, entry.modType(), true, "Output does not match ingredient");
                return false;
            }
        }
        if (ingredientAllNbt && output.hasTag() && !anyIngredientItemMatchesNbt(ingredient, output)) {
            if (diag != null) logDiag(diag, null, entry, 0, entry.modType(), true, "Output NBT does not match ingredient");
            return false;
        }
        return true;
    }

    private static boolean allItemsHaveNbt(Ingredient ingredient) {
        for (ItemStack stack : ingredient.getItems()) {
            if (stack.isEmpty()) continue;
            if (!stack.hasTag()) return false;
        }
        return true;
    }

    private static boolean anyIngredientItemMatchesNbt(Ingredient ingredient, ItemStack output) {
        for (ItemStack stack : ingredient.getItems()) {
            if (!stack.isEmpty() && ItemStack.isSameItemSameTags(stack, output)) return true;
        }
        return false;
    }
}
