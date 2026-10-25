package com.huanghuang.rsintegration.api.consumer;

import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.api.ResourceMatchMode;
import com.huanghuang.rsintegration.api.diagnostic.IntegrationDiagnostic;
import com.huanghuang.rsintegration.api.diagnostic.IntegrationDiagnosticCode;
import com.huanghuang.rsintegration.api.diagnostic.IntegrationSeverity;
import com.huanghuang.rsintegration.api.execution.FailureReason;
import com.huanghuang.rsintegration.api.recipe.RecipeExecutionKind;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsumerApiCodecsTest extends BootstrapTest {
    @Test
    void inspectionRequestRoundTripsAllNestedVariants() {
        IntegrationResourceKey diamond = new IntegrationResourceKey(
                IntegrationResourceKey.ResourceKind.ITEM,
                BuiltInRegistries.ITEM.getKey(Items.DIAMOND),
                tagged("exact")
        );
        ConsumerInspectionRequest expected = new ConsumerInspectionRequest(
                resource("consumer"),
                new ConsumerInspectionRequestId(UUID.randomUUID()),
                List.of(
                        new ConsumerRecipeKey(
                                resource("units"),
                                diamond,
                                new ConsumerRequestAmount.OutputUnits(7L),
                                new ConsumerPlanningPreferences(
                                        Map.of("slot", resource("forced")),
                                        Map.of("material", diamond),
                                        Set.of(resource("excluded"))
                                )
                        ),
                        new ConsumerRecipeKey(
                                resource("executions"),
                                null,
                                new ConsumerRequestAmount.Executions(3),
                                ConsumerPlanningPreferences.empty()
                        )
                ),
                new ConsumerMachineSelector.Preferred(
                        LevelLocation.OVERWORLD,
                        new BlockPos(1, 64, 2),
                        true
                ),
                new ConsumerStorageSelector.Explicit(
                        new ConsumerStorageId(UUID.randomUUID())),
                ConsumerOutputDestination.PLAYER_INVENTORY,
                new ConsumerInspectionOptions(32, true)
        );

        FriendlyByteBuf buffer = buffer();
        ConsumerApiCodecs.writeInspectionRequest(buffer, expected);

        assertEquals(expected, ConsumerApiCodecs.readInspectionRequest(buffer));
    }

    @Test
    void inspectionPageRoundTripsReadyAndShortageResults() {
        ConsumerInspectionTicketId ticketId =
                new ConsumerInspectionTicketId(UUID.randomUUID());
        ConsumerRecipeKey recipe = new ConsumerRecipeKey(
                resource("recipe"),
                null,
                new ConsumerRequestAmount.OutputUnits(2L),
                ConsumerPlanningPreferences.empty()
        );
        ConsumerInspectionPage expected = new ConsumerInspectionPage(
                ticketId,
                0,
                ConsumerInspectionPageState.COMPLETE,
                null,
                9L,
                List.of(
                        new ConsumerCraftability(
                                recipe,
                                ConsumerCraftabilityStatus.READY,
                                2L,
                                2L,
                                List.of(),
                                new ConsumerPlanId(UUID.randomUUID()),
                                List.of()
                        ),
                        new ConsumerCraftability(
                                recipe,
                                ConsumerCraftabilityStatus.MISSING_MATERIALS,
                                2L,
                                0L,
                                List.of(new ConsumerResourceShortage(
                                        itemResource("missing"),
                                        4L,
                                        1L
                                )),
                                null,
                                List.of(integrationDiagnostic())
                        )
                )
        );

        FriendlyByteBuf buffer = buffer();
        ConsumerApiCodecs.writeInspectionPage(buffer, expected);

        assertEquals(expected, ConsumerApiCodecs.readInspectionPage(buffer));
    }

    @Test
    void resultWrappingOrderStatusRoundTrips() {
        ConsumerOrderStatus status = new ConsumerOrderStatus(
                new ConsumerOrderId(UUID.randomUUID()),
                resource("consumer"),
                resource("recipe"),
                ConsumerOrderState.DELIVERING,
                2L,
                5L,
                1L,
                List.of(new ConsumerOutputResult(
                        itemResource("output"),
                        4L,
                        ConsumerOutputDestination.RS_NETWORK,
                        false
                )),
                FailureReason.TIMEOUT,
                List.of(integrationDiagnostic())
        );
        ConsumerResult<ConsumerOrderStatus> expected =
                ConsumerResult.success(status);

        FriendlyByteBuf buffer = buffer();
        ConsumerApiCodecs.writeResult(
                buffer, expected, ConsumerApiCodecs::writeOrderStatus);

        assertEquals(
                expected,
                ConsumerApiCodecs.readResult(
                        buffer, ConsumerApiCodecs::readOrderStatus)
        );
    }

    @Test
    void catalogAndBatchPayloadsPreserveOrdering() {
        ConsumerRecipeCatalogPage catalog = new ConsumerRecipeCatalogPage(
                new ConsumerRecipeQuery.ByOutputs(
                        List.of(itemResource("food")),
                        ResourceMatchMode.EXACT_IDENTITY
                ),
                3L,
                0L,
                1L,
                List.of(new ConsumerRecipeCandidate(
                        resource("first"),
                        resource("process"),
                        RecipeExecutionKind.STANDARD_IO,
                        List.of(itemResource("food"))
                ))
        );
        ConsumerBatchOrderRequest batch = new ConsumerBatchOrderRequest(
                resource("consumer"),
                new ConsumerBatchRequestId(UUID.randomUUID()),
                List.of(order(), order())
        );

        FriendlyByteBuf catalogBuffer = buffer();
        ConsumerApiCodecs.writeRecipeCatalogPage(catalogBuffer, catalog);
        FriendlyByteBuf batchBuffer = buffer();
        ConsumerApiCodecs.writeBatchOrderRequest(batchBuffer, batch);

        assertEquals(
                catalog,
                ConsumerApiCodecs.readRecipeCatalogPage(catalogBuffer)
        );
        assertEquals(
                batch,
                ConsumerApiCodecs.readBatchOrderRequest(batchBuffer)
        );
    }

    @Test
    void unknownWireVersionIsRejectedBeforePayloadDecode() {
        FriendlyByteBuf buffer = buffer();
        buffer.writeVarInt(ConsumerApiCodecs.WIRE_VERSION + 1);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> ConsumerApiCodecs.readAck(buffer)
        );

        assertTrue(failure.getMessage().contains("wire version"));
    }

    private static ConsumerOrderRequest order() {
        return new ConsumerOrderRequest(
                resource("consumer"),
                new ConsumerExternalRequestId(UUID.randomUUID()),
                new ConsumerPlanId(UUID.randomUUID())
        );
    }

    private static IntegrationResourceKey itemResource(String path) {
        return new IntegrationResourceKey(
                IntegrationResourceKey.ResourceKind.ITEM,
                resource(path),
                new CompoundTag()
        );
    }

    private static IntegrationDiagnostic integrationDiagnostic() {
        return new IntegrationDiagnostic(
                IntegrationDiagnosticCode.SNAPSHOT_STALE,
                IntegrationSeverity.WARNING,
                resource("source"),
                Map.of("reason", "test")
        );
    }

    private static CompoundTag tagged(String value) {
        CompoundTag tag = new CompoundTag();
        tag.putString("variant", value);
        return tag;
    }

    private static FriendlyByteBuf buffer() {
        return new FriendlyByteBuf(Unpooled.buffer());
    }

    private static ResourceLocation resource(String path) {
        return new ResourceLocation("test", path);
    }

    private static final class LevelLocation {
        private static final ResourceLocation OVERWORLD =
                new ResourceLocation("minecraft", "overworld");

        private LevelLocation() {}
    }
}
