package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.graph.AllocationId;
import com.huanghuang.rsintegration.crafting.graph.CraftNode;
import com.huanghuang.rsintegration.crafting.graph.InputPortId;
import com.huanghuang.rsintegration.crafting.graph.MaterialAllocation;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.graph.MaterialSource;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
import com.huanghuang.rsintegration.crafting.graph.UnresolvedDemand;
import com.huanghuang.rsintegration.util.Diagnostics;
import com.refinedmods.refinedstorage.api.network.INetwork;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Resolution context — holds the unified recipe index, available item counts,
 * resolution steps, and undo/rollback state during recursive crafting resolution.
 *
 * <p>Extracted from {@link CraftingResolver} as part of Phase 3 refactoring.</p>
 */
final class ResolutionContext {

    static final long MAX_RESOLVE_NANOS =
            RSIntegrationConfig.DEFAULT_CRAFTING_RESOLVE_TIMEOUT_MS * 1_000_000L;

    /** Resolve deadline from config (ms → ns), falling back to the configured default
     *  if the config spec is not yet loaded (should not happen at runtime). */
    private static long resolveDeadlineNanos() {
        try {
            return System.nanoTime()
                    + RSIntegrationConfig.CRAFTING_RESOLVE_TIMEOUT_MS.get() * 1_000_000L;
        } catch (Exception e) {
            return System.nanoTime() + MAX_RESOLVE_NANOS;
        }
    }

    final Level level;
    final Map<Item, List<RecipeIndex.Entry>> index;
    final Map<CraftingResolver.StackKey, Integer> counts;
    final List<CraftingResolver.ResolutionStep> steps;
    final Set<String> resolving;
    final Set<CraftingResolver.StackKey> resolvingOutputs;
    final Deque<Set<Item>> activeConversionFamilies = new ArrayDeque<>();
    final long deadlineNanos;
    final boolean abortOnTimeout;
    int ensureCalls;
    int depthGuardHits;
    int deepestGuardDepth;
    int depthGuardLimit;
    int stepGuardHits;
    int largestGuardStepCount;
    int stepGuardLimit;
    final Deque<UndoEntry> undoStack = new ArrayDeque<>();
    final Deque<Integer> undoCheckpoints = new ArrayDeque<>();
    final Deque<SupplyUndoEntry> supplyUndoStack = new ArrayDeque<>();
    final Deque<Integer> supplyUndoCheckpoints = new ArrayDeque<>();
    final Deque<Integer> stepCheckpoints = new ArrayDeque<>();
    final List<CraftNode> graphNodes = new ArrayList<>();
    final List<MaterialAllocation> graphAllocations = new ArrayList<>();
    final List<UnresolvedDemand> graphUnresolved = new ArrayList<>();
    final Deque<Integer> graphNodeCheckpoints = new ArrayDeque<>();
    final Deque<Integer> graphAllocationCheckpoints = new ArrayDeque<>();
    final Deque<Integer> graphUnresolvedCheckpoints = new ArrayDeque<>();
    final Deque<Integer> supplyCheckpoints = new ArrayDeque<>();
    final Deque<Integer> nodeIdCheckpoints = new ArrayDeque<>();
    final Deque<Long> allocationIdCheckpoints = new ArrayDeque<>();
    final List<SupplyLot> supplies = new ArrayList<>();
    final Map<MaterialKey, List<SupplyLot>> suppliesByMaterial = new HashMap<>();
    int nextNodeId;
    long nextAllocationId;
    @Nullable final Map<ResourceLocation, ResourceLocation> preferredRecipes;
    @Nullable final ServerPlayer player;
    @Nullable final INetwork network;
    final boolean bestEffort;
    boolean strictCandidatePass;
    boolean bestEffortFallbackPass;
    @Nullable final List<String> missingOut;
    @Nullable final List<String> diagLog;

    // Inverted index: Item → pre-cached stacks, built once from initial inventory.
    // Eliminates the O(N) scan of all 546+ inventory types inside countMatching.
    private final Map<Item, List<CachedStack>> inventoryIndex = new HashMap<>();
    private final Map<CraftingResolver.StackKey, CachedStack> stacksByKey = new HashMap<>();

    ResolutionContext(Level level,
                      Map<Item, List<RecipeIndex.Entry>> index,
                      List<ItemStack> available,
                      @Nullable Map<ResourceLocation, ResourceLocation> preferredRecipes) {
        this(level, index, available, preferredRecipes, null, null);
    }

