package com.huanghuang.rsintegration.api.consumer;

import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.api.ResourceMatchMode;
import com.huanghuang.rsintegration.api.diagnostic.IntegrationDiagnostic;
import com.huanghuang.rsintegration.api.diagnostic.IntegrationDiagnosticCode;
import com.huanghuang.rsintegration.api.diagnostic.IntegrationSeverity;
import com.huanghuang.rsintegration.api.execution.FailureReason;
import com.huanghuang.rsintegration.api.recipe.RecipeExecutionKind;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** 无传输副作用的 Consumer DTO 编解码器。 */
public final class ConsumerApiCodecs {
    public static final int WIRE_VERSION = 1;
    private static final int MAX_LIST_SIZE = 4096;
    private static final int MAX_MAP_SIZE = 256;
    private static final int MAX_STRING_LENGTH = 1024;
    private static final int MAX_NBT_DEPTH = 64;
    private static final int MAX_NBT_NODES = 16384;

    private ConsumerApiCodecs() {}

    public static <T> void writeResult(
            FriendlyByteBuf buffer,
            ConsumerResult<T> result,
            BiConsumer<FriendlyByteBuf, T> payloadWriter
    ) {
        writeVersion(buffer);
        buffer.writeBoolean(result.successful());
        if (result.successful()) {
            payloadWriter.accept(buffer, result.value());
        }
        writeList(buffer, result.diagnostics(),
                ConsumerApiCodecs::writeConsumerDiagnosticPayload);
        buffer.writeBoolean(result.idempotentReplay());
    }

    public static <T> ConsumerResult<T> readResult(
            FriendlyByteBuf buffer,
            Function<FriendlyByteBuf, T> payloadReader
    ) {
        readVersion(buffer);
        T value = buffer.readBoolean() ? payloadReader.apply(buffer) : null;
        List<ConsumerDiagnostic> diagnostics = readList(
                buffer, ConsumerApiCodecs::readConsumerDiagnosticPayload);
        boolean replay = buffer.readBoolean();
        return new ConsumerResult<>(value, diagnostics, replay);
    }

    public static void writeInspectionRequest(
            FriendlyByteBuf buffer,
            ConsumerInspectionRequest request
    ) {
        writeVersion(buffer);
        writeInspectionRequestPayload(buffer, request);
    }

    public static ConsumerInspectionRequest readInspectionRequest(
            FriendlyByteBuf buffer
    ) {
        readVersion(buffer);
        return readInspectionRequestPayload(buffer);
    }

    public static void writeInspectionTicket(
            FriendlyByteBuf buffer,
            ConsumerInspectionTicket ticket
    ) {
        writeVersion(buffer);
        writeInspectionTicketPayload(buffer, ticket);
    }

    public static ConsumerInspectionTicket readInspectionTicket(
            FriendlyByteBuf buffer
    ) {
        readVersion(buffer);
        return readInspectionTicketPayload(buffer);
    }

    public static void writeStorageOption(
            FriendlyByteBuf buffer,
            ConsumerStorageOption option
    ) {
        writeVersion(buffer);
        writeStorageOptionPayload(buffer, option);
    }

    public static ConsumerStorageOption readStorageOption(
            FriendlyByteBuf buffer
    ) {
        readVersion(buffer);
        return readStorageOptionPayload(buffer);
    }

    public static void writeStorageOptions(
            FriendlyByteBuf buffer,
            List<ConsumerStorageOption> options
    ) {
        writeVersion(buffer);
        writeList(buffer, options, ConsumerApiCodecs::writeStorageOptionPayload);
    }

    public static List<ConsumerStorageOption> readStorageOptions(
            FriendlyByteBuf buffer
    ) {
        readVersion(buffer);
        return readList(buffer, ConsumerApiCodecs::readStorageOptionPayload);
    }

    public static void writeRecipeCatalogRequest(
            FriendlyByteBuf buffer,
            ConsumerRecipeCatalogRequest request
    ) {
        writeVersion(buffer);
        writeRecipeCatalogRequestPayload(buffer, request);
    }

    public static ConsumerRecipeCatalogRequest readRecipeCatalogRequest(
            FriendlyByteBuf buffer
    ) {
        readVersion(buffer);
        return readRecipeCatalogRequestPayload(buffer);
    }

    public static void writeRecipeCatalogPage(
            FriendlyByteBuf buffer,
            ConsumerRecipeCatalogPage page
    ) {
        writeVersion(buffer);
        writeRecipeCatalogPagePayload(buffer, page);
    }

    public static ConsumerRecipeCatalogPage readRecipeCatalogPage(
            FriendlyByteBuf buffer
    ) {
        readVersion(buffer);
        return readRecipeCatalogPagePayload(buffer);
    }

    public static void writeInspectionPage(
            FriendlyByteBuf buffer,
            ConsumerInspectionPage page
    ) {
        writeVersion(buffer);
        writeInspectionPagePayload(buffer, page);
    }

    public static ConsumerInspectionPage readInspectionPage(
            FriendlyByteBuf buffer
    ) {
        readVersion(buffer);
        return readInspectionPagePayload(buffer);
    }

