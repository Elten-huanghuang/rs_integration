package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.network.AltarBindingRegistry;
import com.huanghuang.rsintegration.network.RSIntegration;
import com.huanghuang.rsintegration.util.Diagnostics;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.util.Action;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;
import net.minecraftforge.items.ItemHandlerHelper;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class ExtractionLedger {

    public enum State {
        IDLE, RESERVING, RESERVED, COMMITTING, COMMITTED, ROLLED_BACK
    }

    private final List<Entry> entries = new ArrayList<>();
    private final Map<CraftingResolver.StackKey, Integer> pendingNet = new HashMap<>();
    private final Map<CraftingResolver.StackKey, Integer> pendingInv = new HashMap<>();
    private State state = State.IDLE;

    private final Map<INetwork, List<ItemStack>> networkEntryCache = new HashMap<>();

    // ── state guards ─────────────────────────────────────────────

    private void requireState(State... allowed) {
        for (State s : allowed) {
            if (state == s) return;
        }
        throw com.huanghuang.rsintegration.RSICraftException.ledgerStateViolation(
                java.util.Arrays.toString(allowed), state.name());
    }

    private void transition(State to) {
        RSIntegrationMod.LOGGER.debug("[RSI-Ledger] {} → {}", state, to);
        Diagnostics.record(Diagnostics.Category.LEDGER_RESERVE,
                state + "→" + to + " entries=" + entries.size());
        state = to;
    }

    /** Record a per-entry diagnostic before adding it to the list. */
    private void recordEntry(Entry e) {
        ResourceLocation rl = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(e.template.getItem());
        String itemId = rl != null ? rl.toString() : e.template.getDisplayName().getString();
        Diagnostics.record(Diagnostics.Category.LEDGER_RESERVE,
                "reserve item=" + itemId + " count=" + e.count + " src=" + e.source);
        entries.add(e);
    }

    // ── reservations ─────────────────────────────────────────────

    public ItemStack reserve(Ingredient ingredient, int count,
                             @Nullable INetwork network, ServerPlayer player,
                             @Nullable ResourceKey<Level> altarDim, @Nullable BlockPos altarPos) {
        requireState(State.IDLE, State.RESERVING);
        if (count <= 0) return ItemStack.EMPTY;
        if (ingredient.isEmpty()) return ItemStack.EMPTY;

        if (state == State.IDLE) transition(State.RESERVING);

        if (network != null) {
            ItemStack matched = findAvailableInNetwork(network, ingredient, count);
            if (!matched.isEmpty()) {
                ItemStack result = matched.copyWithCount(count);
                // 💡 修复点：传入原始 Ingredient 而不是用 Ingredient.of(result)，解决 Tag 无法批量跨品种提取的 Bug
                // pendingNet already tracked inside findAvailableInNetwork
                recordEntry(new Entry(Source.NETWORK, ingredient, result.copy(), null, null, null, network));
                return result;
            }
        }

        if (altarDim != null && altarPos != null) {
            INetwork bindingNet = AltarBindingRegistry.resolveNetworkForAltar(
                    player, altarDim, altarPos);
            if (bindingNet != null) {
                ItemStack matched = findAvailableInNetwork(bindingNet, ingredient, count);
                if (!matched.isEmpty()) {
                    ItemStack result = matched.copyWithCount(count);
                    recordEntry(new Entry(Source.ALTAR_BINDING, ingredient, result.copy(),
                            null, altarDim, altarPos, bindingNet));
                    // pendingNet already tracked inside findAvailableInNetwork
                    return result;
                }
            }
        }

        {
            ItemStack matched = findAvailableInInventory(player, ingredient, count);
            if (!matched.isEmpty()) {
                ItemStack result = matched.copyWithCount(count);
                recordEntry(new Entry(Source.PLAYER_INVENTORY, ingredient, result.copy(), null, null, null, null));
                // pendingInv already tracked inside findAvailableInInventory
                return result;
            }
        }

        return ItemStack.EMPTY;
    }

    public ItemStack reserveFromNetwork(Ingredient ingredient, int count, INetwork network) {
        requireState(State.IDLE, State.RESERVING);
        if (count <= 0 || ingredient.isEmpty()) return ItemStack.EMPTY;
        if (state == State.IDLE) transition(State.RESERVING);

        ItemStack matched = findAvailableInNetwork(network, ingredient, count);
        if (matched.isEmpty()) return ItemStack.EMPTY;

        ItemStack template = matched.copyWithCount(count);
        recordEntry(new Entry(Source.NETWORK, ingredient, template.copy(), null, null, null, network));
        // pendingNet already tracked inside findAvailableInNetwork
        return template;
    }

    public ItemStack reserveFromInventory(Ingredient ingredient, int count, ServerPlayer player) {
        requireState(State.IDLE, State.RESERVING);
        if (count <= 0 || ingredient.isEmpty()) return ItemStack.EMPTY;
        if (state == State.IDLE) transition(State.RESERVING);

        ItemStack matched = findAvailableInInventory(player, ingredient, count);
        if (matched.isEmpty()) return ItemStack.EMPTY;

        ItemStack template = matched.copyWithCount(count);
        recordEntry(new Entry(Source.PLAYER_INVENTORY, ingredient, template.copy(), null, null, null, null));
        // pendingInv already tracked inside findAvailableInInventory
        return template;
    }

    /**
     * Atomically commit all reservations in three phases:
     * <ol>
     *   <li>Pre-check — verify every entry can still be satisfied</li>
     *   <li>Batch extract — extract all entries; if any fail, roll back everything</li>
     *   <li>Confirm — mark committed</li>
     * </ol>
     */
    public boolean commit(@Nullable INetwork network, ServerPlayer player) {
        if (state == State.COMMITTED) return true;
        // Allow IDLE only for no-op commit (no entries)
        if (state == State.IDLE && entries.isEmpty()) {
            transition(State.COMMITTED);
            return true;
        }
        requireState(State.RESERVING, State.RESERVED);

        if (entries.isEmpty()) {
            transition(State.COMMITTED);
            return true;
        }

        transition(State.COMMITTING);

        // ── Phase 1: Pre-check ──────────────────────────────────
        if (!preCheck(network, player)) {
            RSIntegrationMod.LOGGER.warn("[RSI-Ledger] Commit pre-check failed, rolling back");
            transition(State.ROLLED_BACK);
            return false;
        }

        // ── Phase 2: Batch extract ──────────────────────────────
        List<ExtractRecord> extracted = new ArrayList<>(entries.size());
        try {
            for (Entry entry : entries) {
                ItemStack item = extractOne(entry, network, player);
                if (item.isEmpty() || item.getCount() < entry.count) {
                    RSIntegrationMod.LOGGER.warn("[RSI-Ledger] Commit: extractOne returned empty/insufficient for entry {} (need {}, got {})",
                            entry.id, entry.count, item.getCount());
                    rollbackExtractedPhases(extracted, player);
                    transition(State.ROLLED_BACK);
                    return false;
                }
                extracted.add(new ExtractRecord(entry.source, item.copy(),
                        entry.altarDim, entry.altarPos, entry.sourceNetwork));
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Ledger] Commit exception during extraction", e);
            rollbackExtractedPhases(extracted, player);
            transition(State.ROLLED_BACK);
            return false;
        }

        // ── Phase 3: Confirm ────────────────────────────────────
        MaterialSources.invalidateFor(player);
        pendingNet.clear();
        pendingInv.clear();
        networkEntryCache.clear();
        transition(State.COMMITTED);
        return true;
    }

    /**
     * Pre-check every reserved entry before committing to extraction.
     * Verifies that the total reserved count does not exceed physical
     * availability (correcting for any race with other systems).
     */
    private boolean preCheck(@Nullable INetwork network, ServerPlayer player) {
        if (network != null) {
            int netReserved = 0;
            for (Entry e : entries) {
                if (e.source == Source.NETWORK) netReserved += e.count;
            }
            if (netReserved > 0) {
                // Verify the network still has enough total items to cover
                // all reservations. This is a coarse check — individual
                // entries are verified during the extraction phase.
                var cache = network.getItemStorageCache();
                if (cache == null) return false;
                int netTotal = 0;
                for (var s : cache.getList().getStacks()) {
                    netTotal += s.getStack().getCount();
                }
                if (netTotal < netReserved) {
                    RSIntegrationMod.LOGGER.warn("[RSI-Ledger] Pre-check: network total {} < reserved {}",
                            netTotal, netReserved);
                    return false;
                }
            }
        }
        // Player inventory entries are verified at extraction time
        // since inventory can change between reservation and commit.
        return true;
    }

    /** Return extracted items back to their original source. */
    private static void rollbackExtractedPhases(List<ExtractRecord> extracted, ServerPlayer player) {
        for (ExtractRecord rec : extracted) {
            ItemStack s = rec.stack;
            if (s.isEmpty()) continue;
            switch (rec.source) {
                case ALTAR_BINDING -> {
                    if (rec.sourceNetwork != null) {
                        rec.sourceNetwork.insertItem(s, s.getCount(), Action.PERFORM);
                    } else {
                        ItemHandlerHelper.giveItemToPlayer(player, s);
                    }
                }
                case NETWORK -> {
                    if (rec.sourceNetwork != null) {
                        rec.sourceNetwork.insertItem(s, s.getCount(), Action.PERFORM);
                    } else {
                        ItemHandlerHelper.giveItemToPlayer(player, s);
                    }
                }
                case PLAYER_INVENTORY -> ItemHandlerHelper.giveItemToPlayer(player, s);
            }
        }
    }

    /** Lightweight record for extracted items during commit rollback. */
    private record ExtractRecord(Source source, ItemStack stack,
                                  @Nullable ResourceKey<Level> altarDim,
                                  @Nullable BlockPos altarPos,
                                  @Nullable INetwork sourceNetwork) {}

    /** Explicitly roll back all reservations without committing. */
    public void rollback(ServerPlayer player) {
        requireState(State.IDLE, State.RESERVING, State.RESERVED, State.COMMITTING);
        if (state == State.IDLE || state == State.ROLLED_BACK) return;
        releaseReservations(player);
        transition(State.ROLLED_BACK);
    }

    private void releaseReservations(ServerPlayer player) {
        // Pending reservations were never physically extracted — just clear the
        // tracking maps so future reservations see accurate counts.
        pendingNet.clear();
        pendingInv.clear();
        networkEntryCache.clear();
    }

    public int size() { return entries.size(); }
    public boolean isCommitted() { return state == State.COMMITTED; }
    public State state() { return state; }

    public void reset() {
        entries.clear();
        pendingNet.clear();
        pendingInv.clear();
        networkEntryCache.clear();
        state = State.IDLE;
    }

    private enum Source { NETWORK, PLAYER_INVENTORY, ALTAR_BINDING }

    private static final AtomicInteger entryIdSeq = new AtomicInteger();

    private static final class Entry {
        final int id;
        final Source source;
        final Ingredient originalIngredient;
        final ItemStack template;
        final int count;
        @Nullable final ItemStack preExtracted;
        @Nullable final ResourceKey<Level> altarDim;
        @Nullable final BlockPos altarPos;
        @Nullable final INetwork sourceNetwork;

        Entry(Source source, Ingredient originalIngredient, ItemStack template, @Nullable ItemStack preExtracted,
              @Nullable ResourceKey<Level> altarDim, @Nullable BlockPos altarPos,
              @Nullable INetwork sourceNetwork) {
            this.id = entryIdSeq.incrementAndGet();
            this.source = source;
            this.originalIngredient = originalIngredient;
            this.template = template;
            this.count = template.getCount();
            this.preExtracted = preExtracted;
            this.altarDim = altarDim;
            this.altarPos = altarPos;
            this.sourceNetwork = sourceNetwork;
        }
    }

    private static ItemStack extractOne(Entry entry, @Nullable INetwork network, ServerPlayer player) {
        return switch (entry.source) {
            case ALTAR_BINDING -> {
                if (entry.preExtracted != null) yield entry.preExtracted.copy();
                // 使用原版原始 Ingredient 防止因为聚合提取而抛出错误
                yield AltarBindingRegistry.tryExtractFromBindings(player,
                        entry.altarDim, entry.altarPos,
                        entry.originalIngredient, entry.count);
            }
            case NETWORK -> {
                if (network == null) yield ItemStack.EMPTY;
                yield RSIntegration.extractFromNetwork(network, entry.originalIngredient, entry.count);
            }
            case PLAYER_INVENTORY -> {
                // 💡 修复点：背包跨多槽位提取（例如：玩家有两组骨粉，各为30个，需求50个时之前的代码直接报错中止）
                ItemStack extracted = ItemStack.EMPTY;
                int needed = entry.count;
                for (ItemStack stack : player.getInventory().items) {
                    if (entry.originalIngredient.test(stack) && stack.getCount() > 0) {
                        int take = Math.min(needed, stack.getCount());
                        if (extracted.isEmpty()) {
                            extracted = stack.split(take);
                        } else {
                            stack.shrink(take);
                            extracted.grow(take);
                        }
                        needed -= take;
                        if (needed <= 0) break;
                    }
                }
                yield extracted;
            }
        };
    }

    private ItemStack findAvailableInNetwork(INetwork network, Ingredient ingredient, int needed) {
        try {
            List<ItemStack> stacks = networkEntryCache.computeIfAbsent(network, n -> {
                List<ItemStack> list = new ArrayList<>();
                var cache = n.getItemStorageCache();
                if (cache != null) {
                    for (var entry : cache.getList().getStacks()) {
                        ItemStack s = entry.getStack();
                        if (!s.isEmpty()) list.add(s);
                    }
                }
                return list;
            });
            if (stacks.isEmpty()) return ItemStack.EMPTY;

            // Single-stack fast path: one stack has enough → track pendingNet here
            for (ItemStack stored : stacks) {
                if (!ingredient.test(stored)) continue;
                int available = stored.getCount()
                        - pendingNet.getOrDefault(CraftingResolver.StackKey.of(stored, true), 0);
                if (available <= 0) continue;
                if (available >= needed) {
                    ItemStack template = stored.copy();
                    template.setCount(1);
                    pendingNet.merge(CraftingResolver.StackKey.of(stored, true), needed, Integer::sum);
                    return template;
                }
            }

            // Multi-stack aggregation: distribute pendingNet across all
            // contributing stacks so subsequent reservations see accurate counts
            ItemStack pass1Template = ItemStack.EMPTY;
            int pass1Total = 0;
            ItemStack pass2Template = ItemStack.EMPTY;
            int pass2Total = 0;

            for (ItemStack stored : stacks) {
                if (!ingredient.test(stored)) continue;
                int available = stored.getCount()
                        - pendingNet.getOrDefault(CraftingResolver.StackKey.of(stored, true), 0);
                if (available <= 0) continue;

                if (!stored.hasTag()) {
                    pass1Total += available;
                    if (pass1Template.isEmpty()) {
                        pass1Template = stored.copy();
                        pass1Template.setCount(1);
                    }
                } else {
                    pass2Total += available;
                    if (pass2Template.isEmpty()) {
                        pass2Template = stored.copy();
                        pass2Template.setCount(1);
                    }
                }
            }

            ItemStack chosen = ItemStack.EMPTY;
            if (pass1Total >= needed && !pass1Template.isEmpty()) chosen = pass1Template;
            else if (pass1Total + pass2Total >= needed) {
                if (!pass1Template.isEmpty()) chosen = pass1Template;
                else if (!pass2Template.isEmpty()) chosen = pass2Template;
            }
            if (chosen.isEmpty()) return ItemStack.EMPTY;

            // Distribute pendingNet proportionally across all contributing stacks
            int remaining = needed;
            for (ItemStack stored : stacks) {
                if (remaining <= 0) break;
                if (!ingredient.test(stored)) continue;
                int available = stored.getCount()
                        - pendingNet.getOrDefault(CraftingResolver.StackKey.of(stored, true), 0);
                if (available <= 0) continue;
                // Only distribute within the chosen pass (no-tag priority)
                if (chosen == pass1Template && stored.hasTag()) continue;
                int contrib = Math.min(remaining, available);
                pendingNet.merge(CraftingResolver.StackKey.of(stored, true), contrib, Integer::sum);
                remaining -= contrib;
            }
            return chosen;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Ledger] Error scanning network storage", e);
        }
        return ItemStack.EMPTY;
    }

    private ItemStack findAvailableInInventory(ServerPlayer player, Ingredient ingredient, int needed) {
        // Single-stack fast path: one stack has enough → track pendingInv here
        for (ItemStack stack : player.getInventory().items) {
            if (!ingredient.test(stack)) continue;
            int available = stack.getCount()
                    - pendingInv.getOrDefault(CraftingResolver.StackKey.of(stack, true), 0);
            if (available <= 0) continue;

            if (!stack.hasTag() && available >= needed) {
                ItemStack template = stack.copy();
                template.setCount(1);
                pendingInv.merge(CraftingResolver.StackKey.of(stack, true), needed, Integer::sum);
                return template;
            }
        }

        ItemStack pass1Template = ItemStack.EMPTY;
        int pass1Total = 0;
        ItemStack pass2Template = ItemStack.EMPTY;
        int pass2Total = 0;

        for (ItemStack stack : player.getInventory().items) {
            if (!ingredient.test(stack)) continue;
            int available = stack.getCount()
                    - pendingInv.getOrDefault(CraftingResolver.StackKey.of(stack, true), 0);
            if (available <= 0) continue;

            if (!stack.hasTag()) {
                pass1Total += available;
                if (pass1Template.isEmpty()) {
                    pass1Template = stack.copy();
                    pass1Template.setCount(1);
                }
            } else {
                pass2Total += available;
                if (pass2Template.isEmpty()) {
                    pass2Template = stack.copy();
                    pass2Template.setCount(1);
                }
            }
        }

        ItemStack chosen = ItemStack.EMPTY;
        if (pass1Total >= needed && !pass1Template.isEmpty()) chosen = pass1Template;
        else if (pass1Total + pass2Total >= needed) {
            if (!pass1Template.isEmpty()) chosen = pass1Template;
            else if (!pass2Template.isEmpty()) chosen = pass2Template;
        }
        if (chosen.isEmpty()) return ItemStack.EMPTY;

        // Distribute pendingInv across all contributing stacks
        int remaining = needed;
        for (ItemStack stack : player.getInventory().items) {
            if (remaining <= 0) break;
            if (!ingredient.test(stack)) continue;
            int available = stack.getCount()
                    - pendingInv.getOrDefault(CraftingResolver.StackKey.of(stack, true), 0);
            if (available <= 0) continue;
            if (chosen == pass1Template && stack.hasTag()) continue;
            int contrib = Math.min(remaining, available);
            pendingInv.merge(CraftingResolver.StackKey.of(stack, true), contrib, Integer::sum);
            remaining -= contrib;
        }
        return chosen;
    }

    /** Returns a read-only snapshot of ledger entries for debug commands. */
    public List<String> describeEntries() {
        List<String> out = new ArrayList<>();
        for (Entry e : entries) {
            ResourceLocation rl = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(e.template.getItem());
            String itemName = rl != null ? rl.toString() : e.template.getDisplayName().getString();
            out.add(String.format("#%d %s: %s x%d (source=%s)",
                    e.id, e.source, itemName, e.count,
                    switch (e.source) {
                        case NETWORK -> "network";
                        case PLAYER_INVENTORY -> "inventory";
                        case ALTAR_BINDING -> "altar_binding";
                    }));
        }
        return out;
    }

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

    public void releaseReservations(List<ItemStack> stacks) {
        // Collect entry ids to remove upfront so we match by identity, not by
        // (item, count) which can collide across entries.
        java.util.BitSet idsToRemove = new java.util.BitSet();
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) continue;
            CraftingResolver.StackKey key = CraftingResolver.StackKey.of(stack, true);
            int count = stack.getCount();
            pendingNet.computeIfPresent(key, (k, v) -> {
                int nv = v - count;
                return nv <= 0 ? null : nv;
            });
            pendingInv.computeIfPresent(key, (k, v) -> {
                int nv = v - count;
                return nv <= 0 ? null : nv;
            });
            // Find the matching entry by (template item, count) and record its id.
            // Search in reverse so we match the most recently added entry first.
            for (int i = entries.size() - 1; i >= 0; i--) {
                Entry e = entries.get(i);
                if (!idsToRemove.get(e.id)
                        && ItemStack.isSameItem(e.template, stack)
                        && e.count == stack.getCount()) {
                    idsToRemove.set(e.id);
                    break;
                }
            }
        }
        // Remove matched entries by id (in reverse to avoid index shifting issues).
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (idsToRemove.get(entries.get(i).id)) {
                entries.remove(i);
            }
        }
    }
}