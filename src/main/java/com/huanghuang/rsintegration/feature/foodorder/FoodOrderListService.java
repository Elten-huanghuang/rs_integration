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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 点餐列表构建器。
 *
 * <p>性能约定：一次构建只读取一次存储快照与一次配方索引；每种食物最多尝试
 * {@code maxRecipesPerFood} 个配方；总耗时超过配置预算就返回部分结果并让客户端
 * 下次刷新补齐。这样即使装了数百个食物模组也不会在一个 tick 里卡住服务端。</p>
 */
public final class FoodOrderListService {

    private static final long CACHE_TICKS = 20L;
    private static final long REQUEST_COOLDOWN_TICKS = 10L;

    private static final Map<UUID, CachedSnapshot> CACHE = new HashMap<>();
    private static final Map<UUID, Long> LAST_REQUEST = new HashMap<>();
    /** 待构建队列，每 tick 最多取一个玩家，避免多人同时刷新叠加。 */
    private static final Map<UUID, Long> PENDING = new LinkedHashMap<>();
    private static long lastBuildTick = Long.MIN_VALUE;

    private FoodOrderListService() {}

    /** 客户端请求刷新；同一玩家 10 tick 内只处理一次。 */
    public static void request(ServerPlayer player) {
        if (player == null || !FoodOrderService.holding(player)) {
            return;
        }
        long now = player.serverLevel().getGameTime();
        Long last = LAST_REQUEST.get(player.getUUID());
        if (last != null && now - last < REQUEST_COOLDOWN_TICKS) {
            return;
        }
        LAST_REQUEST.put(player.getUUID(), now);
        PENDING.putIfAbsent(player.getUUID(), now);
    }

