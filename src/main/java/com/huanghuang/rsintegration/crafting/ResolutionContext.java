package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
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
    }

    ResolutionContext(Level level,
                      Map<Item, List<RecipeIndex.Entry>> index,
                      Map<CraftingResolver.StackKey, Integer> keyedCounts,
                      @Nullable Map<ResourceLocation, ResourceLocation> preferredRecipes,
                      @Nullable ServerPlayer player,
                      @Nullable INetwork network,
                      boolean unused) {
        this.level = level;
        this.index = index;
        this.counts = new LinkedHashMap<>(keyedCounts);
        this.steps = new ArrayList<>();
        this.resolving = new HashSet<>();
        this.preferredRecipes = preferredRecipes;
        this.player = player;
        this.network = network;
        this.deadlineNanos = System.nanoTime() + MAX_RESOLVE_NANOS;
        this.bestEffort = false;
        this.missingOut = null;
        this.diagLog = Diagnostics.isEnabled() ? new ArrayList<>() : null;
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
        counts.merge(key, stack.getCount(), Integer::sum);
    }

    void add(Item item, int count) {
        if (count <= 0) return;
        CraftingResolver.StackKey key = new CraftingResolver.StackKey(item, null);
        if (!undoCheckpoints.isEmpty()) undoStack.push(new UndoEntry(key, counts.get(key)));
        counts.merge(key, count, Integer::sum);
    }

    int countMatching(Ingredient ingredient) {
        int total = 0;
        for (var entry : counts.entrySet()) {
            if (entry.getValue() > 0) {
                ItemStack stack = entry.getKey().toStack();
                if (ingredient.test(stack)) total += entry.getValue();
            }
        }
        return total;
    }

    boolean consumeMatching(Ingredient ingredient, int needed) {
        int remaining = needed;
        List<CraftingResolver.StackKey> sortedKeys = new ArrayList<>(counts.keySet());
        sortedKeys.sort(Comparator.comparing((CraftingResolver.StackKey k) -> k.tag() != null)
                .thenComparing(k -> ForgeRegistries.ITEMS.getKey(k.item()).toString()));

        for (CraftingResolver.StackKey key : sortedKeys) {
            if (remaining <= 0) return true;
            int available = counts.getOrDefault(key, 0);
            if (available <= 0) continue;
            if (!ingredient.test(key.toStack())) continue;
            int take = Math.min(available, remaining);
            decrement(key, take);
            remaining -= take;
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

    // ── inner types ──────────────────────────────────────────────

    private static final class UndoEntry {
        final CraftingResolver.StackKey key;
        @Nullable final Integer oldValue;
        UndoEntry(CraftingResolver.StackKey key, @Nullable Integer oldValue) {
            this.key = key;
            this.oldValue = oldValue;
        }
    }
}
