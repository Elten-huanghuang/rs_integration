package com.huanghuang.rsintegration.api.consumer;

import com.huanghuang.rsintegration.api.ApiVersion;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Optional;

public interface RSICraftConsumerApi {
    int API_MAJOR = 1;
    int API_MINOR = 0;

    ApiVersion version();

    ConsumerServiceState state();

    ConsumerCapabilities capabilities();

    ConsumerResult<List<ConsumerStorageOption>> storageOptions(
            ServerPlayer player
    );

    ConsumerResult<ConsumerRecipeCatalogPage> findRecipes(
            ServerPlayer player,
            ConsumerRecipeCatalogRequest request
    );

    ConsumerResult<ConsumerInspectionTicket> openInspection(
            ServerPlayer player,
            ConsumerInspectionRequest request
    );

    ConsumerResult<ConsumerInspectionPage> nextInspectionPage(
            ServerPlayer player,
            ConsumerInspectionTicketId ticketId
    );

    ConsumerResult<ConsumerAck> closeInspection(
            ServerPlayer player,
            ConsumerInspectionTicketId ticketId
    );

    ConsumerResult<ConsumerAck> discardPlan(
            ServerPlayer player,
            ResourceLocation consumerId,
            ConsumerPlanId planId
    );

    ConsumerResult<ConsumerOrderReceipt> submit(
            ServerPlayer player,
            ConsumerOrderRequest request
    );

    ConsumerResult<ConsumerBatchOrderReceipt> submitBatch(
            ServerPlayer player,
            ConsumerBatchOrderRequest request
    );

    ConsumerResult<ConsumerOrderStatus> status(
            ServerPlayer player,
            ResourceLocation consumerId,
            ConsumerOrderId orderId
    );

    ConsumerResult<ConsumerCancelResult> cancel(
            ServerPlayer player,
            ResourceLocation consumerId,
            ConsumerOrderId orderId,
            ConsumerExternalRequestId cancellationRequestId
    );

    static Optional<RSICraftConsumerApi> get() {
        return RSIConsumerServices.current();
    }
}