    public static void writeOrderRequest(
            FriendlyByteBuf buffer,
            ConsumerOrderRequest request
    ) {
        writeVersion(buffer);
        writeOrderRequestPayload(buffer, request);
    }

    public static ConsumerOrderRequest readOrderRequest(
            FriendlyByteBuf buffer
    ) {
        readVersion(buffer);
        return readOrderRequestPayload(buffer);
    }

    public static void writeOrderReceipt(
            FriendlyByteBuf buffer,
            ConsumerOrderReceipt receipt
    ) {
        writeVersion(buffer);
        writeOrderReceiptPayload(buffer, receipt);
    }

    public static ConsumerOrderReceipt readOrderReceipt(
            FriendlyByteBuf buffer
    ) {
        readVersion(buffer);
        return readOrderReceiptPayload(buffer);
    }

    public static void writeBatchOrderRequest(
            FriendlyByteBuf buffer,
            ConsumerBatchOrderRequest request
    ) {
        writeVersion(buffer);
        writeBatchOrderRequestPayload(buffer, request);
    }

    public static ConsumerBatchOrderRequest readBatchOrderRequest(
            FriendlyByteBuf buffer
    ) {
        readVersion(buffer);
        return readBatchOrderRequestPayload(buffer);
    }

    public static void writeBatchOrderReceipt(
            FriendlyByteBuf buffer,
            ConsumerBatchOrderReceipt receipt
    ) {
        writeVersion(buffer);
        writeBatchOrderReceiptPayload(buffer, receipt);
    }

    public static ConsumerBatchOrderReceipt readBatchOrderReceipt(
            FriendlyByteBuf buffer
    ) {
        readVersion(buffer);
        return readBatchOrderReceiptPayload(buffer);
    }

    public static void writeOrderStatus(
            FriendlyByteBuf buffer,
            ConsumerOrderStatus status
    ) {
        writeVersion(buffer);
        writeOrderStatusPayload(buffer, status);
    }

    public static ConsumerOrderStatus readOrderStatus(
            FriendlyByteBuf buffer
    ) {
        readVersion(buffer);
        return readOrderStatusPayload(buffer);
    }

    public static void writeCancelResult(
            FriendlyByteBuf buffer,
            ConsumerCancelResult result
    ) {
        writeVersion(buffer);
        writeCancelResultPayload(buffer, result);
    }

    public static ConsumerCancelResult readCancelResult(
            FriendlyByteBuf buffer
    ) {
        readVersion(buffer);
        return readCancelResultPayload(buffer);
    }

    public static void writeAck(
            FriendlyByteBuf buffer,
            ConsumerAck ack
    ) {
        writeVersion(buffer);
        buffer.writeBoolean(ack.accepted());
    }

    public static ConsumerAck readAck(FriendlyByteBuf buffer) {
        readVersion(buffer);
        return new ConsumerAck(buffer.readBoolean());
    }

    private static void writeInspectionRequestPayload(
            FriendlyByteBuf buffer,
            ConsumerInspectionRequest request
    ) {
        buffer.writeResourceLocation(request.consumerId());
        buffer.writeUUID(request.requestId().value());
        writeList(buffer, request.recipes(), ConsumerApiCodecs::writeRecipeKey);
        writeMachineSelector(buffer, request.machineSelector());
        writeStorageSelector(buffer, request.storageSelector());
        writeEnum(buffer, request.outputDestination());
        buffer.writeVarInt(request.options().pageSize());
        buffer.writeBoolean(request.options().includeRecursiveSummary());
    }

    private static ConsumerInspectionRequest readInspectionRequestPayload(
            FriendlyByteBuf buffer
    ) {
        ResourceLocation consumerId = buffer.readResourceLocation();
        ConsumerInspectionRequestId requestId =
                new ConsumerInspectionRequestId(buffer.readUUID());
        List<ConsumerRecipeKey> recipes =
                readList(buffer, ConsumerApiCodecs::readRecipeKey);
        ConsumerMachineSelector machineSelector =
                readMachineSelector(buffer);
        ConsumerStorageSelector storageSelector =
                readStorageSelector(buffer);
        ConsumerOutputDestination outputDestination =
                readEnum(buffer, ConsumerOutputDestination.class);
        ConsumerInspectionOptions options = new ConsumerInspectionOptions(
                buffer.readVarInt(), buffer.readBoolean());
        return new ConsumerInspectionRequest(
                consumerId, requestId, recipes, machineSelector,
                storageSelector, outputDestination, options);
    }

    private static void writeRecipeKey(
            FriendlyByteBuf buffer,
            ConsumerRecipeKey key
    ) {
        buffer.writeResourceLocation(key.recipeId());
        writeOptional(buffer, key.targetOutput(),
                ConsumerApiCodecs::writeResourceKey);
        if (key.amount() instanceof ConsumerRequestAmount.Executions executions) {
            buffer.writeByte(0);
            buffer.writeVarInt(executions.count());
        } else {
            ConsumerRequestAmount.OutputUnits units =
                    (ConsumerRequestAmount.OutputUnits) key.amount();
            buffer.writeByte(1);
            buffer.writeVarLong(units.quantity());
        }
        writePlanningPreferences(buffer, key.preferences());
    }

