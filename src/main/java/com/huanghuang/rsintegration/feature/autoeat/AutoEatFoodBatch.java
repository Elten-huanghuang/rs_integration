package com.huanghuang.rsintegration.feature.autoeat;

import com.huanghuang.rsintegration.api.RSIAutoEatFoodSources.Context;
import com.huanghuang.rsintegration.api.RSIAutoEatFoodSources.Source;
import com.huanghuang.rsintegration.api.RSIAutoEatFoodSources.TakeResult;
import com.huanghuang.rsintegration.storage.StorageItemKey;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Predicate;

/** Quotes and settlement state for one player's current server tick. */
public final class AutoEatFoodBatch {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<ResourceLocation, Source> SOURCES = new TreeMap<>();
    private static final String NETWORK_FAILURE = "rsi.autoeat.error.network_unavailable";
    private final Map<StorageItemKey, Candidate> candidates = new LinkedHashMap<>();
    private final AutoEatStorage storage;
    private final Context context;
    private final ItemStack cost;
    private String failure;

    public static synchronized boolean register(ResourceLocation id, Source source) {
        return SOURCES.putIfAbsent(Objects.requireNonNull(id), Objects.requireNonNull(source)) == null;
    }

    private static synchronized List<Map.Entry<ResourceLocation, Source>> sources() {
        return SOURCES.entrySet().stream().map(Map.Entry::copyOf).toList();
    }

    static final class Candidate {
        final ItemStack template;
        private final StorageItemKey key;
        private final Map<ResourceLocation, Offer> offers = new LinkedHashMap<>();

        private Candidate(StorageItemKey key, ItemStack template) {
            this.key = key;
            this.template = template.copyWithCount(1);
        }
    }

    private record Offer(Source source, ItemStack template) {}

    AutoEatFoodBatch(AutoEatStorage storage, Context context, ItemStack cost) {
        this.storage = storage;
        this.context = context;
        this.cost = cost;

        for (var stored : storage.items()) {
            ItemStack stack = stored.stack();
            if (wanted(stack)) candidates.computeIfAbsent(stored.key(), key -> new Candidate(key, stack));
        }
        for (var entry : sources()) {
            try {
                for (ItemStack stack : entry.getValue().candidates(context)) {
                    if (stack == null || stack.getCount() != 1 || !wanted(stack)) continue;
                    Candidate candidate = candidates.computeIfAbsent(storage.key(stack), key -> new Candidate(key, stack));
                    candidate.offers.putIfAbsent(entry.getKey(), new Offer(entry.getValue(), stack.copy()));
                }
            } catch (RuntimeException | LinkageError e) {
                LOGGER.warn("[RSI-AutoEat] Could not enumerate source {}", entry.getKey(), e);
            }
        }
    }

    private boolean wanted(ItemStack stack) {
        return stack.getItem().isEdible()
                && (context.wantedItems().isEmpty() || context.wantedItems().contains(stack.getItem()));
    }

    Collection<Candidate> candidates() {
        return candidates.values();
    }

    String failure() {
        return failure;
    }

    /** Returns a paid serving, or empty. A recorded failure ends the entire request. */
    ItemStack take(Candidate candidate, Predicate<ItemStack> canEat) {
        ServerPlayer player = context.player();
        if (failure != null || !checkAccess()) return ItemStack.EMPTY;
        var nativeFood = storage.extract(player, candidate.template, 1);
        ItemStack food = nativeFood.stack();
        ItemStack fee = ItemStack.EMPTY;
        boolean committed = false;
        try {
            if (nativeFood.failed()) {
                failure = NETWORK_FAILURE;
                return ItemStack.EMPTY;
            }
            if (food.isEmpty()) {
                if (!nativeFood.confirmedMissing() || candidate.offers.isEmpty()) return ItemStack.EMPTY;
                fee = collectFee();
                if (failure != null) return ItemStack.EMPTY;
                food = takeExternal(candidate);
                if (food.isEmpty() || !checkAccess() || !canEat.test(food)) return ItemStack.EMPTY;
            } else {
                if (!canEat.test(food)) return ItemStack.EMPTY;
                fee = collectFee();
                if (failure != null) return ItemStack.EMPTY;
            }
            committed = true;
            return food;
        } finally {
            if (!committed) {
                try {
                    storage.returnStack(player, food);
                } finally {
                    storage.returnStack(player, fee);
                }
            }
        }
    }

    private boolean checkAccess() {
        if (storage.accessible(context.player())) return true;
        failure = NETWORK_FAILURE;
        return false;
    }

    private ItemStack collectFee() {
        if (cost.isEmpty()) return ItemStack.EMPTY;
        var payment = storage.extract(context.player(), cost, cost.getCount());
        if (payment.failed()) failure = NETWORK_FAILURE;
        else if (payment.stack().getCount() != cost.getCount()) failure = "rsi.autoeat.missing_cost";
        return payment.stack();
    }

    private ItemStack takeExternal(Candidate candidate) {
        var offers = candidate.offers.entrySet().iterator();
        while (offers.hasNext()) {
            if (!checkAccess()) return ItemStack.EMPTY;
            var entry = offers.next();
            Offer offer = entry.getValue();
            try {
                var result = offer.source().takeOne(context, offer.template().copy());
                if (result instanceof TakeResult.Unavailable) {
                    offers.remove();
                    continue;
                }
                if (result instanceof TakeResult.Taken taken && taken.stack() != null
                        && taken.stack().getCount() == 1
                        && candidate.key.equals(storage.key(taken.stack()))) {
                    return taken.stack();
                }
                LOGGER.error("[RSI-AutoEat] Indeterminate or invalid delivery from {}", entry.getKey());
            } catch (RuntimeException | LinkageError error) {
                LOGGER.error("[RSI-AutoEat] Delivery from {} failed", entry.getKey(), error);
            }
            failure = "rsi.autoeat.error.food_source";
            return ItemStack.EMPTY;
        }
        return ItemStack.EMPTY;
    }
}
