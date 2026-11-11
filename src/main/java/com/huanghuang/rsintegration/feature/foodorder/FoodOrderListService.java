package com.huanghuang.rsintegration.feature.foodorder;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.api.consumer.RSICraftConsumerApi;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.CraftingResolver;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.MaterialSources;
import com.huanghuang.rsintegration.crafting.RecipeIndex;
import com.huanghuang.rsintegration.crafting.availability.MaterialAvailability;
import com.huanghuang.rsintegration.crafting.batch.CraftExecutionPolicy;
import com.huanghuang.rsintegration.crafting.batch.RecipeAvailabilityChecker;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoints;
import com.huanghuang.rsintegration.feature.foodorder.network.FoodOrderListPacket;
import com.huanghuang.rsintegration.mods.historystages.HistoryStagesCompat;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import com.huanghuang.rsintegration.storage.StorageRestockSupport;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Recipe;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 点餐列表构建器。
 *
 * <p>完整目录始终参与搜索；可用性判定按预算跨 tick 继续，刷新不会重置未完成的进度。
 * 每轮构建只读取一次存储快照与配方索引，每种食物最多尝试有限个配方。</p>
 */
public final class FoodOrderListService {

    private static final long REQUEST_COOLDOWN_TICKS = 10L;
    private static final long UPDATE_INTERVAL_TICKS = 10L;

    private static final Map<UUID, FoodOrderSnapshot> CACHE = new HashMap<>();
    private static final Map<UUID, Long> LAST_REQUEST = new HashMap<>();
    private static final Map<UUID, BuildState> BUILDS = new HashMap<>();
    private static final Map<UUID, Long> LAST_SENT = new HashMap<>();
    /** 待构建队列，每 tick 最多取一个玩家，避免多人同时刷新叠加。 */
    private static final Map<UUID, Long> PENDING = new LinkedHashMap<>();
    private static long lastBuildTick = Long.MIN_VALUE;

    private FoodOrderListService() {}

    /** 客户端请求刷新；冷却期内的请求合并到队列，不能直接丢弃导致界面一直加载。 */
    public static void request(ServerPlayer player) {
        if (player == null || !FoodOrderService.holding(player)) {
            return;
        }
        long now = player.serverLevel().getGameTime();
        Long last = LAST_REQUEST.get(player.getUUID());
        if (last != null && now - last < REQUEST_COOLDOWN_TICKS) {
            PENDING.putIfAbsent(player.getUUID(), last + REQUEST_COOLDOWN_TICKS);
            LAST_SENT.remove(player.getUUID());
            return;
        }
        LAST_REQUEST.put(player.getUUID(), now);
        LAST_SENT.remove(player.getUUID());
        PENDING.putIfAbsent(player.getUUID(), now);
    }

    /** 立即请求一次列表；实际构建仍在 tick 队列中串行执行。 */
    public static void sendNow(ServerPlayer player) {
        if (player == null || !FoodOrderService.holding(player)) {
            return;
        }
        PENDING.put(player.getUUID(), player.serverLevel().getGameTime());
        LAST_SENT.remove(player.getUUID());
    }

