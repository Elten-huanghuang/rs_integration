package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.quantity.SaturatingLongMath;
import com.huanghuang.rsintegration.crafting.CompatibilityProfile.Phase;
import com.huanghuang.rsintegration.mods.historystages.HistoryStagesCompat;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import com.huanghuang.rsintegration.mods.goety.GoetyDynamicRitualRecipe;
import com.huanghuang.rsintegration.mods.ironsspellbooks.IronSpellBooksRecipeCatalog;
import com.huanghuang.rsintegration.mods.farmersdelight.MinersDelightCopperPotSupport;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.mods.slashblade.SlashBladeRecipeHandler;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.common.crafting.StrictNBTIngredient;
import net.minecraftforge.registries.ForgeRegistries;
import com.huanghuang.rsintegration.mods.vanilla.SmithingRecipeHandler;
import com.huanghuang.rsintegration.mods.vanilla.VanillaMachineRecipeHandler;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import net.minecraft.world.item.crafting.SmithingTrimRecipe;

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
     * 同上，但额外知道本次还差多少 {@code remaining}。
     * 流体在 RSI 里被建模成 token 物品（stack 数量就是 mB 数），于是「1 块 → 1000 mB 一炉」
     * 与「81 粒 → 810 mB 八十一炉」在原有分数上完全同分，只有操作次数能把两者区分开。
     */
    static List<RecipeIndex.Entry> findCandidates(Ingredient ingredient, ResolutionContext ctx,
                                                  long remaining) {
        return findCandidates(ingredient, ctx, null, remaining);
    }

    /**
     * Same as above, but fills {@code diag} with per-candidate scoring/skip info.
     */
    static List<RecipeIndex.Entry> findCandidates(Ingredient ingredient, ResolutionContext ctx,
                                                   @Nullable List<CandidateDiagnostic> diag) {
        return findCandidates(ingredient, ctx, diag, 0L);
    }

    /**
     * 实现版。{@code remaining <= 0} 表示需求量未知，此时不比较操作次数。
     */
    static List<RecipeIndex.Entry> findCandidates(Ingredient ingredient, ResolutionContext ctx,
                                                   @Nullable List<CandidateDiagnostic> diag,
                                                   long remaining) {
        PlanningStack stack = new PlanningStack();
        return stack.resolve(done -> findCandidates(ingredient, ctx, diag, remaining, stack, done));
    }

    static void findCandidates(Ingredient ingredient, ResolutionContext ctx,
                               @Nullable List<CandidateDiagnostic> diag, long remaining,
                               PlanningStack stack, Consumer<List<RecipeIndex.Entry>> done) {
        new CandidateSearch(ingredient, ctx, diag, remaining, stack, done).start();
    }

    private static final class CandidateSearch {
        private final Ingredient ingredient;
        private final ResolutionContext ctx;
        private final List<CandidateDiagnostic> diag;
        private final long remaining;
        private final PlanningStack stack;
        private final Consumer<List<RecipeIndex.Entry>> done;
        private final ItemStack[] items;
        private final boolean nbtStrict;
        private final boolean ingredientAllNbt;
        // Shared recipes can have distinct machine routes and runtime outputs.
        private final Map<CandidateKey, RecipeIndex.Entry> byId = new LinkedHashMap<>();
        private final List<RecipeIndex.Entry> result = new ArrayList<>();
        private int nextItem;
        private Item item;
        private Iterator<RecipeIndex.Entry> indexed = Collections.emptyIterator();
        private Iterator<RecipeIndex.Entry> outputs;
        private boolean checkingCrafting = true;
        private int craftingEntries;
        private int nextScore;
        private Map<Ingredient, Long> matchCache;
        private Map<RecipeIndex.Entry, CandidateRank> ranks;
        private Map<ResourceLocation, Integer> scrollLevels;
        private Map<ResourceLocation, Integer> scrollRarities;
        private int nextScroll;
        private int nextDiagnostic;

        CandidateSearch(Ingredient ingredient, ResolutionContext ctx, @Nullable List<CandidateDiagnostic> diag,
                        long remaining, PlanningStack stack, Consumer<List<RecipeIndex.Entry>> done) {
            this.ingredient = ingredient;
            this.ctx = ctx;
            this.diag = diag;
            this.remaining = remaining;
            this.stack = stack;
            this.done = done;
            items = ingredient.getItems();
            nbtStrict = ingredient instanceof StrictNBTIngredient;
            ingredientAllNbt = !nbtStrict && allItemsHaveNbt(ingredient);
        }

        void start() {
            stack.resume(this::gather);
        }

        // 每次只筛一个配方或展开一个标签成员，原生调用之间允许让出当前 tick。
        private void gather() {
            if (ctx.timedOut() || (!indexed.hasNext() && nextItem == items.length)) {
                checkingCrafting = craftingEntries != 0;
                outputs = byId.values().iterator();
                stack.resume(this::checkOutput);
                return;
            }
            stack.resume(this::gather);
            if (indexed.hasNext()) {
                gatherEntry(indexed.next());
                return;
            }
            ItemStack variant = items[nextItem++];
            if (variant.isEmpty()) return;
            if (ctx.isNoRecipe(variant)) {
                if (diag != null) {
                    logDiag(diag, variant.getItem(), null, 0, null, true,
                            "NoRecipe preference disables this material");
                }
                return;
            }
            item = variant.getItem();
            List<RecipeIndex.Entry> recipes = semanticSpellRecipes(variant, ctx);
            if (recipes == null) recipes = ctx.index.get(item);
            if (recipes == null) {
                if (diag != null) logDiag(diag, item, null, 0, null, true, "No recipes indexed for this item");
                return;
            }
            indexed = recipes.iterator();
        }

        private void gatherEntry(RecipeIndex.Entry entry) {
            if (ctx.materialProfile != null) ctx.materialProfile.scanned();
            ResourceLocation rid = entry.recipe().getId();
            if (ctx.excludedRecipes.contains(rid)) return;
            CandidateKey candidateKey = CandidateKey.of(entry);
            if (byId.containsKey(candidateKey)) return;
            if (ctx.genericCandidatesOnly && entry.modType() != ModType.GENERIC) {
                if (diag != null) logDiag(diag, item, entry, 0, entry.modType(), true,
                        "非通用合成候选不能内联执行");
                return;
            }
            if (HistoryStagesCompat.isRecipeLocked(entry.recipe(), ctx.player)) {
                if (diag != null) logDiag(diag, item, entry, 0, entry.modType(), true,
                        "History Stages recipe lock");
                return;
            }
            if (entry.modType() != ModType.GENERIC && !entry.modType().isVirtual()) {
                var allowlist = RSIntegrationConfig.MULTIBLOCK_RECIPE_ALLOWLIST.get();
                if (!allowlist.isEmpty() && !allowlist.contains(rid.toString())) {
                    if (diag != null) logDiag(diag, item, entry, 0, entry.modType(), true, "Not in allowlist");
                    return;
                }
                if (RSIntegrationConfig.MULTIBLOCK_RECIPE_BLACKLIST.get().contains(rid.toString())) {
                    if (diag != null) logDiag(diag, item, entry, 0, entry.modType(), true, "Blocklisted");
                    return;
                }
            }
            byId.put(candidateKey, entry);
            if (entry.recipe() instanceof CraftingRecipe) craftingEntries++;
        }

        private void checkOutput() {
            // 保留工作台优先的检查顺序；同类候选不再为不存在的另一类空扫一轮。
            if (ctx.timedOut() || (!outputs.hasNext() && (!checkingCrafting || craftingEntries == byId.size()))) {
                if (result.size() < 2 && diag == null) {
                    stack.complete(done, result);
                    return;
                }
                matchCache = new HashMap<>();
                ranks = new IdentityHashMap<>(result.size());
                stack.resume(this::score);
                return;
            }
            stack.resume(this::checkOutput);
            if (!outputs.hasNext()) {
                checkingCrafting = false;
                outputs = byId.values().iterator();
                return;
            }
            RecipeIndex.Entry entry = outputs.next();
            if ((entry.recipe() instanceof CraftingRecipe) != checkingCrafting) return;
            ItemStack output = outputForDemand(entry, ingredient, ctx);
            if (!passesOutputCheck(entry, output, ingredient, ingredientAllNbt, nbtStrict, diag)) return;
            if (entry.recipe() instanceof CraftingRecipe cr
                    && variantGuardEnabled()
                        && (NonProductiveTagConversionGuard.shouldSkip(ingredient, cr, output)
                        || ctx.shouldSkipActiveConversion(cr, output))) {
                    if (ctx.diagnosticsEnabled()) ctx.diag("candidate SKIP " + entry.recipe().getId()
                            + ": non-productive tag conversion");
                    if (diag != null) logDiag(diag, null, entry, 0, entry.modType(), true,
                            "Non-productive tag conversion");
                    return;
            }
            // 同物品的不同 NBT 变体共用索引，产物匹配后才探测世界中的机器。
            if (entry.modType() != ModType.GENERIC) {
                String unavailable = ctx.candidateRouteCheck(entry, () -> checkRoute(entry, ctx));
                if (!unavailable.isEmpty()) {
                    if (diag != null) logDiag(diag, output.getItem(), entry, 0, entry.modType(), true, unavailable);
                    return;
                }
            }
            result.add(entry);
        }

        private void score() {
            ctx.materialStage(PreviewMaterialProfile.Stage.SCORE);
            if (ctx.timedOut() || nextScore == result.size()) {
                stack.resume(this::sort);
                return;
            }
            RecipeIndex.Entry entry = result.get(nextScore++);
            if (ctx.materialProfile != null) ctx.materialProfile.scored();
            long start = ctx.profileStart();
            var score = scoreEntry(entry, ctx, nbtStrict, matchCache);
            ctx.profileEnd(Phase.SCORE, start);
            ranks.put(entry, new CandidateRank(score.score(), remaining > 0L
                    ? estimatedOperations(remaining, score.outputCount())
                    : Integer.MAX_VALUE, score.availableGroups(), score.inputGroups()));
            stack.resume(this::score);
        }

        private void sort() {
            result.sort((a, b) -> {
                int cmp = rankOf(a).compareTo(rankOf(b));
                if (cmp != 0) return cmp;
                cmp = a.recipe().getId().compareTo(b.recipe().getId());
                return cmp != 0 ? cmp : a.modType().id().compareTo(b.modType().id());
            });
            if (SpellScrollSelection.acceptsAnyScroll(ingredient)) {
                scrollLevels = new HashMap<>();
                scrollRarities = new HashMap<>();
                stack.resume(this::scoreScroll);
            } else {
                stack.resume(this::diagnose);
            }
        }

        private void scoreScroll() {
            if (nextScroll < result.size() && !ctx.timedOut()) {
                RecipeIndex.Entry entry = result.get(nextScroll++);
                ItemStack output = ctx.candidateDeclaredResult(entry.recipe());
                scrollLevels.put(entry.recipe().getId(), SpellScrollSelection.level(output));
                scrollRarities.put(entry.recipe().getId(), SpellScrollSelection.rarity(output));
                stack.resume(this::scoreScroll);
                return;
            }
            // 排序保持同稀有度、同等级配方的原有优先顺序。
            result.sort(Comparator
                    .comparingInt((RecipeIndex.Entry entry) -> scrollRarities.getOrDefault(
                            entry.recipe().getId(), Integer.MAX_VALUE))
                    .thenComparingInt(entry -> scrollLevels.getOrDefault(
                            entry.recipe().getId(), Integer.MAX_VALUE)));
            stack.resume(this::diagnose);
        }

        private void diagnose() {
            if (diag == null || nextDiagnostic == result.size() || ctx.timedOut()) {
                stack.complete(done, result);
                return;
            }
            RecipeIndex.Entry entry = result.get(nextDiagnostic++);
            int score = rankOf(entry).score();
            String pref = isPreferred(entry, ctx) ? " (PREFERRED)" : "";
            logDiag(diag, null, entry, score, entry.modType(), false, "Score=" + score + pref);
            stack.resume(this::diagnose);
        }

        private CandidateRank rankOf(RecipeIndex.Entry entry) {
            return ranks.getOrDefault(entry, CandidateRank.UNSCORED);
        }
    }

    /**
     * 完整排序：分数 → 需求补满所需操作次数 → 满足的原料种类数 → 独立原料组数 → recipeId。
     *
     * <p>操作次数排在分数之后、其余指标之前：同一种流体既有一块熔一炉（810/1000 mB）、
     * 也有一粒熔一炉（10 mB）时，少烧几次比「刚好凑够数量」更省时间，所以块优先。
     * 库存差异仍然先体现在分数里（原料不可用要扣分，全齐才有产出加成），
     * 因此不会为了大批量而动用库存里没有的原料。
     */
    record CandidateRank(int score, int operations, int availableGroups, int inputGroups)
            implements Comparable<CandidateRank> {
        static final CandidateRank UNSCORED = new CandidateRank(0, Integer.MAX_VALUE, 0, Integer.MAX_VALUE);

        @Override
        public int compareTo(CandidateRank other) {
            int cmp = Integer.compare(other.score, score);
            if (cmp != 0) return cmp;
            cmp = Integer.compare(operations, other.operations);
            if (cmp != 0) return cmp;
            cmp = Integer.compare(other.availableGroups, availableGroups);
            return cmp != 0 ? cmp : Integer.compare(inputGroups, other.inputGroups);
        }
    }

    /** Resolves shared runtime recipes against the exact NBT-bearing item being requested. */
    static ItemStack outputForDemand(RecipeIndex.Entry entry, Ingredient demand,
                                     ResolutionContext ctx) {
        if (GoetyDynamicRitualRecipe.isSupported(entry.recipe())) {
            ItemStack dynamic = GoetyDynamicRitualRecipe.matchingOutput(entry.recipe(), demand);
            if (!dynamic.isEmpty()) return dynamic;
        }
        ItemStack output = ctx.candidateDeclaredResult(entry.recipe());
        return adaptOutputForDemand(entry, output, demand, ctx);
    }

    private static ItemStack adaptOutputForDemand(RecipeIndex.Entry entry, ItemStack output,
                                                  Ingredient demand, ResolutionContext ctx) {
        output = SlashBladeRecipeHandler.planningOutput(entry.recipe(), output, demand);
        output = MinersDelightCopperPotSupport.adaptResult(entry.modType(), output);
        return inheritSmithingBaseTag(entry.recipe(), output, demand, ctx);
    }

    static ItemStack inheritSmithingBaseTag(Recipe<?> recipe, ItemStack output,
                                            Ingredient demand, ResolutionContext ctx) {
        if (recipe instanceof SmithingTransformRecipe smithing
                && IngredientMatcher.requiresNbt(demand)) {
            ItemStack inherited = findStockedSmithingOutput(smithing,
                    stack -> IngredientMatcher.test(demand, stack), ctx.counts, ctx.index,
                    ctx.level.registryAccess(), new HashSet<>(), ctx::timedOut);
            if (!inherited.isEmpty()) return inherited;
        }
        return inheritSmithingBaseTag(recipe, output, demand);
    }

    static ItemStack findStockedSmithingOutput(
            SmithingTransformRecipe recipe,
            Predicate<ItemStack> accepts,
            Map<CraftingResolver.StackKey, ? extends Number> available,
            Map<Item, List<RecipeIndex.Entry>> index, RegistryAccess access,
            Set<ResourceLocation> visited, BooleanSupplier timedOut) {
        return findStockedSmithingOutput(recipe, accepts, new InventoryCandidateLookup(available),
                index, access, visited, timedOut);
    }

    static ItemStack findStockedSmithingOutput(
            SmithingTransformRecipe recipe,
            Predicate<ItemStack> accepts,
            InventoryCandidateLookup available,
            Map<Item, List<RecipeIndex.Entry>> index, RegistryAccess access,
            Set<ResourceLocation> visited, BooleanSupplier timedOut) {
        if (timedOut.getAsBoolean() || visited.size() >= 64 || !visited.add(recipe.getId())) {
            return ItemStack.EMPTY;
        }
        var handler = new SmithingRecipeHandler();
        var specs = recipe.getClass() == SmithingTransformRecipe.class
                ? handler.getIngredients(recipe) : null;
        var stockKeys = specs != null && specs.size() == 3
                ? available.keysFor(specs.get(1).ingredient()) : available.allKeys();
        for (var stored : stockKeys) {
            if (timedOut.getAsBoolean()) return ItemStack.EMPTY;
            if (available.count(stored) <= 0) continue;
            ItemStack base = stored.toStack();
            if (!recipe.isBaseIngredient(base)) continue;
            ItemStack assembled = SmithingRecipeHandler
                    .assembleWithBase(recipe, base, access);
            if (!assembled.isEmpty() && accepts.test(assembled)) return assembled;
        }
        if (specs == null) specs = handler.getIngredients(recipe);
        if (specs == null || specs.size() != 3) return ItemStack.EMPTY;
        for (ItemStack base : specs.get(1).ingredient().getItems()) {
            for (RecipeIndex.Entry entry : index.getOrDefault(base.getItem(), List.of())) {
                if (!(entry.recipe() instanceof SmithingTransformRecipe upstream)) continue;
                ItemStack inheritedBase = findStockedSmithingOutput(upstream, candidate -> {
                    if (!recipe.isBaseIngredient(candidate)) return false;
                    ItemStack assembled = SmithingRecipeHandler
                            .assembleWithBase(recipe, candidate, access);
                    return !assembled.isEmpty() && accepts.test(assembled);
                }, available, index, access, visited, timedOut);
                if (!inheritedBase.isEmpty()) {
                    return SmithingRecipeHandler
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
        if (!(recipe instanceof SmithingTransformRecipe)) {
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

    @Nullable
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
            var key = IronSpellBooksRecipeCatalog.spellScrollKey(requested);
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

    private static void logDiag(List<CandidateDiagnostic> diag, @Nullable Item item,
                                 @Nullable RecipeIndex.Entry entry, int score,
                                 @Nullable ModType mt, boolean skipped, String reason) {
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
        if (entry.recipe() instanceof SmithingTransformRecipe
                || entry.recipe() instanceof SmithingTrimRecipe) {
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

    private static String checkRoute(RecipeIndex.Entry entry, ResolutionContext ctx) {
        var handler = ModRecipeHandlers.claimFor(entry.recipe()).winner();
        if (handler != null && !ctx.readRecipe(entry.recipe(), () -> handler.isAvailableForPlanning(entry.recipe(), ctx.player))) {
            return "Execution context unavailable";
        }
        return ctx.readRecipe(entry.recipe(), () -> isMachineAvailable(entry, ctx)) ? "" : "Machine not available";
    }

    static boolean routeStillAvailable(RecipeIndex.Entry entry, ResolutionContext ctx) {
        return !HistoryStagesCompat.isRecipeLocked(entry.recipe(), ctx.player)
                && (entry.modType() == ModType.GENERIC || checkRoute(entry, ctx).isEmpty());
    }

    static boolean frozenRouteStillAvailable(RecipeIndex.Entry entry, ResolutionContext ctx) {
        if (entry.modType() == ModType.GENERIC || !HybridBranchPlanning.nativeMachine(entry.recipe().getClass())
                || !(ModRecipeHandlers.claimFor(entry.recipe()).winner() instanceof VanillaMachineRecipeHandler)) {
            return routeStillAvailable(entry, ctx);
        }
        // 固定原版机器只沿用当前切片内的候选检查；跨切片交回的结果会重新探测。
        return !HistoryStagesCompat.isRecipeLocked(entry.recipe(), ctx.player)
                && ctx.candidateRouteCheck(entry, () -> checkRoute(entry, ctx)).isEmpty();
    }

    static boolean safeFrozenGrid(RecipeIndex.Entry entry, ItemStack output, ResolutionContext ctx) {
        if (!(entry.recipe() instanceof CraftingRecipe grid) || !routeStillAvailable(entry, ctx)) return false;
        Ingredient exact = CraftingResolver.ingredientOf(output, output.hasTag());
        return !variantGuardEnabled() || !NonProductiveTagConversionGuard.shouldSkip(exact, grid, output)
                && !ctx.shouldSkipActiveConversion(grid, output);
    }

    private static boolean isPreferred(RecipeIndex.Entry entry, ResolutionContext ctx) {
        ItemStack output = ctx.candidateDeclaredResult(entry.recipe());
        if (output.isEmpty()) return false;
        ResourceLocation pref = ctx.preferredRecipeFor(output);
        return pref != null && pref.equals(entry.recipe().getId());
    }

    private record CandidateScore(int score, int availableGroups, int inputGroups, int outputCount) {}

    private static CandidateScore scoreEntry(RecipeIndex.Entry entry, ResolutionContext ctx, boolean nbtStrict,
                                             Map<Ingredient, Long> matchCache) {
        boolean crafting = entry.recipe() instanceof CraftingRecipe;
        int score = entry.modType() == ModType.GENERIC ? 10 : 0;
        if (!crafting && nbtStrict && entry.nbtSensitive()) score += 5;
        List<IngredientSpec> specs = entry.recipe() instanceof CraftingRecipe recipe
                ? CraftPacketUtils.extractCraftingIngredientSpecs(recipe)
                : ingredientSpecs(entry, ctx);
        long start = ctx.profileStart();
        Collection<IngredientDemand> demands = specs == null ? List.of() : ctx.scoreDemands(entry, specs);
        ctx.profileEnd(Phase.GROUP, start);
        int availableGroups = 0;
        for (IngredientDemand demand : demands) {
            long available = cachedCountMatching(ctx, demand.ingredient(), matchCache);
            if (available >= demand.required()) {
                availableGroups++;
                score += 10;
            } else if (available > 0) {
                score += SaturatingLongMath.clampToInt(Math.max(0L, 10L - (demand.required() - available) * 5L));
            }
            score -= demand.required();
        }
        if (specs != null) score += reusableCatalystPreferenceScore(specs, demands, ctx, matchCache);

        ItemStack declared = ctx.candidateDeclaredResult(entry.recipe());
        ItemStack output = adaptOutputForDemand(entry, declared, Ingredient.EMPTY, ctx);
        if (crafting || !output.isEmpty()) {
            if (entry.recipe().getId().equals(ctx.preferredRecipeFor(declared))) score += PREFERRED_RECIPE_BONUS;
            // 原料未齐时不奖励解压缩等高产量配方。
            ItemStack scoredOutput = crafting ? declared : output;
            if (scoredOutput.getCount() > 1 && specs != null && availableGroups == demands.size()) {
                score += cappedOutputBonus(scoredOutput.getCount());
            }
        }
        return new CandidateScore(score, availableGroups, specs == null ? Integer.MAX_VALUE : demands.size(),
                output.isEmpty() ? 0 : output.getCount());
    }

    private static long cachedCountMatching(ResolutionContext ctx, Ingredient ing,
                                            @Nullable Map<Ingredient, Long> cache) {
        long start = ctx.profileStart();
        long count = cache == null ? ctx.countMatching(ing) : cache.computeIfAbsent(ing, ctx::countMatching);
        ctx.profileEnd(Phase.COUNT, start);
        return count;
    }

    private static int reusableCatalystPreferenceScore(
            List<IngredientSpec> recipeSpecs, Collection<IngredientDemand> demands,
            ResolutionContext ctx, @Nullable Map<Ingredient, Long> matchCache) {
        if (!catalystPreferenceEnabled()) return 0;

        int score = 0;
        int bonus = catalystPreferenceBonus();
        if (hasReusableCatalyst(recipeSpecs)
                && reusableCatalystsAvailable(recipeSpecs, ctx, matchCache)) {
            score += bonus;
        }
        if (ctx.reusableCatalystRoutes.isEmpty()) return score;
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
          @Nullable Map<Ingredient, Long> matchCache) {
        Set<ResourceLocation> checked = null;
        for (ItemStack option : demanded.getItems()) {
            if (option.isEmpty()) continue;
            List<RecipeIndex.ReusableCatalystRoute> routes =
                    ctx.reusableCatalystRoutes.get(option.getItem());
            if (routes == null || routes.isEmpty()) continue;
            if (checked == null) checked = new HashSet<>();
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
          @Nullable Map<Ingredient, Long> matchCache) {
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

    /**
     * 估算补满需求需要几次执行。每批产量越大、炉数越少。流体 token 的 stack 数量就是
     * mB 数，所以「一块 1000 mB」估出 1 炉，「一粒 10 mB」估出 81 炉——这正是让预览树
     * 与执行派发保持一致的关键维度。需求未知（{@code remaining <= 0}）时返回 0，
     * 表示该维度不参与比较。
     */
    /** 向上取整的炉数估算，防溢出；每批产量非正时视为不可比较（排最后）。 */
    static int estimatedOperations(long remaining, long perOperation) {
        if (perOperation <= 0L) return Integer.MAX_VALUE;
        if (remaining <= 0L) return 0;
        long batches = remaining / perOperation + (remaining % perOperation == 0L ? 0L : 1L);
        return (int) Math.min(batches, Integer.MAX_VALUE);
    }

    record IngredientDemand(Ingredient ingredient, int required, DemandRole role) {}

    private static List<IngredientSpec> ingredientSpecs(RecipeIndex.Entry entry,
                                                        ResolutionContext ctx) {
        List<IngredientSpec> specs = ctx.declaredRecursiveInputs(entry);
        specs = CraftPacketUtils.applyPlanningIngredientContext(entry.recipe(), ctx.player, specs);
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

    static boolean passesOutputCheck(RecipeIndex.Entry entry, ItemStack output,
                                              Ingredient ingredient, boolean ingredientAllNbt,
                                              boolean nbtStrict,
                                              @Nullable List<CandidateDiagnostic> diag) {
        if (output.isEmpty() || (!IngredientMatcher.test(ingredient, output)
                && !matchesSemanticSpellScroll(ingredient, output))) {
            boolean slashBladeChain = !output.isEmpty()
                    && SlashBladeRecipeHandler.isSlashBladeIngredient(ingredient)
                    && SlashBladeRecipeHandler.matchesMaterialKey(ingredient, MaterialKey.of(output));
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
                        && IronSpellBooksRecipeCatalog.sameSpellScroll(declared, output)) {
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
