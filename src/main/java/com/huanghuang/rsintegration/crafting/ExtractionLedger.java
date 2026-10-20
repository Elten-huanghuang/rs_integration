package com.huanghuang.rsintegration.crafting;
import java.lang.reflect.Method;

import com.huanghuang.rsintegration.storage.StorageBackendId;
import com.huanghuang.rsintegration.storage.StorageOperationStatus;
import com.huanghuang.rsintegration.storage.StoredItem;
import com.huanghuang.rsintegration.util.ItemStackUtils;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;

import com.huanghuang.rsintegration.crafting.RSICraftException;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import com.huanghuang.rsintegration.util.CraftLogContext;
import com.huanghuang.rsintegration.util.Diagnostics;
import com.huanghuang.rsintegration.util.LogSampler;
import com.huanghuang.rsintegration.util.ModIds;
import com.huanghuang.rsintegration.util.PlayerUtils;
import com.huanghuang.rsintegration.mods.goety.GoetySoulTotemCrafting;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.resonance.api.ResonanceStorageView;
import com.huanghuang.rsintegration.storage.StorageItemKey;
import com.huanghuang.rsintegration.storage.StorageReservationSource;
import com.huanghuang.rsintegration.storage.StorageSettlementLedger;
import com.huanghuang.rsintegration.storage.StorageOperationMode;
import com.huanghuang.rsintegration.storage.StorageOperationResult;
import com.huanghuang.rsintegration.storage.StorageSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
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

public final class ExtractionLedger implements AutoCloseable {
    private static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("RS Integration");
    private static final LogSampler SETTLEMENT_MIRROR_LOGS = new LogSampler(60_000L);
    private static final LogSampler REFUND_LOGS = new LogSampler(60_000L);

    public enum State {
        IDLE, RESERVING, RESERVED, COMMITTING, COMMITTED, ROLLED_BACK
    }

    private final List<Entry> entries = new ArrayList<>();
    private final Map<Integer, Entry> entriesById = new HashMap<>();
    private final Map<CraftingResolver.StackKey, Long> pendingNet = new HashMap<>();
    private final Map<CraftingResolver.StackKey, Long> pendingInv = new HashMap<>();
    private final Map<ResonanceReservationKey, Long> pendingResonance = new HashMap<>();
    private final List<Ingredient> preferredResonanceIngredients = new ArrayList<>();
    private State state = State.IDLE;
    @Nullable private UUID refundOwner;
    @Nullable private MinecraftServer refundServer;

    @Nullable
    private CraftLogContext logContext;

    private final Map<CraftStorageEndpoint, List<ItemStack>> endpointEntryCache = new HashMap<>();
    /** Backend-neutral endpoint used by migrated callers. */
    @Nullable
    private CraftStorageEndpoint storageEndpoint;
    @Nullable
    private CraftStorageEndpoint reservationSnapshotEndpoint;
    @Nullable
    private StorageSnapshot reservationSnapshot;
    private long reservationSnapshotTick = Long.MIN_VALUE;

    /** Backend-neutral settlement mirror; physical extraction remains here for compatibility. */
    private StorageSettlementLedger settlementLedger = new StorageSettlementLedger();
    private final Map<Integer, StorageSettlementLedger.EntryId> settlementEntries = new HashMap<>();

    public void setStorageEndpoint(@Nullable CraftStorageEndpoint endpoint) {
        if (this.storageEndpoint == endpoint) return;
        this.storageEndpoint = endpoint;
        this.endpointEntryCache.clear();
        clearReservationSnapshot();
    }

    @Nullable
    private StorageSnapshot reservationSnapshot(CraftStorageEndpoint endpoint, ServerPlayer player) {
        long currentTick = player.server.getTickCount();
        if (reservationSnapshot != null && reservationSnapshotEndpoint == endpoint
                && reservationSnapshotTick == currentTick) {
            return reservationSnapshot;
        }
        StorageSnapshot loaded = endpoint.snapshot(player).snapshot().orElse(null);
        if (loaded == null) {
            clearReservationSnapshot();
            return null;
        }
        reservationSnapshotEndpoint = endpoint;
        reservationSnapshot = loaded;
        reservationSnapshotTick = currentTick;
        return loaded;
    }

    private void clearReservationSnapshot() {
        reservationSnapshotEndpoint = null;
        reservationSnapshot = null;
        reservationSnapshotTick = Long.MIN_VALUE;
    }

    /** Prefer a matching tool/catalyst from the selected backend's resonance disk. */
    public void preferResonanceFor(@Nonnull Ingredient ingredient) {
        requireState(State.IDLE, State.RESERVING);
        if (!ingredient.isEmpty()) preferredResonanceIngredients.add(ingredient);
    }

    public boolean hasPreferredResonanceReservations() {
        return !preferredResonanceIngredients.isEmpty();
    }

    /** 批量执行前的只读可用量探针，避免多个 int 来源相加时溢出。 */
    public long countAvailableExactForBatch(@Nonnull ItemStack template,
                                            @Nonnull ServerPlayer player) {
        requireState(State.IDLE, State.RESERVING);
        if (template.isEmpty()) return 0L;
        long available = countExactAvailableInInventoryLong(player, template);
        if (storageEndpoint != null) {
            StorageSnapshot snapshot = reservationSnapshot(storageEndpoint, player);
            if (snapshot != null) {
                long stored = snapshot.countExact(storageEndpoint.session().itemKey(template));
                long pending = pendingNet.getOrDefault(
                        CraftingResolver.StackKey.of(template, true), 0L);
                available = saturatedAdd(available, Math.max(0L, stored - pending));
            }
        }
        return available;
    }

    /**
     * Returns the backend selected for this ledger. Delegates use this to keep
     * machine execution on the same storage session that performed planning
     * and reservation (including non-RS backends).
     */
    @Nullable
    public CraftStorageEndpoint storageEndpoint() {
        return storageEndpoint;
    }

    private CraftStorageEndpoint endpointFor(@Nullable CraftStorageEndpoint endpoint) {
        return endpoint != null ? endpoint : storageEndpoint;
    }

    /** Attach a correlation context for structured log output. */
    public void setLogContext(@Nullable CraftLogContext ctx) {
        this.logContext = ctx;
    }

    private String fmt(String message) {
        return logContext != null ? logContext.format(message) : message;
    }

    // ── state guards ─────────────────────────────────────────────

    private void requireState(State... allowed) {
        for (State s : allowed) {
            if (state == s) return;
        }
        throw RSICraftException.ledgerStateViolation(
                Arrays.toString(allowed), state.name());
    }

    private void transition(State to) {
        LOGGER.debug(fmt("Ledger {} → {}"), state, to);
        Diagnostics.record(Diagnostics.Category.LEDGER_RESERVE,
                state + "→" + to + " entries=" + entries.size());
        state = to;
    }

    /** Record a per-entry diagnostic before adding it to the list. */
    private void recordEntry(Entry e) {
        ResourceLocation rl = BuiltInRegistries.ITEM.getKey(e.template.getItem());
        String itemId = rl != null ? rl.toString()
                : ItemStackUtils.registryId(e.template);
        Diagnostics.record(Diagnostics.Category.LEDGER_RESERVE,
                "reserve item=" + itemId + " count=" + e.count + " src=" + e.source);
        entries.add(e);
        entriesById.put(e.id, e);
        registerSettlementEntry(e);
    }

    private void registerSettlementEntry(Entry entry) {
        try {
            StorageReservationSource source;
            StorageItemKey key;
            CraftStorageEndpoint endpoint = endpointFor(entry.sourceEndpoint);
            if (entry.source == Source.PLAYER_INVENTORY) {
                // Player inventory reservations are tied to the active player
                // at execution time; this mirror is descriptive only.
                source = StorageReservationSource.external("player_inventory");
                key = StorageItemKey.fromItemStack(
                        new StorageBackendId("local"),
                        entry.template);
            } else if (entry.source == Source.RESONANCE_DISK && entry.resonanceView != null) {
                source = StorageReservationSource.external(
                        "resonance_disk:" + entry.resonanceView.backendId());
                key = StorageItemKey.fromItemStack(
                        new StorageBackendId(
                                entry.resonanceView.backendId()), entry.template);
            } else if (endpoint != null) {
                var reference = endpoint.session().reference();
                source = StorageReservationSource.storage(reference);
                key = endpoint.session().itemKey(entry.template);
            } else {
                source = StorageReservationSource.external(entry.source.name().toLowerCase(Locale.ROOT));
                key = StorageItemKey.fromItemStack(
                        new StorageBackendId("unknown"),
                        entry.template);
            }
            settlementEntries.put(entry.id,
                    settlementLedger.reserve(source, key, entry.count));
        } catch (RuntimeException ex) {
            // The compatibility ledger must never change the established
            // physical extraction path; an unavailable descriptive key is
            // recorded in diagnostics and handled by the legacy ledger.
            LOGGER.debug("[RSI-Ledger] settlement mirror skipped for entry {}", entry.id, ex);
        }
    }

    // ── reservations ─────────────────────────────────────────────

    /** Reserve one exact item/NBT identity while retaining the original source for refund. */
    /** Endpoint-only exact reservation used by backend-neutral callers. */
    @Nonnull
    public ItemStack reserveExactToEndpoint(
            @Nonnull ItemStack template, int count,
            @Nullable CraftStorageEndpoint endpoint, @Nonnull ServerPlayer player,
            @Nullable ResourceKey<Level> altarDim, @Nullable BlockPos altarPos) {
        requireState(State.IDLE, State.RESERVING);
        if (count <= 0 || template.isEmpty()) return ItemStack.EMPTY;
        if (state == State.IDLE) transition(State.RESERVING);

        Ingredient ingredient = Ingredient.of(template.copyWithCount(1));
        CraftStorageEndpoint selected = endpoint != null ? endpoint : storageEndpoint;
        if (selected != null) {
            ItemStack stored = reserveExactFromEndpoint(template, count, selected, player);
            if (!stored.isEmpty()) return stored;
            if (reserveExactInventoryAvailability(player, template, count)) {
                ItemStack reserved = template.copyWithCount(count);
                recordEntry(new Entry(Source.PLAYER_INVENTORY, ingredient, reserved, null,
                        null, null, null, true));
                return reserved;
            }
            return ItemStack.EMPTY;
        }

        CraftStorageEndpoint binding = CraftStorageEndpoints.resolveBinding(
                player, altarDim, altarPos).orElse(null);
        if (binding != null) {
            ItemStack reserved = reserveExactFromEndpoint(template, count, binding, player);
            if (!reserved.isEmpty()) return reserved;
        }

        if (reserveExactInventoryAvailability(player, template, count)) {
            ItemStack reserved = template.copyWithCount(count);
            recordEntry(new Entry(Source.PLAYER_INVENTORY, ingredient, reserved, null,
                    null, null, null, true));
            return reserved;
        }
        return ItemStack.EMPTY;
    }