    /** 每 tick 最多处理一个玩家的列表构建，把主线程成本摊开。 */
    public static void tick(MinecraftServer server) {
        if (PENDING.isEmpty()) {
            return;
        }
        long now = server.overworld().getGameTime();
        if (now <= lastBuildTick) {
            return;
        }
        var iterator = PENDING.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Long> entry = iterator.next();
            if (entry.getValue() > now) {
                continue;
            }
            iterator.remove();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null || player.hasDisconnected()
                    || !FoodOrderService.holding(player)) {
                BUILDS.remove(entry.getKey());
                LAST_SENT.remove(entry.getKey());
                continue;
            }
            lastBuildTick = now;
            try {
                FoodOrderSnapshot snapshot = snapshot(player, true);
                Long lastSent = LAST_SENT.get(player.getUUID());
                if (lastSent == null || now - lastSent >= UPDATE_INTERVAL_TICKS
                        || (!snapshot.indexing() && !snapshot.partial())) {
                    FoodOrderListPacket.send(player, snapshot);
                    LAST_SENT.put(player.getUUID(), now);
                }
            } catch (Throwable throwable) {
                BUILDS.remove(player.getUUID());
                PENDING.remove(player.getUUID());
                RSIntegrationMod.LOGGER.warn(
                        "[RSI-FoodOrder] 构建点餐列表异常，已跳过本次请求", throwable);
            }
            break;
        }
    }

    /**
     * 最近一次构建结果，保留至刷新或玩家状态失效。
     * 下单只从中选取候选配方，实际材料和机器由 Consumer API 重新预检；
     * 不能因浏览菜单超过一秒就丢弃列表，再把尚未完成的重建误判为无菜可点。
     */
    public static FoodOrderSnapshot cached(ServerPlayer player) {
        return CACHE.get(player.getUUID());
    }

    public static FoodOrderSnapshot snapshot(ServerPlayer player, boolean rebuild) {
        if (!rebuild) {
            FoodOrderSnapshot cached = cached(player);
            if (cached != null) {
                return cached;
            }
        }
        FoodOrderSnapshot snapshot;
        try {
            snapshot = build(player);
        } catch (Throwable throwable) {
            BUILDS.remove(player.getUUID());
            RSIntegrationMod.LOGGER.warn(
                    "[RSI-FoodOrder] 构建列表失败，返回空列表", throwable);
            snapshot = new FoodOrderSnapshot(player.serverLevel().getGameTime(),
                    List.of(), Component.translatable("rsi.food_order.error.failed"),
                    0, 0, false, false);
        }
        CACHE.put(player.getUUID(), snapshot);
        if (snapshot.indexing() || snapshot.partial()) {
            // 放到队尾，下一 tick 接着算；多人刷新时仍然轮流推进。
            PENDING.putIfAbsent(player.getUUID(), player.serverLevel().getGameTime());
        } else {
            BUILDS.remove(player.getUUID());
            PENDING.remove(player.getUUID());
        }
        return snapshot;
    }

    public static void invalidate() {
        CACHE.clear();
        LAST_REQUEST.clear();
        BUILDS.clear();
        LAST_SENT.clear();
        PENDING.clear();
        lastBuildTick = Long.MIN_VALUE;
        FoodOrderFoods.invalidate();
    }

    public static void clearPlayer(UUID playerId) {
        CACHE.remove(playerId);
        LAST_REQUEST.remove(playerId);
        BUILDS.remove(playerId);
        LAST_SENT.remove(playerId);
        PENDING.remove(playerId);
    }

    private static FoodOrderSnapshot build(ServerPlayer player) {
        long epoch = player.serverLevel().getGameTime();
        BuildState state = BUILDS.get(player.getUUID());
        if (state == null) {
            state = new BuildState(new FoodOrderListProgress(FoodOrderFoods.uneaten(player)),
                    sourcesComponent(null, apiVersion()));
            BUILDS.put(player.getUUID(), state);
        }

        if (!RecipeIndex.isReady(player.serverLevel())) {
            RecipeIndex.warmUp(player.serverLevel());
            return state.progress.snapshot(epoch, state.sources, true);
        }
        if (state.index == null) {
            try {
                state.index = RecipeIndex.get(player.serverLevel());
            } catch (RuntimeException | LinkageError ignored) {
                return state.progress.snapshot(epoch, state.sources, true);
            }
            CraftStorageEndpoint endpoint = resolveEndpoint(player);
            try {
                state.available = endpoint == null
                        ? MaterialSources.countInventory(player)
                        : MaterialSources.listAllAvailable(player, endpoint);
            } catch (RuntimeException | LinkageError ignored) {
                state.available = Map.of();
            }
            state.sources = sourcesComponent(endpoint, apiVersion());
        }
        int maxRecipes = Math.max(1, RSIntegrationConfig.FOOD_ORDER_MAX_RECIPES_PER_FOOD.get());
        long budgetNanos = Math.max(2_000L,
                RSIntegrationConfig.FOOD_ORDER_LIST_BUDGET_MICROS.get()) * 1_000L;
        BuildState current = state;
        state.progress.advance(System.nanoTime() + budgetNanos, (food, deadline) ->
                evaluateFood(player, food, current.index.get(food), current.available,
                        maxRecipes, deadline));
        return state.progress.snapshot(epoch, state.sources, false);
    }

    private static String apiVersion() {
        return RSICraftConsumerApi.get()
                .map(api -> {
                    var version = api.version();
                    return version.major() + "." + version.minor() + "." + version.patch();
                })
                .orElse("-");
    }

    private static CraftStorageEndpoint resolveEndpoint(ServerPlayer player) {
        CraftStorageEndpoint endpoint = null;
        try {
            endpoint = StorageRestockSupport.resolve(player).orElse(null);
            if (endpoint == null) {
                endpoint = CraftStorageEndpoints.resolveDefault(player).orElse(null);
            }
            // 没有取出权限的网络不能用来判断“可做”，否则列表会显示实际下不了单的条目。
            if (endpoint != null && !StorageRestockSupport.canExtract(endpoint, player)) {
                endpoint = null;
            }
        } catch (Throwable throwable) {
            // 存储后端异常不应让整份列表不可用，退化为只读背包。
            endpoint = null;
        }
        return endpoint;
    }

    private static FoodOrderEntry evaluateFood(ServerPlayer player, Item food,
                                               List<RecipeIndex.Entry> recipes,
                                               Map<CraftingResolver.StackKey, Long> available,
                                               int maxRecipes, long deadline) {
        FoodOrderKind kind = FoodOrderKind.NORECIPE;
        ResourceLocation chosenRecipe = null;
        List<IngredientSpec> missingSpecs = List.of();
        if (recipes != null && !recipes.isEmpty()) {
            kind = FoodOrderKind.WAITING;
            int attempted = 0;
            for (RecipeIndex.Entry entry : recipes) {
                if (attempted++ >= maxRecipes) {
                    break;
                }
                Evaluation evaluation = evaluate(player, entry, available);
                FoodOrderKind evaluated = evaluation.kind();
                if (evaluated == FoodOrderKind.READY) {
                    kind = evaluated;
                    Recipe<?> recipe = entry.recipe();
                    chosenRecipe = recipe == null ? null : recipe.getId();
                    if (chosenRecipe == null) {
                        kind = FoodOrderKind.WAITING;
                    }
                    break;
                }
                if (evaluated.rank() < kind.rank()) {
                    kind = evaluated;
                    chosenRecipe = entry.recipe() == null ? null : entry.recipe().getId();
                    missingSpecs = evaluation.specs();
                }
            }
        }
        Component detail = Component.translatable(kind.detailKey());
        if (kind == FoodOrderKind.MISSING && !missingSpecs.isEmpty()) {
            try {
                detail = FoodOrderMaterials.describe(missingSpecs, available, deadline);
            } catch (RuntimeException | LinkageError ignored) {
                // 特殊材料无法枚举时保留状态说明，不影响其他食物。
            }
        }
        return new FoodOrderEntry(new ItemStack(food), kind, chosenRecipe, detail);
    }

    private static Evaluation evaluate(ServerPlayer player,
                                       RecipeIndex.Entry entry,
                                       Map<CraftingResolver.StackKey, Long> available) {
        Recipe<?> recipe = entry.recipe();
        if (recipe == null) {
            return new Evaluation(FoodOrderKind.NORECIPE, List.of());
        }
        try {
            if (HistoryStagesCompat.isRecipeLocked(recipe, player)) {
                return new Evaluation(FoodOrderKind.LOCKED, List.of());
            }
            ModType modType = entry.modType();
            if (CraftExecutionPolicy.requiresBoundMachine(recipe, modType)
                    && !AltarBindingRegistry.hasBindingForRecipe(player, recipe, modType)) {
                return new Evaluation(FoodOrderKind.NO_MACHINE, List.of());
            }
            List<IngredientSpec> specs = recipe instanceof CraftingRecipe craftingRecipe
                    ? CraftPacketUtils.extractCraftingIngredientSpecs(craftingRecipe)
                    : CraftPacketUtils.extractIngredientSpecs(recipe);
            if (specs == null || specs.isEmpty()
                    || specs.stream().allMatch(IngredientSpec::isEmpty)) {
                return new Evaluation(FoodOrderKind.NORECIPE, List.of());
            }
            List<IngredientSpec> contextual = CraftPacketUtils
                    .applyPlanningIngredientContext(recipe, player, specs);
            if (contextual != null && !contextual.isEmpty()) {
                specs = contextual;
            }
            MaterialAvailability availability = RecipeAvailabilityChecker.evaluate(specs, available);
            FoodOrderKind kind = switch (availability) {
                case READY -> FoodOrderKind.READY;
                case MISSING -> FoodOrderKind.MISSING;
                case UNKNOWN -> FoodOrderKind.WAITING;
            };
            return new Evaluation(kind, specs);
        } catch (Throwable throwable) {
            return new Evaluation(FoodOrderKind.WAITING, List.of());
        }
    }

    private static Component sourcesComponent(CraftStorageEndpoint endpoint, String apiVersion) {
        return Component.translatable("rsi.food_order.sources",
                Component.translatable(endpoint == null
                        ? "rsi.food_order.source.inventory"
                        : "rsi.food_order.source.network"),
                apiVersion);
    }

    private record Evaluation(FoodOrderKind kind, List<IngredientSpec> specs) {}

    private static final class BuildState {
        private final FoodOrderListProgress progress;
        private Map<Item, List<RecipeIndex.Entry>> index;
        private Map<CraftingResolver.StackKey, Long> available;
        private Component sources;

        private BuildState(FoodOrderListProgress progress, Component sources) {
            this.progress = progress;
            this.sources = sources;
        }
    }
}