    private static ConsumerRecipeKey readRecipeKey(FriendlyByteBuf buffer) {
        ResourceLocation recipeId = buffer.readResourceLocation();
        IntegrationResourceKey target =
                readOptional(buffer, ConsumerApiCodecs::readResourceKey);
        int amountKind = buffer.readUnsignedByte();
        ConsumerRequestAmount amount = switch (amountKind) {
            case 0 -> new ConsumerRequestAmount.Executions(
                    buffer.readVarInt());
            case 1 -> new ConsumerRequestAmount.OutputUnits(
                    buffer.readVarLong());
            default -> throw new IllegalArgumentException(
                    "unknown consumer request amount kind");
        };
        return new ConsumerRecipeKey(
                recipeId, target, amount, readPlanningPreferences(buffer));
    }

    private static void writePlanningPreferences(
            FriendlyByteBuf buffer,
            ConsumerPlanningPreferences preferences
    ) {
        writeStringMap(buffer, preferences.forcedRecipes(),
                FriendlyByteBuf::writeResourceLocation);
        writeStringMap(buffer, preferences.materialLocks(),
                ConsumerApiCodecs::writeResourceKey);
        writeCollection(buffer, preferences.excludedRecipes(),
                FriendlyByteBuf::writeResourceLocation);
    }

    private static ConsumerPlanningPreferences readPlanningPreferences(
            FriendlyByteBuf buffer
    ) {
        Map<String, ResourceLocation> forced =
                readStringMap(buffer, FriendlyByteBuf::readResourceLocation);
        Map<String, IntegrationResourceKey> locks =
                readStringMap(buffer, ConsumerApiCodecs::readResourceKey);
        Set<ResourceLocation> excluded = readCollection(
                buffer, LinkedHashSet::new,
                FriendlyByteBuf::readResourceLocation);
        return new ConsumerPlanningPreferences(forced, locks, excluded);
    }

    private static void writeMachineSelector(
            FriendlyByteBuf buffer,
            ConsumerMachineSelector selector
    ) {
        if (selector instanceof ConsumerMachineSelector.AutomaticBound) {
            buffer.writeByte(0);
            return;
        }
        if (selector instanceof ConsumerMachineSelector.Exact exact) {
            buffer.writeByte(1);
            buffer.writeResourceLocation(exact.dimension());
            buffer.writeBlockPos(exact.position());
            return;
        }
        ConsumerMachineSelector.Preferred preferred =
                (ConsumerMachineSelector.Preferred) selector;
        buffer.writeByte(2);
        buffer.writeResourceLocation(preferred.dimension());
        buffer.writeBlockPos(preferred.position());
        buffer.writeBoolean(preferred.allowAutomaticFallback());
    }

    private static ConsumerMachineSelector readMachineSelector(
            FriendlyByteBuf buffer
    ) {
        return switch (buffer.readUnsignedByte()) {
            case 0 -> new ConsumerMachineSelector.AutomaticBound();
            case 1 -> new ConsumerMachineSelector.Exact(
                    buffer.readResourceLocation(), buffer.readBlockPos());
            case 2 -> new ConsumerMachineSelector.Preferred(
                    buffer.readResourceLocation(),
                    buffer.readBlockPos(),
                    buffer.readBoolean()
            );
            default -> throw new IllegalArgumentException(
                    "unknown consumer machine selector");
        };
    }

    private static void writeStorageSelector(
            FriendlyByteBuf buffer,
            ConsumerStorageSelector selector
    ) {
        if (selector instanceof ConsumerStorageSelector.Default) {
            buffer.writeByte(0);
            return;
        }
        if (selector instanceof ConsumerStorageSelector.PlayerInventoryOnly) {
            buffer.writeByte(1);
            return;
        }
        ConsumerStorageSelector.Explicit explicit =
                (ConsumerStorageSelector.Explicit) selector;
        buffer.writeByte(2);
        buffer.writeUUID(explicit.storageId().value());
    }

    private static ConsumerStorageSelector readStorageSelector(
            FriendlyByteBuf buffer
    ) {
        return switch (buffer.readUnsignedByte()) {
            case 0 -> new ConsumerStorageSelector.Default();
            case 1 -> new ConsumerStorageSelector.PlayerInventoryOnly();
            case 2 -> new ConsumerStorageSelector.Explicit(
                    new ConsumerStorageId(buffer.readUUID()));
            default -> throw new IllegalArgumentException(
                    "unknown consumer storage selector");
        };
    }

    private static void writeStorageOptionPayload(
            FriendlyByteBuf buffer,
            ConsumerStorageOption option
    ) {
        buffer.writeUUID(option.storageId().value());
        writeString(buffer, Component.Serializer.toJson(option.displayName()));
        buffer.writeBoolean(option.playerInventoryIncluded());
        buffer.writeVarLong(option.revision());
    }

