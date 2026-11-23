package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.api.RSICraftingMaterialSources.*;
import com.huanghuang.rsintegration.api.RSIPlanExtensions;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Ingredient;

import javax.annotation.Nullable;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** Converts live extension snapshots into planner values and manages their shared budgets. */
public final class RegisteredMaterialSources {
    public static final String QUOTE_CHANGED = "rsi.plan.failure.material_quote_changed";
    private static final Map<ResourceLocation, Registration> SOURCES = new TreeMap<>();
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

    private RegisteredMaterialSources() {}

    private record Registration(RSIPlanExtensions.Source toggle, Source source) {}
    record Pool(ResourceLocation source, ResourceLocation id) {}
    record Stock(ResourceLocation source, StackKey key, Pool pool, BigInteger cost, long limit) {}
    public record Share(ResourceLocation source, StackKey key, long count) {}

    public static synchronized boolean register(ResourceLocation id, RSIPlanExtensions.Source toggle, Source source) {
        return SOURCES.putIfAbsent(Objects.requireNonNull(id),
                new Registration(Objects.requireNonNull(toggle), Objects.requireNonNull(source))) == null;
    }

    public static synchronized boolean hasEnabled(MaterialSourceSelection selection) {
        var enabled = switches(selection);
        return SOURCES.values().stream().anyMatch(registration -> enabled.contains(registration.toggle()));
    }

    private static synchronized List<Map.Entry<ResourceLocation, Registration>> registrations() {
        return SOURCES.entrySet().stream().map(entry -> Map.entry(entry.getKey(), entry.getValue())).toList();
    }

    private static EnumSet<RSIPlanExtensions.Source> switches(MaterialSourceSelection selection) {
        var enabled = EnumSet.noneOf(RSIPlanExtensions.Source.class);
        if (selection.inventory()) enabled.add(RSIPlanExtensions.Source.INVENTORY);
        if (selection.storageNetwork()) enabled.add(RSIPlanExtensions.Source.STORAGE_NETWORK);
        if (selection.backpacks()) enabled.add(RSIPlanExtensions.Source.BACKPACKS);
        return enabled;
    }

    public static Context context(ServerPlayer player, @Nullable CraftStorageEndpoint endpoint,
                                  MaterialSourceSelection selection, Set<Item> types, Phase phase,
                                  @Nullable Plan plan, @Nullable UUID taskId) {
        var network = Optional.ofNullable(endpoint).map(value -> value.session().reference())
                .map(reference -> new RSIPlanExtensions.Network(reference.backendId().value(), reference.networkId()));
        return new Context(player, network, switches(selection), types, phase,
                Optional.ofNullable(plan), Optional.ofNullable(taskId));
    }

    public static StockSnapshot capture(Context context) {
        if (context.player().getServer() == null || !context.player().getServer().isSameThread()) {
            return StockSnapshot.EMPTY;
        }
        Map<Pool, BigInteger> balances = new HashMap<>();
        Map<StackKey, List<Stock>> offers = new LinkedHashMap<>();
        for (var entry : registrations()) {
            if (!context.enabledSources().contains(entry.getValue().toggle())) continue;
            try {
                Snapshot snapshot = entry.getValue().source().snapshot(context);
                ResourceLocation id = entry.getKey();
                Map<StackKey, Stock> unique = new LinkedHashMap<>();
                for (Offer offer : snapshot.offers()) {
                    StackKey key = StackKey.of(offer.target(), true);
                    if (!context.itemTypes().isEmpty() && !context.itemTypes().contains(key.item())) continue;
                    if (offer.unitCost().signum() > 0
                            && snapshot.balances().get(offer.pool()).compareTo(offer.unitCost()) < 0) continue;
                    var stock = new Stock(id, key, new Pool(id, offer.pool()), offer.unitCost(), offer.limit());
                    if (unique.putIfAbsent(key, stock) != null) {
                        throw new IllegalArgumentException("duplicate variant in material source " + id);
                    }
                }
                unique.forEach((key, stock) -> {
                    if (stock.cost().signum() > 0) {
                        balances.put(stock.pool(), snapshot.balances().get(stock.pool().id()));
                    }
                    offers.computeIfAbsent(key, ignored -> new ArrayList<>()).add(stock);
                });
            } catch (RuntimeException | LinkageError failure) {
                RSIntegrationMod.LOGGER.warn("[RSI-Materials] Source {} snapshot failed", entry.getKey(), failure);
            }
        }
        return new StockSnapshot(balances, offers);
    }

    public static StockSnapshot capture(ServerPlayer player, @Nullable CraftStorageEndpoint endpoint,
                                        MaterialSourceSelection selection, Set<Item> types, Phase phase,
                                        @Nullable Plan plan, @Nullable UUID taskId) {
        if (!hasEnabled(selection)) return StockSnapshot.EMPTY;
        return capture(context(player, endpoint, selection, types, phase, plan, taskId));
    }