    ResolutionContext(Level level,
                      Map<Item, List<RecipeIndex.Entry>> index,
                      List<ItemStack> available,
                      @Nullable Map<ResourceLocation, ResourceLocation> preferredRecipes,
                      @Nullable ServerPlayer player,
                      @Nullable INetwork network) {
        this.level = level;
        this.index = index;
        this.counts = new LinkedHashMap<>();
        this.steps = new ArrayList<>();
        this.resolving = new HashSet<>();
        this.resolvingOutputs = new HashSet<>();
        this.preferredRecipes = preferredRecipes;
        this.player = player;
        this.network = network;
        this.deadlineNanos = resolveDeadlineNanos();
        this.abortOnTimeout = false;
        this.bestEffort = false;
        this.missingOut = null;
        this.diagLog = Diagnostics.isEnabled() ? new ArrayList<>() : null;

        for (ItemStack stack : available) addInitial(stack);
        buildInventoryIndex();
    }

    ResolutionContext(Level level,
                      Map<Item, List<RecipeIndex.Entry>> index,
                      Map<CraftingResolver.StackKey, Integer> keyedCounts,
                      @Nullable Map<ResourceLocation, ResourceLocation> preferredRecipes,
                      boolean bestEffort,
                      @Nullable List<String> missingOut) {
        this(level, index, keyedCounts, preferredRecipes, null, null, bestEffort, missingOut);
    }

    ResolutionContext(Level level,
                      Map<Item, List<RecipeIndex.Entry>> index,
                      Map<CraftingResolver.StackKey, Integer> keyedCounts,
                      @Nullable Map<ResourceLocation, ResourceLocation> preferredRecipes,
                      boolean bestEffort,
                      @Nullable List<String> missingOut,
                      long deadlineNanos,
                      boolean abortOnTimeout) {
        this(level, index, keyedCounts, preferredRecipes, null, null, bestEffort,
                missingOut, deadlineNanos, abortOnTimeout);
    }

    ResolutionContext(Level level,
                      Map<Item, List<RecipeIndex.Entry>> index,
                      Map<CraftingResolver.StackKey, Integer> keyedCounts,
                      @Nullable Map<ResourceLocation, ResourceLocation> preferredRecipes,
                      @Nullable ServerPlayer player,
                      @Nullable INetwork network,
                      boolean bestEffort,
                      @Nullable List<String> missingOut) {
        this(level, index, keyedCounts, preferredRecipes, player, network, bestEffort,
                missingOut, resolveDeadlineNanos(), false);
    }

    ResolutionContext(Level level,
                      Map<Item, List<RecipeIndex.Entry>> index,
                      Map<CraftingResolver.StackKey, Integer> keyedCounts,
                      @Nullable Map<ResourceLocation, ResourceLocation> preferredRecipes,
                      @Nullable ServerPlayer player,
                      @Nullable INetwork network,
                      boolean bestEffort,
                      @Nullable List<String> missingOut,
                      long deadlineNanos,
                      boolean abortOnTimeout) {
        this.level = level;
        this.index = index;
        this.counts = new LinkedHashMap<>(keyedCounts);
        this.steps = new ArrayList<>();
        this.resolving = new HashSet<>();
        this.resolvingOutputs = new HashSet<>();
        this.preferredRecipes = preferredRecipes;
        this.player = player;
        this.network = network;
        this.deadlineNanos = deadlineNanos;
        this.abortOnTimeout = abortOnTimeout;
        this.bestEffort = bestEffort;
        this.missingOut = missingOut;
        this.diagLog = Diagnostics.isEnabled() ? new ArrayList<>() : null;
        for (Map.Entry<CraftingResolver.StackKey, Integer> entry : keyedCounts.entrySet()) {
            if (entry.getValue() > 0) {
                MaterialKey material = MaterialKey.of(entry.getKey().toStack());
                addSupply(newSupply(material, new MaterialSource.InitialPool(material), entry.getValue()));
            }
        }
        buildInventoryIndex();
    }

    void diag(String msg) {
        if (diagLog != null) diagLog.add(msg);
    }

    void pushConversionFamily(Set<Item> family) {
        if (family != null && !family.isEmpty()) activeConversionFamilies.addLast(family);
    }