    private static ConsumerStorageOption readStorageOptionPayload(
            FriendlyByteBuf buffer
    ) {
        ConsumerStorageId storageId =
                new ConsumerStorageId(buffer.readUUID());
        Component displayName =
                Component.Serializer.fromJson(readString(buffer));
        if (displayName == null) {
            throw new IllegalArgumentException(
                    "storage display name is invalid");
        }
        return new ConsumerStorageOption(
                storageId,
                displayName,
                buffer.readBoolean(),
                buffer.readVarLong()
        );
    }

    private static void writeRecipeCatalogRequestPayload(
            FriendlyByteBuf buffer,
            ConsumerRecipeCatalogRequest request
    ) {
        writeRecipeQuery(buffer, request.query());
        buffer.writeVarInt(request.pageSize());
        buffer.writeVarLong(request.cursor());
    }

    private static ConsumerRecipeCatalogRequest
    readRecipeCatalogRequestPayload(FriendlyByteBuf buffer) {
        ConsumerRecipeQuery query = readRecipeQuery(buffer);
        return new ConsumerRecipeCatalogRequest(
                query, buffer.readVarInt(), buffer.readVarLong());
    }

    private static void writeRecipeCatalogPagePayload(
            FriendlyByteBuf buffer,
            ConsumerRecipeCatalogPage page
    ) {
        writeRecipeQuery(buffer, page.query());
        buffer.writeVarLong(page.recipeRevision());
        buffer.writeVarLong(page.cursor());
        writeOptionalLong(buffer, page.nextCursor());
        writeList(buffer, page.candidates(),
                ConsumerApiCodecs::writeRecipeCandidate);
    }

    private static ConsumerRecipeCatalogPage
    readRecipeCatalogPagePayload(FriendlyByteBuf buffer) {
        ConsumerRecipeQuery query = readRecipeQuery(buffer);
        long revision = buffer.readVarLong();
        long cursor = buffer.readVarLong();
        Long next = readOptionalLong(buffer);
        List<ConsumerRecipeCandidate> candidates =
                readList(buffer, ConsumerApiCodecs::readRecipeCandidate);
        return new ConsumerRecipeCatalogPage(
                query, revision, cursor, next, candidates);
    }

    private static void writeRecipeQuery(
            FriendlyByteBuf buffer,
            ConsumerRecipeQuery query
    ) {
        if (query instanceof ConsumerRecipeQuery.All) {
            buffer.writeByte(0);
            return;
        }
        if (query instanceof ConsumerRecipeQuery.ByRecipeId byRecipeId) {
            buffer.writeByte(1);
            buffer.writeResourceLocation(byRecipeId.recipeId());
            return;
        }
        if (query instanceof ConsumerRecipeQuery.ByOutput byOutput) {
            buffer.writeByte(2);
            writeResourceKey(buffer, byOutput.output());
            writeEnum(buffer, byOutput.matchMode());
            return;
        }
        if (query instanceof ConsumerRecipeQuery.ByOutputs byOutputs) {
            buffer.writeByte(3);
            writeList(buffer, byOutputs.outputs(),
                    ConsumerApiCodecs::writeResourceKey);
            writeEnum(buffer, byOutputs.matchMode());
            return;
        }
        ConsumerRecipeQuery.ByProcess byProcess =
                (ConsumerRecipeQuery.ByProcess) query;
        buffer.writeByte(4);
        buffer.writeResourceLocation(byProcess.processId());
    }

    private static ConsumerRecipeQuery readRecipeQuery(
            FriendlyByteBuf buffer
    ) {
        return switch (buffer.readUnsignedByte()) {
            case 0 -> new ConsumerRecipeQuery.All();
            case 1 -> new ConsumerRecipeQuery.ByRecipeId(
                    buffer.readResourceLocation());
            case 2 -> new ConsumerRecipeQuery.ByOutput(
                    readResourceKey(buffer),
                    readEnum(buffer, ResourceMatchMode.class)
            );
            case 3 -> new ConsumerRecipeQuery.ByOutputs(
                    readList(buffer, ConsumerApiCodecs::readResourceKey),
                    readEnum(buffer, ResourceMatchMode.class)
            );
            case 4 -> new ConsumerRecipeQuery.ByProcess(
                    buffer.readResourceLocation());
            default -> throw new IllegalArgumentException(
                    "unknown consumer recipe query");
        };
    }

    private static void writeRecipeCandidate(
            FriendlyByteBuf buffer,
            ConsumerRecipeCandidate candidate
    ) {
        buffer.writeResourceLocation(candidate.recipeId());
        buffer.writeResourceLocation(candidate.processId());
        writeEnum(buffer, candidate.executionKind());
        writeList(buffer, candidate.declaredOutputs(),
                ConsumerApiCodecs::writeResourceKey);
    }

