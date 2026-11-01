package com.huanghuang.rsintegration.feature.goety.integration;

import com.huanghuang.rsintegration.api.*;
import com.huanghuang.rsintegration.api.consumer.*;
import com.huanghuang.rsintegration.api.storage.StorageLink;
import com.huanghuang.rsintegration.crafting.CraftingResolver;
import net.minecraftforge.registries.ForgeRegistries;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import java.util.*;

/** 缺料通过公共 Consumer API 递归制作，并统一管理订单结算和取消。 */
public final class MissingMaterialOrders {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("rs_integration", "brew_materials");
    public record MaterialNeed(List<ItemStack> alternatives, int amount) {
        public MaterialNeed {
            if (alternatives.isEmpty() || amount < 1) throw new IllegalArgumentException("Empty material demand");
            alternatives = alternatives.stream().map(s -> s.copyWithCount(1)).toList();
        }
    }
    private final Deque<MaterialNeed> queue = new ArrayDeque<>();
    private final Deque<ConsumerRecipeKey> pendingKeys = new ArrayDeque<>();
    private MaterialNeed currentNeed;
    private RSICraftConsumerApi api;
    private StorageLink storage;
    private ConsumerInspectionTicketId ticket;
    private ConsumerOrderId order;
    private ItemStack current = ItemStack.EMPTY;
    private Component message;
    private int polls;
    private long cursor;
    private Long nextCursor, catalogRevision;
    private boolean succeeded;
    private ConsumerPlanningPreferences preferences = ConsumerPlanningPreferences.empty();
    public boolean busy() { return ticket != null || order != null || !current.isEmpty() || !queue.isEmpty(); }
    public boolean succeeded() { return succeeded && !busy(); }
    public Component takeMessage() { var result = message; message = null; return result; }