    /** Backend-neutral reservation with the same inventory fallback semantics. */
    public ItemStack reserve(Ingredient ingredient, int count,
                             @Nullable CraftStorageEndpoint endpoint, ServerPlayer player,
                             @Nullable ResourceKey<Level> altarDim, @Nullable BlockPos altarPos) {
        requireState(State.IDLE, State.RESERVING);
        if (count <= 0 || ingredient.isEmpty()) return ItemStack.EMPTY;
        if (state == State.IDLE) transition(State.RESERVING);

        CraftStorageEndpoint selected = endpoint != null ? endpoint : storageEndpoint;
        if (selected != null) {
            return reserveAcrossEndpointAndInventory(ingredient, count, selected, player);
        }

        CraftStorageEndpoint binding = CraftStorageEndpoints.resolveBinding(
                player, altarDim, altarPos).orElse(null);
        if (binding != null) {
            ItemStack matched = reserveAcrossEndpointAndInventory(
                    ingredient, count, binding, player);
            if (!matched.isEmpty()) return matched;
        }
        return reserveFromInventory(ingredient, count, player);
    }

    /** 先使用 endpoint 的可用部分，剩余数量再从玩家背包补足。 */
    private ItemStack reserveAcrossEndpointAndInventory(
            Ingredient ingredient, int count, CraftStorageEndpoint endpoint,
            ServerPlayer player) {
        if (hasPreferredResonanceReservations()) {
            ItemStack stored = reserveFromEndpoint(ingredient, count, endpoint, player);
            return stored.isEmpty() ? reserveFromInventory(ingredient, count, player) : stored;
        }

        int endpointCount = Math.min(count,
                countAvailableInEndpoint(endpoint, ingredient, player));
        if (endpointCount <= 0) {
            return reserveFromInventory(ingredient, count, player);
        }

        int mark = reservationMark();
        ItemStack fromEndpoint = reserveFromEndpoint(
                ingredient, endpointCount, endpoint, player);
        if (fromEndpoint.isEmpty() || fromEndpoint.getCount() != endpointCount) {
            cancelReservationsSince(mark);
            return reserveFromInventory(ingredient, count, player);
        }

        int remaining = count - endpointCount;
        if (remaining <= 0) return fromEndpoint;

        ItemStack fromInventory = reserveFromInventory(ingredient, remaining, player);
        if (fromInventory.isEmpty() || fromInventory.getCount() != remaining) {
            cancelReservationsSince(mark);
            return reserveFromInventory(ingredient, count, player);
        }
        if (!MaterialMatcher.equivalentRuntimeFragment(fromEndpoint, fromInventory)) {
            // 单个返回栈不能安全表示两种运行时变体，仅在该槽位能完全由背包满足时回退。
            cancelReservationsSince(mark);
            return reserveFromInventory(ingredient, count, player);
        }
        return fromEndpoint.copyWithCount(count);
    }

