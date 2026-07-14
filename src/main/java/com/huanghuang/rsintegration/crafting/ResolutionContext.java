package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.graph.AllocationId;
import com.huanghuang.rsintegration.crafting.graph.CraftNode;
import com.huanghuang.rsintegration.crafting.graph.InputPortId;
import com.huanghuang.rsintegration.crafting.graph.MaterialAllocation;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.graph.MaterialSource;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
import com.huanghuang.rsintegration.crafting.graph.UnresolvedDemand;
import com.huanghuang.rsintegration.recipe.SlashBladeRecipeHandler;
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

    static final long MAX_RESOLVE_NANOS = 500_000_000L; // 500ms — fallback if config unavailable

    /** Resolve deadline from config (ms → ns), falling back to the 500ms default
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
    final long deadlineNanos;
    int ensureCalls;
    final Deque<UndoEntry> undoStack = new ArrayDeque<>();
    final Deque<Integer> undoCheckpoints = new ArrayDeque<>();
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
    int nextNodeId;
    long nextAllocationId;
    @Nullable final Map<ResourceLocation, ResourceLocation> preferredRecipes;
    @Nullable final ServerPlayer player;
    @Nullable final INetwork network;
    final boolean bestEffort;
    @Nullable final List<String> missingOut;
    @Nullable final List<String> diagLog;

    // Inverted index: Item → pre-cached stacks, built once from initial inventory.
    // Eliminates the O(N) scan of all 546+ inventory types inside countMatching.
    private final Map<Item, List<CachedStack>> inventoryIndex = new HashMap<>();

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
        this.preferredRecipes = preferredRecipes;
        this.player = player;
        this.network = network;
        this.deadlineNanos = resolveDeadlineNanos();
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
                      @Nullable ServerPlayer player,
                      @Nullable INetwork network,
                      boolean bestEffort,
                      @Nullable List<String> missingOut) {
        this.level = level;
        this.index = index;
        this.counts = new LinkedHashMap<>(keyedCounts);
        this.steps = new ArrayList<>();
        this.resolving = new HashSet<>();
        this.preferredRecipes = preferredRecipes;
        this.player = player;
        this.network = network;
        this.deadlineNanos = resolveDeadlineNanos();
        this.bestEffort = bestEffort;
        this.missingOut = missingOut;
        this.diagLog = Diagnostics.isEnabled() ? new ArrayList<>() : null;
        for (Map.Entry<CraftingResolver.StackKey, Integer> entry : keyedCounts.entrySet()) {
            if (entry.getValue() > 0) {
                MaterialKey material = MaterialKey.of(entry.getKey().toStack());
                supplies.add(newSupply(material, new MaterialSource.InitialPool(material), entry.getValue()));
            }
        }
        buildInventoryIndex();
    }

    void diag(String msg) {
        if (diagLog != null) diagLog.add(msg);
    }

    boolean timedOut() { return System.nanoTime() > deadlineNanos; }

    void beginUndo() {
        undoCheckpoints.push(undoStack.size());
        stepCheckpoints.push(steps.size());
        graphNodeCheckpoints.push(graphNodes.size());
        graphAllocationCheckpoints.push(graphAllocations.size());
        graphUnresolvedCheckpoints.push(graphUnresolved.size());
        supplyCheckpoints.push(supplies.size());
        nodeIdCheckpoints.push(nextNodeId);
        allocationIdCheckpoints.push(nextAllocationId);
        for (SupplyLot supply : supplies) supply.beginUndo();
    }

    void commitUndo() {
        undoCheckpoints.pop();
        stepCheckpoints.pop();
        graphNodeCheckpoints.pop();
        graphAllocationCheckpoints.pop();
        graphUnresolvedCheckpoints.pop();
        supplyCheckpoints.pop();
        nodeIdCheckpoints.pop();
        allocationIdCheckpoints.pop();
        for (SupplyLot supply : supplies) supply.commitUndo();
        if (undoCheckpoints.isEmpty()) undoStack.clear();
    }

    void rollback() {
        int ucp = undoCheckpoints.pop();
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
        while (supplies.size() > supplyCount) supplies.remove(supplies.size() - 1);
        for (SupplyLot supply : supplies) supply.rollback();
        if (undoCheckpoints.isEmpty()) undoStack.clear();
    }

    void add(ItemStack stack) {
        if (stack.isEmpty() || stack.getCount() <= 0) return;
        addCount(stack);
    }

    private void addInitial(ItemStack stack) {
        if (stack.isEmpty() || stack.getCount() <= 0) return;
        addCount(stack);
        MaterialKey material = MaterialKey.of(stack);
        supplies.add(newSupply(material, new MaterialSource.InitialPool(material), stack.getCount()));
    }

    void addProduced(ItemStack stack, MaterialSource.ProducerOutput source) {
        if (stack.isEmpty() || stack.getCount() <= 0) return;
        addCount(stack);
        supplies.add(newSupply(MaterialKey.of(stack), source, stack.getCount()));
    }

    private SupplyLot newSupply(MaterialKey material, MaterialSource source, int count) {
        SupplyLot supply = new SupplyLot(material, source, count);
        for (int i = 0; i < undoCheckpoints.size(); i++) supply.beginUndo();
        return supply;
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
        List<CachedStack> stacks = inventoryIndex.computeIfAbsent(key.item(), k -> new ArrayList<>());
        // A key removed during a speculative branch remains cached in this index.
        // Re-adding it after rollback must not append a duplicate candidate, or
        // countMatching would count the same physical stack more than once.
        for (CachedStack cached : stacks) {
            if (cached.key.equals(key)) return;
        }
        stacks.add(new CachedStack(key));
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
                if (IngredientMatcher.test(ingredient, candidate.stack)
                        || SlashBladeRecipeHandler.matchesStackKey(ingredient, candidate.key)
                        || matchesSlashBladeFallback(ingredient, candidate.key)) {
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
                if (entry.getValue() > 0 && IngredientMatcher.test(ingredient, entry.getKey().toStack())) {
                    total += entry.getValue();
                }
            }
        }
        return total;
    }

    private static boolean matchesSlashBladeFallback(Ingredient ingredient, CraftingResolver.StackKey key) {
        if (!SlashBladeRecipeHandler.isSlashBladeIngredient(ingredient)) return false;
        if (key.tag() != null) return false; // handled by matchesStackKey
        for (ItemStack ingItem : ingredient.getItems()) {
            if (!ingItem.isEmpty() && ingItem.getItem() == key.item()) return true;
        }
        return false;
    }

    SupplyConsumption consumeMatchingDetailed(Ingredient ingredient, int needed) {
        int remaining = needed;
        List<SupplySlice> slices = new ArrayList<>();
        List<CraftingResolver.StackKey> sortedKeys = sortedMatchingKeys();

        for (CraftingResolver.StackKey key : sortedKeys) {
            if (remaining <= 0) break;
            int available = counts.getOrDefault(key, 0);
            if (available <= 0 || !matches(ingredient, key)) continue;
            int take = Math.min(available, remaining);
            int supplied = consumeSupplyLots(MaterialKey.of(key.toStack()), take, slices);
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
        for (CraftingResolver.StackKey key : sortedMatchingKeys()) {
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

    private List<CraftingResolver.StackKey> sortedMatchingKeys() {
        List<CraftingResolver.StackKey> sortedKeys = new ArrayList<>(counts.keySet());
        sortedKeys.sort(Comparator.comparing((CraftingResolver.StackKey k) -> k.tag() != null)
                .thenComparing(k -> {
                    var rl = ForgeRegistries.ITEMS.getKey(k.item());
                    return rl != null ? rl.toString() : "";
                }));
        return sortedKeys;
    }

    private static boolean matches(Ingredient ingredient, CraftingResolver.StackKey key) {
        return IngredientMatcher.test(ingredient, key.toStack())
                || SlashBladeRecipeHandler.matchesStackKey(ingredient, key)
                || matchesSlashBladeFallback(ingredient, key);
    }

    private int consumeSupplyLots(MaterialKey material, int needed, List<SupplySlice> slices) {
        int remaining = needed;
        for (SupplyLot supply : supplies) {
            if (remaining <= 0) break;
            if (!supply.material.equals(material) || supply.remaining <= 0) continue;
            int take = Math.min(supply.remaining, remaining);
            supply.consume(take);
            slices.add(new SupplySlice(supply.source, supply.material, take));
            remaining -= take;
        }
        return needed - remaining;
    }

    private void addRemainder(CraftingResolver.StackKey key, int count) {
        try {
            ItemStack remainder = key.toStack().getCraftingRemainingItem();
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
        for (var entry : counts.entrySet()) {
            inventoryIndex.computeIfAbsent(entry.getKey().item(), k -> new ArrayList<>())
                    .add(new CachedStack(entry.getKey()));
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
        final Deque<Integer> checkpoints = new ArrayDeque<>();
        int remaining;

        SupplyLot(MaterialKey material, MaterialSource source, int remaining) {
            this.material = Objects.requireNonNull(material, "material");
            this.source = Objects.requireNonNull(source, "source");
            if (remaining <= 0) throw new IllegalArgumentException("supply amount must be positive");
            this.remaining = remaining;
        }

        void beginUndo() {
            checkpoints.push(remaining);
        }

        void commitUndo() {
            checkpoints.pop();
        }

        void rollback() {
            remaining = checkpoints.pop();
        }

        void consume(int amount) {
            if (amount <= 0 || amount > remaining) {
                throw new IllegalArgumentException("invalid supply consumption: " + amount);
            }
            remaining -= amount;
        }
    }

    /** Pre-created ItemStack to avoid allocating 200K+ ItemStack instances
     *  during candidate scoring.  The ItemStack is created once at index
     *  build time and reused for every ingredient.test() call. */
    private static class CachedStack {
        final CraftingResolver.StackKey key;
        final ItemStack stack;

        CachedStack(CraftingResolver.StackKey key) {
            this.key = key;
            this.stack = new ItemStack(key.item());
            if (key.tag() != null) {
                try {
                    this.stack.setTag(net.minecraft.nbt.TagParser.parseTag(key.tag()));
                } catch (Exception e) { /* defensive — invalid NBT falls back to tag-less stack */ }
            }
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
