package com.huanghuang.rsintegration.feature.autoeat;

import com.huanghuang.rsintegration.api.RSIAutoEatFoodSources.Context;
import com.huanghuang.rsintegration.api.RSIAutoEatFoodSources.Source;
import com.huanghuang.rsintegration.api.RSIAutoEatFoodSources.TakeResult;
import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
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

/** 记录单个玩家在当前服务端刻内的候选食物与结算状态。 */
public final class AutoEatFoodBatch {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<ResourceLocation, Source> SOURCES = new TreeMap<>();
    private static final String NETWORK_FAILURE = "rsi.autoeat.error.network_unavailable";
    private final Map<CompoundTag, Candidate> candidates = new LinkedHashMap<>();
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
        private final CompoundTag identity;
        private final Map<ResourceLocation, Offer> offers = new LinkedHashMap<>();

        private Candidate(CompoundTag identity, ItemStack template) {
            this.identity = identity;
            this.template = template;
        }
    }

    private record Offer(Source source, ItemStack template) {}

    AutoEatFoodBatch(AutoEatStorage storage, Context context, ItemStack cost) {
        this.storage = storage;
        this.context = context;
        this.cost = cost;

        for (var stored : storage.items()) {
            ItemStack stack = stored.stack();
            if (wanted(stack)) addCandidate(stack);
        }
        for (var entry : sources()) {
            try {
                for (ItemStack stack : entry.getValue().candidates(context)) {
                    if (stack == null || stack.getCount() != 1 || !wanted(stack)) continue;
                    ItemStack template = stack.copy();
                    Candidate candidate = addCandidate(template);
                    candidate.offers.putIfAbsent(entry.getKey(), new Offer(entry.getValue(), template));
                }
            } catch (RuntimeException | LinkageError e) {
                LOGGER.warn("[RSI-AutoEat] Could not enumerate source {}", entry.getKey(), e);
            }
        }
    }

    private Candidate addCandidate(ItemStack template) {
        return candidates.computeIfAbsent(exactIdentity(template), identity -> new Candidate(identity, template.copy()));
    }

    private static CompoundTag exactIdentity(ItemStack singleItem) {
        return singleItem.save(new CompoundTag());
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

    /** 返回已结算的一份食物；没有可用食物时返回空栈，记录失败时结束整个请求。 */
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
                // 网络的匹配规则可能比外部报价宽松；取到不同变体并不等于确认缺货。
                if (!candidate.offers.isEmpty() && !candidate.identity.equals(exactIdentity(food))) {
                    return ItemStack.EMPTY;
                }
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
                        && candidate.identity.equals(exactIdentity(taken.stack()))) {
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