    /** 立即请求一次列表；实际构建仍在 tick 队列中串行执行。 */
    public static void sendNow(ServerPlayer player) {
        if (player == null || !FoodOrderService.holding(player)) {
            return;
        }
        PENDING.put(player.getUUID(), player.serverLevel().getGameTime());
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
            iterator.remove();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null || player.hasDisconnected()
                    || !FoodOrderService.holding(player)) {
                continue;
            }
            lastBuildTick = now;
            try {
                FoodOrderListPacket.send(player, snapshot(player, true));
            } catch (Throwable throwable) {
                RSIntegrationMod.LOGGER.warn(
                        "[RSI-FoodOrder] 构建点餐列表异常，已跳过本次请求", throwable);
            }
            break;
        }
    }

    /** 最近一次构建结果；过期返回 null。 */
    public static FoodOrderSnapshot cached(ServerPlayer player) {
        CachedSnapshot cached = CACHE.get(player.getUUID());
        if (cached == null) {
            return null;
        }
        long now = player.serverLevel().getGameTime();
        if (now - cached.builtAt() > CACHE_TICKS) {
            return null;
        }
        return cached.snapshot();
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
            RSIntegrationMod.LOGGER.warn(
                    "[RSI-FoodOrder] 构建列表失败，返回空列表", throwable);
            snapshot = new FoodOrderSnapshot(player.serverLevel().getGameTime(),
                    List.of(), Component.translatable("rsi.food_order.error.failed"),
                    0, 0, false, true);
        }
        CACHE.put(player.getUUID(), new CachedSnapshot(
                player.serverLevel().getGameTime(), snapshot));
        return snapshot;
    }

    public static void invalidate() {
        CACHE.clear();
        LAST_REQUEST.clear();
        PENDING.clear();
        lastBuildTick = Long.MIN_VALUE;
        FoodOrderFoods.invalidate();
    }

    public static void clearPlayer(UUID playerId) {
        CACHE.remove(playerId);
        LAST_REQUEST.remove(playerId);
        PENDING.remove(playerId);
    }

    private static FoodOrderSnapshot build(ServerPlayer player) {
        long epoch = player.serverLevel().getGameTime();
        List<Item> foods = FoodOrderFoods.uneaten(player);
        int maxList = Math.max(10, RSIntegrationConfig.FOOD_ORDER_MAX_LIST_ENTRIES.get());
        String apiVersion = RSICraftConsumerApi.get()
                .map(api -> api.version().toString())
                .orElse("-");

        if (!RecipeIndex.isReady(player.serverLevel())) {
            RecipeIndex.warmUp(player.serverLevel());
            List<FoodOrderEntry> placeholders = new ArrayList<>();
            for (Item food : foods) {
                if (placeholders.size() >= maxList) {
                    break;
                }
                placeholders.add(new FoodOrderEntry(new ItemStack(food),
                        FoodOrderKind.INDEXING, null,
                        Component.translatable(FoodOrderKind.INDEXING.detailKey())));
            }
            return new FoodOrderSnapshot(epoch, placeholders,
                    sourcesComponent(null, apiVersion),
                    foods.size(), 0, true, false);
        }

        Map<Item, List<RecipeIndex.Entry>> index;
        try {
            index = RecipeIndex.get(player.serverLevel());
        } catch (Throwable throwable) {
            // 索引在构建期间失效：降级为空列表占位，避免把异常抛给客户端。
            return new FoodOrderSnapshot(epoch, List.of(),
                    sourcesComponent(null, apiVersion),
                    foods.size(), 0, true, false);
        }

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
        Map<CraftingResolver.StackKey, Long> available;
        try {
            available = endpoint == null
                    ? MaterialSources.countInventory(player)
                    : MaterialSources.listAllAvailable(player, endpoint);
        } catch (Throwable throwable) {
            available = Map.of();
        }

        int maxRecipes = Math.max(1, RSIntegrationConfig.FOOD_ORDER_MAX_RECIPES_PER_FOOD.get());
        long budgetNanos = Math.max(2_000L,
                RSIntegrationConfig.FOOD_ORDER_LIST_BUDGET_MICROS.get()) * 1_000L;
        long deadline = System.nanoTime() + budgetNanos;
        boolean partial = false;
        int orderable = 0;
        List<FoodOrderEntry> entries = new ArrayList<>(foods.size());

        for (Item food : foods) {
            if (System.nanoTime() >= deadline) {
                partial = true;
                break;
            }
            List<RecipeIndex.Entry> recipes = index.get(food);
            FoodOrderKind kind = FoodOrderKind.NORECIPE;
            ResourceLocation chosenRecipe = null;
            if (recipes != null && !recipes.isEmpty()) {
                kind = FoodOrderKind.WAITING;
                int attempted = 0;
                for (RecipeIndex.Entry entry : recipes) {
                    if (attempted++ >= maxRecipes) {
                        break;
                    }
                    if (System.nanoTime() >= deadline) {
                        // 预算在配方循环里用完，也要标记为部分结果，避免客户端误以为已经完整评估。
                        partial = true;
                        break;
                    }
                    FoodOrderKind evaluated = evaluate(player, entry, available);
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
                    }
                }
            }
            if (kind == FoodOrderKind.READY) {
                orderable++;
            }
            entries.add(new FoodOrderEntry(new ItemStack(food), kind, chosenRecipe,
                    Component.translatable(kind.detailKey())));
        }

        entries.sort(Comparator.comparingInt(entry -> entry.kind().rank()));
        List<FoodOrderEntry> limited = entries;
        if (entries.size() > maxList) {
            limited = new ArrayList<>(entries.subList(0, maxList));
            partial = true;
        }
        return new FoodOrderSnapshot(epoch, limited,
                sourcesComponent(endpoint, apiVersion),
                foods.size(), orderable, false, partial);
    }

    private static FoodOrderKind evaluate(ServerPlayer player,
                                          RecipeIndex.Entry entry,
                                          Map<CraftingResolver.StackKey, Long> available) {
        Recipe<?> recipe = entry.recipe();
        if (recipe == null) {
            return FoodOrderKind.NORECIPE;
        }
        try {
            if (HistoryStagesCompat.isRecipeLocked(recipe, player)) {
                return FoodOrderKind.LOCKED;
            }
            ModType modType = entry.modType();
            if (CraftExecutionPolicy.requiresBoundMachine(recipe, modType)
                    && !AltarBindingRegistry.hasBindingForRecipe(player, recipe, modType)) {
                return FoodOrderKind.NO_MACHINE;
            }
            List<IngredientSpec> specs = recipe instanceof CraftingRecipe craftingRecipe
                    ? CraftPacketUtils.extractCraftingIngredientSpecs(craftingRecipe)
                    : CraftPacketUtils.extractIngredientSpecs(recipe);
            if (specs == null || specs.isEmpty()
                    || specs.stream().allMatch(IngredientSpec::isEmpty)) {
                return FoodOrderKind.NORECIPE;
            }
            List<IngredientSpec> contextual = CraftPacketUtils
                    .applyPlanningIngredientContext(recipe, player, specs);
            if (contextual != null && !contextual.isEmpty()) {
                specs = contextual;
            }
            MaterialAvailability availability = RecipeAvailabilityChecker.evaluate(specs, available);
            return switch (availability) {
                case READY -> FoodOrderKind.READY;
                case MISSING -> FoodOrderKind.MISSING;
                case UNKNOWN -> FoodOrderKind.WAITING;
            };
        } catch (Throwable throwable) {
            return FoodOrderKind.WAITING;
        }
    }

    private static Component sourcesComponent(CraftStorageEndpoint endpoint, String apiVersion) {
        return Component.translatable("rsi.food_order.sources",
                Component.translatable(endpoint == null
                        ? "rsi.food_order.source.inventory"
                        : "rsi.food_order.source.network"),
                apiVersion);
    }

    private record CachedSnapshot(long builtAt, FoodOrderSnapshot snapshot) {}
}