    void popConversionFamily(Set<Item> family) {
        if (family != null && !family.isEmpty()) activeConversionFamilies.removeLastOccurrence(family);
    }

    boolean shouldSkipActiveConversion(net.minecraft.world.item.crafting.CraftingRecipe recipe,
                                       ItemStack output) {
        for (Set<Item> family : activeConversionFamilies) {
            if (NonProductiveTagConversionGuard.shouldSkipForFamily(family, recipe, output)) return true;
        }
        return false;
    }

    void recordDepthGuard(int depth, int maxDepth) {
        depthGuardHits++;
        deepestGuardDepth = Math.max(deepestGuardDepth, depth);
        depthGuardLimit = maxDepth;
    }

    void recordStepGuard(int steps, int maxSteps) {
        stepGuardHits++;
        largestGuardStepCount = Math.max(largestGuardStepCount, steps);
        stepGuardLimit = maxSteps;
    }

    void logGuardSummary() {
        if (depthGuardHits == 0 && stepGuardHits == 0) return;
        RSIntegrationMod.debug("[RSI-Step] resolution guards depthHits={} deepestDepth={}/{} "
                        + "stepHits={} largestStepCount={}/{}",
                depthGuardHits, deepestGuardDepth, depthGuardLimit,
                stepGuardHits, largestGuardStepCount, stepGuardLimit);
    }

    boolean timedOut() {
        boolean expired = System.nanoTime() - deadlineNanos >= 0L;
        if (expired && abortOnTimeout) throw new CraftingPlanningTimeoutException();
        return expired;
    }

    static long deadlineAfterMillis(int timeoutMs) {
        long started = System.nanoTime();
        long budget = Math.max(1L, timeoutMs) * 1_000_000L;
        long deadline = started + budget;
        return deadline < started ? Long.MAX_VALUE : deadline;
    }

    void beginUndo() {
        undoCheckpoints.push(undoStack.size());
        supplyUndoCheckpoints.push(supplyUndoStack.size());
        stepCheckpoints.push(steps.size());
        graphNodeCheckpoints.push(graphNodes.size());
        graphAllocationCheckpoints.push(graphAllocations.size());
        graphUnresolvedCheckpoints.push(graphUnresolved.size());
        supplyCheckpoints.push(supplies.size());
        nodeIdCheckpoints.push(nextNodeId);
        allocationIdCheckpoints.push(nextAllocationId);
    }

    void commitUndo() {
        undoCheckpoints.pop();
        supplyUndoCheckpoints.pop();
        stepCheckpoints.pop();
        graphNodeCheckpoints.pop();
        graphAllocationCheckpoints.pop();
        graphUnresolvedCheckpoints.pop();
        supplyCheckpoints.pop();
        nodeIdCheckpoints.pop();
        allocationIdCheckpoints.pop();
        if (undoCheckpoints.isEmpty()) {
            undoStack.clear();
            supplyUndoStack.clear();
        }
    }

    void rollback() {
        int ucp = undoCheckpoints.pop();
        int supplyUndoCp = supplyUndoCheckpoints.pop();
        int scp = stepCheckpoints.pop();
        int ncp = graphNodeCheckpoints.pop();
        int acp = graphAllocationCheckpoints.pop();
        int unresolvedCp = graphUnresolvedCheckpoints.pop();
        int supplyCount = supplyCheckpoints.pop();
        nextNodeId = nodeIdCheckpoints.pop();
        nextAllocationId = allocationIdCheckpoints.pop();
        while (undoStack.size() > ucp) {
            UndoEntry e = undoStack.pop();
            if (e.oldValue == null) counts.remove(e.key);
            else counts.put(e.key, e.oldValue);
        }
        while (steps.size() > scp) steps.remove(steps.size() - 1);
        while (graphNodes.size() > ncp) graphNodes.remove(graphNodes.size() - 1);
        while (graphAllocations.size() > acp) graphAllocations.remove(graphAllocations.size() - 1);
        while (graphUnresolved.size() > unresolvedCp) graphUnresolved.remove(graphUnresolved.size() - 1);
        while (supplyUndoStack.size() > supplyUndoCp) {
            SupplyUndoEntry entry = supplyUndoStack.pop();
            entry.supply.remaining = entry.oldRemaining;
        }
        while (supplies.size() > supplyCount) removeLastSupply();
        if (undoCheckpoints.isEmpty()) {
            undoStack.clear();
            supplyUndoStack.clear();
        }
    }