    private static ConsumerRecipeCandidate readRecipeCandidate(
            FriendlyByteBuf buffer
    ) {
        return new ConsumerRecipeCandidate(
                buffer.readResourceLocation(),
                buffer.readResourceLocation(),
                readEnum(buffer, RecipeExecutionKind.class),
                readList(buffer, ConsumerApiCodecs::readResourceKey)
        );
    }

    private static void writeInspectionTicketPayload(
            FriendlyByteBuf buffer,
            ConsumerInspectionTicket ticket
    ) {
        buffer.writeUUID(ticket.ticketId().value());
        buffer.writeUUID(ticket.playerId());
        buffer.writeResourceLocation(ticket.consumerId());
        buffer.writeVarInt(ticket.totalRecipes());
        buffer.writeVarInt(ticket.pageSize());
        buffer.writeVarLong(ticket.snapshotRevision());
    }

    private static ConsumerInspectionTicket readInspectionTicketPayload(
            FriendlyByteBuf buffer
    ) {
        return new ConsumerInspectionTicket(
                new ConsumerInspectionTicketId(buffer.readUUID()),
                buffer.readUUID(),
                buffer.readResourceLocation(),
                buffer.readVarInt(),
                buffer.readVarInt(),
                buffer.readVarLong()
        );
    }

    private static void writeInspectionPagePayload(
            FriendlyByteBuf buffer,
            ConsumerInspectionPage page
    ) {
        buffer.writeUUID(page.ticketId().value());
        buffer.writeVarInt(page.pageIndex());
        writeEnum(buffer, page.state());
        writeOptionalInt(buffer, page.nextPageIndex());
        buffer.writeVarLong(page.snapshotRevision());
        writeList(buffer, page.results(),
                ConsumerApiCodecs::writeCraftability);
    }

    private static ConsumerInspectionPage readInspectionPagePayload(
            FriendlyByteBuf buffer
    ) {
        return new ConsumerInspectionPage(
                new ConsumerInspectionTicketId(buffer.readUUID()),
                buffer.readVarInt(),
                readEnum(buffer, ConsumerInspectionPageState.class),
                readOptionalInt(buffer),
                buffer.readVarLong(),
                readList(buffer, ConsumerApiCodecs::readCraftability)
        );
    }

    private static void writeCraftability(
            FriendlyByteBuf buffer,
            ConsumerCraftability craftability
    ) {
        writeRecipeKey(buffer, craftability.recipe());
        writeEnum(buffer, craftability.status());
        buffer.writeVarLong(craftability.requestedQuantity());
        buffer.writeVarLong(craftability.executableQuantity());
        writeList(buffer, craftability.shortages(),
                ConsumerApiCodecs::writeShortage);
        writeOptional(buffer, craftability.planId(),
                (target, planId) -> target.writeUUID(planId.value()));
        writeList(buffer, craftability.diagnostics(),
                ConsumerApiCodecs::writeDiagnosticPayload);
    }

    private static ConsumerCraftability readCraftability(
            FriendlyByteBuf buffer
    ) {
        ConsumerRecipeKey recipe = readRecipeKey(buffer);
        ConsumerCraftabilityStatus status =
                readEnum(buffer, ConsumerCraftabilityStatus.class);
        long requested = buffer.readVarLong();
        long executable = buffer.readVarLong();
        List<ConsumerResourceShortage> shortages =
                readList(buffer, ConsumerApiCodecs::readShortage);
        ConsumerPlanId planId = readOptional(
                buffer, source -> new ConsumerPlanId(source.readUUID()));
        List<IntegrationDiagnostic> diagnostics =
                readList(buffer, ConsumerApiCodecs::readDiagnosticPayload);
        return new ConsumerCraftability(
                recipe, status, requested, executable,
                shortages, planId, diagnostics);
    }

    private static void writeShortage(
            FriendlyByteBuf buffer,
            ConsumerResourceShortage shortage
    ) {
        writeResourceKey(buffer, shortage.resource());
        buffer.writeVarLong(shortage.required());
        buffer.writeVarLong(shortage.available());
    }

    private static ConsumerResourceShortage readShortage(
            FriendlyByteBuf buffer
    ) {
        return new ConsumerResourceShortage(
                readResourceKey(buffer),
                buffer.readVarLong(),
                buffer.readVarLong()
        );
    }

    private static void writeOrderRequestPayload(
            FriendlyByteBuf buffer,
            ConsumerOrderRequest request
    ) {
        buffer.writeResourceLocation(request.consumerId());
        buffer.writeUUID(request.externalRequestId().value());
        buffer.writeUUID(request.planId().value());
    }

    private static ConsumerOrderRequest readOrderRequestPayload(
            FriendlyByteBuf buffer
    ) {
        return new ConsumerOrderRequest(
                buffer.readResourceLocation(),
                new ConsumerExternalRequestId(buffer.readUUID()),
                new ConsumerPlanId(buffer.readUUID())
        );
    }

    private static void writeOrderReceiptPayload(
            FriendlyByteBuf buffer,
            ConsumerOrderReceipt receipt
    ) {
        buffer.writeUUID(receipt.orderId().value());
        writeEnum(buffer, receipt.state());
    }

