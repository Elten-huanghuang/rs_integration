package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.integration.AltarBindingRegistry;
import com.huanghuang.rsintegration.integration.RSIntegration;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.util.Action;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;
import net.minecraftforge.items.ItemHandlerHelper;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Deferred extraction ledger.
 *
 * Call {@link #reserve} for each needed ingredient — this checks availability
 * across all sources (altar binding → RS network → player inventory) but does
 * NOT physically extract anything.  Returns a copy of the item that can be
 * placed in machine slots immediately.
 *
 * Once all reservations succeed, call {@link #commit} to perform the actual
 * extractions.  If any reservation fails, simply discard the ledger — no
 * cleanup is needed because nothing was physically moved.
 */
public final class ExtractionLedger {

    private final List<Entry> entries = new ArrayList<>();
    private final Map<CraftingResolver.StackKey, Integer> pendingNet = new HashMap<>();
    private final Map<CraftingResolver.StackKey, Integer> pendingInv = new HashMap<>();
    private boolean committed;

    /**
     * Reserve {@code count} items matching {@code ingredient}.
     * Returns an ItemStack copy on success, ItemStack.EMPTY on failure.
     * The returned stack is a copy suitable for slot placement.
     *
     * Uses the actual available item (found in the source) as the extraction
     * template, not {@code ingredient.getItems()[0]}, so tag ingredients
     * resolve to whatever concrete item the player/network actually holds.
     */
    public ItemStack reserve(Ingredient ingredient, int count,
                             @Nullable INetwork network, ServerPlayer player,
                             @Nullable ResourceKey<Level> altarDim, @Nullable BlockPos altarPos) {
        if (count <= 0) return ItemStack.EMPTY;
        if (ingredient.isEmpty()) return ItemStack.EMPTY;

        // 1. Try RS network (includes binding-resolved networks from caller)
        if (network != null) {
            ItemStack matched = findAvailableInNetwork(network, ingredient, count);
            if (!matched.isEmpty()) {
                ItemStack result = matched.copyWithCount(count);
                entries.add(new Entry(Source.NETWORK, result.copy(), null, null, null));
                pendingNet.merge(CraftingResolver.StackKey.of(matched, true), count, Integer::sum);
                return result;
            }
        }

        // 2. Try altar binding (check-only via network from binding data)
        if (altarDim != null && altarPos != null) {
            INetwork bindingNet = AltarBindingRegistry.resolveNetworkForAltar(
                    player, altarDim, altarPos);
            if (bindingNet != null) {
                ItemStack matched = findAvailableInNetwork(bindingNet, ingredient, count);
                if (!matched.isEmpty()) {
                    ItemStack result = matched.copyWithCount(count);
                    entries.add(new Entry(Source.ALTAR_BINDING, result.copy(),
                            null, altarDim, altarPos));
                    pendingNet.merge(CraftingResolver.StackKey.of(matched, true), count, Integer::sum);
                    return result;
                }
            }
        }

        // 3. Try player inventory
        {
            ItemStack matched = findAvailableInInventory(player, ingredient, count);
            if (!matched.isEmpty()) {
                ItemStack result = matched.copyWithCount(count);
                entries.add(new Entry(Source.PLAYER_INVENTORY, result.copy(), null, null, null));
                pendingInv.merge(CraftingResolver.StackKey.of(matched, true), count, Integer::sum);
                return result;
            }
        }

        return ItemStack.EMPTY;
    }

    /**
     * Reserve using only the RS network (used by executeCraftingSteps).
     * Finds the actual matching item in RS cache, accounting for already-reserved
     * items, to avoid mismatches with tag ingredients.
     */
    public ItemStack reserveFromNetwork(Ingredient ingredient, int count, INetwork network) {
        if (count <= 0 || ingredient.isEmpty()) return ItemStack.EMPTY;

        ItemStack matched = findAvailableInNetwork(network, ingredient, count);
        if (matched.isEmpty()) return ItemStack.EMPTY;

        ItemStack template = matched.copyWithCount(count);
        entries.add(new Entry(Source.NETWORK, template.copy(), null, null, null));
        pendingNet.merge(CraftingResolver.StackKey.of(matched, true), count, Integer::sum);
        return template;
    }

    /**
     * Reserve from player inventory only.
     * Finds the actual matching item in the player's inventory, accounting for
     * already-reserved items.
     */
    public ItemStack reserveFromInventory(Ingredient ingredient, int count, ServerPlayer player) {
        if (count <= 0 || ingredient.isEmpty()) return ItemStack.EMPTY;

        ItemStack matched = findAvailableInInventory(player, ingredient, count);
        if (matched.isEmpty()) return ItemStack.EMPTY;

        ItemStack template = matched.copyWithCount(count);
        entries.add(new Entry(Source.PLAYER_INVENTORY, template.copy(), null, null, null));
        pendingInv.merge(CraftingResolver.StackKey.of(matched, true), count, Integer::sum);
        return template;
    }

    /**
     * Execute all deferred extractions.  Must be called exactly once.
     * After commit, this ledger is sealed.
     *
     * @return true if all extractions succeeded
     */
    public boolean commit(@Nullable INetwork network, ServerPlayer player) {
        if (committed) return true;
        committed = true;

        RSIntegrationMod.LOGGER.debug("[RSI-Ledger] commit: {} entries, pendingNet={}",
                entries.size(), pendingNet);

        List<ItemStack> extracted = new ArrayList<>();
        try {
            for (int i = 0; i < entries.size(); i++) {
                Entry entry = entries.get(i);
                RSIntegrationMod.LOGGER.debug("[RSI-Ledger] commit entry {}/{}: source={} item={} count={}",
                        i + 1, entries.size(), entry.source,
                        net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(entry.template.getItem()),
                        entry.count);

                ItemStack item = extractOne(entry, network, player);
                if (item.isEmpty() || item.getCount() < entry.count) {
                    var failKey = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(entry.template.getItem());
                    RSIntegrationMod.LOGGER.error("[RSI-Ledger] commit FAILED at entry {}/{}: source={} item={} need={} got={}",
                            i + 1, entries.size(), entry.source, failKey, entry.count,
                            item.isEmpty() ? 0 : item.getCount());
                    // Rollback already-extracted items
                    for (ItemStack s : extracted) {
                        var rollKey = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem());
                        RSIntegrationMod.LOGGER.debug("[RSI-Ledger] rollback: {}", rollKey);
                        if (network != null) {
                            network.insertItem(s, s.getCount(), Action.PERFORM);
                        } else {
                            ItemHandlerHelper.giveItemToPlayer(player, s);
                        }
                    }
                    return false;
                }
                extracted.add(item);
            }
            RSIntegrationMod.LOGGER.debug("[RSI-Ledger] commit SUCCESS: {} entries", entries.size());
            MaterialSources.invalidateFor(player);
            return true;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Ledger] Commit exception: {}", e.toString());
            for (ItemStack s : extracted) {
                if (network != null) {
                    network.insertItem(s, s.getCount(), Action.PERFORM);
                } else {
                    ItemHandlerHelper.giveItemToPlayer(player, s);
                }
            }
            return false;
        }
    }

    /** Number of reserved entries. */
    public int size() { return entries.size(); }

    /** Whether commit() has been called. */
    public boolean isCommitted() { return committed; }

    /**
     * Reset this ledger to a clean state so it can be reused for the next
     * step in a chain.  Call only after {@link #commit} has succeeded for
     * the previous step.
     */
    public void reset() {
        entries.clear();
        pendingNet.clear();
        pendingInv.clear();
        committed = false;
    }

    // ── internal ──────────────────────────────────────────────────

    private enum Source { NETWORK, PLAYER_INVENTORY, ALTAR_BINDING }

    private static final class Entry {
        final Source source;
        final ItemStack template;  // what to extract
        final int count;
        @Nullable final ItemStack preExtracted; // already extracted from binding
        @Nullable final ResourceKey<Level> altarDim;
        @Nullable final BlockPos altarPos;

        Entry(Source source, ItemStack template, @Nullable ItemStack preExtracted,
              @Nullable ResourceKey<Level> altarDim, @Nullable BlockPos altarPos) {
            this.source = source;
            this.template = template;
            this.count = template.getCount();
            this.preExtracted = preExtracted;
            this.altarDim = altarDim;
            this.altarPos = altarPos;
        }
    }

    private static ItemStack extractOne(Entry entry, @Nullable INetwork network, ServerPlayer player) {
        return switch (entry.source) {
            case ALTAR_BINDING -> {
                if (entry.preExtracted != null) yield entry.preExtracted.copy();
                yield AltarBindingRegistry.tryExtractFromBindings(player,
                        entry.altarDim, entry.altarPos,
                        Ingredient.of(entry.template), entry.count);
            }
            case NETWORK -> {
                if (network == null) yield ItemStack.EMPTY;
                yield RSIntegration.extractFromNetwork(network, Ingredient.of(entry.template), entry.count);
            }
            case PLAYER_INVENTORY -> {
                for (ItemStack stack : player.getInventory().items) {
                    if (Ingredient.of(entry.template).test(stack) && stack.getCount() >= entry.count) {
                        yield stack.split(entry.count);
                    }
                }
                yield ItemStack.EMPTY;
            }
        };
    }

    /**
     * Find a matching stack in the RS network with enough available count
     * after subtracting already-reserved items.  If no single stack has
     * enough, aggregates across all matching stacks — the ledger tracks
     * per-stack reservations, but the actual RS extraction uses an
     * Ingredient that will match any stack at commit time.
     */
    private ItemStack findAvailableInNetwork(INetwork network, Ingredient ingredient, int needed) {
        try {
            var cache = network.getItemStorageCache();
            if (cache == null) return ItemStack.EMPTY;
            ItemStack fallbackTemplate = ItemStack.EMPTY;
            int totalAvailable = 0;
            for (var entry : cache.getList().getStacks()) {
                ItemStack stored = entry.getStack();
                if (!stored.isEmpty() && ingredient.test(stored)) {
                    int available = stored.getCount()
                            - pendingNet.getOrDefault(CraftingResolver.StackKey.of(stored, true), 0);
                    if (available >= needed) {
                        ItemStack template = stored.copy();
                        template.setCount(1);
                        return template;
                    }
                    if (available > 0) {
                        totalAvailable += available;
                        if (fallbackTemplate.isEmpty()) {
                            fallbackTemplate = stored.copy();
                            fallbackTemplate.setCount(1);
                        }
                    }
                }
            }
            if (totalAvailable >= needed && !fallbackTemplate.isEmpty()) {
                return fallbackTemplate;
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Ledger] Network scan failed: {}", e.toString());
        }
        return ItemStack.EMPTY;
    }

    /**
     * Find a matching stack in the player inventory with enough available
     * count after subtracting already-reserved items.  Falls back to
     * aggregating across inventory slots, since items are often split
     * across multiple slots.
     */
    private ItemStack findAvailableInInventory(ServerPlayer player, Ingredient ingredient, int needed) {
        ItemStack fallbackTemplate = ItemStack.EMPTY;
        int totalAvailable = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (ingredient.test(stack)) {
                int available = stack.getCount()
                        - pendingInv.getOrDefault(CraftingResolver.StackKey.of(stack, true), 0);
                if (available >= needed) {
                    ItemStack template = stack.copy();
                    template.setCount(1);
                    return template;
                }
                if (available > 0) {
                    totalAvailable += available;
                    if (fallbackTemplate.isEmpty()) {
                        fallbackTemplate = stack.copy();
                        fallbackTemplate.setCount(1);
                    }
                }
            }
        }
        if (totalAvailable >= needed && !fallbackTemplate.isEmpty()) {
            return fallbackTemplate;
        }
        return ItemStack.EMPTY;
    }

    /** Debug helper: describe pending reservations for logging. */
    public String describePending() {
        StringBuilder sb = new StringBuilder();
        for (var e : pendingNet.entrySet()) {
            if (e.getValue() > 0) {
                net.minecraft.resources.ResourceLocation rl =
                        net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(e.getKey().item());
                if (rl != null) sb.append("net:").append(rl).append("=").append(e.getValue()).append(" ");
            }
        }
        for (var e : pendingInv.entrySet()) {
            if (e.getValue() > 0) {
                net.minecraft.resources.ResourceLocation rl =
                        net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(e.getKey().item());
                if (rl != null) sb.append("inv:").append(rl).append("=").append(e.getValue()).append(" ");
            }
        }
        return sb.toString();
    }

    /**
     * Release previously-reserved items from this ledger without committing.
     * Call when a multi-block step fails after reservation — the reserved
     * stacks are being refunded to the chain's virtual inventory.
     */
    public void releaseReservations(List<ItemStack> stacks) {
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) continue;
            CraftingResolver.StackKey key = CraftingResolver.StackKey.of(stack, true);
            int count = stack.getCount();
            // Remove from pendingNet
            pendingNet.computeIfPresent(key, (k, v) -> {
                int nv = v - count;
                return nv <= 0 ? null : nv;
            });
            // Remove from pendingInv
            pendingInv.computeIfPresent(key, (k, v) -> {
                int nv = v - count;
                return nv <= 0 ? null : nv;
            });
        }
        // Remove the last N matching entries from the ledger
        int toRemove = stacks.size();
        for (int i = entries.size() - 1; i >= 0 && toRemove > 0; i--) {
            Entry e = entries.get(i);
            for (ItemStack stack : stacks) {
                if (!stack.isEmpty() && ItemStack.isSameItem(e.template, stack)
                        && e.count == stack.getCount()) {
                    entries.remove(i);
                    toRemove--;
                    break;
                }
            }
        }
    }
}
