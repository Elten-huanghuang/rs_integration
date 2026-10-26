package com.example.rsiintegration;

import com.huanghuang.rsintegration.api.consumer.ConsumerInspectionOptions;
import com.huanghuang.rsintegration.api.consumer.ConsumerInspectionRequest;
import com.huanghuang.rsintegration.api.consumer.ConsumerInspectionRequestId;
import com.huanghuang.rsintegration.api.consumer.ConsumerInspectionTicket;
import com.huanghuang.rsintegration.api.consumer.ConsumerMachineSelector;
import com.huanghuang.rsintegration.api.consumer.ConsumerOrderReceipt;
import com.huanghuang.rsintegration.api.consumer.ConsumerOrderRequest;
import com.huanghuang.rsintegration.api.consumer.ConsumerOutputDestination;
import com.huanghuang.rsintegration.api.consumer.ConsumerRecipeKey;
import com.huanghuang.rsintegration.api.consumer.ConsumerResult;
import com.huanghuang.rsintegration.api.consumer.ConsumerServiceState;
import com.huanghuang.rsintegration.api.consumer.ConsumerStorageSelector;
import com.huanghuang.rsintegration.api.consumer.RSICraftConsumerApi;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Optional;

/** Compile-only example of the Consumer API boundary. */
public final class PublicConsumerExample {
    private PublicConsumerExample() {}

    public static Optional<ConsumerInspectionTicket> openInspection(
            ServerPlayer player,
            ResourceLocation consumerId,
            ConsumerInspectionRequestId requestId,
            List<ConsumerRecipeKey> recipes
    ) {
        RSICraftConsumerApi api = RSICraftConsumerApi.get().orElse(null);
        if (api == null || api.state() != ConsumerServiceState.READY) {
            return Optional.empty();
        }
        ConsumerInspectionRequest request = new ConsumerInspectionRequest(
                consumerId,
                requestId,
                recipes,
                new ConsumerMachineSelector.AutomaticBound(),
                new ConsumerStorageSelector.Default(),
                ConsumerOutputDestination.PLAYER_INVENTORY,
                new ConsumerInspectionOptions(64, true)
        );
        ConsumerResult<ConsumerInspectionTicket> result =
                api.openInspection(player, request);
        return result.successful()
                ? Optional.of(result.value()) : Optional.empty();
    }

    public static Optional<ConsumerOrderReceipt> submit(
            ServerPlayer player,
            ConsumerOrderRequest request
    ) {
        return RSICraftConsumerApi.get()
                .map(api -> api.submit(player, request))
                .filter(ConsumerResult::successful)
                .map(ConsumerResult::value);
    }
}