    private static ConsumerOrderReceipt readOrderReceiptPayload(
            FriendlyByteBuf buffer
    ) {
        return new ConsumerOrderReceipt(
                new ConsumerOrderId(buffer.readUUID()),
                readEnum(buffer, ConsumerOrderState.class)
        );
    }

    private static void writeBatchOrderRequestPayload(
            FriendlyByteBuf buffer,
            ConsumerBatchOrderRequest request
    ) {
        buffer.writeResourceLocation(request.consumerId());
        buffer.writeUUID(request.batchRequestId().value());
        writeList(buffer, request.orders(),
                ConsumerApiCodecs::writeOrderRequestPayload);
    }

    private static ConsumerBatchOrderRequest readBatchOrderRequestPayload(
            FriendlyByteBuf buffer
    ) {
        return new ConsumerBatchOrderRequest(
                buffer.readResourceLocation(),
                new ConsumerBatchRequestId(buffer.readUUID()),
                readList(buffer, ConsumerApiCodecs::readOrderRequestPayload)
        );
    }

    private static void writeBatchOrderReceiptPayload(
            FriendlyByteBuf buffer,
            ConsumerBatchOrderReceipt receipt
    ) {
        buffer.writeUUID(receipt.batchRequestId().value());
        writeList(buffer, receipt.entries(),
                ConsumerApiCodecs::writeBatchOrderEntry);
    }

    private static ConsumerBatchOrderReceipt readBatchOrderReceiptPayload(
            FriendlyByteBuf buffer
    ) {
        return new ConsumerBatchOrderReceipt(
                new ConsumerBatchRequestId(buffer.readUUID()),
                readList(buffer, ConsumerApiCodecs::readBatchOrderEntry)
        );
    }

    private static void writeBatchOrderEntry(
            FriendlyByteBuf buffer,
            ConsumerBatchOrderEntry entry
    ) {
        buffer.writeUUID(entry.externalRequestId().value());
        writeResult(buffer, entry.result(),
                ConsumerApiCodecs::writeOrderReceiptPayload);
    }

    private static ConsumerBatchOrderEntry readBatchOrderEntry(
            FriendlyByteBuf buffer
    ) {
        ConsumerExternalRequestId requestId =
                new ConsumerExternalRequestId(buffer.readUUID());
        ConsumerResult<ConsumerOrderReceipt> result = readResult(
                buffer, ConsumerApiCodecs::readOrderReceiptPayload);
        return new ConsumerBatchOrderEntry(requestId, result);
    }

    private static void writeOrderStatusPayload(
            FriendlyByteBuf buffer,
            ConsumerOrderStatus status
    ) {
        buffer.writeUUID(status.orderId().value());
        buffer.writeResourceLocation(status.consumerId());
        buffer.writeResourceLocation(status.recipeId());
        writeEnum(buffer, status.state());
        buffer.writeVarLong(status.completedOperations());
        buffer.writeVarLong(status.totalOperations());
        buffer.writeVarLong(status.runningOperations());
        writeList(buffer, status.outputs(),
                ConsumerApiCodecs::writeOutputResult);
        writeOptionalEnum(buffer, status.failureReason());
        writeList(buffer, status.diagnostics(),
                ConsumerApiCodecs::writeDiagnosticPayload);
    }

    private static ConsumerOrderStatus readOrderStatusPayload(
            FriendlyByteBuf buffer
    ) {
        ConsumerOrderId orderId = new ConsumerOrderId(buffer.readUUID());
        ResourceLocation consumerId = buffer.readResourceLocation();
        ResourceLocation recipeId = buffer.readResourceLocation();
        ConsumerOrderState state = readEnum(buffer, ConsumerOrderState.class);
        long completed = buffer.readVarLong();
        long total = buffer.readVarLong();
        long running = buffer.readVarLong();
        List<ConsumerOutputResult> outputs =
                readList(buffer, ConsumerApiCodecs::readOutputResult);
        FailureReason failure = readOptionalEnum(
                buffer, FailureReason.class);
        List<IntegrationDiagnostic> diagnostics =
                readList(buffer, ConsumerApiCodecs::readDiagnosticPayload);
        return new ConsumerOrderStatus(
                orderId, consumerId, recipeId, state,
                completed, total, running, outputs, failure, diagnostics);
    }

    private static void writeOutputResult(
            FriendlyByteBuf buffer,
            ConsumerOutputResult output
    ) {
        writeResourceKey(buffer, output.resource());
        buffer.writeVarLong(output.amount());
        writeEnum(buffer, output.destination());
        buffer.writeBoolean(output.delivered());
    }

    private static ConsumerOutputResult readOutputResult(
            FriendlyByteBuf buffer
    ) {
        return new ConsumerOutputResult(
                readResourceKey(buffer),
                buffer.readVarLong(),
                readEnum(buffer, ConsumerOutputDestination.class),
                buffer.readBoolean()
        );
    }

    private static void writeCancelResultPayload(
            FriendlyByteBuf buffer,
            ConsumerCancelResult result
    ) {
        buffer.writeUUID(result.orderId().value());
        writeEnum(buffer, result.state());
    }