    void add(ItemStack stack) {
        if (stack.isEmpty() || stack.getCount() <= 0) return;
        addCount(stack);
    }

    private void addInitial(ItemStack stack) {
        if (stack.isEmpty() || stack.getCount() <= 0) return;
        addCount(stack);
        MaterialKey material = MaterialKey.of(stack);
        addSupply(newSupply(material, new MaterialSource.InitialPool(material), stack.getCount()));
    }

    void addProduced(ItemStack stack, MaterialSource.ProducerOutput source) {
        if (stack.isEmpty() || stack.getCount() <= 0) return;
        addCount(stack);
        addSupply(newSupply(MaterialKey.of(stack), source, stack.getCount()));
    }

    private SupplyLot newSupply(MaterialKey material, MaterialSource source, int count) {
        return new SupplyLot(material, source, count);
    }

    private void addSupply(SupplyLot supply) {
        supplies.add(supply);
        suppliesByMaterial.computeIfAbsent(supply.material, ignored -> new ArrayList<>())
                .add(supply);
    }

    private void removeLastSupply() {
        SupplyLot supply = supplies.remove(supplies.size() - 1);
        List<SupplyLot> materialSupplies = suppliesByMaterial.get(supply.material);
        if (materialSupplies == null) return;
        materialSupplies.remove(supply);
        if (materialSupplies.isEmpty()) suppliesByMaterial.remove(supply.material);
    }

    private void addCount(ItemStack stack) {
        CraftingResolver.StackKey key = CraftingResolver.StackKey.of(stack, true);
        if (!undoCheckpoints.isEmpty()) undoStack.push(new UndoEntry(key, counts.get(key)));
        Integer prev = counts.get(key);
        counts.merge(key, stack.getCount(), Integer::sum);
        if (prev == null) indexStack(key);
    }

    void add(Item item, int count) {
        if (count <= 0) return;
        ItemStack stack = new ItemStack(item, count);
        addCount(stack);
    }

    NodeId allocateNodeId() {
        return new NodeId(nextNodeId++);
    }

    void addGraphNode(CraftNode node) {
        graphNodes.add(node);
    }

    void addAllocation(InputPortId consumer, SupplySlice slice) {
        graphAllocations.add(new MaterialAllocation(new AllocationId(nextAllocationId++), consumer,
                slice.source(), slice.material(), slice.quantity()));
    }

    void addUnresolved(InputPortId consumer, Ingredient ingredient, int quantity) {
        ItemStack[] display = ingredient.getItems();
        ItemStack hint = display.length == 0 ? ItemStack.EMPTY : display[0];
        graphUnresolved.add(new UnresolvedDemand(consumer, ingredient, quantity, hint));
    }

    private void indexStack(CraftingResolver.StackKey key) {
        if (stacksByKey.containsKey(key)) return;
        CachedStack cachedStack = new CachedStack(key);
        stacksByKey.put(key, cachedStack);
        List<CachedStack> stacks = inventoryIndex.computeIfAbsent(key.item(), k -> new ArrayList<>());
        // A key removed during a speculative branch remains cached in this index.
        // Re-adding it after rollback must not append a duplicate candidate, or
        // countMatching would count the same physical stack more than once.
        stacks.add(cachedStack);
    }

    int countMatching(Ingredient ingredient) {
        if (ingredient.isEmpty()) return 0;

        int total = 0;
        Set<Item> checkedItems = new HashSet<>();

        // Inverted-index fast path: for each Item the ingredient accepts,
        // do an O(1) lookup in inventoryIndex instead of scanning all 546+ types.
        for (ItemStack template : ingredient.getItems()) {
            if (template.isEmpty()) continue;
            Item targetItem = template.getItem();
            if (!checkedItems.add(targetItem)) continue; // dedupe repeated Items

            List<CachedStack> candidates = inventoryIndex.get(targetItem);
            if (candidates == null) continue; // nothing of this Item in storage

            for (CachedStack candidate : candidates) {
                // Use the pre-created ItemStack — zero allocation per call
                if (IngredientMatcher.test(ingredient, candidate.stack)) {
                    total += counts.getOrDefault(candidate.key, 0);
                }
            }
        }
        // Some CraftTweaker ingredient wrappers do not expose a reliable display
        // stack array even though test(actualStack) implements the real NBT rule.
        // Fall back to the authoritative keyed inventory scan when the fast path
        // had no item keys, or when every displayed option is NBT-constrained.
        boolean constrained = checkedItems.isEmpty();
        if (!constrained) {
            constrained = true;
            for (ItemStack template : ingredient.getItems()) {
                if (!template.isEmpty() && !template.hasTag()) {
                    constrained = false;
                    break;
                }
            }
        }
        if (constrained) {
            total = 0;
            for (Map.Entry<CraftingResolver.StackKey, Integer> entry : counts.entrySet()) {
                if (entry.getValue() > 0 && matches(ingredient, entry.getKey())) {
                    total += entry.getValue();
                }
            }
        }
        return total;
    }

