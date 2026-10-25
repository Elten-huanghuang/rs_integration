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
        ConsumerResult<List<ConsumerStorageOption>> unavailable =
                lifecycleFailure();
        if (unavailable != null) return unavailable;
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
        ConsumerResult<ConsumerRecipeCatalogPage> unavailable =
                lifecycleFailure();
        if (unavailable != null) return unavailable;
        return catalog.findRecipes(player, request, CAPABILITIES);
    }

    @Override
    public ConsumerResult<ConsumerInspectionTicket> openInspection(
            ServerPlayer player,
            ConsumerInspectionRequest request
    ) {
        ConsumerResult<ConsumerInspectionTicket> unavailable =
                lifecycleFailure();
        if (unavailable != null) return unavailable;
        return runtime.openInspection(player, request, CAPABILITIES);
    }

    @Override
    public ConsumerResult<ConsumerInspectionPage> nextInspectionPage(
            ServerPlayer player,
            ConsumerInspectionTicketId ticketId
    ) {
        ConsumerResult<ConsumerInspectionPage> unavailable =
                lifecycleFailure();
        if (unavailable != null) return unavailable;
        return runtime.nextInspectionPage(
                player, ticketId, CAPABILITIES);
    }

    @Override
    public ConsumerResult<ConsumerAck> closeInspection(
            ServerPlayer player,
            ConsumerInspectionTicketId ticketId
    ) {
        ConsumerResult<ConsumerAck> unavailable = lifecycleFailure();
        if (unavailable != null) return unavailable;
        return runtime.closeInspection(
                player, ticketId, CAPABILITIES);
    }

    @Override
    public ConsumerResult<ConsumerAck> discardPlan(
            ServerPlayer player,
            ResourceLocation consumerId,
            ConsumerPlanId planId
    ) {
        ConsumerResult<ConsumerAck> unavailable = lifecycleFailure();
        if (unavailable != null) return unavailable;
        return runtime.discardPlan(player, consumerId, planId);
    }

    @Override
    public ConsumerResult<ConsumerOrderReceipt> submit(
            ServerPlayer player,
            ConsumerOrderRequest request
    ) {
        ConsumerResult<ConsumerOrderReceipt> unavailable =
                lifecycleFailure();
        if (unavailable != null) return unavailable;
        return runtime.submit(player, request, CAPABILITIES);
    }

    @Override
    public ConsumerResult<ConsumerBatchOrderReceipt> submitBatch(
            ServerPlayer player,
            ConsumerBatchOrderRequest request
    ) {
        ConsumerResult<ConsumerBatchOrderReceipt> unavailable =
                lifecycleFailure();
        if (unavailable != null) return unavailable;
        return runtime.submitBatch(player, request, CAPABILITIES);
    }

    @Override
    public ConsumerResult<ConsumerOrderStatus> status(
            ServerPlayer player,
            ResourceLocation consumerId,
            ConsumerOrderId orderId
    ) {
        ConsumerResult<ConsumerOrderStatus> unavailable =
                lifecycleFailure();
        if (unavailable != null) return unavailable;
        return runtime.status(player, consumerId, orderId);
    }

    @Override
    public ConsumerResult<ConsumerCancelResult> cancel(
            ServerPlayer player,
            ResourceLocation consumerId,
            ConsumerOrderId orderId,
            ConsumerExternalRequestId cancellationRequestId
    ) {
        ConsumerResult<ConsumerCancelResult> unavailable =
                lifecycleFailure();
        if (unavailable != null) return unavailable;
        return runtime.cancel(
                player,
                consumerId,
                orderId,
                cancellationRequestId
        );
    }

    public void markIndexing() {
        if (state != ConsumerServiceState.STOPPING) {
            state = ConsumerServiceState.INDEXING;
        }
    }

    public void tick(ServerLevel level) {
        if (state == ConsumerServiceState.CREATED
                || state == ConsumerServiceState.INDEXING) {
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

    private <T> ConsumerResult<T> lifecycleFailure() {
        if (state == ConsumerServiceState.READY) return null;
        if (state == ConsumerServiceState.STOPPING
                || state == ConsumerServiceState.STOPPED) {
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
