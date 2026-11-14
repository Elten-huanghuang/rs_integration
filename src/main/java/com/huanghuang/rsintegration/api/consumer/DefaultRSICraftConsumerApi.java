package com.huanghuang.rsintegration.api.consumer;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.api.ApiVersion;
import com.huanghuang.rsintegration.crafting.RecipeIndex;
import com.huanghuang.rsintegration.crafting.batch.ConsumerCraftingRuntime;
import com.huanghuang.rsintegration.crafting.external.ExternalConsumerCatalogService;
import com.huanghuang.rsintegration.storage.StorageNetworkDescriptor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

@ApiStatus.Internal
public final class DefaultRSICraftConsumerApi
        implements RSICraftConsumerApi {
    private static final ConsumerCapabilities CAPABILITIES =
            new ConsumerCapabilities(
                    64,
                    4096,
                    4,
                    128,
                    4096,
                    32768,
                    32768,
                    64,
                    32,
                    1200L,
                    600L,
                    24_000L
            );

    private final ExternalConsumerCatalogService catalog =
            new ExternalConsumerCatalogService();
    private final ConsumerCraftingRuntime runtime =
            ConsumerCraftingRuntime.instance();
    private volatile ConsumerServiceState state =
            ConsumerServiceState.CREATED;

    @Override
    public ApiVersion version() {
        return new ApiVersion(API_MAJOR, API_MINOR, 0);
    }

    @Override
    public ConsumerServiceState state() {
        return state;
    }

    @Override
    public ConsumerCapabilities capabilities() {
        return CAPABILITIES;
    }

    @Override
    public ConsumerResult<List<ConsumerStorageOption>> storageOptions(
            ServerPlayer player
    ) {
        return whenReady(() -> availableStorageOptions(player));
    }

    private ConsumerResult<List<ConsumerStorageOption>> availableStorageOptions(ServerPlayer player) {
        if (player == null || player.server == null
                || !player.server.isSameThread()) {
            return reject(
                    ConsumerDiagnosticCode.WRONG_THREAD,
                    "storage options must be queried on the server thread",
                    true
            );
        }
        ConsumerStorageId inventoryId = runtime.registerStorage(player, null);
        List<ConsumerStorageOption> options = new ArrayList<>();
        options.add(new ConsumerStorageOption(
                inventoryId,
                Component.literal("Player Inventory"),
                true,
                player.serverLevel().getGameTime()
        ));
        for (StorageNetworkDescriptor network
                : RSIntegrationMod.STORAGE_BACKENDS.registry()
                .discoverNetworksForPlayer(player)) {
            ConsumerStorageId id = runtime.registerStorage(
                    player, network.reference());
            options.add(new ConsumerStorageOption(
                    id,
                    Component.literal(network.displayName()),
                    false,
                    player.serverLevel().getGameTime()
            ));
        }
        return ConsumerResult.success(List.copyOf(options));
    }

    @Override
    public ConsumerResult<ConsumerRecipeCatalogPage> findRecipes(
            ServerPlayer player,
            ConsumerRecipeCatalogRequest request
    ) {
        return whenReady(() -> catalog.findRecipes(player, request, CAPABILITIES));
    }

    @Override
    public ConsumerResult<ConsumerInspectionTicket> openInspection(
            ServerPlayer player,
            ConsumerInspectionRequest request
    ) {
        return whenReady(() -> runtime.openInspection(player, request, CAPABILITIES));
    }

    @Override
    public ConsumerResult<ConsumerInspectionPage> nextInspectionPage(
            ServerPlayer player,
            ConsumerInspectionTicketId ticketId
    ) {
        return whenReady(() -> runtime.nextInspectionPage(player, ticketId, CAPABILITIES));
    }

    @Override
    public ConsumerResult<ConsumerAck> closeInspection(
            ServerPlayer player,
            ConsumerInspectionTicketId ticketId
    ) {
        return whenReady(() -> runtime.closeInspection(player, ticketId, CAPABILITIES));
    }

    @Override
    public ConsumerResult<ConsumerAck> discardPlan(
            ServerPlayer player,
            ResourceLocation consumerId,
            ConsumerPlanId planId
    ) {
        return whenReady(() -> runtime.discardPlan(player, consumerId, planId));
    }

    @Override
    public ConsumerResult<ConsumerOrderReceipt> submit(
            ServerPlayer player,
            ConsumerOrderRequest request
    ) {
        return whenReady(() -> runtime.submit(player, request, CAPABILITIES));
    }

    @Override
    public ConsumerResult<ConsumerBatchOrderReceipt> submitBatch(
            ServerPlayer player,
            ConsumerBatchOrderRequest request
    ) {
        return whenReady(() -> runtime.submitBatch(player, request, CAPABILITIES));
    }

    @Override
    public ConsumerResult<ConsumerOrderStatus> status(
            ServerPlayer player,
            ResourceLocation consumerId,
            ConsumerOrderId orderId
    ) {
        return whenReady(() -> runtime.status(player, consumerId, orderId));
    }

    @Override
    public ConsumerResult<ConsumerCancelResult> cancel(
            ServerPlayer player,
            ResourceLocation consumerId,
            ConsumerOrderId orderId,
            ConsumerExternalRequestId cancellationRequestId
    ) {
        return whenReady(() -> runtime.cancel(player, consumerId, orderId, cancellationRequestId));
    }

    public void markIndexing() {
        if (state != ConsumerServiceState.STOPPING) {
            state = ConsumerServiceState.INDEXING;
        }
    }

    public void tick(ServerLevel level) {
        if (state == ConsumerServiceState.CREATED
                || state == ConsumerServiceState.INDEXING
                || state == ConsumerServiceState.READY) {
            state = RecipeIndex.isReady(level)
                    ? ConsumerServiceState.READY
                    : ConsumerServiceState.INDEXING;
        }
    }

    public void stop() {
        state = ConsumerServiceState.STOPPING;
        runtime.expireTransientState();
        state = ConsumerServiceState.STOPPED;
    }

    public void clearPlayer(UUID playerId) {
        runtime.clearPlayer(playerId);
    }

    /** 延迟调用业务层，确保未就绪时不读取玩家、请求或创建订单。 */
    private <T> ConsumerResult<T> whenReady(Supplier<ConsumerResult<T>> action) {
        ConsumerServiceState current = state;
        if (current == ConsumerServiceState.READY) return action.get();
        if (current == ConsumerServiceState.STOPPING
                || current == ConsumerServiceState.STOPPED) {
            return reject(
                    ConsumerDiagnosticCode.SERVICE_STOPPING,
                    "consumer service is stopping or stopped",
                    true
            );
        }
        return reject(
                ConsumerDiagnosticCode.CATALOG_NOT_READY,
                "consumer service is still indexing",
                true
        );
    }

    private static <T> ConsumerResult<T> reject(
            ConsumerDiagnosticCode code,
            String detail,
            boolean retryable
    ) {
        return ConsumerResult.rejected(
                ConsumerDiagnostic.error(code, detail, retryable));
    }
}