    private int countAvailableInEndpoint(
            CraftStorageEndpoint endpoint, Ingredient ingredient, ServerPlayer player) {
        StorageSnapshot snapshot = reservationSnapshot(endpoint, player);
        if (snapshot == null) return 0;
        Map<CraftingResolver.StackKey, Long> available = new LinkedHashMap<>();
        for (StoredItem stored : snapshot.match(ingredient).items()) {
            CraftingResolver.StackKey key =
                    CraftingResolver.StackKey.of(stored.stack(), true);
            available.merge(key, stored.amount(), ExtractionLedger::saturatedAdd);
        }
        long total = 0L;
        for (Map.Entry<CraftingResolver.StackKey, Long> entry : available.entrySet()) {
            long unreserved = Math.max(0L,
                    entry.getValue() - pendingNet.getOrDefault(entry.getKey(), 0L));
            total = saturatedAdd(total, unreserved);
            if (total >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
        }
        return (int) total;
    }

    /**
     * Reserve one exact item/NBT identity across the RS network and the player's
     * inventory. A graph requirement represents one logical stack, but its
     * physical items may be split between both sources.
     */
    /** Endpoint-aware exact reservation used by graph and async execution. */
    @Nonnull
    public ItemStack reserveExactAcrossNetworkAndInventory(
            @Nonnull ItemStack template, int count,
            @Nullable CraftStorageEndpoint endpoint, @Nonnull ServerPlayer player) {
        requireState(State.IDLE, State.RESERVING);
        if (count <= 0 || template.isEmpty()) return ItemStack.EMPTY;
        if (state == State.IDLE) transition(State.RESERVING);
        CraftStorageEndpoint selected = endpoint != null ? endpoint : storageEndpoint;
        if (selected != null) setStorageEndpoint(selected);
        int endpointAvailable = countExactAvailableInEndpoint(selected, template, player);
        int inventoryAvailable = countExactAvailableInInventory(player, template);
        int[] allocation = allocateExactAcrossSources(count, endpointAvailable, inventoryAvailable);
        if (allocation.length == 0) return ItemStack.EMPTY;

        int mark = reservationMark();
        Ingredient ingredient = Ingredient.of(template.copyWithCount(1));
        if (allocation[0] > 0
                && (selected == null
                || reserveExactFromEndpoint(template, allocation[0], selected, player).isEmpty())) {
            cancelReservationsSince(mark);
            return ItemStack.EMPTY;
        }
        if (allocation[1] > 0) {
            if (!reserveExactInventoryAvailability(player, template, allocation[1])) {
                cancelReservationsSince(mark);
                return ItemStack.EMPTY;
            }
            ItemStack reserved = template.copyWithCount(allocation[1]);
            recordEntry(new Entry(Source.PLAYER_INVENTORY, ingredient, reserved,
                    null, null, null, null, true));
        }
        return template.copyWithCount(count);
    }

    private ItemStack reserveExactFromEndpoint(@Nonnull ItemStack template, int count,
                                               @Nonnull CraftStorageEndpoint endpoint,
                                               @Nonnull ServerPlayer player) {
        ItemStack resonance = reserveFromPreferredResonance(
                Ingredient.of(template.copyWithCount(1)), template, count, endpoint, player);
        if (!resonance.isEmpty()) return resonance;
        var snapshot = reservationSnapshot(endpoint, player);
        CraftingResolver.StackKey pendingKey = CraftingResolver.StackKey.of(template, true);
        long available = snapshot == null ? 0L
                : snapshot.countExact(endpoint.session().itemKey(template));
        available -= pendingNet.getOrDefault(pendingKey, 0L);
        if (available < count) {
            return ItemStack.EMPTY;
        }
        Ingredient ingredient = Ingredient.of(template.copyWithCount(1));
        ItemStack reserved = template.copyWithCount(count);
        pendingNet.merge(pendingKey, (long) count, ExtractionLedger::saturatedAdd);
        recordEntry(new Entry(Source.NETWORK, ingredient, reserved, null,
                null, null, endpoint, true));
        return reserved;
    }

    static int[] allocateExactAcrossSources(int needed, int networkAvailable, int inventoryAvailable) {
        if (needed <= 0 || networkAvailable < 0 || inventoryAvailable < 0
                || (long) networkAvailable + inventoryAvailable < needed) {
            return new int[0];
        }
        int fromNetwork = Math.min(needed, networkAvailable);
        return new int[]{fromNetwork, needed - fromNetwork};
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

    /** Reserve an ingredient from an already-authorized backend-neutral endpoint. */
    @Nonnull
    public ItemStack reserveFromEndpoint(@Nonnull Ingredient ingredient, int count,
                                         @Nonnull CraftStorageEndpoint endpoint,
                                         @Nonnull ServerPlayer player) {
        requireState(State.IDLE, State.RESERVING);
        if (count <= 0 || ingredient.isEmpty()) return ItemStack.EMPTY;
        if (state == State.IDLE) transition(State.RESERVING);
        setStorageEndpoint(endpoint);
        ItemStack resonance = reserveFromPreferredResonance(
                ingredient, ItemStack.EMPTY, count, endpoint, player);
        if (!resonance.isEmpty()) return resonance;
        // 装桶、装瓶由容器配方分开预留空容器和流体，成品只按真实库存预留。
        var snapshot = reservationSnapshot(endpoint, player);
        if (snapshot == null) return ItemStack.EMPTY;
        EndpointReservation reservation = reserveEndpointMatches(
                snapshot.match(ingredient).items(), count, pendingNet);
        if (reservation.template().isEmpty()) return ItemStack.EMPTY;
        // Keep the original Ingredient so commit-time extraction may consume
        // several concrete variants that together satisfy a tag or predicate.
        ItemStack reserved = reservation.template();
        Entry entry = new Entry(Source.NETWORK, ingredient, reserved.copy(), null, null,
                null, endpoint, GoetySoulTotemCrafting.isSoulTotemIngredient(ingredient));
        entry.pendingNetworkAllocation.putAll(reservation.allocations());
        recordEntry(entry);
        return reserved;
    }

    static EndpointReservation reserveEndpointMatches(
            List<StoredItem> matches, int count,
            Map<CraftingResolver.StackKey, Long> pending) {
        if (count <= 0) return EndpointReservation.EMPTY;
        ItemStack template = ItemStack.EMPTY;
        Map<CraftingResolver.StackKey, Long> allocations = new LinkedHashMap<>();
        Map<CraftingResolver.StackKey, Long> seenAmounts = new HashMap<>();
        int remaining = count;
        for (var stored : matches) {
            if (remaining <= 0) break;
            ItemStack stack = stored.stack();
            CraftingResolver.StackKey key = CraftingResolver.StackKey.of(stack, true);
            // Backend identities may share one crafting key. Charge existing
            // reservations once against their cumulative physical quantity.
            long available = seenAmounts.merge(key, stored.amount(), ExtractionLedger::saturatedAdd);
            long unreserved = Math.max(0L,
                    available - pending.getOrDefault(key, 0L)
                            - allocations.getOrDefault(key, 0L));
            int contribution = (int) Math.min(remaining, unreserved);
            if (contribution <= 0) continue;
            if (template.isEmpty()) template = stack;
            allocations.merge(key, (long) contribution, ExtractionLedger::saturatedAdd);
            remaining -= contribution;
            if (remaining == 0) break;
        }
        // Failed reservations must not publish provisional allocations.
        if (remaining > 0) return EndpointReservation.EMPTY;
        allocations.forEach((key, amount) ->
                pending.merge(key, amount, ExtractionLedger::saturatedAdd));
        return new EndpointReservation(template.copyWithCount(count), Map.copyOf(allocations));
    }

    record EndpointReservation(ItemStack template,
                               Map<CraftingResolver.StackKey, Long> allocations) {
        private static final EndpointReservation EMPTY =
                new EndpointReservation(ItemStack.EMPTY, Map.of());
    }

    private ItemStack reserveFromPreferredResonance(
            Ingredient requested, ItemStack exactTemplate, int count,
            CraftStorageEndpoint endpoint, ServerPlayer player) {
        if (preferredResonanceIngredients.isEmpty() || count <= 0) return ItemStack.EMPTY;
        for (ResonanceStorageView view : ResonanceCraftingSource.viewsFor(endpoint, player)) {
            for (ResonanceStorageView.StoredStack stored : view.storedStacks()) {
                ItemStack stack = stored.stack();
                if (stack.isEmpty() || !IngredientMatcher.test(requested, stack)
                        || !matchesPreferredResonanceIngredient(stack)) continue;
                if (!exactTemplate.isEmpty()
                        && !MaterialMatcher.sameRuntimeFragment(exactTemplate, stack)) continue;
                ResonanceReservationKey key = new ResonanceReservationKey(
                        view.backendId(), stored.slot(), CraftingResolver.StackKey.of(stack, true));
                long available = stack.getCount() - pendingResonance.getOrDefault(key, 0L);
                if (available < count) continue;
                ItemStack reserved = stack.copyWithCount(count);
                pendingResonance.merge(key, (long) count, ExtractionLedger::saturatedAdd);
                recordEntry(Entry.resonance(requested, reserved, view, stored.slot(), key));
                return reserved;
            }
        }
        return ItemStack.EMPTY;
    }

    private boolean matchesPreferredResonanceIngredient(ItemStack stack) {
        for (Ingredient preferred : preferredResonanceIngredients) {
            if (IngredientMatcher.test(preferred, stack)) return true;
        }
        return false;
    }

    /**
     * Reserve as much as possible, preferring the player's main inventory and
     * using the RS network only for the remainder. Every exact item/NBT variant
     * gets its own ledger entry so a broad ingredient cannot flatten variants.
     */
    public int reserveUpToFromMainInventoryThenEndpoint(
            @Nonnull Ingredient ingredient, int limit, @Nonnull ServerPlayer player,
            @Nullable CraftStorageEndpoint endpoint) {
        requireState(State.IDLE, State.RESERVING);
        if (limit <= 0 || ingredient.isEmpty()) return 0;

        LinkedHashMap<CraftingResolver.StackKey, ItemStack> inventoryTemplates = new LinkedHashMap<>();
        LinkedHashMap<CraftingResolver.StackKey, Long> inventoryCounts = new LinkedHashMap<>();
        collectMatchingAvailability(player.getInventory().items, ingredient, pendingInv,
                inventoryTemplates, inventoryCounts);

        LinkedHashMap<CraftingResolver.StackKey, ItemStack> networkTemplates = new LinkedHashMap<>();
        LinkedHashMap<CraftingResolver.StackKey, Long> networkCounts = new LinkedHashMap<>();
        CraftStorageEndpoint selected = endpoint != null ? endpoint : storageEndpoint;
        if (selected != null) {
            List<ItemStack> stored = endpointEntries(selected, player);
            collectMatchingAvailability(stored, ingredient, pendingNet,
                    networkTemplates, networkCounts);
        }

        int inventoryAvailable = saturatedSum(inventoryCounts.values(), List.of());
        int networkAvailable = saturatedSum(networkCounts.values(), List.of());
        int[] allocation = allocateInventoryFirst(limit, inventoryAvailable, networkAvailable);
        int target = allocation[0] + allocation[1];
        if (target <= 0) return 0;
        if (state == State.IDLE) transition(State.RESERVING);

        int remaining = reserveCandidates(inventoryTemplates, inventoryCounts, pendingInv,
                ingredient, allocation[0], Source.PLAYER_INVENTORY, null);
        if (remaining != 0) {
            throw new IllegalStateException("inventory availability changed while reserving");
        }
        remaining = reserveCandidates(networkTemplates, networkCounts, pendingNet,
                ingredient, allocation[1], Source.NETWORK, selected);
        if (remaining != 0) {
            throw new IllegalStateException("network availability changed while reserving");
        }
        return target;
    }

    static int[] allocateInventoryFirst(int needed, int inventoryAvailable, int networkAvailable) {
        if (needed <= 0 || inventoryAvailable < 0 || networkAvailable < 0) return new int[]{0, 0};
        int inventory = Math.min(needed, inventoryAvailable);
        int network = Math.min(needed - inventory, networkAvailable);
        return new int[]{inventory, network};
    }

    private static int saturatedSum(Collection<? extends Number> first,
                                    Collection<? extends Number> second) {
        long total = 0L;
        for (Number value : first) {
            total = Math.min(Integer.MAX_VALUE, total + Math.max(0L, value.longValue()));
        }
        for (Number value : second) {
            total = Math.min(Integer.MAX_VALUE, total + Math.max(0L, value.longValue()));
        }
        return (int) total;
    }

    private static void collectMatchingAvailability(
            Collection<ItemStack> stacks, Ingredient ingredient,
            Map<CraftingResolver.StackKey, Long> pending,
            LinkedHashMap<CraftingResolver.StackKey, ItemStack> templates,
            LinkedHashMap<CraftingResolver.StackKey, Long> counts) {
        for (ItemStack stack : stacks) {
            if (stack.isEmpty() || !IngredientMatcher.test(ingredient, stack)) continue;
            CraftingResolver.StackKey key = CraftingResolver.StackKey.of(stack, true);
            templates.putIfAbsent(key, stack.copyWithCount(1));
            counts.merge(key, (long) stack.getCount(), ExtractionLedger::saturatedAdd);
        }
        counts.replaceAll((key, count) ->
                Math.max(0L, count - pending.getOrDefault(key, 0L)));
        counts.entrySet().removeIf(entry -> entry.getValue() <= 0);
    }

    private int reserveCandidates(
            LinkedHashMap<CraftingResolver.StackKey, ItemStack> templates,
            LinkedHashMap<CraftingResolver.StackKey, Long> counts,
            Map<CraftingResolver.StackKey, Long> pending, Ingredient ingredient,
            int needed, Source source, @Nullable CraftStorageEndpoint sourceEndpoint) {
        int remaining = needed;
        for (var candidate : counts.entrySet()) {
            if (remaining <= 0) break;
            int take = (int) Math.min(remaining, candidate.getValue());
            if (take <= 0) continue;
            ItemStack template = templates.get(candidate.getKey());
            pending.merge(candidate.getKey(), (long) take, ExtractionLedger::saturatedAdd);
            recordEntry(new Entry(source, ingredient, template.copyWithCount(take), null,
                    null, null, sourceEndpoint, true));
            remaining -= take;
        }
        return remaining;
    }

    // Three-phase atomic commit: pre-check, batch extract, confirm.
    /** 兼容旧调用签名的端点入口，实际提交仍走统一的端点实现。 */
    public boolean commit(@Nullable CraftStorageEndpoint endpoint,
                          @Nonnull ServerPlayer player) {
        return commitToEndpoint(endpoint, player);
    }

    public boolean commitToEndpoint(@Nullable CraftStorageEndpoint endpoint,
                                    @Nonnull ServerPlayer player) {
        if (state == State.COMMITTED) return true;
        refundOwner = player.getUUID();
        refundServer = player.server;
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
        settlementLedger.beginCommit();

        // ── Phase 1: Pre-check ──────────────────────────────────
        if (!preCheck(endpoint, player)) {
            LOGGER.warn(fmt("Ledger commit pre-check failed, rolling back"));
            resetSettlementMirror();
            transition(State.ROLLED_BACK);
            return false;
        }

        // ── Phase 2: Batch extract ──────────────────────────────
        List<ExtractRecord> extracted = new ArrayList<>(entries.size());
        boolean extractionFailed = false;
        try {
            for (Entry entry : entries) {
                StorageOperationResult result = extractOne(entry, endpoint, player);
                extracted.add(new ExtractRecord(entry.id, entry.source, result,
                        entry.altarDim, entry.altarPos, entry.sourceEndpoint));
                if (result.kind() != StorageOperationResult.Kind.EXTRACT
                        || !result.complete() || result.mode() != StorageOperationMode.PERFORM
                        || result.transferredAmount().orElse(-1L) != entry.count) {
                    LOGGER.warn(fmt("Ledger commit: extraction incomplete for entry {} (need {}, status {})"),
                            entry.id, entry.count, result.status());
                    extractionFailed = true;
                    break;
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[RSI-Ledger] Commit exception during extraction", e);
            extractionFailed = true;
        }
        if (extractionFailed) {
            resetSettlementMirror();
            transition(State.ROLLED_BACK);
            rollbackExtractedPhases(extracted, player);
            return false;
        }

        // Sync player inventory to client after modifying items directly
        boolean extractedFromInv = entries.stream().anyMatch(e -> e.source == Source.PLAYER_INVENTORY);
        if (extractedFromInv) {
            player.getInventory().setChanged();
            PlayerUtils.broadcastInventoryChanges(player);
        }

        // ── Phase 3: Confirm ────────────────────────────────────
        for (ExtractRecord record : extracted) {
            Entry entry = entriesById.get(record.entryId);
            if (entry == null) {
                rollbackExtractedPhases(extracted, player);
                resetSettlementMirror();
                transition(State.ROLLED_BACK);
                return false;
            }
            entry.confirmExtracted(record.result.extractedStacks());
            StorageSettlementLedger.EntryId settlementId = settlementEntries.get(entry.id);
            if (settlementId != null) {
                try {
                    CraftStorageEndpoint sourceEndpoint = endpointFor(entry.sourceEndpoint);
                    settlementLedger.recordExtraction(settlementId, record.result,
                            stack -> settlementKey(entry, sourceEndpoint));
                } catch (RuntimeException mirrorFailure) {
                    String key = "extraction:" + mirrorFailure.getClass().getName()
                            + ":" + String.valueOf(mirrorFailure.getMessage());
                    if (SETTLEMENT_MIRROR_LOGS.allow(key)) {
                        LOGGER.warn(
                                "[RSI-Ledger] settlement mirror extraction skipped; legacy extraction committed ({}: {})",
                                mirrorFailure.getClass().getSimpleName(), mirrorFailure.getMessage());
                        RSIntegrationMod.debug(
                                "[RSI-Ledger] settlement mirror extraction detail entry={} template={} returned={}",
                                entry.id, entry.template, entry.extracted, mirrorFailure);
                    }
                }
            }
        }
        if (settlementLedger.state() == StorageSettlementLedger.State.COMMITTING) {
            try {
                settlementLedger.finishCommit();
            } catch (RuntimeException mirrorFailure) {
                // The settlement ledger is a diagnostic mirror.  It must not
                // veto the authoritative extraction ledger: doing so leaves
                // graph nodes in an endless retry loop after RS has already
                // removed the requested items.  Keep the legacy transaction
                // committed and discard only the failed mirror state.
                String key = "commit:" + mirrorFailure.getClass().getName()
                        + ":" + String.valueOf(mirrorFailure.getMessage());
                if (SETTLEMENT_MIRROR_LOGS.allow(key)) {
                    LOGGER.warn(
                            "[RSI-Ledger] settlement mirror commit skipped; legacy extraction committed ({}: {})",
                            mirrorFailure.getClass().getSimpleName(), mirrorFailure.getMessage());
                    RSIntegrationMod.debug(
                            "[RSI-Ledger] settlement mirror commit detail", mirrorFailure);
                }
                resetSettlementMirror();
            }
        }
        MaterialSources.invalidateFor(player, storageEndpoint);
        pendingNet.clear();
        pendingInv.clear();
        pendingResonance.clear();
        endpointEntryCache.clear();
        clearReservationSnapshot();
        transition(State.COMMITTED);
        return true;
    }

    /**
     * Pre-check every reserved entry before committing to extraction.
     * Verifies that the total reserved count does not exceed physical
     * availability (correcting for any race with other systems).
     */
    private boolean preCheck(@Nullable CraftStorageEndpoint endpoint, ServerPlayer player) {
        CraftStorageEndpoint selected = endpoint != null ? endpoint : storageEndpoint;
        if (selected != null) {
            // Group reservations by ingredient for accurate per-type checking.
            Map<Ingredient, Integer> neededByIngredient = new HashMap<>();
            for (Entry e : entries) {
                if (e.source == Source.NETWORK) {
                    neededByIngredient.merge(e.originalIngredient, e.count, Integer::sum);
                }
            }
            if (!neededByIngredient.isEmpty()) {
                var snapshot = selected.snapshot(player,
                        IngredientMatcher.itemTypesForAll(neededByIngredient.keySet()))
                        .snapshot().orElse(null);
                if (snapshot == null) return false;
                for (var ingEntry : neededByIngredient.entrySet()) {
                    StorageSnapshot.MatchResult match = snapshot.match(ingEntry.getKey());
                    if (!match.successful()) return false;
                    long available = 0L;
                    for (var item : match.items()) {
                        available = saturatedAdd(available, item.amount());
                    }
                    if (available < ingEntry.getValue()) {
                        LOGGER.warn(
                                "[RSI-Ledger] Pre-check endpoint: insufficient {} in storage (need {}, have {})",
                                CraftPacketUtils.describeIngredient(ingEntry.getKey()),
                                ingEntry.getValue(), available);
                        return false;
                    }
                }
                return preCheckResonance();
            }
        }
        return preCheckResonance();
    }

    private boolean preCheckResonance() {
        for (Entry entry : entries) {
            if (entry.source != Source.RESONANCE_DISK) continue;
            if (entry.resonanceView == null) return false;
            ItemStack simulated = entry.resonanceView.extractExactView(
                    entry.resonanceSlot, entry.template, entry.count, true);
            if (simulated.getCount() != entry.count
                    || !MaterialMatcher.sameRuntimeFragment(entry.template, simulated)) {
                LOGGER.warn(
                        "[RSI-Ledger] Resonance tool changed before commit: backend={} slot={} expected={}",
                        entry.resonanceView.backendId(), entry.resonanceSlot, entry.template);
                return false;
            }
        }
        // Player inventory entries are verified at extraction time
        // since inventory can change between reservation and commit.
        return true;
    }

    /** Return extracted items back to their original source. */
    private void rollbackExtractedPhases(List<ExtractRecord> extracted, ServerPlayer player) {
        for (ExtractRecord rec : extracted) {
            if (rec.result.kind() != StorageOperationResult.Kind.EXTRACT
                    || rec.result.mode() != StorageOperationMode.PERFORM) continue;
            List<ItemStack> fragments = new ArrayList<>(rec.result.extractedStacks());
            fragments.addAll(rec.result.recoveryStacks());
            for (ItemStack s : fragments) {
                try {
                    rollbackFragment(rec, s, player);
                } catch (RuntimeException failure) {
                    logUncertainRefund(rec.entryId, s, failure.getClass().getSimpleName());
                }
            }
        }
    }

    private void rollbackFragment(ExtractRecord rec, ItemStack s, ServerPlayer player) {
        if (s.isEmpty()) return;
        CraftStorageEndpoint destination = rec.sourceEndpoint != null
                ? rec.sourceEndpoint : storageEndpoint;
        if (InkFluidSupport.isToken(s)) {
            ItemStack leftover = destination == null ? s : refundRemainder(
                    destination.insert(player, s, false), rec.entryId, s);
            if (!leftover.isEmpty()) {
                CraftFluidRecovery.retainToEndpoint(
                        player.server, player.getUUID(), player, leftover, destination);
            }
            return;
        }
        switch (rec.source) {
            case ALTAR_BINDING, NETWORK -> {
                ItemStack leftover = destination == null ? s : refundRemainder(
                        destination.insert(player, s, false), rec.entryId, s);
                if (!leftover.isEmpty()) {
                    PlayerUtils.safeGiveToStorage(player, leftover, destination);
                }
            }
            case RESONANCE_DISK -> {
                Entry entry = entriesById.get(rec.entryId);
                if (entry == null || entry.resonanceView == null) {
                    PlayerUtils.safeGiveToStorage(player, s, null);
                    return;
                }
                ItemStack remainder = entry.resonanceView.insertView(
                        entry.resonanceSlot, s, s.getCount(), false);
                entry.resonanceView.markDirty(player);
                if (!remainder.isEmpty()) {
                    PlayerUtils.safeGiveToStorage(player, remainder, null);
                }
            }
            case PLAYER_INVENTORY -> PlayerUtils.safeGiveToStorage(player, s, null);
        }
    }

    private static ItemStack refundRemainder(StorageOperationResult result, int entryId, ItemStack stack) {
        var remainder = result.remainder();
        if (remainder.isEmpty()) logUncertainRefund(entryId, stack, result.status().name());
        return remainder.orElse(ItemStack.EMPTY);
    }

    private static void logUncertainRefund(int entryId, ItemStack stack, String reason) {
        // Unknown transfer amounts must not be retried: storage may have
        // accepted the item before reporting an exception.
        if (REFUND_LOGS.allow(reason)) {
            LOGGER.error("[RSI-Ledger] Refund outcome unknown; not retried: entry={} item={} count={} reason={}",
                    entryId, ItemStackUtils.registryId(stack),
                    stack.getCount(), reason);
        }
    }

    /** Lightweight record for extracted items during commit rollback. */
    private record ExtractRecord(int entryId, Source source, StorageOperationResult result,
                                  @Nullable ResourceKey<Level> altarDim,
                                  @Nullable BlockPos altarPos,
                                  @Nullable CraftStorageEndpoint sourceEndpoint) {}

    /** Explicitly roll back all reservations without committing. */
    public void rollback(@Nullable ServerPlayer player) {
        if (state == State.IDLE || state == State.ROLLED_BACK) return;
        if (state == State.COMMITTED) {
            refundCommitted(null, player);
            return;
        }
        requireState(State.RESERVING, State.RESERVED, State.COMMITTING);
        releaseReservations(player);
        resetSettlementMirror();
        transition(State.ROLLED_BACK);
    }

    private void releaseReservations(@Nullable ServerPlayer player) {
        // Pending reservations were never physically extracted — just clear the
        // tracking maps so future reservations see accurate counts.
        pendingNet.clear();
        pendingInv.clear();
        pendingResonance.clear();
        endpointEntryCache.clear();
        clearReservationSnapshot();
    }

    private void resetSettlementMirror() {
        settlementLedger.reset();
        settlementEntries.clear();
    }

    /**
     * Forget cached network contents while preserving this ledger's pending
     * reservations. Inline auto-crafting may add a new stack between reserves.
     */
    public void invalidateNetworkSnapshot() {
        endpointEntryCache.clear();
        clearReservationSnapshot();
    }

    public int size() { return entries.size(); }
    public boolean isCommitted() { return state == State.COMMITTED; }
    public State state() { return state; }

    /** Opaque ownership token for entries reserved by one craft operation. */
    public record ReservationToken(List<Integer> entryIds) {
        public ReservationToken {
            entryIds = List.copyOf(entryIds);
        }
    }

    /** Stable return target for a reusable item reserved from a resonance disk. */
    public record ResonanceReturnTarget(ResonanceStorageView view, int slot) {}

    /** Resolve the original resonance slot for a reusable item before settlement. */
    @Nullable
    public ResonanceReturnTarget resonanceReturnTarget(
            @Nonnull ItemStack reservedItem, @Nonnull Ingredient matcher) {
        if (reservedItem.isEmpty() || matcher.isEmpty()) return null;
        for (Entry entry : entries) {
            if (entry.source != Source.RESONANCE_DISK
                    || entry.resonanceView == null
                    || entry.count != 1
                    || !ItemStack.isSameItem(entry.template, reservedItem)
                    || !matcher.test(entry.template)) continue;
            return new ResonanceReturnTarget(entry.resonanceView, entry.resonanceSlot);
        }
        return null;
    }

    /** Mark the current end of the reservation list before reserving one operation. */
    public int reservationMark() {
        requireState(State.IDLE, State.RESERVING);
        return entries.size();
    }

    /** Capture exact entry identities added since {@link #reservationMark()}. */
    public ReservationToken tokenSince(int mark) {
        if (mark < 0 || mark > entries.size()) {
            throw new IllegalArgumentException("invalid reservation mark: " + mark);
        }
        List<Integer> ids = new ArrayList<>(entries.size() - mark);
        for (int i = mark; i < entries.size(); i++) ids.add(entries.get(i).id);
        return new ReservationToken(ids);
    }

    /**
     * Settle a successfully completed operation. Settled entries are removed from
     * the committed ledger so a later group abort refunds only unfinished work.
     */
    public void settleCommitted(ReservationToken token) {
        requireState(State.COMMITTED);
        List<Entry> owned = requireOwnedEntries(token);
        List<StorageSettlementLedger.EntryId> settlementIds = owned.stream()
                .map(entry -> settlementEntries.get(entry.id))
                .filter(Objects::nonNull)
                .toList();
        entries.removeAll(owned);
        for (Entry entry : owned) entriesById.remove(entry.id);
        if (!settlementIds.isEmpty()
                && settlementLedger.state() == StorageSettlementLedger.State.COMMITTED) {
            settlementLedger.settle(new StorageSettlementLedger.ReservationToken(settlementIds));
        }
        for (Entry entry : owned) settlementEntries.remove(entry.id);
    }

    /** Refund only the committed entries owned by operations that never dispatched. */
    public void refundCommittedToEndpoint(ReservationToken token,
                                          @Nullable CraftStorageEndpoint endpoint,
                                          @Nullable ServerPlayer player) {
        requireState(State.COMMITTED);
        List<Entry> owned = requireOwnedEntries(token);
        for (Entry entry : owned) refundEntry(entry, endpoint, player);
        entries.removeAll(owned);
        for (Entry entry : owned) entriesById.remove(entry.id);
        resetSettlementMirror();
    }

    private List<Entry> requireOwnedEntries(ReservationToken token) {
        if (token == null || token.entryIds().isEmpty()) return List.of();
        List<Entry> owned = new ArrayList<>(token.entryIds().size());
        for (Integer id : token.entryIds()) {
            Entry entry = entriesById.get(id);
            if (entry == null) {
                throw new IllegalStateException("reservation token already settled or unknown: " + id);
            }
            owned.add(entry);
        }
        return owned;
    }

    /** Mark every remaining committed fragment as irreversibly consumed. */
    public void settleAllCommitted() {
        requireState(State.COMMITTED);
        entries.clear();
        entriesById.clear();
        resetSettlementMirror();
    }

    public void reset() {
        entries.clear();
        entriesById.clear();
        pendingNet.clear();
        pendingInv.clear();
        pendingResonance.clear();
        preferredResonanceIngredients.clear();
        endpointEntryCache.clear();
        clearReservationSnapshot();
        refundOwner = null;
        refundServer = null;
        // A chain reuses its master ledger across vanilla steps. The settlement
        // mirror is a per-commit scope and must be reset with the legacy ledger;
        // otherwise the next step calls beginCommit() on COMMITTED state.
        resetSettlementMirror();
        state = State.IDLE;
    }

    /**
     * Auto-rollback guard for try-with-resources.
     * No-op if already committed or rolled back; otherwise releases
     * all in-memory reservations (no physical items were moved, so
     * no refunds are needed).
     */
    @Override
    public void close() {
        if (state == State.COMMITTED || state == State.ROLLED_BACK) return;
        // An IDLE ledger with no live reservations has nothing to abandon — this
        // is the normal resting state for delegates that reset() between phases
        // (e.g. the WR Arcane Iterator per-level scheduler). Only warn when
        // reservations are actually being discarded.
        if (state != State.IDLE || !entries.isEmpty()) {
            LOGGER.warn(fmt("Ledger auto-rollback via close() — {} entries abandoned"), entries.size());
        }
        entries.clear();
        entriesById.clear();
        pendingNet.clear();
        pendingInv.clear();
        pendingResonance.clear();
        preferredResonanceIngredients.clear();
        endpointEntryCache.clear();
        clearReservationSnapshot();
        resetSettlementMirror();
        state = State.ROLLED_BACK;
    }

    private enum Source { NETWORK, PLAYER_INVENTORY, ALTAR_BINDING, RESONANCE_DISK }

    private record ResonanceReservationKey(
            String backendId, int slot, CraftingResolver.StackKey stackKey) {}

    private static final AtomicInteger entryIdSeq = new AtomicInteger();

    private static final class Entry {
        final int id;
        final Source source;
        final Ingredient originalIngredient;
        final ItemStack template;
        final int count;
        final boolean exactIdentity;
        @Nullable final ItemStack preExtracted;
        @Nullable final ResourceKey<Level> altarDim;
        @Nullable final BlockPos altarPos;
        @Nullable final CraftStorageEndpoint sourceEndpoint;
        @Nullable final ResonanceStorageView resonanceView;
        final int resonanceSlot;
        @Nullable final ResonanceReservationKey resonanceKey;
        final Map<CraftingResolver.StackKey, Long> pendingNetworkAllocation = new HashMap<>();
        List<ItemStack> extracted = List.of();

        Entry(Source source, Ingredient originalIngredient, ItemStack template, @Nullable ItemStack preExtracted,
              @Nullable ResourceKey<Level> altarDim, @Nullable BlockPos altarPos,
              @Nullable CraftStorageEndpoint sourceEndpoint) {
            this(source, originalIngredient, template, preExtracted, altarDim, altarPos, sourceEndpoint, false);
        }

        Entry(Source source, Ingredient originalIngredient, ItemStack template, @Nullable ItemStack preExtracted,
              @Nullable ResourceKey<Level> altarDim, @Nullable BlockPos altarPos,
              @Nullable CraftStorageEndpoint sourceEndpoint, boolean exactIdentity) {
            this(source, originalIngredient, template, preExtracted, altarDim, altarPos,
                    sourceEndpoint, exactIdentity, null, -1, null);
        }

        private Entry(Source source, Ingredient originalIngredient, ItemStack template,
                      @Nullable ItemStack preExtracted,
                      @Nullable ResourceKey<Level> altarDim, @Nullable BlockPos altarPos,
                      @Nullable CraftStorageEndpoint sourceEndpoint, boolean exactIdentity,
                      @Nullable ResonanceStorageView resonanceView, int resonanceSlot,
                      @Nullable ResonanceReservationKey resonanceKey) {
            this.id = entryIdSeq.incrementAndGet();
            this.source = source;
            this.originalIngredient = originalIngredient;
            this.template = template;
            this.count = template.getCount();
            this.exactIdentity = exactIdentity;
            this.preExtracted = preExtracted;
            this.altarDim = altarDim;
            this.altarPos = altarPos;
            this.sourceEndpoint = sourceEndpoint;
            this.resonanceView = resonanceView;
            this.resonanceSlot = resonanceSlot;
            this.resonanceKey = resonanceKey;
        }

        static Entry resonance(Ingredient ingredient, ItemStack template,
                               ResonanceStorageView view, int slot,
                               ResonanceReservationKey key) {
            return new Entry(Source.RESONANCE_DISK, ingredient, template,
                    null, null, null, null, true, view, slot, key);
        }

        void confirmExtracted(List<ItemStack> stacks) {
            // Callers hand over copies owned by the operation result or refund matcher.
            this.extracted = List.copyOf(stacks);
        }
    }

    private StorageOperationResult extractOne(Entry entry,
                                              @Nullable CraftStorageEndpoint endpoint,
                                              ServerPlayer player) {
        CraftStorageEndpoint sourceEndpoint = entry.sourceEndpoint != null
                ? entry.sourceEndpoint : endpoint != null ? endpoint : storageEndpoint;
        if (entry.source == Source.NETWORK && sourceEndpoint != null) {
            return entry.exactIdentity
                    ? sourceEndpoint.extractExact(player, entry.template, entry.count, false)
                    : sourceEndpoint.extractMatching(
                            player, entry.originalIngredient, entry.count, false);
        }
        ItemStack stack = extractLegacyOne(entry, player);
        return StorageOperationResult.extracted(StorageOperationMode.PERFORM, entry.count,
                stack.isEmpty() ? List.of() : List.of(stack));
    }

    private ItemStack extractLegacyOne(Entry entry, ServerPlayer player) {
        return switch (entry.source) {
            case ALTAR_BINDING -> {
                if (entry.preExtracted != null) yield entry.preExtracted.copy();
                if (entry.exactIdentity && entry.sourceEndpoint != null) {
                    yield entry.sourceEndpoint.extractExact(
                            player, entry.template, entry.count, false)
                            .extractedStacks().stream().findFirst().orElse(ItemStack.EMPTY);
                }
                // Use original Ingredient to avoid errors from aggregated extraction
                yield AltarBindingRegistry.tryExtractFromBindings(player,
                        entry.altarDim, entry.altarPos,
                        entry.originalIngredient, entry.count);
            }
            case NETWORK -> ItemStack.EMPTY;
            case RESONANCE_DISK -> {
                if (entry.resonanceView == null) yield ItemStack.EMPTY;
                ItemStack extracted = entry.resonanceView.extractExactView(
                        entry.resonanceSlot, entry.template, entry.count, false);
                if (!extracted.isEmpty()) entry.resonanceView.markDirty(player);
                yield extracted;
            }
            case PLAYER_INVENTORY -> {
                ItemStack extracted = extractFromSlots(entry, player.getInventory().items);
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

    private static ItemStack extractFromSlots(Entry entry, NonNullList<ItemStack> slots) {
        ItemStack extracted = ItemStack.EMPTY;
        int needed = entry.count;
        for (ItemStack stack : slots) {
            if (IngredientMatcher.test(entry.originalIngredient, stack)
                    && MaterialMatcher.sameRuntimeFragment(entry.template, stack)
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

    private static ItemStack extractFromSlots(Entry entry, NonNullList<ItemStack> slots, int limit) {
        ItemStack extracted = ItemStack.EMPTY;
        int remaining = Math.min(limit, entry.count);
        for (ItemStack stack : slots) {
            if (IngredientMatcher.test(entry.originalIngredient, stack)
                    && MaterialMatcher.sameRuntimeFragment(entry.template, stack)
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

    private int countExactAvailableInEndpoint(@Nullable CraftStorageEndpoint endpoint,
                                              ItemStack template,
                                              @Nullable ServerPlayer player) {
        try {
            CraftStorageEndpoint selected = endpoint != null ? endpoint : storageEndpoint;
            if (selected != null && player != null) {
                StorageSnapshot snapshot = reservationSnapshot(selected, player);
                long endpointAvailable = snapshot == null ? 0L
                        : snapshot.countExact(selected.session().itemKey(template));
                endpointAvailable = Math.max(0L, endpointAvailable
                        - pendingNet.getOrDefault(CraftingResolver.StackKey.of(template, true), 0L));
                long resonanceAvailable = countExactAvailableInPreferredResonance(
                        template, selected, player);
                return (int) Math.min(Integer.MAX_VALUE,
                        Math.max(0L, endpointAvailable + resonanceAvailable));
            }
            return 0;
        } catch (Exception e) {
            LOGGER.warn("[RSI-Ledger] Error counting exact network stack", e);
            return 0;
        }
    }

    private int countExactAvailableInPreferredResonance(
            ItemStack template, CraftStorageEndpoint endpoint, ServerPlayer player) {
        if (template.isEmpty() || !matchesPreferredResonanceIngredient(template)) return 0;
        long available = 0L;
        for (ResonanceStorageView view : ResonanceCraftingSource.viewsFor(endpoint, player)) {
            for (ResonanceStorageView.StoredStack stored : view.storedStacks()) {
                ItemStack stack = stored.stack();
                if (stack.isEmpty() || !MaterialMatcher.sameRuntimeFragment(template, stack)) continue;
                ResonanceReservationKey key = new ResonanceReservationKey(
                        view.backendId(), stored.slot(), CraftingResolver.StackKey.of(stack, true));
                available += Math.max(0,
                        stack.getCount() - pendingResonance.getOrDefault(key, 0L));
                if (available >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
            }
        }
        return (int) available;
    }

    private int countExactAvailableInInventory(ServerPlayer player, ItemStack template) {
        CraftingResolver.StackKey key = CraftingResolver.StackKey.of(template, true);
        int available = countExact(player.getInventory().items, template);
        for (IItemHandler backpack : findAllBackpackInventories(player)) {
            for (int slot = 0; slot < backpack.getSlots(); slot++) {
                ItemStack stored = backpack.getStackInSlot(slot);
                if (MaterialMatcher.sameRuntimeFragment(template, stored)) available += stored.getCount();
            }
        }
        return (int) Math.min(Integer.MAX_VALUE,
                Math.max(0L, available - pendingInv.getOrDefault(key, 0L)));
    }

    private long countExactAvailableInInventoryLong(ServerPlayer player, ItemStack template) {
        CraftingResolver.StackKey key = CraftingResolver.StackKey.of(template, true);
        long available = 0L;
        for (ItemStack stack : player.getInventory().items) {
            if (!stack.isEmpty() && MaterialMatcher.sameRuntimeFragment(template, stack)) {
                available = saturatedAdd(available, stack.getCount());
            }
        }
        for (ItemStack stack : player.getInventory().offhand) {
            if (!stack.isEmpty() && MaterialMatcher.sameRuntimeFragment(template, stack)) {
                available = saturatedAdd(available, stack.getCount());
            }
        }
        for (IItemHandler backpack : findAllBackpackInventories(player)) {
            for (int slot = 0; slot < backpack.getSlots(); slot++) {
                ItemStack stored = backpack.getStackInSlot(slot);
                if (MaterialMatcher.sameRuntimeFragment(template, stored)) {
                    available = saturatedAdd(available, stored.getCount());
                }
            }
        }
        return Math.max(0L, available - pendingInv.getOrDefault(key, 0L));
    }

    private boolean reserveExactInventoryAvailability(ServerPlayer player, ItemStack template, int needed) {
        CraftingResolver.StackKey key = CraftingResolver.StackKey.of(template, true);
        int available = countExactAvailableInInventory(player, template);
        if (available < needed) return false;
        pendingInv.merge(key, (long) needed, ExtractionLedger::saturatedAdd);
        return true;
    }

    private static StorageItemKey settlementKey(
            Entry entry, @Nullable CraftStorageEndpoint endpoint) {
        // The mirror key must use the same source namespace as reserve(). A
        // ledger can contain RS and player-inventory entries at once; mapping
        // an inventory entry through the active RS session changes only the
        // backend id and falsely reports an NBT/item identity mismatch.
        if (entry.source == Source.PLAYER_INVENTORY) {
            return StorageItemKey.fromItemStack(
                    new StorageBackendId("local"),
                    entry.template);
        }
        if (entry.source == Source.RESONANCE_DISK && entry.resonanceView != null) {
            return StorageItemKey.fromItemStack(
                    new StorageBackendId(
                            entry.resonanceView.backendId()), entry.template);
        }
        if (endpoint != null) return endpoint.session().itemKey(entry.template);
        return StorageItemKey.fromItemStack(
                new StorageBackendId("unknown"),
                entry.template);
    }

    /** Diagnostic snapshot used when a graph reservation cannot be fulfilled. */
    String describeExactAvailability(ItemStack template, ServerPlayer player) {
        if (template == null || template.isEmpty() || player == null) return "unreadable";
        try {
            int network = countExactAvailableInEndpoint(storageEndpoint, template, player);
            int inventory = countExactAvailableInInventory(player, template);
            CraftingResolver.StackKey key = CraftingResolver.StackKey.of(template, true);
            return "network=" + network + ", inventory=" + inventory
                    + ", pendingNetwork=" + pendingNet.getOrDefault(key, 0L)
                    + ", pendingInventory=" + pendingInv.getOrDefault(key, 0L);
        } catch (RuntimeException e) {
            return "unreadable:" + e.getClass().getSimpleName();
        }
    }

    private static int countExact(List<ItemStack> stacks, ItemStack template) {
        int total = 0;
        for (ItemStack stack : stacks) {
            if (MaterialMatcher.sameRuntimeFragment(template, stack)) total += stack.getCount();
        }
        return total;
    }

    private ItemStack findAvailableInEndpoint(CraftStorageEndpoint endpoint, Ingredient ingredient,
                                              int needed, @Nullable ServerPlayer player) {
        try {
            List<ItemStack> stacks = endpointEntries(endpoint, player);
            if (stacks.isEmpty()) return ItemStack.EMPTY;
            if (GoetySoulTotemCrafting.isSoulTotemIngredient(ingredient)) {
                stacks = new ArrayList<>(stacks);
                stacks.sort(Comparator.comparingInt(GoetySoulTotemCrafting::storedSouls).reversed());
            }

            // Single-stack fast path: one stack has enough → track pendingNet here
            for (ItemStack stored : stacks) {
                if (!IngredientMatcher.test(ingredient, stored)) continue;
                long available = stored.getCount()
                        - pendingNet.getOrDefault(CraftingResolver.StackKey.of(stored, true), 0L);
                if (available <= 0) continue;
                if (available >= needed) {
                    ItemStack template = stored.copy();
                    template.setCount(1);
                    pendingNet.merge(CraftingResolver.StackKey.of(stored, true),
                            (long) needed, ExtractionLedger::saturatedAdd);
                    return template;
                }
            }

            // Multi-stack aggregation: distribute pendingNet across all
            // contributing stacks so subsequent reservations see accurate counts
            ItemStack pass1Template = ItemStack.EMPTY;
            long pass1Total = 0L;
            ItemStack pass2Template = ItemStack.EMPTY;
            long pass2Total = 0L;

            for (ItemStack stored : stacks) {
                if (!IngredientMatcher.test(ingredient, stored)) continue;
                long available = stored.getCount()
                        - pendingNet.getOrDefault(CraftingResolver.StackKey.of(stored, true), 0L);
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
                if (!IngredientMatcher.test(ingredient, stored)) continue;
                long available = stored.getCount()
                        - pendingNet.getOrDefault(CraftingResolver.StackKey.of(stored, true), 0L);
                if (available <= 0) continue;
                if (pass1Sufficient && stored.hasTag()) continue;
                int contrib = (int) Math.min(remaining, available);
                pendingNet.merge(CraftingResolver.StackKey.of(stored, true),
                        (long) contrib, ExtractionLedger::saturatedAdd);
                remaining -= contrib;
            }
            return chosen;
        } catch (Exception e) {
            LOGGER.warn("[RSI-Ledger] Error scanning network storage", e);
        }
        return ItemStack.EMPTY;
    }

    private ItemStack findAvailableInInventory(ServerPlayer player, Ingredient ingredient, int needed) {
        if (GoetySoulTotemCrafting.isSoulTotemIngredient(ingredient)) {
            ItemStack best = ItemStack.EMPTY;
            for (ItemStack stack : player.getInventory().items) {
                if (!IngredientMatcher.test(ingredient, stack)) continue;
                long available = stack.getCount()
                        - pendingInv.getOrDefault(CraftingResolver.StackKey.of(stack, true), 0L);
                if (available >= needed && (best.isEmpty()
                        || GoetySoulTotemCrafting.storedSouls(stack)
                        > GoetySoulTotemCrafting.storedSouls(best))) {
                    best = stack;
                }
            }
            for (IItemHandler backpack : findAllBackpackInventories(player)) {
                for (int slot = 0; slot < backpack.getSlots(); slot++) {
                    ItemStack stack = backpack.getStackInSlot(slot);
                    if (!IngredientMatcher.test(ingredient, stack)
                            || !InventoryProtectionPolicy.mayUseFromBackpack(stack, ingredient)) continue;
                    long available = stack.getCount()
                            - pendingInv.getOrDefault(CraftingResolver.StackKey.of(stack, true), 0L);
                    if (available >= needed && (best.isEmpty()
                            || GoetySoulTotemCrafting.storedSouls(stack)
                            > GoetySoulTotemCrafting.storedSouls(best))) {
                        best = stack;
                    }
                }
            }
            if (best.isEmpty()) return ItemStack.EMPTY;
            CraftingResolver.StackKey key = CraftingResolver.StackKey.of(best, true);
            pendingInv.merge(key, (long) needed, ExtractionLedger::saturatedAdd);
            return best.copyWithCount(1);
        }
        // Single-stack fast path: one stack has enough → track pendingInv here
        for (ItemStack stack : player.getInventory().items) {
            if (!IngredientMatcher.test(ingredient, stack)) continue;
            long available = stack.getCount()
                    - pendingInv.getOrDefault(CraftingResolver.StackKey.of(stack, true), 0L);
            if (available <= 0) continue;

            if (!stack.hasTag() && available >= needed) {
                ItemStack template = stack.copy();
                template.setCount(1);
                pendingInv.merge(CraftingResolver.StackKey.of(stack, true), (long) needed, ExtractionLedger::saturatedAdd);
                return template;
            }
        }

        // Fast path for backpack contents
        for (IItemHandler bp : findAllBackpackInventories(player)) {
            for (int s = 0; s < bp.getSlots(); s++) {
                ItemStack stack = bp.getStackInSlot(s);
                if (stack.isEmpty() || !IngredientMatcher.test(ingredient, stack)) continue;
                if (!InventoryProtectionPolicy.mayUseFromBackpack(stack, ingredient)) continue;
                long available = stack.getCount()
                        - pendingInv.getOrDefault(CraftingResolver.StackKey.of(stack, true), 0L);
                if (available <= 0) continue;
                if (!stack.hasTag() && available >= needed) {
                    ItemStack template = stack.copy();
                    template.setCount(1);
                    pendingInv.merge(CraftingResolver.StackKey.of(stack, true), (long) needed, ExtractionLedger::saturatedAdd);
                    return template;
                }
            }
        }

        ItemStack pass1Template = ItemStack.EMPTY;
        long pass1Total = 0L;
        ItemStack pass2Template = ItemStack.EMPTY;
        long pass2Total = 0L;

        for (ItemStack stack : player.getInventory().items) {
            if (!IngredientMatcher.test(ingredient, stack)) continue;
            long available = stack.getCount()
                    - pendingInv.getOrDefault(CraftingResolver.StackKey.of(stack, true), 0L);
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
                if (stack.isEmpty() || !IngredientMatcher.test(ingredient, stack)) continue;
                if (!InventoryProtectionPolicy.mayUseFromBackpack(stack, ingredient)) continue;
                long available = stack.getCount()
                        - pendingInv.getOrDefault(CraftingResolver.StackKey.of(stack, true), 0L);
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
        long remaining = needed;
        for (ItemStack stack : player.getInventory().items) {
            if (remaining <= 0) break;
            if (!IngredientMatcher.test(ingredient, stack)) continue;
            long available = stack.getCount()
                    - pendingInv.getOrDefault(CraftingResolver.StackKey.of(stack, true), 0L);
            if (available <= 0) continue;
            if (pass1Sufficient && stack.hasTag()) continue;
            int contrib = (int) Math.min(remaining, available);
            pendingInv.merge(CraftingResolver.StackKey.of(stack, true), (long) contrib, ExtractionLedger::saturatedAdd);
            remaining -= contrib;
        }
        // Distribute remaining across backpack contents
        for (IItemHandler bp : findAllBackpackInventories(player)) {
            if (remaining <= 0) break;
            for (int s = 0; s < bp.getSlots(); s++) {
                if (remaining <= 0) break;
                ItemStack stack = bp.getStackInSlot(s);
                if (stack.isEmpty() || !IngredientMatcher.test(ingredient, stack)) continue;
                if (!InventoryProtectionPolicy.mayUseFromBackpack(stack, ingredient)) continue;
                long available = stack.getCount()
                        - pendingInv.getOrDefault(CraftingResolver.StackKey.of(stack, true), 0L);
                if (available <= 0) continue;
                if (pass1Sufficient && stack.hasTag()) continue;
                int contrib = (int) Math.min(remaining, available);
                pendingInv.merge(CraftingResolver.StackKey.of(stack, true), (long) contrib, ExtractionLedger::saturatedAdd);
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
            LOGGER.debug("[RSI-Ledger] Backpack inventory lookup failed", e);
        }
        return null;
    }

    /** Collect consumable backpack inventories from the main inventory and Curios. */
    static List<IItemHandler> findAllBackpackInventories(ServerPlayer player) {
        List<IItemHandler> result = new ArrayList<>();
        if (ModList.get() == null
                || !ModList.get().isLoaded(ModIds.SOPHISTICATED_BACKPACKS)) return result;

        // Main inventory
        for (ItemStack stack : player.getInventory().items) {
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
                    LivingEntity.class).invoke(null, player);
            if (result == null) return;
            Object handler;
            try {
                Object opt = result.getClass().getMethod("resolve").invoke(result);
                if (opt instanceof Optional<?> o) {
                    handler = o.orElse(null);
                } else {
                    return;
                }
            } catch (NoSuchMethodException e) {
                return;
            }
            if (handler == null) return;
            Object curios = handler.getClass().getMethod("getCurios").invoke(handler);
            Map<String, ?> curiosMap = (Map<String, ?>) curios;
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
            LOGGER.warn("[RSI-Ledger] Curios backpack scan failed", e);
        }
    }

    /** Extract up to {@code limit} items from backpack internals for the given entry. */
    private static ItemStack extractFromBackpackSlots(Entry entry, ServerPlayer player, int limit) {
        ItemStack collected = ItemStack.EMPTY;
        int remaining = Math.min(limit, entry.count);
        for (IItemHandler bp : findAllBackpackInventories(player)) {
            for (int s = 0; s < bp.getSlots() && remaining > 0; s++) {
                ItemStack inSlot = bp.getStackInSlot(s);
                if (inSlot.isEmpty() || !IngredientMatcher.test(entry.originalIngredient, inSlot)
                        || !InventoryProtectionPolicy.mayUseFromBackpack(inSlot, entry.originalIngredient)
                        || !MaterialMatcher.sameRuntimeFragment(entry.template, inSlot)) continue;
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
            ResourceLocation rl = BuiltInRegistries.ITEM.getKey(e.template.getItem());
            String itemName = rl != null ? rl.toString()
                    : ItemStackUtils.registryId(e.template);
            out.add(String.format("#%d %s: %s x%d (source=%s)",
                    e.id, e.source, itemName, e.count,
                    switch (e.source) {
                        case NETWORK -> "network";
                        case PLAYER_INVENTORY -> "inventory";
                        case ALTAR_BINDING -> "altar_binding";
                        case RESONANCE_DISK -> "resonance_disk";
                    }));
        }
        return out;
    }

    /**
     * Refund all committed entries by re-inserting their actual fragments back to
     * the original source (network, altar binding, or player inventory).
     * Best-effort: individual failures are logged but do not abort the loop.
     */
    /** 兼容旧调用签名的端点退款入口。 */
    public void refundCommitted(@Nullable CraftStorageEndpoint endpoint,
                                @Nullable ServerPlayer player) {
        refundCommittedToEndpoint(endpoint, player);
    }

    public void refundCommittedToEndpoint(@Nullable CraftStorageEndpoint endpoint,
                                          @Nullable ServerPlayer player) {
        if (state != State.COMMITTED) return;
        for (Entry e : entries) refundEntry(e, endpoint, player);
        transition(State.ROLLED_BACK);
    }

    private static long saturatedAdd(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }

    private void refundEntry(Entry e, @Nullable CraftStorageEndpoint endpoint,
                             @Nullable ServerPlayer player) {
        if (e.extracted.isEmpty()) {
            LOGGER.error("[RSI-Ledger] Committed entry {} has no recorded extracted fragment", e.id);
            return;
        }
        List<ItemStack> fragments = e.extracted;
        e.extracted = List.of();
        for (ItemStack fragment : fragments) {
            try {
                refundFragment(e, fragment.copy(), endpoint, player);
            } catch (RuntimeException failure) {
                logUncertainRefund(e.id, fragment, failure.getClass().getSimpleName());
            }
        }
    }

    private void refundFragment(Entry e, ItemStack refund, @Nullable CraftStorageEndpoint endpoint,
                                @Nullable ServerPlayer player) {
        CraftStorageEndpoint destination = e.sourceEndpoint != null
                ? e.sourceEndpoint : endpoint != null ? endpoint : storageEndpoint;
        if (InkFluidSupport.isToken(refund)) {
            ItemStack leftover = refund;
            if (destination != null) {
                StorageOperationResult result = player != null
                        ? destination.insert(player, refund, false)
                        : destination.insert(refund, false);
                leftover = refundRemainder(result, e.id, refund);
            }
            refundLeftoverToPlayerOrStorage(leftover, player, destination);
            return;
        }
        switch (e.source) {
            case NETWORK, ALTAR_BINDING -> {
                if (destination != null && player != null) {
                    var result = destination.insert(player, refund, false);
                    ItemStack leftover = refundRemainder(result, e.id, refund);
                    if (!leftover.isEmpty()) {
                        LOGGER.warn("[RSI-Ledger] Endpoint refund had leftover for {} x{}",
                                ItemStackUtils.registryId(leftover), leftover.getCount());
                        refundLeftoverToPlayerOrStorage(leftover, player, destination);
                    }
                    return;
                }
                refundLeftoverToPlayerOrStorage(refund, player, destination);
            }
            case RESONANCE_DISK -> {
                if (e.resonanceView == null || player == null) {
                    refundLeftoverToPlayerOrStorage(refund, player, destination);
                    return;
                }
                ItemStack leftover = e.resonanceView.insertView(
                        e.resonanceSlot, refund, refund.getCount(), false);
                e.resonanceView.markDirty(player);
                if (!leftover.isEmpty()) {
                    refundLeftoverToPlayerOrStorage(leftover, player, destination);
                }
            }
            case PLAYER_INVENTORY -> refundLeftoverToPlayerOrStorage(
                    refund, player, destination);
        }
    }

    /**
     * Give {@code stack} back to the player if online; otherwise (offline abort,
     * player == null) fall back to inserting into the network. If the network is
     * full or missing, DROP the item as a physical ItemEntity at the network's
     * controller position so the material is never silently destroyed.
     */
    private void refundLeftoverToPlayerOrStorage(ItemStack stack, @Nullable ServerPlayer player,
                                                 @Nullable CraftStorageEndpoint endpoint) {
        if (stack.isEmpty()) return;
        if (InkFluidSupport.isToken(stack)) {
            MinecraftServer server = player != null ? player.server : refundServer;
            UUID owner = player != null ? player.getUUID() : refundOwner;
            if (server == null || owner == null) {
                throw new IllegalStateException("流体退款缺少玩家或服务器上下文");
            }
            CraftFluidRecovery.retainToEndpoint(server, owner, player, stack, endpoint);
            return;
        }
        if (player != null) {
            PlayerUtils.safeGiveToStorage(player, stack, endpoint);
            return;
        }

        ItemStack leftover = stack;
        if (endpoint != null) {
            leftover = endpoint.insert(stack, false)
                    .remainder().orElse(ItemStack.EMPTY);
            if (leftover.isEmpty()) return;
        }

        MinecraftServer recoveryServer = refundServer;
        UUID recoveryOwner = refundOwner;
        if (recoveryServer != null && recoveryOwner != null) {
            try {
                CraftItemRecovery.get(recoveryServer).retain(recoveryOwner, leftover);
                LOGGER.warn("[RSI-Ledger] Player offline; retained {} x{} for later claim",
                        ItemStackUtils.registryId(leftover), leftover.getCount());
                return;
            } catch (RuntimeException failure) {
                LOGGER.error("[RSI-Ledger] Could not persist offline item refund; "
                        + "falling back to a world drop", failure);
            }
        }

        // Player offline AND (network full or absent) — never discard. Drop a
        // physical ItemEntity at the network controller so nothing is lost.
        if (endpoint != null) {
            var location = endpoint.location().orElse(null);
            if (location != null) {
                Level level = location.level(
                        refundServer).orElse(null);
                BlockPos pos = location.position();
                if (level instanceof ServerLevel serverLevel) {
                ItemEntity drop = new ItemEntity(serverLevel,
                        pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, leftover.copy());
                drop.setDeltaMovement(0, 0.2, 0);
                serverLevel.addFreshEntity(drop);
                LOGGER.warn("[RSI-Ledger] Player offline & storage full — dropped refund at {} {}: {} x{}",
                        level.dimension().location(), pos,
                        ItemStackUtils.registryId(leftover), leftover.getCount());
                return;
                }
            }
        }

        LOGGER.error("[RSI-Ledger] CRITICAL: could not refund/drop item (no storage location) — LOST: {} x{}",
                ItemStackUtils.registryId(leftover), leftover.getCount());
    }

    public String describePending() {
        StringBuilder sb = new StringBuilder();
        for (var e : pendingNet.entrySet()) {
            if (e.getValue() > 0) {
                ResourceLocation rl =
                        BuiltInRegistries.ITEM.getKey(e.getKey().item());
                if (rl != null) sb.append("net:").append(rl).append("=").append(e.getValue()).append(" ");
            }
        }
        for (var e : pendingInv.entrySet()) {
            if (e.getValue() > 0) {
                ResourceLocation rl =
                        BuiltInRegistries.ITEM.getKey(e.getKey().item());
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
        entriesById.remove(last.id);
        decrementPending(last);
        cancelSettlementEntry(last);
    }

    private void cancelSettlementEntry(Entry entry) {
        StorageSettlementLedger.EntryId id = settlementEntries.remove(entry.id);
        if (id == null) return;
        try {
            settlementLedger.cancelReservation(id);
        } catch (RuntimeException failure) {
            resetSettlementMirror();
            LOGGER.debug("[RSI-Ledger] cancellation mirror reset for entry {}", entry.id, failure);
        }
    }

    /** Undo every uncommitted reservation created after the supplied mark. */
    public void cancelReservationsSince(int mark) {
        requireState(State.IDLE, State.RESERVING);
        if (mark < 0 || mark > entries.size()) {
            throw new IllegalArgumentException("reservation mark out of range: " + mark);
        }
        while (entries.size() > mark) cancelLastReservation();
    }

    public void releaseReservations(List<ItemStack> stacks) {
        removeMatchingEntries(stacks, true);
    }

    /**
     * Remove committed entries that have already been returned independently.
     * Used by partial parallel start failure so a later group abort cannot refund
     * the same material slice twice.
     */
    public void releaseCommittedEntries(List<ItemStack> stacks) {
        requireState(State.COMMITTED);
        List<ItemStack> committed = entries.stream().flatMap(entry -> entry.extracted.stream()).toList();
        List<ItemStack> reversed = new ArrayList<>(committed);
        Collections.reverse(reversed);
        List<ItemStack> released = new ArrayList<>(matchRecoveredRefunds(reversed, stacks));
        Collections.reverse(released);
        List<ItemStack> retained = new ArrayList<>(committed.size());
        for (int i = 0; i < committed.size(); i++) {
            retained.add(committed.get(i).copyWithCount(
                    committed.get(i).getCount() - released.get(i).getCount()));
        }
        replaceCommittedRefunds(retained);
    }

    /**
     * Keep only quantities that failure cleanup physically recovered. Missing
     * or consumed fragments are settled instead of recreated during refund.
     */
    public void retainCommittedRefunds(List<ItemStack> recovered) {
        requireState(State.COMMITTED);
        List<ItemStack> committed = entries.stream()
                .flatMap(entry -> entry.extracted.stream())
                .toList();
        List<ItemStack> refundable = matchRecoveredRefunds(committed, recovered);
        replaceCommittedRefunds(refundable);
    }

    private void replaceCommittedRefunds(List<ItemStack> refundable) {
        List<Entry> removed = new ArrayList<>();
        int offset = 0;
        for (Entry entry : entries) {
            int end = offset + entry.extracted.size();
            entry.confirmExtracted(refundable.subList(offset, end).stream()
                    .filter(stack -> !stack.isEmpty()).toList());
            offset = end;
            if (entry.extracted.isEmpty()) {
                removed.add(entry);
            }
        }
        entries.removeAll(removed);
        for (Entry entry : removed) {
            entriesById.remove(entry.id);
            settlementEntries.remove(entry.id);
        }
        resetSettlementMirror();
    }

    static List<ItemStack> matchRecoveredRefunds(List<ItemStack> committed,
                                                 List<ItemStack> recovered) {
        List<ItemStack> pool = new ArrayList<>();
        if (recovered != null) {
            for (ItemStack stack : recovered) {
                if (stack != null && !stack.isEmpty()) pool.add(stack.copy());
            }
        }
        List<ItemStack> refundable = new ArrayList<>(committed.size());
        for (ItemStack expected : committed) {
            int remaining = expected == null ? 0 : expected.getCount();
            int matched = 0;
            for (ItemStack stack : pool) {
                if (remaining <= 0) break;
                if (stack.isEmpty() || expected == null
                        || !MaterialMatcher.sameRuntimeFragment(expected, stack)) continue;
                int take = Math.min(remaining, stack.getCount());
                stack.shrink(take);
                remaining -= take;
                matched += take;
            }
            refundable.add(expected == null || matched <= 0
                    ? ItemStack.EMPTY : expected.copyWithCount(matched));
        }
        return List.copyOf(refundable);
    }

    private void removeMatchingEntries(List<ItemStack> stacks, boolean updatePending) {
        // Collect entry ids to remove upfront so we match by identity, not by
        // (item, count) which can collide across entries.
        Set<Integer> idsToRemove = new HashSet<>();
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) continue;
            // Find the matching entry by (template item, count) and record its id.
            // Search in reverse so we match the most recently added entry first.
            for (int i = entries.size() - 1; i >= 0; i--) {
                Entry e = entries.get(i);
                if (!idsToRemove.contains(e.id)
                        && MaterialMatcher.sameRuntimeFragment(e.template, stack)
                        && e.count == stack.getCount()) {
                    if (updatePending) decrementPending(e);
                    idsToRemove.add(e.id);
                    break;
                }
            }
        }
        // Remove matched entries by id (in reverse to avoid index shifting issues).
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (idsToRemove.contains(entries.get(i).id)) {
                Entry removed = entries.remove(i);
                entriesById.remove(removed.id);
                if (updatePending) cancelSettlementEntry(removed);
            }
        }
    }

    private List<ItemStack> endpointEntries(@Nullable CraftStorageEndpoint endpoint,
                                            @Nullable ServerPlayer player) {
        List<ItemStack> cached = endpointEntryCache.get(endpoint);
        if (cached != null) return cached;

        List<ItemStack> loaded;
        CraftStorageEndpoint selected = endpoint != null ? endpoint : storageEndpoint;
        if (selected != null && player != null) {
            loaded = selected.snapshot(player).snapshot()
                    .map(snapshot -> snapshot.items().stream().map(item -> {
                        ItemStack stack = item.stack();
                        stack.setCount((int) Math.min(Integer.MAX_VALUE, item.amount()));
                        return stack;
                    }).toList())
                    .orElseGet(List::of);
        } else {
            loaded = List.of();
        }
        endpointEntryCache.put(selected, loaded);
        return loaded;
    }

    private void decrementPending(Entry entry) {
        if (entry.source == Source.RESONANCE_DISK && entry.resonanceKey != null) {
            pendingResonance.computeIfPresent(entry.resonanceKey, (ignored, reserved) -> {
                long remaining = reserved - entry.count;
                return remaining <= 0 ? null : remaining;
            });
            return;
        }
        if (entry.source == Source.NETWORK && !entry.pendingNetworkAllocation.isEmpty()) {
            for (var allocation : entry.pendingNetworkAllocation.entrySet()) {
                pendingNet.computeIfPresent(allocation.getKey(), (ignored, reserved) -> {
                    long remaining = reserved - allocation.getValue();
                    return remaining <= 0 ? null : remaining;
                });
            }
            return;
        }
        CraftingResolver.StackKey key = CraftingResolver.StackKey.of(entry.template, true);
        Map<CraftingResolver.StackKey, Long> pending =
                entry.source == Source.PLAYER_INVENTORY ? pendingInv : pendingNet;
        pending.computeIfPresent(key, (ignored, reserved) -> {
            long remaining = reserved - entry.count;
            return remaining <= 0 ? null : remaining;
        });
    }
}