    SupplyConsumption consumeMatchingDetailed(Ingredient ingredient, int needed) {
        int remaining = needed;
        List<SupplySlice> slices = new ArrayList<>();
        List<CraftingResolver.StackKey> sortedKeys = sortedMatchingKeys(ingredient);

        for (CraftingResolver.StackKey key : sortedKeys) {
            if (remaining <= 0) break;
            int available = counts.getOrDefault(key, 0);
            if (available <= 0 || !matches(ingredient, key)) continue;
            int take = Math.min(available, remaining);
            CachedStack cached = stacksByKey.get(key);
            MaterialKey material = cached != null
                    ? cached.material : MaterialKey.of(key.toStack());
            int supplied = consumeSupplyLots(material, take, slices);
            if (supplied != take) {
                return new SupplyConsumption(List.copyOf(slices), needed, needed - remaining);
            }
            decrement(key, take);
            remaining -= take;
            addRemainder(key, take);
        }
        return new SupplyConsumption(List.copyOf(slices), needed, needed - remaining);
    }

    boolean consumeMatching(Ingredient ingredient, int needed) {
        int remaining = needed;
        for (CraftingResolver.StackKey key : sortedMatchingKeys(ingredient)) {
            if (remaining <= 0) return true;
            int available = counts.getOrDefault(key, 0);
            if (available <= 0 || !matches(ingredient, key)) continue;
            int take = Math.min(available, remaining);
            decrement(key, take);
            remaining -= take;
            addRemainder(key, take);
        }
        return remaining <= 0;
    }

    private List<CraftingResolver.StackKey> sortedMatchingKeys(Ingredient ingredient) {
        ItemStack[] templates = ingredient.getItems();
        Set<Item> ingredientItems = new LinkedHashSet<>();
        // Custom Ingredient subclasses may accept stacks that are not exposed by
        // getItems(). Only vanilla's concrete Ingredient has a complete candidate list.
        boolean requiresAuthoritativeScan = ingredient.getClass() != Ingredient.class
                || templates.length == 0;
        if (!requiresAuthoritativeScan) {
            requiresAuthoritativeScan = true;
            for (ItemStack template : templates) {
                if (template.isEmpty()) continue;
                ingredientItems.add(template.getItem());
                if (!template.hasTag()) requiresAuthoritativeScan = false;
            }
            if (ingredientItems.isEmpty()) requiresAuthoritativeScan = true;
        }

        List<CraftingResolver.StackKey> sortedKeys;
        if (requiresAuthoritativeScan) {
            sortedKeys = new ArrayList<>(counts.keySet());
        } else {
            Set<CraftingResolver.StackKey> matchingItems = new LinkedHashSet<>();
            for (Item item : ingredientItems) {
                List<CachedStack> cached = inventoryIndex.get(item);
                if (cached == null) continue;
                for (CachedStack stack : cached) matchingItems.add(stack.key);
            }
            sortedKeys = new ArrayList<>(matchingItems);
        }
        sortedKeys.sort(Comparator.comparing((CraftingResolver.StackKey k) -> k.tag() != null)
                .thenComparing(k -> {
                    var rl = ForgeRegistries.ITEMS.getKey(k.item());
                    return rl != null ? rl.toString() : "";
                }));
        return sortedKeys;
    }

    private boolean matches(Ingredient ingredient, CraftingResolver.StackKey key) {
        CachedStack cached = stacksByKey.get(key);
        return cached != null
                ? IngredientMatcher.test(ingredient, cached.stack)
                : IngredientMatcher.test(ingredient, key);
    }

