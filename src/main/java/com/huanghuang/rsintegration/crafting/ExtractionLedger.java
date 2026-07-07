package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.RSICraftException;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import com.huanghuang.rsintegration.network.RSIntegrationNetwork;
import com.huanghuang.rsintegration.util.Diagnostics;
import com.huanghuang.rsintegration.util.ModIds;
import com.huanghuang.rsintegration.util.PlayerUtils;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.util.Action;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;

import javax.annotation.Nonnull;
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
        throw RSICraftException.ledgerStateViolation(
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
                // Pass original Ingredient (not Ingredient.of(result)) so tag-based
                // extraction can pull across different item types within the same tag.
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
                    return result;
                }
            }
        }

        {
            ItemStack matched = findAvailableInInventory(player, ingredient, count);
            if (!matched.isEmpty()) {
                ItemStack result = matched.copyWithCount(count);
                recordEntry(new Entry(Source.PLAYER_INVENTORY, ingredient, result.copy(), null, null, null, null));
                return result;
            }
        }

        return ItemStack.EMPTY;
    }

    @Nonnull
    public ItemStack reserveFromNetwork(@Nonnull Ingredient ingredient, int count, @Nonnull INetwork network) {
        requireState(State.IDLE, State.RESERVING);
        if (count <= 0 || ingredient.isEmpty()) return ItemStack.EMPTY;
        if (state == State.IDLE) transition(State.RESERVING);

        ItemStack matched = findAvailableInNetwork(network, ingredient, count);
        if (matched.isEmpty()) return ItemStack.EMPTY;

        ItemStack template = matched.copyWithCount(count);
        recordEntry(new Entry(Source.NETWORK, ingredient, template.copy(), null, null, null, network));
        return template;
    }

    @Nonnull
    public ItemStack reserveFromInventory(@Nonnull Ingredient ingredient, int count, @Nonnull ServerPlayer player) {
        requireState(State.IDLE, State.RESERVING);
        if (count <= 0 || ingredient.isEmpty()) return ItemStack.EMPTY;
        if (state == State.IDLE) transition(State.RESERVING);

        ItemStack matched = findAvailableInInventory(player, ingredient, count);
        if (matched.isEmpty()) return ItemStack.EMPTY;

        ItemStack template = matched.copyWithCount(count);
        recordEntry(new Entry(Source.PLAYER_INVENTORY, ingredient, template.copy(), null, null, null, null));
        return template;
    }

    // Three-phase atomic commit: pre-check, batch extract, confirm.
    public boolean commit(@Nullable INetwork network, @Nonnull ServerPlayer player) {
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

        // Sync player inventory to client after modifying items directly
        boolean extractedFromInv = entries.stream().anyMatch(e -> e.source == Source.PLAYER_INVENTORY);
        if (extractedFromInv) {
            player.getInventory().setChanged();
            player.inventoryMenu.broadcastChanges();
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
    private boolean preCheck(INetwork network, ServerPlayer player) {
        if (network != null) {
            // Group reservations by ingredient for accurate per-type checking.
            Map<Ingredient, Integer> neededByIngredient = new HashMap<>();
            for (Entry e : entries) {
                if (e.source == Source.NETWORK) {
                    neededByIngredient.merge(e.originalIngredient, e.count, Integer::sum);
                }
            }
            if (!neededByIngredient.isEmpty()) {
                var cache = network.getItemStorageCache();
                if (cache == null) return false;
                for (var ingEntry : neededByIngredient.entrySet()) {
                    int available = 0;
                    for (var s : cache.getList().getStacks()) {
                        ItemStack stored = s.getStack();
                        if (!stored.isEmpty() && ingEntry.getKey().test(stored)) {
                            available += stored.getCount();
                        }
                    }
                    if (available < ingEntry.getValue()) {
                        RSIntegrationMod.LOGGER.warn("[RSI-Ledger] Pre-check: insufficient {} in network (need {}, have {})",
                                CraftPacketUtils.describeIngredient(ingEntry.getKey()), ingEntry.getValue(), available);
                        return false;
                    }
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
                        var tracker = rec.sourceNetwork.getItemStorageTracker();
                        if (tracker != null) tracker.changed(player, s.copy());
                        ItemStack leftover = rec.sourceNetwork.insertItem(s, s.getCount(), Action.PERFORM);
                        if (!leftover.isEmpty()) {
                            PlayerUtils.safeGiveToPlayer(player, leftover, rec.sourceNetwork);
                        }
                    } else {
                        PlayerUtils.safeGiveToPlayer(player, s, null);
                    }
                }
                case NETWORK -> {
                    if (rec.sourceNetwork != null) {
                        var tracker = rec.sourceNetwork.getItemStorageTracker();
                        if (tracker != null) tracker.changed(player, s.copy());
                        ItemStack leftover = rec.sourceNetwork.insertItem(s, s.getCount(), Action.PERFORM);
                        if (!leftover.isEmpty()) {
                            PlayerUtils.safeGiveToPlayer(player, leftover, rec.sourceNetwork);
                        }
                    } else {
                        PlayerUtils.safeGiveToPlayer(player, s, null);
                    }
                }
                case PLAYER_INVENTORY -> PlayerUtils.safeGiveToPlayer(player, s, null);
            }
        }
    }

    /** Lightweight record for extracted items during commit rollback. */
    private record ExtractRecord(Source source, ItemStack stack,
                                  @Nullable ResourceKey<Level> altarDim,
                                  @Nullable BlockPos altarPos,
                                  @Nullable INetwork sourceNetwork) {}

    /** Explicitly roll back all reservations without committing. */
    public void rollback(@Nonnull ServerPlayer player) {
        if (state == State.IDLE || state == State.ROLLED_BACK) return;
        if (state == State.COMMITTED) {
            refundCommitted(null, player);
            return;
        }
        requireState(State.RESERVING, State.RESERVED, State.COMMITTING);
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

    private static ItemStack extractOne(Entry entry, INetwork network, ServerPlayer player) {
        return switch (entry.source) {
            case ALTAR_BINDING -> {
                if (entry.preExtracted != null) yield entry.preExtracted.copy();
                // Use original Ingredient to avoid errors from aggregated extraction
                yield AltarBindingRegistry.tryExtractFromBindings(player,
                        entry.altarDim, entry.altarPos,
                        entry.originalIngredient, entry.count);
            }
            case NETWORK -> {
                if (network == null) yield ItemStack.EMPTY;
                yield RSIntegrationNetwork.extractFromNetwork(network, entry.originalIngredient, entry.count, player);
            }
            case PLAYER_INVENTORY -> {
                ItemStack extracted = extractFromSlots(entry, player.getInventory().items);
                if (extracted.getCount() < entry.count) {
                    ItemStack off = extractFromSlots(entry, player.getInventory().offhand,
                            entry.count - extracted.getCount());
                    if (!off.isEmpty()) {
                        if (extracted.isEmpty()) extracted = off;
                        else extracted.grow(off.getCount());
                    }
                }
                if (extracted.getCount() < entry.count) {
                    ItemStack armor = extractFromSlots(entry, player.getInventory().armor,
                            entry.count - extracted.getCount());
                    if (!armor.isEmpty()) {
                        if (extracted.isEmpty()) extracted = armor;
                        else extracted.grow(armor.getCount());
                    }
                }
                if (extracted.getCount() < entry.count) {
                    ItemStack bp = extractFromBackpackSlots(entry, player,
                            entry.count - extracted.getCount());
                    if (!bp.isEmpty()) {
                        if (extracted.isEmpty()) extracted = bp;
                        else extracted.grow(bp.getCount());
                    }
                }
                yield extracted;
            }
        };
    }

    private static ItemStack extractFromSlots(Entry entry, net.minecraft.core.NonNullList<ItemStack> slots) {
        ItemStack extracted = ItemStack.EMPTY;
        int needed = entry.count;
        for (ItemStack stack : slots) {
            if (entry.originalIngredient.test(stack)
                    && ItemStack.isSameItemSameTags(stack, entry.template)
                    && stack.getCount() > 0) {
                int take = Math.min(needed, stack.getCount());
                ItemStack part = stack.split(take);
                if (extracted.isEmpty()) {
                    extracted = part;
                } else {
                    extracted.grow(part.getCount());
                }
                needed -= take;
                if (needed <= 0) break;
            }
        }
        return extracted;
    }

    private static ItemStack extractFromSlots(Entry entry, net.minecraft.core.NonNullList<ItemStack> slots, int limit) {
        ItemStack extracted = ItemStack.EMPTY;
        int remaining = Math.min(limit, entry.count);
        for (ItemStack stack : slots) {
            if (entry.originalIngredient.test(stack)
                    && ItemStack.isSameItemSameTags(stack, entry.template)
                    && stack.getCount() > 0) {
                int take = Math.min(remaining, stack.getCount());
                ItemStack part = stack.split(take);
                if (extracted.isEmpty()) {
                    extracted = part;
                } else {
                    extracted.grow(part.getCount());
                }
                remaining -= take;
                if (remaining <= 0) break;
            }
        }
        return extracted;
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

            // Prefer untagged stacks; only skip tagged ones when pass1 alone
            // is sufficient.  Otherwise we MUST draw from both pools or the
            // pendingNet reservation won't cover the full needed count,
            // cascading into false negatives for subsequent specs.
            boolean pass1Sufficient = pass1Total >= needed;
            ItemStack chosen = ItemStack.EMPTY;
            if (pass1Sufficient && !pass1Template.isEmpty()) {
                chosen = pass1Template;
            } else if (pass1Total + pass2Total >= needed) {
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
                if (pass1Sufficient && stored.hasTag()) continue;
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

        // Fast path for backpack contents
        for (IItemHandler bp : findAllBackpackInventories(player)) {
            for (int s = 0; s < bp.getSlots(); s++) {
                ItemStack stack = bp.getStackInSlot(s);
                if (stack.isEmpty() || !ingredient.test(stack)) continue;
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

        // Aggregate backpack contents into availability
        for (IItemHandler bp : findAllBackpackInventories(player)) {
            for (int s = 0; s < bp.getSlots(); s++) {
                ItemStack stack = bp.getStackInSlot(s);
                if (stack.isEmpty() || !ingredient.test(stack)) continue;
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
        }

        boolean pass1Sufficient = pass1Total >= needed;
        ItemStack chosen = ItemStack.EMPTY;
        if (pass1Sufficient && !pass1Template.isEmpty()) {
            chosen = pass1Template;
        } else if (pass1Total + pass2Total >= needed) {
            if (!pass1Template.isEmpty()) chosen = pass1Template;
            else if (!pass2Template.isEmpty()) chosen = pass2Template;
        }
        if (chosen.isEmpty()) return ItemStack.EMPTY;

        // Distribute pendingInv across all contributing stacks (main inventory)
        int remaining = needed;
        for (ItemStack stack : player.getInventory().items) {
            if (remaining <= 0) break;
            if (!ingredient.test(stack)) continue;
            int available = stack.getCount()
                    - pendingInv.getOrDefault(CraftingResolver.StackKey.of(stack, true), 0);
            if (available <= 0) continue;
            if (pass1Sufficient && stack.hasTag()) continue;
            int contrib = Math.min(remaining, available);
            pendingInv.merge(CraftingResolver.StackKey.of(stack, true), contrib, Integer::sum);
            remaining -= contrib;
        }
        // Distribute remaining across backpack contents
        for (IItemHandler bp : findAllBackpackInventories(player)) {
            if (remaining <= 0) break;
            for (int s = 0; s < bp.getSlots(); s++) {
                if (remaining <= 0) break;
                ItemStack stack = bp.getStackInSlot(s);
                if (stack.isEmpty() || !ingredient.test(stack)) continue;
                int available = stack.getCount()
                        - pendingInv.getOrDefault(CraftingResolver.StackKey.of(stack, true), 0);
                if (available <= 0) continue;
                if (pass1Sufficient && stack.hasTag()) continue;
                int contrib = Math.min(remaining, available);
                pendingInv.merge(CraftingResolver.StackKey.of(stack, true), contrib, Integer::sum);
                remaining -= contrib;
            }
        }
        return chosen;
    }

    // ── Backpack inventory scanning ──────────────────────────────

    private static IItemHandler getBackpackInventory(ItemStack stack) {
        if (stack.isEmpty()) return null;
        if (!ModList.get().isLoaded(ModIds.SOPHISTICATED_BACKPACKS)) return null;
        try {
            var opt = stack.getCapability(
                    net.p3pp3rf1y.sophisticatedbackpacks.api.CapabilityBackpackWrapper.getCapabilityInstance())
                    .resolve();
            if (opt.isPresent()) {
                return opt.get().getInventoryForUpgradeProcessing();
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Ledger] Backpack inventory lookup failed", e);
        }
        return null;
    }

    /** Collect internal inventories of all backpacks found on the player (including curio slots). */
    static List<IItemHandler> findAllBackpackInventories(ServerPlayer player) {
        List<IItemHandler> result = new ArrayList<>();
        if (!ModList.get().isLoaded(ModIds.SOPHISTICATED_BACKPACKS)) return result;

        // Main inventory
        for (ItemStack stack : player.getInventory().items) {
            IItemHandler h = getBackpackInventory(stack);
            if (h != null) result.add(h);
        }
        // Offhand
        IItemHandler off = getBackpackInventory(player.getOffhandItem());
        if (off != null) result.add(off);
        // Armor
        for (ItemStack stack : player.getInventory().armor) {
            IItemHandler h = getBackpackInventory(stack);
            if (h != null) result.add(h);
        }
        // Curio slots (reflective — Curios is optional)
        scanCurioSlotsForBackpacks(player, result);

        return result;
    }

    @SuppressWarnings("unchecked")
    private static void scanCurioSlotsForBackpacks(ServerPlayer player, List<IItemHandler> sink) {
        if (!ModList.get().isLoaded(ModIds.CURIOS)) return;
        try {
            Class<?> curiosApiClass = Class.forName("top.theillusivec4.curios.api.CuriosApi");
            Object result = curiosApiClass.getMethod("getCuriosInventory",
                    net.minecraft.world.entity.LivingEntity.class).invoke(null, player);
            if (result == null) return;
            Object handler;
            try {
                Object opt = result.getClass().getMethod("resolve").invoke(result);
                if (opt instanceof java.util.Optional<?> o) {
                    handler = o.orElse(null);
                } else {
                    return;
                }
            } catch (NoSuchMethodException e) {
                return;
            }
            if (handler == null) return;
            Object curios = handler.getClass().getMethod("getCurios").invoke(handler);
            java.util.Map<String, ?> curiosMap = (java.util.Map<String, ?>) curios;
            for (var entry : curiosMap.values()) {
                Object stacks = entry.getClass().getMethod("getStacks").invoke(entry);
                if (stacks instanceof IItemHandler itemHandler) {
                    for (int s = 0; s < itemHandler.getSlots(); s++) {
                        IItemHandler bh = getBackpackInventory(itemHandler.getStackInSlot(s));
                        if (bh != null) sink.add(bh);
                    }
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Ledger] Curios backpack scan failed", e);
        }
    }

    /** Extract up to {@code limit} items from backpack internals for the given entry. */
    private static ItemStack extractFromBackpackSlots(Entry entry, ServerPlayer player, int limit) {
        ItemStack collected = ItemStack.EMPTY;
        int remaining = Math.min(limit, entry.count);
        for (IItemHandler bp : findAllBackpackInventories(player)) {
            for (int s = 0; s < bp.getSlots() && remaining > 0; s++) {
                ItemStack inSlot = bp.getStackInSlot(s);
                if (inSlot.isEmpty() || !entry.originalIngredient.test(inSlot)
                        || !ItemStack.isSameItemSameTags(inSlot, entry.template)) continue;
                int take = Math.min(remaining, inSlot.getCount());
                ItemStack taken = bp.extractItem(s, take, false);
                if (!taken.isEmpty()) {
                    if (collected.isEmpty()) collected = taken;
                    else collected.grow(taken.getCount());
                    remaining -= taken.getCount();
                }
            }
            if (remaining <= 0) break;
        }
        return collected;
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

    /**
     * Refund all committed entries by re-inserting their templates back to
     * the original source (network, altar binding, or player inventory).
     * Best-effort: individual failures are logged but do not abort the loop.
     */
    public void refundCommitted(@Nullable INetwork network, @Nonnull ServerPlayer player) {
        if (state != State.COMMITTED) return;
        for (Entry e : entries) {
            if (e.template.isEmpty()) continue;
            ItemStack refund = e.template.copyWithCount(e.count);
            switch (e.source) {
                case NETWORK, ALTAR_BINDING -> {
                    INetwork net = e.sourceNetwork != null ? e.sourceNetwork : network;
                    if (net != null) {
                        var tracker = net.getItemStorageTracker();
                        if (tracker != null) tracker.changed(player, refund.copy());
                        ItemStack leftover = net.insertItem(refund, refund.getCount(), Action.PERFORM);
                        if (!leftover.isEmpty()) {
                            RSIntegrationMod.LOGGER.warn("[RSI-Ledger] Refund: RS insert had leftover for {} x{}",
                                    refund.getDisplayName().getString(), refund.getCount());
                            PlayerUtils.safeGiveToPlayer(player, leftover, network);
                        }
                    } else {
                        PlayerUtils.safeGiveToPlayer(player, refund, network);
                    }
                }
                case PLAYER_INVENTORY -> PlayerUtils.safeGiveToPlayer(player, refund, network);
            }
        }
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

    /**
     * Remove and undo the most recently added reservation entry.
     * Used when an availability check passes but the item should not
     * actually be extracted (e.g. catalyst items that survive crafting).
     */
    public void cancelLastReservation() {
        if (entries.isEmpty()) return;
        Entry last = entries.remove(entries.size() - 1);
        CraftingResolver.StackKey key = CraftingResolver.StackKey.of(last.template, true);
        pendingNet.computeIfPresent(key, (k, v) -> {
            int nv = v - last.count;
            return nv <= 0 ? null : nv;
        });
        pendingInv.computeIfPresent(key, (k, v) -> {
            int nv = v - last.count;
            return nv <= 0 ? null : nv;
        });
    }

    public void releaseReservations(List<ItemStack> stacks) {
        // Collect entry ids to remove upfront so we match by identity, not by
        // (item, count) which can collide across entries.
        Set<Integer> idsToRemove = new HashSet<>();
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
                if (!idsToRemove.contains(e.id)
                        && ItemStack.isSameItem(e.template, stack)
                        && e.count == stack.getCount()) {
                    idsToRemove.add(e.id);
                    break;
                }
            }
        }
        // Remove matched entries by id (in reverse to avoid index shifting issues).
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (idsToRemove.contains(entries.get(i).id)) {
                entries.remove(i);
            }
        }
    }
}