    private static ConsumerCancelResult readCancelResultPayload(
            FriendlyByteBuf buffer
    ) {
        return new ConsumerCancelResult(
                new ConsumerOrderId(buffer.readUUID()),
                readEnum(buffer, ConsumerOrderState.class)
        );
    }

    private static void writeResourceKey(
            FriendlyByteBuf buffer,
            IntegrationResourceKey resource
    ) {
        writeEnum(buffer, resource.kind());
        buffer.writeResourceLocation(resource.id());
        CompoundTag identity = resource.identity();
        validateNbt(identity);
        buffer.writeNbt(identity);
    }

    private static IntegrationResourceKey readResourceKey(
            FriendlyByteBuf buffer
    ) {
        IntegrationResourceKey.ResourceKind kind = readEnum(
                buffer, IntegrationResourceKey.ResourceKind.class);
        ResourceLocation id = buffer.readResourceLocation();
        CompoundTag identity = buffer.readNbt();
        CompoundTag safeIdentity = identity == null
                ? new CompoundTag() : identity;
        validateNbt(safeIdentity);
        return new IntegrationResourceKey(kind, id, safeIdentity);
    }

    private static void writeDiagnosticPayload(
            FriendlyByteBuf buffer,
            IntegrationDiagnostic diagnostic
    ) {
        writeEnum(buffer, diagnostic.code());
        writeEnum(buffer, diagnostic.severity());
        writeOptional(buffer, diagnostic.sourceId(),
                FriendlyByteBuf::writeResourceLocation);
        writeStringMap(buffer, diagnostic.attributes(),
                ConsumerApiCodecs::writeString);
    }

    private static void writeConsumerDiagnosticPayload(
            FriendlyByteBuf buffer,
            ConsumerDiagnostic diagnostic
    ) {
        writeEnum(buffer, diagnostic.code());
        writeEnum(buffer, diagnostic.severity());
        writeString(buffer, diagnostic.detail());
        buffer.writeBoolean(diagnostic.retryable());
    }

    private static ConsumerDiagnostic readConsumerDiagnosticPayload(
            FriendlyByteBuf buffer
    ) {
        return new ConsumerDiagnostic(
                readEnum(buffer, ConsumerDiagnosticCode.class),
                readEnum(buffer, IntegrationSeverity.class),
                readString(buffer),
                buffer.readBoolean()
        );
    }

    private static IntegrationDiagnostic readDiagnosticPayload(
            FriendlyByteBuf buffer
    ) {
        IntegrationDiagnosticCode code = readEnum(
                buffer, IntegrationDiagnosticCode.class);
        IntegrationSeverity severity = readEnum(
                buffer, IntegrationSeverity.class);
        ResourceLocation source = readOptional(
                buffer, FriendlyByteBuf::readResourceLocation);
        Map<String, String> attributes = readStringMap(
                buffer, ConsumerApiCodecs::readString);
        return new IntegrationDiagnostic(
                code, severity, source, attributes);
    }

    private static void validateNbt(CompoundTag tag) {
        validateNbt(tag, 0, new int[]{0});
    }

    private static void validateNbt(
            CompoundTag tag,
            int depth,
            int[] nodes
    ) {
        if (depth > MAX_NBT_DEPTH || ++nodes[0] > MAX_NBT_NODES) {
            throw new IllegalArgumentException("NBT payload is too complex");
        }
        for (String key : tag.getAllKeys()) {
            if (key.length() > MAX_STRING_LENGTH) {
                throw new IllegalArgumentException("NBT key is too long");
            }
            validateTag(tag.get(key), depth + 1, nodes);
        }
    }

    private static void validateTag(
            @Nullable Object value,
            int depth,
            int[] nodes
    ) {
        if (value == null) return;
        if (depth > MAX_NBT_DEPTH || ++nodes[0] > MAX_NBT_NODES) {
            throw new IllegalArgumentException("NBT payload is too complex");
        }
        if (value instanceof CompoundTag compound) {
            for (String key : compound.getAllKeys()) {
                if (key.length() > MAX_STRING_LENGTH) {
                    throw new IllegalArgumentException("NBT key is too long");
                }
                validateTag(compound.get(key), depth + 1, nodes);
            }
            return;
        }
        if (value instanceof ListTag list) {
            for (int index = 0; index < list.size(); index++) {
                validateTag(list.get(index), depth + 1, nodes);
            }
        }
    }

    private static void writeVersion(FriendlyByteBuf buffer) {
        buffer.writeVarInt(WIRE_VERSION);
    }

    private static void readVersion(FriendlyByteBuf buffer) {
        int version = buffer.readVarInt();
        if (version != WIRE_VERSION) {
            throw new IllegalArgumentException(
                    "unsupported consumer wire version: " + version);
        }
    }

    private static void writeString(
            FriendlyByteBuf buffer,
            String value
    ) {
        if (value.length() > MAX_STRING_LENGTH) {
            throw new IllegalArgumentException("string is too long");
        }
        buffer.writeUtf(value, MAX_STRING_LENGTH);
    }