    private int consumeSupplyLots(MaterialKey material, int needed, List<SupplySlice> slices) {
        int remaining = needed;
        List<SupplyLot> matchingSupplies = suppliesByMaterial.get(material);
        if (matchingSupplies == null) return 0;
        for (SupplyLot supply : matchingSupplies) {
            if (remaining <= 0) break;
            if (supply.remaining <= 0) continue;
            int take = Math.min(supply.remaining, remaining);
            if (!supplyUndoCheckpoints.isEmpty()) {
                supplyUndoStack.push(new SupplyUndoEntry(supply, supply.remaining));
            }
            supply.consume(take);
            slices.add(new SupplySlice(supply.source, supply.material, take));
            remaining -= take;
        }
        return needed - remaining;
    }

    private void addRemainder(CraftingResolver.StackKey key, int count) {
        try {
            CachedStack cached = stacksByKey.get(key);
            ItemStack source = cached != null ? cached.stack : key.toStack();
            ItemStack remainder = source.getCraftingRemainingItem();
            if (!remainder.isEmpty()) add(remainder.copyWithCount(count));
        } catch (Exception ignored) {
            // Defensive against broken remainder implementations.
        }
    }

    private void decrement(CraftingResolver.StackKey key, int amount) {
        if (!undoCheckpoints.isEmpty()) undoStack.push(new UndoEntry(key, counts.get(key)));
        int current = counts.getOrDefault(key, 0);
        int newCount = current - amount;
        if (newCount <= 0) counts.remove(key);
        else counts.put(key, newCount);
    }

    // ── inverted index ──────────────────────────────────────────

    /** Populate the Item→CachedStack index from {@link #counts}. Called once per context. */
    private void buildInventoryIndex() {
        inventoryIndex.clear();
        stacksByKey.clear();
        for (var entry : counts.entrySet()) {
            indexStack(entry.getKey());
        }
    }

    // ── inner types ──────────────────────────────────────────────

    record SupplySlice(MaterialSource source, MaterialKey material, int quantity) {
        SupplySlice {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(material, "material");
            if (quantity <= 0) throw new IllegalArgumentException("supply quantity must be positive");
        }
    }

    record SupplyConsumption(List<SupplySlice> slices, int requested, int supplied) {
        SupplyConsumption {
            slices = List.copyOf(slices);
            if (requested < 0 || supplied < 0 || supplied > requested) {
                throw new IllegalArgumentException("invalid supply consumption");
            }
        }

        boolean complete() {
            return supplied == requested;
        }
    }

    private static final class SupplyLot {
        final MaterialKey material;
        final MaterialSource source;
        int remaining;

        SupplyLot(MaterialKey material, MaterialSource source, int remaining) {
            this.material = Objects.requireNonNull(material, "material");
            this.source = Objects.requireNonNull(source, "source");
            if (remaining <= 0) throw new IllegalArgumentException("supply amount must be positive");
            this.remaining = remaining;
        }

        void consume(int amount) {
            if (amount <= 0 || amount > remaining) {
                throw new IllegalArgumentException("invalid supply consumption: " + amount);
            }
            remaining -= amount;
        }
    }

    private static final class SupplyUndoEntry {
        final SupplyLot supply;
        final int oldRemaining;

        SupplyUndoEntry(SupplyLot supply, int oldRemaining) {
            this.supply = supply;
            this.oldRemaining = oldRemaining;
        }
    }

    /** Pre-created ItemStack to avoid allocating 200K+ ItemStack instances
     *  during candidate scoring.  The ItemStack is created once at index
     *  build time and reused for every ingredient.test() call. */
    private static class CachedStack {
        final CraftingResolver.StackKey key;
        final ItemStack stack;
        final MaterialKey material;

        CachedStack(CraftingResolver.StackKey key) {
            this.key = key;
            this.stack = new ItemStack(key.item());
            if (key.tag() != null) {
                try {
                    this.stack.setTag(net.minecraft.nbt.TagParser.parseTag(key.tag()));
                } catch (Exception e) { /* defensive — invalid NBT falls back to tag-less stack */ }
            }
            this.material = MaterialKey.of(this.stack);
        }
    }

    private static final class UndoEntry {
        final CraftingResolver.StackKey key;
        @Nullable final Integer oldValue;
        UndoEntry(CraftingResolver.StackKey key, Integer oldValue) {
            this.key = key;
            this.oldValue = oldValue;
        }
    }
}