    public Component start(ServerPlayer player, List<ItemStack> needs, StorageLink storage) {
        return startAlternatives(player, needs.stream().map(s -> new MaterialNeed(List.of(s), s.getCount())).toList(), storage);
    }
    public Component startAlternatives(ServerPlayer player, List<MaterialNeed> needs, StorageLink storage) {
        return startAlternatives(player, needs, storage, ConsumerPlanningPreferences.empty());
    }
    public Component startAlternatives(ServerPlayer player, List<MaterialNeed> needs, StorageLink storage,
                                       ConsumerPlanningPreferences preferences) {
        if (busy()) return text("orders_busy");
        succeeded = false;
        this.preferences = preferences;
        this.storage = storage;
        message = null;
        var missing = needs.stream().filter(need -> available(player, need) < need.amount()).toList();
        if (missing.isEmpty()) {
            succeeded = true;
            return text("materials_ready");
        }
        api = RSICraftConsumerApi.get().orElse(null);
        if (api == null || api.state() != ConsumerServiceState.READY) return text("api_unavailable");
        queue.addAll(missing);
        polls = 0;
        return text("orders_queued");
    }
    public void tick(ServerPlayer player) {
        if (!busy() || api == null) return;
        if (!RsiStorageLink.isCurrentDefault(player, storage)) { fail(player, text("bind_failed")); return; }
        if (++polls > 600) { fail(player, text("order_timeout")); return; }
        if (order != null) {
            var result = api.status(player, ID, order);
            if (!result.successful()) { fail(player, diagnostics(result)); return; }
            var status = result.value();
            if (!status.terminal()) { message = Component.translatable("gui.rs_integration.order_progress", current.getHoverName(), status.completedOperations(), status.totalOperations()); return; }
            order = null;
            if (status.state() != ConsumerOrderState.SUCCEEDED) {
                LOGGER.warn("RSI material order ended: state={}, reason={}, diagnostics={}",
                        status.state(), status.failureReason(), status.diagnostics());
                fail(player, text("order_failed").copy().append(" (" + status.failureReason() + ")")); return;
            }
            polls = 0;
            if (currentNeed != null && available(player, currentNeed) >= currentNeed.amount()) {
                current = ItemStack.EMPTY; currentNeed = null;
                succeeded = queue.isEmpty();
                message = text(queue.isEmpty() ? "materials_ready" : "orders_queued");
            } else {
                // 标签可能由多个物品或多次配方执行满足，必须等实际库存足额后才能继续。
                current = currentNeed == null ? ItemStack.EMPTY : currentNeed.alternatives().get(0);
                cursor = 0; nextCursor = null; catalogRevision = null; pendingKeys.clear();
                message = text("orders_queued");
            }
            return;
        }
        if (ticket != null) {
            var result = api.nextInspectionPage(player, ticket);
            if (!result.successful()) { fail(player, diagnostics(result)); return; }
            var page = result.value();
            if (page.state() == ConsumerInspectionPageState.PENDING || page.state() == ConsumerInspectionPageState.RUNNING) return;
            ConsumerPlanId chosen = null;
            ConsumerRecipeKey chosenRecipe = null;
            for (var candidate : page.results()) {
                if (chosen == null && candidate.canSubmit()) { chosen = candidate.planId(); chosenRecipe = candidate.recipe(); }
                else if (candidate.planId() != null) api.discardPlan(player, ID, candidate.planId());
            }
            if (chosen == null && page.nextPageIndex() != null) return;
            api.closeInspection(player, ticket); ticket = null;
            if (chosen == null) {
                if (!pendingKeys.isEmpty()) return;
                if (nextCursor != null) { cursor = nextCursor; return; }
                fail(player, Component.translatable("gui.rs_integration.cannot_craft_item", current.getHoverName())); return;
            }
            var submitted = api.submit(player, new ConsumerOrderRequest(ID, new ConsumerExternalRequestId(UUID.randomUUID()), chosen));
            if (!submitted.successful()) { api.discardPlan(player, ID, chosen); fail(player, diagnostics(submitted)); return; }
            final var output = chosenRecipe.targetOutput();
            current = currentNeed.alternatives().stream().filter(s -> IntegrationResourceKey.item(s).equals(output)).findFirst().orElse(current);
            pendingKeys.clear(); order = submitted.value().orderId(); polls = 0; return;
        }
        if (current.isEmpty()) {
            currentNeed = queue.removeFirst(); current = currentNeed.alternatives().get(0); polls = 0; cursor = 0; catalogRevision = null; nextCursor = null;
            // 先前订单也可能产出后续材料，每次轮到该材料时重新统计。
            if (available(player, currentNeed) >= currentNeed.amount()) {
                current = ItemStack.EMPTY; currentNeed = null; succeeded = queue.isEmpty();
                message = text(queue.isEmpty() ? "materials_ready" : "orders_queued"); return;
            }
        }
        int pageSize = Math.min(32, Math.min(api.capabilities().maxPageSize(), api.capabilities().maxRecipesPerSession()));
        if (pendingKeys.isEmpty()) {
            var outputs = currentNeed.alternatives().stream().map(IntegrationResourceKey::item).distinct().toList();
            var found = api.findRecipes(player, new ConsumerRecipeCatalogRequest(
                    new ConsumerRecipeQuery.ByOutputs(outputs, ResourceMatchMode.EXACT_IDENTITY), pageSize, cursor));
            if (!found.successful()) { fail(player, diagnostics(found)); return; }
            var page = found.value();
            if (catalogRevision != null && catalogRevision != page.recipeRevision()) { fail(player, text("recipes_changed")); return; }
            catalogRevision = page.recipeRevision(); nextCursor = page.nextCursor();
            var keys = new LinkedHashSet<ConsumerRecipeKey>();
            for (var candidate : page.candidates()) for (var target : currentNeed.alternatives()) {
                var output = IntegrationResourceKey.item(target);
                if (!candidate.declaredOutputs().contains(output)) continue;
                var exactKey = CraftingResolver.preferenceKey(target);
                var preferred = exactKey == null ? null : preferences.forcedRecipes().get(exactKey.toString());
                if (preferred == null) {
                    preferred = preferences.forcedRecipes().get(ForgeRegistries.ITEMS.getKey(target.getItem()).toString());
                }
                if (preferred != null && !preferred.equals(candidate.recipeId())) continue;
                int missing = (int)Math.max(0, currentNeed.amount() - available(player, currentNeed));
                if (missing > 0) keys.add(new ConsumerRecipeKey(candidate.recipeId(), output,
                        new ConsumerRequestAmount.OutputUnits(missing), preferences));
            }
            pendingKeys.addAll(keys);
            if (pendingKeys.isEmpty()) {
                if (nextCursor != null) { cursor = nextCursor; return; }
                fail(player, Component.translatable("gui.rs_integration.no_recipe_item", current.getHoverName())); return;
            }
        }
        var keys = new ArrayList<ConsumerRecipeKey>();
        // Consumer API 要求单次检查中的配方 ID 唯一；同配方的其他替代产物留待后续检查。
        var recipeIds = new HashSet<ResourceLocation>();
        var iterator = pendingKeys.iterator();
        while (iterator.hasNext() && keys.size() < pageSize) {
            var key = iterator.next();
            if (recipeIds.add(key.recipeId())) {
                keys.add(key);
                iterator.remove();
            }
        }
        var inspection = api.openInspection(player, new ConsumerInspectionRequest(ID, new ConsumerInspectionRequestId(UUID.randomUUID()),
                keys, new ConsumerMachineSelector.AutomaticBound(), new ConsumerStorageSelector.Default(),
                ConsumerOutputDestination.PLAYER_INVENTORY, new ConsumerInspectionOptions(pageSize, true)));
        if (!inspection.successful()) { fail(player, diagnostics(inspection)); return; }
        ticket = inspection.value().ticketId(); message = Component.translatable("gui.rs_integration.planning_item", current.getHoverName());
    }
    public void close(ServerPlayer player) {
        succeeded = false;
        queue.clear(); pendingKeys.clear();
        try {
            if (api != null && ticket != null) api.closeInspection(player, ticket);
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.error("RSI inspection cleanup failed: {}", ticket, failure);
        } finally {
            ticket = null;
            try {
                if (api != null && order != null) api.cancel(player, ID, order, new ConsumerExternalRequestId(UUID.randomUUID()));
            } catch (RuntimeException | LinkageError failure) {
                LOGGER.error("RSI order cancellation failed: {}", order, failure);
            } finally { order = null; current = ItemStack.EMPTY; currentNeed = null; }
        }
    }
    private long available(ServerPlayer player, MaterialNeed need) {
        return need.alternatives().stream().mapToLong(s -> available(player, s)).reduce(0L, (a, b) -> a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b);
    }
    private long available(ServerPlayer player, ItemStack stack) {
        return RsiStorageLink.inventoryCount(player, stack) + RsiStorageLink.count(player, storage, stack);
    }
    private void fail(ServerPlayer player, Component detail) { close(player); message = detail; }
    private static Component text(String key) { return Component.translatable("gui.rs_integration." + key); }
    private static Component diagnostics(ConsumerResult<?> result) {
        return result.diagnostics().stream().findFirst().<Component>map(d -> Component.literal(d.code() + ": " + d.detail())).orElse(text("order_failed"));
    }
}