    private static String readString(FriendlyByteBuf buffer) {
        return buffer.readUtf(MAX_STRING_LENGTH);
    }

    private static <E extends Enum<E>> void writeEnum(
            FriendlyByteBuf buffer,
            E value
    ) {
        writeString(buffer, value.name());
    }

    private static <E extends Enum<E>> E readEnum(
            FriendlyByteBuf buffer,
            Class<E> type
    ) {
        String name = readString(buffer);
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "unknown " + type.getSimpleName() + ": " + name,
                    exception);
        }
    }

    private static <T> void writeOptional(
            FriendlyByteBuf buffer,
            @Nullable T value,
            BiConsumer<FriendlyByteBuf, T> writer
    ) {
        buffer.writeBoolean(value != null);
        if (value != null) writer.accept(buffer, value);
    }

    @Nullable
    private static <T> T readOptional(
            FriendlyByteBuf buffer,
            Function<FriendlyByteBuf, T> reader
    ) {
        return buffer.readBoolean() ? reader.apply(buffer) : null;
    }

    private static void writeOptionalLong(
            FriendlyByteBuf buffer,
            @Nullable Long value
    ) {
        buffer.writeBoolean(value != null);
        if (value != null) buffer.writeVarLong(value);
    }

    @Nullable
    private static Long readOptionalLong(FriendlyByteBuf buffer) {
        return buffer.readBoolean() ? buffer.readVarLong() : null;
    }

    private static void writeOptionalInt(
            FriendlyByteBuf buffer,
            @Nullable Integer value
    ) {
        buffer.writeBoolean(value != null);
        if (value != null) buffer.writeVarInt(value);
    }

    @Nullable
    private static Integer readOptionalInt(FriendlyByteBuf buffer) {
        return buffer.readBoolean() ? buffer.readVarInt() : null;
    }

    private static <E extends Enum<E>> void writeOptionalEnum(
            FriendlyByteBuf buffer,
            @Nullable E value
    ) {
        writeOptional(buffer, value, ConsumerApiCodecs::writeEnum);
    }

    @Nullable
    private static <E extends Enum<E>> E readOptionalEnum(
            FriendlyByteBuf buffer,
            Class<E> type
    ) {
        return readOptional(
                buffer, source -> readEnum(source, type));
    }

    private static <T> void writeList(
            FriendlyByteBuf buffer,
            List<T> values,
            BiConsumer<FriendlyByteBuf, T> writer
    ) {
        if (values.size() > MAX_LIST_SIZE) {
            throw new IllegalArgumentException("list is too large");
        }
        buffer.writeVarInt(values.size());
        for (T value : values) writer.accept(buffer, value);
    }

    private static <T> List<T> readList(
            FriendlyByteBuf buffer,
            Function<FriendlyByteBuf, T> reader
    ) {
        int size = readSize(buffer);
        List<T> values = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            values.add(reader.apply(buffer));
        }
        return List.copyOf(values);
    }

    private static <T, C extends Collection<T>> void writeCollection(
            FriendlyByteBuf buffer,
            C values,
            BiConsumer<FriendlyByteBuf, T> writer
    ) {
        if (values.size() > MAX_LIST_SIZE) {
            throw new IllegalArgumentException("collection is too large");
        }
        buffer.writeVarInt(values.size());
        for (T value : values) writer.accept(buffer, value);
    }

    private static <T, C extends Collection<T>> C readCollection(
            FriendlyByteBuf buffer,
            Function<Integer, C> factory,
            Function<FriendlyByteBuf, T> reader
    ) {
        int size = readSize(buffer);
        C values = factory.apply(size);
        for (int index = 0; index < size; index++) {
            values.add(reader.apply(buffer));
        }
        return values;
    }

    private static <T> void writeStringMap(
            FriendlyByteBuf buffer,
            Map<String, T> values,
            BiConsumer<FriendlyByteBuf, T> writer
    ) {
        if (values.size() > MAX_MAP_SIZE) {
            throw new IllegalArgumentException("map is too large");
        }
        buffer.writeVarInt(values.size());
        values.forEach((key, value) -> {
            writeString(buffer, key);
            writer.accept(buffer, value);
        });
    }

    private static <T> Map<String, T> readStringMap(
            FriendlyByteBuf buffer,
            Function<FriendlyByteBuf, T> reader
    ) {
        int size = buffer.readVarInt();
        if (size < 0 || size > MAX_MAP_SIZE) {
            throw new IllegalArgumentException("invalid map size: " + size);
        }
        Map<String, T> values = new LinkedHashMap<>();
        for (int index = 0; index < size; index++) {
            values.put(readString(buffer), reader.apply(buffer));
        }
        return Map.copyOf(values);
    }

    private static int readSize(FriendlyByteBuf buffer) {
        int size = buffer.readVarInt();
        if (size < 0 || size > MAX_LIST_SIZE) {
            throw new IllegalArgumentException(
                    "invalid collection size: " + size);
        }
        return size;
    }
}
