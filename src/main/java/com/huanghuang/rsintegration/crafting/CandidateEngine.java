package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import com.huanghuang.rsintegration.mods.goety.GoetyDynamicRitualRecipe;
import com.huanghuang.rsintegration.mods.farmersdelight.MinersDelightCopperPotSupport;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.recipe.SlashBladeRecipeHandler;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
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
    private record CandidateKey(ResourceLocation recipeId, String modTypeId) {
        static CandidateKey of(RecipeIndex.Entry entry) {
            return new CandidateKey(entry.recipe().getId(), entry.modType().id());
        }
    }

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
        // Shared recipes can have distinct machine routes and runtime outputs.
        Map<CandidateKey, RecipeIndex.Entry> byId = new LinkedHashMap<>();
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
            if (ctx.timedOut()) break;
            ItemStack stack = items[idx];
            if (stack.isEmpty()) continue;
            Item item = stack.getItem();

            List<RecipeIndex.Entry> recipes = semanticSpellRecipes(stack, ctx);
            if (recipes == null) recipes = ctx.index.get(item);
            if (recipes == null) {
                if (diag != null) logDiag(diag, item, null, 0, null, true, "No recipes indexed for this item");
                continue;
            }
            itemsWithRecipes++;

            for (RecipeIndex.Entry entry : recipes) {
                if (ctx.timedOut()) break;
                ResourceLocation rid = entry.recipe().getId();
                CandidateKey candidateKey = CandidateKey.of(entry);
                if (byId.containsKey(candidateKey)) continue;
                if (entry.modType() != ModType.GENERIC) {
                    var handler = ModRecipeHandlers.handlerFor(entry.recipe());
                    if (handler != null
                            && !handler.isAvailableForPlanning(entry.recipe(), ctx.player)) {
                        if (diag != null) logDiag(diag, item, entry, 0, entry.modType(), true,
                                "Execution context unavailable");
                        continue;
                    }
                    if (!isMachineAvailable(entry, ctx)) {
                        if (diag != null) logDiag(diag, item, entry, 0, entry.modType(), true, "Machine not available");
                        continue;
                    }
                    if (!entry.modType().isVirtual()) {
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
                }
                byId.put(candidateKey, entry);
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
        Map<CandidateKey, RecipeIndex.Entry> dedup = new LinkedHashMap<>();

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
            ItemStack output = outputForDemand(entry, ingredient, ctx);
            if (passesOutputCheck(entry, output, ingredient, ingredientAllNbt, nbtStrict, diag)) {
                boolean skipConversion = variantGuardEnabled()
                        && (NonProductiveTagConversionGuard.shouldSkip(ingredient, cr, output)
                        || ctx.shouldSkipActiveConversion(cr, output));
                if (skipConversion) {
                    ctx.diag("candidate SKIP " + entry.recipe().getId()
                            + ": non-productive tag conversion");
                    if (diag != null) logDiag(diag, null, entry, 0, entry.modType(), true,
                            "Non-productive tag conversion");
                    continue;
                }
                dedup.put(CandidateKey.of(entry), entry);
            }
        }
        int modCount = 0;
        for (RecipeIndex.Entry entry : byId.values()) {
            if (ctx.timedOut()) break;
            if (entry.recipe() instanceof CraftingRecipe) continue;
            modCount++;
            ItemStack output = outputForDemand(entry, ingredient, ctx);
            if (passesOutputCheck(entry, output, ingredient, ingredientAllNbt, nbtStrict, diag)) {
                dedup.put(CandidateKey.of(entry), entry);
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
        Map<CandidateKey, Integer> scoreCache = new HashMap<>();
        Map<CandidateKey, Integer> availCache = new HashMap<>();
        for (RecipeIndex.Entry entry : result) {
            if (ctx.timedOut()) break; // don't burn remaining budget on scoring
            CandidateKey key = CandidateKey.of(entry);
            scoreCache.put(key, scoreEntry(entry, ctx, nbtStrict, matchCache));
            availCache.put(key, countAvailableIngredients(entry, ctx, matchCache));
        }

        long sortStart = System.nanoTime();
        result.sort((a, b) -> {
            CandidateKey keyA = CandidateKey.of(a);
            CandidateKey keyB = CandidateKey.of(b);
            int cmp = Integer.compare(scoreCache.getOrDefault(keyB, 0),
                    scoreCache.getOrDefault(keyA, 0));
            if (cmp != 0) return cmp;
            cmp = Integer.compare(availCache.getOrDefault(keyB, 0),
                    availCache.getOrDefault(keyA, 0));
            if (cmp != 0) return cmp;
            cmp = a.recipe().getId().compareTo(b.recipe().getId());
            return cmp != 0 ? cmp : a.modType().id().compareTo(b.modType().id());
        });

        if (SpellScrollSelection.acceptsAnyScroll(ingredient)) {
            Map<ResourceLocation, Integer> scrollLevels = new HashMap<>();
            Map<ResourceLocation, Integer> scrollRarities = new HashMap<>();
            for (RecipeIndex.Entry entry : result) {
                ItemStack output = ModRecipeHandlers.tryGetResultItem(
                        entry.recipe(), ctx.level.registryAccess());
                scrollLevels.put(entry.recipe().getId(), SpellScrollSelection.level(output));
                scrollRarities.put(entry.recipe().getId(), SpellScrollSelection.rarity(output));
            }
            // List.sort is stable: recipes at the same rarity and level retain
            // the normal availability/material-cost score calculated above.
            result.sort(Comparator
                    .comparingInt((RecipeIndex.Entry entry) -> scrollRarities.getOrDefault(
                            entry.recipe().getId(), Integer.MAX_VALUE))
                    .thenComparingInt(entry -> scrollLevels.getOrDefault(
                            entry.recipe().getId(), Integer.MAX_VALUE)));
        }

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

    /** Resolves shared runtime recipes against the exact NBT-bearing item being requested. */
    static ItemStack outputForDemand(RecipeIndex.Entry entry, Ingredient demand,
                                     ResolutionContext ctx) {
        if (GoetyDynamicRitualRecipe.isSupported(entry.recipe())) {
            ItemStack dynamic = GoetyDynamicRitualRecipe.matchingOutput(entry.recipe(), demand);
            if (!dynamic.isEmpty()) return dynamic;
        }
        ItemStack output = ModRecipeHandlers.tryGetResultItem(
                entry.recipe(), ctx.level.registryAccess());
        output = MinersDelightCopperPotSupport.adaptResult(entry.modType(), output);
        return inheritSmithingBaseTag(entry.recipe(), output, demand, ctx);
    }

    static ItemStack inheritSmithingBaseTag(Recipe<?> recipe, ItemStack output,
                                            Ingredient demand, ResolutionContext ctx) {
        if (recipe instanceof net.minecraft.world.item.crafting.SmithingTransformRecipe smithing
                && IngredientMatcher.requiresNbt(demand)) {
            ItemStack inherited = findStockedSmithingOutput(smithing,
                    stack -> IngredientMatcher.test(demand, stack), ctx.counts, ctx.index,
                    ctx.level.registryAccess(), new HashSet<>(), ctx::timedOut);
            if (!inherited.isEmpty()) return inherited;
        }
        return inheritSmithingBaseTag(recipe, output, demand);
    }

    static ItemStack findStockedSmithingOutput(
            net.minecraft.world.item.crafting.SmithingTransformRecipe recipe,
            java.util.function.Predicate<ItemStack> accepts,
            Map<CraftingResolver.StackKey, Integer> available,
            Map<Item, List<RecipeIndex.Entry>> index, net.minecraft.core.RegistryAccess access,
            Set<ResourceLocation> visited, java.util.function.BooleanSupplier timedOut) {
        return findStockedSmithingOutput(recipe, accepts, new InventoryCandidateLookup(available),
                index, access, visited, timedOut);
    }

    static ItemStack findStockedSmithingOutput(
            net.minecraft.world.item.crafting.SmithingTransformRecipe recipe,
            java.util.function.Predicate<ItemStack> accepts,
            InventoryCandidateLookup available,
            Map<Item, List<RecipeIndex.Entry>> index, net.minecraft.core.RegistryAccess access,
            Set<ResourceLocation> visited, java.util.function.BooleanSupplier timedOut) {
        if (timedOut.getAsBoolean() || visited.size() >= 64 || !visited.add(recipe.getId())) {
            return ItemStack.EMPTY;
        }
        var handler = new com.huanghuang.rsintegration.mods.vanilla.SmithingRecipeHandler();
        var specs = recipe.getClass() == net.minecraft.world.item.crafting.SmithingTransformRecipe.class
                ? handler.getIngredients(recipe) : null;
        var stockKeys = specs != null && specs.size() == 3
                ? available.keysFor(specs.get(1).ingredient()) : available.allKeys();
        for (var stored : stockKeys) {
            if (timedOut.getAsBoolean()) return ItemStack.EMPTY;
            if (available.count(stored) <= 0) continue;
            ItemStack base = stored.toStack();
            if (!recipe.isBaseIngredient(base)) continue;
            ItemStack assembled = com.huanghuang.rsintegration.mods.vanilla.SmithingRecipeHandler
                    .assembleWithBase(recipe, base, access);
            if (!assembled.isEmpty() && accepts.test(assembled)) return assembled;
        }
        if (specs == null) specs = handler.getIngredients(recipe);
        if (specs == null || specs.size() != 3) return ItemStack.EMPTY;
        for (ItemStack base : specs.get(1).ingredient().getItems()) {
            for (RecipeIndex.Entry entry : index.getOrDefault(base.getItem(), List.of())) {
                if (!(entry.recipe() instanceof net.minecraft.world.item.crafting.SmithingTransformRecipe upstream)) continue;
                ItemStack inheritedBase = findStockedSmithingOutput(upstream, candidate -> {
                    if (!recipe.isBaseIngredient(candidate)) return false;
                    ItemStack assembled = com.huanghuang.rsintegration.mods.vanilla.SmithingRecipeHandler
                            .assembleWithBase(recipe, candidate, access);
                    return !assembled.isEmpty() && accepts.test(assembled);
                }, available, index, access, visited, timedOut);
                if (!inheritedBase.isEmpty()) {
                    return com.huanghuang.rsintegration.mods.vanilla.SmithingRecipeHandler
                            .assembleWithBase(recipe, inheritedBase, access);
                }
            }
        }
        return ItemStack.EMPTY;
    }

    /**
     * Vanilla smithing transform recipes copy the base stack's tag to the
     * result at assemble-time, while {@code getResultItem()} only exposes the
     * tagless declared result.  Preserve the requested NBT during candidate
     * discovery so strict ingredients such as an Unbreakable sword can still
     * resolve their smithing upgrade chain.
     */
    static ItemStack inheritSmithingBaseTag(Recipe<?> recipe, ItemStack output,
                                            Ingredient demand) {
        if (output == null || output.isEmpty() || demand == null
                || demand.isEmpty()) return output;
        if (!(recipe instanceof net.minecraft.world.item.crafting.SmithingTransformRecipe)) {
            return output;
        }
        for (ItemStack requested : demand.getItems()) {
            if (requested == null || requested.isEmpty() || !requested.hasTag()
                    || requested.getItem() != output.getItem()) continue;
            ItemStack tagged = output.copy();
            tagged.setTag(requested.getTag().copy());
            return tagged;
        }
        return output;
    }

    @javax.annotation.Nullable
    private static List<RecipeIndex.Entry> semanticSpellRecipes(
            ItemStack requested, ResolutionContext ctx) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(requested.getItem());
        if (id == null || !id.equals(new ResourceLocation("irons_spellbooks", "scroll"))) {
            return null;
        }
        try {
            // A compound ingredient may intentionally accept several distinct
            // spell scrolls; keep the normal candidate union for that case.
            if (requested == null || requested.isEmpty()) return null;
            var key = com.huanghuang.rsintegration.mods.ironsspellbooks
                    .IronSpellBooksRecipeCatalog.spellScrollKey(requested);
            return key == null ? null : RecipeIndex.spellScrollCandidates(ctx.level, requested);
        } catch (LinkageError ignored) {
            return null;
        }
    }

    private static boolean variantGuardEnabled() {
        try {
            return RSIntegrationConfig.ENABLE_CRAFTING_VARIANT_CONVERSION_GUARD.get();
        } catch (Exception ignored) {
            return true;
        }
    }

    static int compareCandidateIds(ResourceLocation idA, ResourceLocation idB,
                                   Map<ResourceLocation, Integer> scoreCache,
                                   Map<ResourceLocation, Integer> availCache) {
        // Scoring intentionally stops on timeout. Keep the unscored tail
        // neutral instead of unboxing a missing cache value and throwing NPE.
        int cmp = Integer.compare(scoreCache.getOrDefault(idB, 0),
                scoreCache.getOrDefault(idA, 0));
        if (cmp != 0) return cmp;

        cmp = Integer.compare(availCache.getOrDefault(idB, 0),
                availCache.getOrDefault(idA, 0));
        if (cmp != 0) return cmp;

        boolean aMc = "minecraft".equals(idA.getNamespace());
        boolean bMc = "minecraft".equals(idB.getNamespace());
        if (aMc && !bMc) return -1;
        if (bMc && !aMc) return 1;
        return 0;
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
        if (entry.recipe() instanceof CraftingRecipe craftingRecipe
                && !CraftPacketUtils.isCraftingRecipeAvailable(craftingRecipe, ctx.player)) {
            return false;
        }
        // Binding is an execution authorization, not a prerequisite for
        // discovering the vanilla smithing upgrade chain.
        if (entry.recipe() instanceof net.minecraft.world.item.crafting.SmithingTransformRecipe
                || entry.recipe() instanceof net.minecraft.world.item.crafting.SmithingTrimRecipe) {
            return true;
        }
        if (entry.modType() == ModType.GENERIC || entry.modType().isVirtual()) return true;
        if (ctx.player == null) return false;
        if (MinersDelightCopperPotSupport.isCopperPotType(entry.modType())) {
            return AltarBindingRegistry.hasAnyBindingForType(ctx.player, entry.modType());
        }
        return AltarBindingRegistry.hasBindingForRecipe(
                ctx.player, entry.recipe(), entry.modType());
    }

    private static boolean isPreferred(RecipeIndex.Entry entry, ResolutionContext ctx) {
        if (ctx.preferredRecipes == null) return false;
        ItemStack output = ModRecipeHandlers.tryGetResultItem(entry.recipe(), ctx.level.registryAccess());
        if (output.isEmpty()) return false;
        ResourceLocation itemKey = CraftingResolver.preferenceKey(output);
        if (itemKey == null) return false;
        ResourceLocation pref = ctx.preferredRecipes.get(itemKey);
        if (pref == null) pref = ctx.preferredRecipes.get(ForgeRegistries.ITEMS.getKey(output.getItem()));
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
        List<IngredientSpec> specs = ingredientSpecs(entry, ctx);
        if (specs != null) {
            Map<String, IngredientDemand> demands = specDemands(specs);
            for (IngredientDemand demand : demands.values()) {
                int available = cachedCountMatching(ctx, demand.ingredient(), matchCache);
                if (available >= demand.required()) score += 10;
                else if (available > 0) {
                    score += Math.max(0, 10 - (demand.required() - available) * 5);
                }
            }
            score -= demands.values().stream().mapToInt(IngredientDemand::required).sum();
            score += reusableCatalystPreferenceScore(specs, demands.values(), ctx, matchCache);
        }
        ItemStack output = outputForDemand(entry, Ingredient.EMPTY, ctx);
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
        ItemStack output = ModRecipeHandlers.tryGetResultItem(recipe, ctx.level.registryAccess());
        ResourceLocation outputKey = CraftingResolver.preferenceKey(output);
        if (outputKey != null && ctx.preferredRecipes != null) {
            ResourceLocation preferred = ctx.preferredRecipes.get(outputKey);
            if (preferred == null) preferred = ctx.preferredRecipes.get(
                    ForgeRegistries.ITEMS.getKey(output.getItem()));
            if (preferred != null && preferred.equals(recipe.getId())) {
                score += PREFERRED_RECIPE_BONUS;
            }
        }

        List<IngredientSpec> specs = CraftPacketUtils.extractCraftingIngredientSpecs(recipe);
        Map<String, IngredientDemand> demands = specDemands(specs);
        for (IngredientDemand demand : demands.values()) {
            int available = cachedCountMatching(ctx, demand.ingredient(), matchCache);
            if (available >= demand.required()) score += 10;
            else if (available > 0) score += Math.max(0, 10 - (demand.required() - available) * 5);
        }

        score -= demands.values().stream().mapToInt(IngredientDemand::required).sum();
        score += reusableCatalystPreferenceScore(specs, demands.values(), ctx, matchCache);

        if (output.getCount() > 1) {
            // Gate output bonus behind ingredient availability for the same
            // reason as the mod-recipe path: avoid over-ranking decompression
            // recipes when their inputs are themselves craftable only via the
            // output they produce.
            boolean allAvail = true;
            for (IngredientDemand demand : demands.values()) {
                if (cachedCountMatching(ctx, demand.ingredient(), matchCache)
                        < demand.required()) {
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

    private static int reusableCatalystPreferenceScore(
            List<IngredientSpec> recipeSpecs, Collection<IngredientDemand> demands,
            ResolutionContext ctx, @javax.annotation.Nullable Map<Ingredient, Integer> matchCache) {
        if (!catalystPreferenceEnabled()) return 0;

        int score = 0;
        int bonus = catalystPreferenceBonus();
        if (hasReusableCatalyst(recipeSpecs)
                && reusableCatalystsAvailable(recipeSpecs, ctx, matchCache)) {
            score += bonus;
        }
        for (IngredientDemand demand : demands) {
            if (demand.role() != DemandRole.CATALYST
                    && hasAvailableReusableCatalystProducer(
                    demand.ingredient(), ctx, matchCache)) {
                score += bonus;
                break;
            }
        }
        return score;
    }

    private static boolean hasAvailableReusableCatalystProducer(
            Ingredient demanded, ResolutionContext ctx,
            @javax.annotation.Nullable Map<Ingredient, Integer> matchCache) {
        Set<ResourceLocation> checked = new HashSet<>();
        for (ItemStack option : demanded.getItems()) {
            if (option.isEmpty()) continue;
            List<RecipeIndex.ReusableCatalystRoute> routes =
                    ctx.reusableCatalystRoutes.get(option.getItem());
            if (routes == null) continue;
            for (RecipeIndex.ReusableCatalystRoute route : routes) {
                if (!checked.add(route.recipeId())) continue;
                if (!IngredientMatcher.test(demanded, route.output())) continue;
                if (reusableCatalystsAvailable(route.specs(), ctx, matchCache)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasReusableCatalyst(List<IngredientSpec> specs) {
        return specs.stream().anyMatch(spec -> !spec.isEmpty()
                && spec.role() == DemandRole.CATALYST);
    }

    private static boolean reusableCatalystsAvailable(
            List<IngredientSpec> specs, ResolutionContext ctx,
            @javax.annotation.Nullable Map<Ingredient, Integer> matchCache) {
        boolean found = false;
        for (IngredientSpec spec : specs) {
            if (spec.isEmpty() || spec.role() != DemandRole.CATALYST) continue;
            found = true;
            if (cachedCountMatching(ctx, spec.ingredient(), matchCache) < spec.count()) return false;
        }
        return found;
    }

    private static boolean catalystPreferenceEnabled() {
        try {
            return RSIntegrationConfig.ENABLE_CATALYST_RECIPE_PREFERENCE.get();
        } catch (Exception ignored) {
            return true;
        }
    }

    private static int catalystPreferenceBonus() {
        try {
            return RSIntegrationConfig.CATALYST_RECIPE_PREFERENCE_BONUS.get();
        } catch (Exception ignored) {
            return RSIntegrationConfig.DEFAULT_CATALYST_RECIPE_PREFERENCE_BONUS;
        }
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
            for (IngredientDemand demand : craftingDemands(cr).values()) {
                if (cachedCountMatching(ctx, demand.ingredient(), matchCache) < demand.required()) {
                    return false;
                }
            }
            return true;
        }
        List<IngredientSpec> specs = ingredientSpecs(entry, ctx);
        if (specs == null) return false;
        for (IngredientDemand demand : specDemands(specs).values()) {
            if (cachedCountMatching(ctx, demand.ingredient(), matchCache) < demand.required()) return false;
        }
        return true;
    }

    /** Count how many distinct ingredients of a recipe have matching items available. */
    private static int countAvailableIngredients(RecipeIndex.Entry entry, ResolutionContext ctx,
                                                    @javax.annotation.Nullable Map<Ingredient, Integer> matchCache) {
        if (entry.recipe() instanceof CraftingRecipe cr) {
            return (int) craftingDemands(cr).values().stream()
                    .filter(demand -> cachedCountMatching(ctx, demand.ingredient(), matchCache)
                            >= demand.required())
                    .count();
        }
        List<IngredientSpec> specs = ingredientSpecs(entry, ctx);
        if (specs == null) return 0;
        Map<String, IngredientDemand> demands = specDemands(specs);
        return (int) demands.values().stream()
                .filter(demand -> cachedCountMatching(ctx, demand.ingredient(), matchCache)
                        >= demand.required())
                .count();
    }

    record IngredientDemand(Ingredient ingredient, int required, DemandRole role) {}

    private static List<IngredientSpec> ingredientSpecs(RecipeIndex.Entry entry,
                                                        ResolutionContext ctx) {
        List<IngredientSpec> specs = CraftPacketUtils.extractIngredientSpecs(entry.recipe());
        return MinersDelightCopperPotSupport.adaptIngredientSpecs(
                entry.modType(), specs, entry.recipe(), ctx.level.registryAccess());
    }

    static Map<String, IngredientDemand> craftingDemands(CraftingRecipe recipe) {
        return specDemands(CraftPacketUtils.extractCraftingIngredientSpecs(recipe));
    }

    static Map<String, IngredientDemand> specDemands(List<IngredientSpec> specs) {
        Map<String, IngredientDemand> demands = new LinkedHashMap<>();
        for (IngredientSpec spec : specs) {
            if (spec.isEmpty()) continue;
            String key = spec.role() + ":" + ingredientKey(spec.ingredient());
            IngredientDemand old = demands.get(key);
            demands.put(key, new IngredientDemand(spec.ingredient(),
                    (old == null ? 0 : old.required()) + spec.count(), spec.role()));
        }
        return demands;
    }

    private static String ingredientKey(Ingredient ingredient) {
        StringBuilder key = new StringBuilder();
        for (ItemStack stack : ingredient.getItems()) {
            if (stack.isEmpty()) continue;
            key.append(ForgeRegistries.ITEMS.getKey(stack.getItem())).append('|')
                    .append(stack.getTag()).append(';');
        }
        return key.toString();
    }

    private static boolean passesOutputCheck(RecipeIndex.Entry entry, ItemStack output,
                                              Ingredient ingredient, boolean ingredientAllNbt,
                                              boolean nbtStrict,
                                              @javax.annotation.Nullable List<CandidateDiagnostic> diag) {
        if (output.isEmpty() || (!IngredientMatcher.test(ingredient, output)
                && !matchesSemanticSpellScroll(ingredient, output))) {
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
        // ingredient.test(output) is authoritative. Forge partial-NBT
        // ingredients intentionally accept extra runtime state; TACZ guns
        // carry more NBT than the GunId required by soul-stone recipes.
        return true;
    }

    /**
     * Iron spell scrolls from datapacks and addons may serialize the same
     * level with different numeric NBT tag types. Their gameplay identity is
     * the spell id plus level, so candidate discovery must use the same
     * semantic comparison as runtime output settlement.
     */
    static boolean matchesSemanticSpellScroll(Ingredient ingredient, ItemStack output) {
        ResourceLocation outputId = ForgeRegistries.ITEMS.getKey(output.getItem());
        if (outputId == null
                || !outputId.equals(new ResourceLocation("irons_spellbooks", "scroll"))) {
            return false;
        }
        try {
            for (ItemStack declared : ingredient.getItems()) {
                if (!declared.isEmpty()
                        && com.huanghuang.rsintegration.mods.ironsspellbooks
                                .IronSpellBooksRecipeCatalog.sameSpellScroll(declared, output)) {
                    return true;
                }
            }
        } catch (LinkageError ignored) {
            // Iron's Spell Books is optional; retain normal Ingredient semantics.
        }
        return false;
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
            if (!stack.isEmpty() && MaterialMatcher.equivalentRuntimeFragment(stack, output)) return true;
        }
        return false;
    }
}
