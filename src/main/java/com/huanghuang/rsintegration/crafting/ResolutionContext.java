package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
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

    static final long MAX_RESOLVE_NANOS = 500_000_000L; // 500ms

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
        this.deadlineNanos = System.nanoTime() + MAX_RESOLVE_NANOS;
        this.bestEffort = false;
        this.missingOut = null;
        this.diagLog = Diagnostics.isEnabled() ? new ArrayList<>() : null;

        for (ItemStack stack : available) add(stack);
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
        this.deadlineNanos = System.nanoTime() + MAX_RESOLVE_NANOS;
        this.bestEffort = bestEffort;
        this.missingOut = missingOut;
        this.diagLog = Diagnostics.isEnabled() ? new ArrayList<>() : null;
        buildInventoryIndex();
    }

    void diag(String msg) {
        if (diagLog != null) diagLog.add(msg);
    }

    boolean timedOut() { return System.nanoTime() > deadlineNanos; }

    void beginUndo() {
        undoCheckpoints.push(undoStack.size());
        stepCheckpoints.push(steps.size());
    }

    void commitUndo() {
        undoCheckpoints.pop();
        stepCheckpoints.pop();
        if (undoCheckpoints.isEmpty()) undoStack.clear();
    }

    void rollback() {
        int ucp = undoCheckpoints.pop();
        int scp = stepCheckpoints.pop();
        while (undoStack.size() > ucp) {
            UndoEntry e = undoStack.pop();
            if (e.oldValue == null) counts.remove(e.key);
            else counts.put(e.key, e.oldValue);
        }
        while (steps.size() > scp) steps.remove(steps.size() - 1);
        if (undoCheckpoints.isEmpty()) undoStack.clear();
    }

    void add(ItemStack stack) {
        if (stack.isEmpty() || stack.getCount() <= 0) return;
        CraftingResolver.StackKey key = CraftingResolver.StackKey.of(stack, true);
        if (!undoCheckpoints.isEmpty()) undoStack.push(new UndoEntry(key, counts.get(key)));
        Integer prev = counts.get(key);
        counts.merge(key, stack.getCount(), Integer::sum);
        if (prev == null) indexStack(key); // new item type — add to inverted index
    }

    void add(Item item, int count) {
        if (count <= 0) return;
        CraftingResolver.StackKey key = new CraftingResolver.StackKey(item, null);
        if (!undoCheckpoints.isEmpty()) undoStack.push(new UndoEntry(key, counts.get(key)));
        Integer prev = counts.get(key);
        counts.merge(key, count, Integer::sum);
        if (prev == null) indexStack(key);
    }

    private void indexStack(CraftingResolver.StackKey key) {
        inventoryIndex.computeIfAbsent(key.item(), k -> new ArrayList<>())
                .add(new CachedStack(key));
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
                if (ingredient.test(candidate.stack)
                        || SlashBladeRecipeHandler.matchesStackKey(ingredient, candidate.key)
                        || matchesSlashBladeFallback(ingredient, candidate.key)) {
                    total += counts.getOrDefault(candidate.key, 0);
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

    boolean consumeMatching(Ingredient ingredient, int needed) {
        int remaining = needed;
        List<CraftingResolver.StackKey> sortedKeys = new ArrayList<>(counts.keySet());
        sortedKeys.sort(Comparator.comparing((CraftingResolver.StackKey k) -> k.tag() != null)
                .thenComparing(k -> {
                    var rl = ForgeRegistries.ITEMS.getKey(k.item());
                    return rl != null ? rl.toString() : "";
                }));

        for (CraftingResolver.StackKey key : sortedKeys) {
            if (remaining <= 0) return true;
            int available = counts.getOrDefault(key, 0);
            if (available <= 0) continue;
            if (!ingredient.test(key.toStack()) && !SlashBladeRecipeHandler.matchesStackKey(ingredient, key)
                    && !matchesSlashBladeFallback(ingredient, key)) continue;
            int take = Math.min(available, remaining);
            decrement(key, take);
            remaining -= take;
            // Return crafting remainder immediately so it stays available
            // for subsequent ingredient groups within the same recipe.
            try {
                ItemStack remainder = key.toStack().getCraftingRemainingItem();
                if (!remainder.isEmpty()) {
                    add(remainder.copyWithCount(take));
                }
            } catch (Exception e) {
                // defensive — broken getCraftingRemainingItem implementations
            }
        }
        return remaining <= 0;
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