    static Optional<Reservation> reserve(ResourceLocation id, Context context, Allocation requested,
                                         List<Allocation> earlier) {
        Registration registration;
        synchronized (RegisteredMaterialSources.class) {
            registration = SOURCES.get(id);
        }
        if (registration == null || !context.enabledSources().contains(registration.toggle())) return Optional.empty();
        try {
            return Objects.requireNonNull(registration.source().reserve(context, requested, List.copyOf(earlier)));
        } catch (RuntimeException | LinkageError failure) {
            RSIntegrationMod.LOGGER.warn("[RSI-Materials] Source {} reservation failed", id, failure);
            return Optional.empty();
        }
    }

    /** Contains IDs, item identities and numeric limits only. Safe to pass to planning workers. */
    public record StockSnapshot(Map<Pool, BigInteger> balances, Map<StackKey, List<Stock>> offers) {
        public static final StockSnapshot EMPTY = new StockSnapshot(Map.of(), Map.of());

        public StockSnapshot {
            balances = Map.copyOf(balances);
            Map<StackKey, List<Stock>> copy = new LinkedHashMap<>();
            offers.forEach((key, values) -> copy.put(key, List.copyOf(values)));
            offers = java.util.Collections.unmodifiableMap(copy);
        }

        public boolean isEmpty() { return offers.isEmpty(); }
        public Set<StackKey> variants() { return offers.keySet(); }
        public Budget budget() { return new Budget(this); }

        public boolean canSupplement(CraftPlanGraph plan) {
            return !isEmpty() && (plan.unresolvedDemands().stream().anyMatch(demand -> matches(demand.ingredient()))
                    || plan.rootDemands().stream().anyMatch(root -> root.unresolvedQuantity() > 0 && matches(root.ingredient())));
        }

        boolean matches(Ingredient ingredient) {
            return variants().stream().anyMatch(key -> IngredientMatcher.test(ingredient, key));
        }

        /** Revalidation uses a single budget for the complete external demand. */
        public boolean covers(Map<StackKey, Long> required, Map<StackKey, Long> physical) {
            Budget budget = budget();
            for (var entry : required.entrySet()) {
                long shortfall = entry.getValue() - physical.getOrDefault(entry.getKey(), 0L);
                if (shortfall > 0 && budget.take(entry.getKey(), shortfall).stream().mapToLong(Share::count).sum() != shortfall) {
                    return false;
                }
            }
            return true;
        }

        /** A preview can retain its quoted shares while its exact shortfalls and claims still agree. */
        public boolean matchesAllocation(Map<StackKey, Long> required, Map<StackKey, Long> physical,
                                         List<Share> planned) {
            Budget budget = budget();
            Map<StackKey, Long> allocated = new HashMap<>();
            for (Share share : planned) {
                if (!budget.retain(share)) return false;
                allocated.merge(share.key(), share.count(), MaterialSources::saturatedAdd);
            }
            for (var entry : required.entrySet()) {
                long shortfall = Math.max(0, entry.getValue() - physical.getOrDefault(entry.getKey(), 0L));
                if (shortfall != allocated.getOrDefault(entry.getKey(), 0L)) return false;
            }
            return required.keySet().containsAll(allocated.keySet());
        }
    }

    public static final class Budget {
        private record Debit(Stock stock, long count) {}
        private final StockSnapshot snapshot;
        private final Map<Pool, BigInteger> balances;
        private final Map<Stock, Long> used = new HashMap<>();
        private final List<Debit> debits = new ArrayList<>();

        private Budget(StockSnapshot snapshot) {
            this.snapshot = snapshot;
            this.balances = new HashMap<>(snapshot.balances());
        }

        public int mark() { return debits.size(); }

        public void rollback(int mark) {
            while (debits.size() > mark) {
                Debit debit = debits.remove(debits.size() - 1);
                used.merge(debit.stock(), -debit.count(), Long::sum);
                adjustBalance(debit.stock(), debit.count());
            }
        }

        public List<Share> take(StackKey key, long needed) {
            List<Share> shares = new ArrayList<>();
            for (Stock stock : snapshot.offers().getOrDefault(key, List.of())) {
                long take = Math.min(needed, available(stock));
                if (take <= 0) continue;
                debit(stock, take);
                shares.add(new Share(stock.source(), key, take));
                needed -= take;
                if (needed == 0) break;
            }
            return shares;
        }

        public boolean retain(Share share) {
            for (Stock stock : snapshot.offers().getOrDefault(share.key(), List.of())) {
                if (!stock.source().equals(share.source()) || available(stock) < share.count()) continue;
                debit(stock, share.count());
                return true;
            }
            return false;
        }

        private void debit(Stock stock, long count) {
            used.merge(stock, count, Long::sum);
            adjustBalance(stock, -count);
            debits.add(new Debit(stock, count));
        }

        private long available(Stock stock) {
            long limit = stock.limit() - used.getOrDefault(stock, 0L);
            return stock.cost().signum() == 0 ? limit
                    : Math.min(limit, balances.get(stock.pool()).divide(stock.cost()).min(LONG_MAX).longValue());
        }

        private void adjustBalance(Stock stock, long count) {
            if (stock.cost().signum() == 0) return;
            balances.merge(stock.pool(), stock.cost().multiply(BigInteger.valueOf(count)), BigInteger::add);
        }
    }
}
