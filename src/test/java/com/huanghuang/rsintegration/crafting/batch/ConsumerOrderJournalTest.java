package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.api.consumer.ConsumerBatchOrderEntry;
import com.huanghuang.rsintegration.api.consumer.ConsumerBatchOrderReceipt;
import com.huanghuang.rsintegration.api.consumer.ConsumerBatchOrderRequest;
import com.huanghuang.rsintegration.api.consumer.ConsumerBatchRequestId;
import com.huanghuang.rsintegration.api.consumer.ConsumerCancelResult;
import com.huanghuang.rsintegration.api.consumer.ConsumerExternalRequestId;
import com.huanghuang.rsintegration.api.consumer.ConsumerOrderId;
import com.huanghuang.rsintegration.api.consumer.ConsumerOrderReceipt;
import com.huanghuang.rsintegration.api.consumer.ConsumerOrderRequest;
import com.huanghuang.rsintegration.api.consumer.ConsumerOrderState;
import com.huanghuang.rsintegration.api.consumer.ConsumerOutputDestination;
import com.huanghuang.rsintegration.api.consumer.ConsumerPlanId;
import com.huanghuang.rsintegration.api.consumer.ConsumerResult;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsumerOrderJournalTest extends BootstrapTest {
    @Test
    void journalRestoresOrdersAndIdempotencyResults() {
        UUID playerId = UUID.randomUUID();
        ResourceLocation consumerId = resource("consumer");
        ConsumerOrderId orderId = new ConsumerOrderId(UUID.randomUUID());
        ConsumerExternalRequestId requestId =
                new ConsumerExternalRequestId(UUID.randomUUID());
        ConsumerOrderReceipt receipt = new ConsumerOrderReceipt(
                orderId, ConsumerOrderState.ACCEPTED);
        ConsumerOrderRequest request = new ConsumerOrderRequest(
                consumerId, requestId, new ConsumerPlanId(UUID.randomUUID()));
        ConsumerCraftingRuntime.OrderRecord order =
                new ConsumerCraftingRuntime.OrderRecord(
                        orderId,
                        playerId,
                        consumerId,
                        requestId,
                        resource("recipe"),
                        ConsumerOutputDestination.PLAYER_INVENTORY,
                        100L
                );
        ConsumerCraftingRuntime.SubmitKey submitKey =
                new ConsumerCraftingRuntime.SubmitKey(
                        playerId, consumerId, requestId.value());
        ConsumerCraftingRuntime.SubmissionRecord submission =
                new ConsumerCraftingRuntime.SubmissionRecord(
                        request.planId(),
                        ConsumerResult.success(receipt)
                );
        ConsumerBatchRequestId batchId =
                new ConsumerBatchRequestId(UUID.randomUUID());
        ConsumerBatchOrderRequest batchRequest =
                new ConsumerBatchOrderRequest(
                        consumerId, batchId, List.of(request));
        ConsumerCraftingRuntime.BatchSubmitKey batchKey =
                new ConsumerCraftingRuntime.BatchSubmitKey(
                        playerId, consumerId, batchId.value());
        ConsumerCraftingRuntime.BatchSubmissionRecord batchRecord =
                new ConsumerCraftingRuntime.BatchSubmissionRecord(
                        List.of(request),
                        ConsumerResult.success(
                                new ConsumerBatchOrderReceipt(
                                        batchId,
                                        List.of(new ConsumerBatchOrderEntry(
                                                requestId,
                                                ConsumerResult.success(receipt)
                                        ))
                                )
                        )
                );
        ConsumerCraftingRuntime.CancelKey cancelKey =
                new ConsumerCraftingRuntime.CancelKey(
                        playerId,
                        consumerId,
                        UUID.randomUUID()
                );
        ConsumerCraftingRuntime.CancelRecord cancelRecord =
                new ConsumerCraftingRuntime.CancelRecord(
                        orderId,
                        ConsumerResult.success(
                                new ConsumerCancelResult(
                                        orderId,
                                        ConsumerOrderState.CANCELLED
                                )
                        )
                );

        ConsumerCraftingRuntime.Journal journal =
                new ConsumerCraftingRuntime.Journal();
        journal.putOrder(order);
        journal.putSubmission(submitKey, submission);
        journal.putBatch(batchKey, batchRecord);
        journal.putCancel(cancelKey, cancelRecord);
        CompoundTag saved = journal.save(new CompoundTag());
        ConsumerCraftingRuntime.Journal loaded =
                new ConsumerCraftingRuntime.Journal(saved);

        assertEquals(1, loaded.orders.size());
        assertEquals(
                ConsumerOrderState.FAILED,
                loaded.orders.get(orderId).status().state()
        );
        assertEquals(
                receipt,
                loaded.submissions.get(submitKey).result().value()
        );
        assertTrue(loaded.batches.containsKey(batchKey));
        assertEquals(
                ConsumerOrderState.CANCELLED,
                loaded.cancellations.get(cancelKey)
                        .result().value().state()
        );
    }

    @Test
    void corruptEntriesDoNotBlockOtherJournalRecords() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("version", 1);
        ListTag orders = new ListTag();
        CompoundTag corrupt = new CompoundTag();
        corrupt.putUUID("id", UUID.randomUUID());
        corrupt.putUUID("player", UUID.randomUUID());
        corrupt.putUUID("external", UUID.randomUUID());
        corrupt.putString("consumer", "test:consumer");
        corrupt.putByteArray("status", new byte[]{1, 2, 3});
        orders.add(corrupt);
        tag.put("orders", orders);

        ConsumerCraftingRuntime.Journal loaded =
                new ConsumerCraftingRuntime.Journal(tag);

        assertTrue(loaded.orders.isEmpty());
    }

    private static ResourceLocation resource(String path) {
        return new ResourceLocation("test", path);
    }
}
