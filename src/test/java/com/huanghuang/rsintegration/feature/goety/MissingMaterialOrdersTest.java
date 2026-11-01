package com.huanghuang.rsintegration.feature.goety;

import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.api.consumer.*;
import com.huanghuang.rsintegration.api.recipe.RecipeExecutionKind;
import com.huanghuang.rsintegration.api.storage.RSIStorageApi;
import com.huanghuang.rsintegration.api.storage.RSIStorageServices;
import com.huanghuang.rsintegration.api.storage.StorageLink;
import com.huanghuang.rsintegration.feature.goety.integration.MissingMaterialOrders;
import com.huanghuang.rsintegration.feature.goety.integration.RsiStorageLink;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class MissingMaterialOrdersTest extends BootstrapTest {
    private static final ResourceLocation RECIPE = ResourceLocation.fromNamespaceAndPath("test", "material");
    private static final ResourceLocation CONSUMER = ResourceLocation.fromNamespaceAndPath("rs_integration", "brew_materials");
    private final ConsumerInspectionTicketId ticket = new ConsumerInspectionTicketId(UUID.randomUUID());
    private final ConsumerOrderId order = new ConsumerOrderId(UUID.randomUUID());
    private ServerPlayer player;
    private Inventory inventory;
    private StorageLink link;
    private RSIStorageApi storage;
    private RSICraftConsumerApi api;
    private MissingMaterialOrders orders;

    @BeforeEach
    void setupServices() {
        player = mock(ServerPlayer.class);
        inventory = new Inventory(player);
        when(player.getInventory()).thenReturn(inventory);
        when(player.getUUID()).thenReturn(UUID.randomUUID());
        link = mock(StorageLink.class);
        storage = mock(RSIStorageApi.class);
        when(storage.defaultStorage(player)).thenReturn(Optional.of(link));
        when(storage.isCurrentDefault(player, link)).thenReturn(true);
        RSIStorageServices.install(storage);
        api = mock(RSICraftConsumerApi.class);
        when(api.state()).thenReturn(ConsumerServiceState.READY);
        when(api.capabilities()).thenReturn(new ConsumerCapabilities(
                32, 64, 4, 64, 1024, 1024, 1024, 32, 4, 1200, 1200, 1200));
        RSIConsumerServices.install(api);
        orders = new MissingMaterialOrders();
    }

    @AfterEach
    void cleanupServices() {
        orders.close(player);
        RSIStorageServices.clear();
        RSIConsumerServices.clear();
    }

    @Test
    void storageIsResolvedFromThePlayersCurrentDefaultWithoutACauldronBinding() {
        assertSame(link, RsiStorageLink.resolve(player).orElseThrow());
        verify(storage).defaultStorage(player);
        verify(storage, never()).resolve(any(), any(), any());
    }

    @Test
    void stockedMaterialsDoNotRequireTheRecursiveCraftService() {
        inventory.items.set(0, new ItemStack(Items.DIAMOND, 2));
        RSIConsumerServices.clear();
        orders.start(player, List.of(new ItemStack(Items.DIAMOND, 2)), link);
        assertTrue(orders.succeeded());
        assertFalse(orders.busy());
        verifyNoInteractions(api);
    }

    @Test
    void successfulPreviousAttemptDoesNotMakeANewRejectedAttemptSucceed() {
        inventory.items.set(0, new ItemStack(Items.DIAMOND));
        orders.start(player, List.of(new ItemStack(Items.DIAMOND)), link);
        assertTrue(orders.succeeded());
        inventory.items.set(0, ItemStack.EMPTY);
        RSIConsumerServices.clear();
        orders.start(player, List.of(new ItemStack(Items.DIAMOND)), link);
        assertFalse(orders.succeeded());
        assertFalse(orders.busy());
    }

    @Test
    void recursiveCraftOnlyFinishesAfterTheOrderSucceedsAndMaterialsArrive() {
        configureCraftableDiamond();
        when(api.status(player, CONSUMER, order)).thenReturn(
                status(ConsumerOrderState.RUNNING), status(ConsumerOrderState.SUCCEEDED));
        orders.start(player, List.of(new ItemStack(Items.DIAMOND, 2)), link);
        orders.tick(player);
        var inspection = ArgumentCaptor.forClass(ConsumerInspectionRequest.class);
        verify(api).openInspection(eq(player), inspection.capture());
        assertTrue(inspection.getValue().options().includeRecursiveSummary());
        assertEquals(new ConsumerRequestAmount.OutputUnits(2), inspection.getValue().recipes().get(0).amount());
        orders.tick(player);
        orders.tick(player);
        assertTrue(orders.busy());
        assertFalse(orders.succeeded());
        inventory.items.set(0, new ItemStack(Items.DIAMOND, 2));
        orders.tick(player);
        assertFalse(orders.busy());
        assertTrue(orders.succeeded());
        verify(api).closeInspection(player, ticket);
    }

    @Test
    void successfulOrderWithoutEnoughDeliveredMaterialsCannotTriggerBrewing() {
        configureCraftableDiamond();
        when(api.status(player, CONSUMER, order)).thenReturn(status(ConsumerOrderState.SUCCEEDED));
        orders.start(player, List.of(new ItemStack(Items.DIAMOND, 2)), link);
        orders.tick(player);
        orders.tick(player);
        inventory.items.set(0, new ItemStack(Items.DIAMOND));
        orders.tick(player);
        assertTrue(orders.busy());
        assertFalse(orders.succeeded());
    }

    @Test
    void earlierRecursiveOutputsCanSatisfyLaterMaterialsWithoutAnotherOrder() {
        configureCraftableDiamond();
        when(api.status(player, CONSUMER, order)).thenReturn(status(ConsumerOrderState.SUCCEEDED));
        orders.start(player, List.of(new ItemStack(Items.DIAMOND), new ItemStack(Items.STICK)), link);
        orders.tick(player);
        orders.tick(player);
        inventory.items.set(0, new ItemStack(Items.DIAMOND));
        inventory.items.set(1, new ItemStack(Items.STICK));
        orders.tick(player);
        assertFalse(orders.succeeded());
        orders.tick(player);
        assertTrue(orders.succeeded());
        verify(api, times(1)).submit(eq(player), any());
    }

    @Test
    void failedOrdersCannotTriggerTheAutomaticBrewingContinuation() {
        configureCraftableDiamond();
        when(api.status(player, CONSUMER, order)).thenReturn(status(ConsumerOrderState.FAILED));
        orders.start(player, List.of(new ItemStack(Items.DIAMOND)), link);
        orders.tick(player);
        orders.tick(player);
        orders.tick(player);
        assertFalse(orders.busy());
        assertFalse(orders.succeeded());
        assertNotNull(orders.takeMessage());
    }

    @Test
    void closingOrChangingTheNetworkCancelsTheOutstandingOrder() {
        configureCraftableDiamond();
        orders.start(player, List.of(new ItemStack(Items.DIAMOND)), link);
        orders.tick(player);
        orders.tick(player);
        when(storage.isCurrentDefault(player, link)).thenReturn(false);
        orders.tick(player);
        assertFalse(orders.busy());
        assertFalse(orders.succeeded());
        verify(api).cancel(eq(player), eq(CONSUMER), eq(order), any());
        orders.close(player);
        verify(api, times(1)).cancel(eq(player), eq(CONSUMER), eq(order), any());
    }

    @Test
    void alternativeOutputsOfOneRecipeAreInspectedWithoutDuplicateRecipeIds() {
        var diamond = IntegrationResourceKey.item(new ItemStack(Items.DIAMOND));
        var emerald = IntegrationResourceKey.item(new ItemStack(Items.EMERALD));
        when(api.findRecipes(eq(player), any())).thenAnswer(call -> {
            ConsumerRecipeCatalogRequest request = call.getArgument(1);
            return ConsumerResult.success(new ConsumerRecipeCatalogPage(request.query(), 1, 0, null,
                    List.of(new ConsumerRecipeCandidate(RECIPE, RECIPE, RecipeExecutionKind.STANDARD_IO,
                            List.of(diamond, emerald)))));
        });
        when(api.openInspection(eq(player), any())).thenAnswer(call -> {
            ConsumerInspectionRequest request = call.getArgument(1);
            assertEquals(request.recipes().size(),
                    request.recipes().stream().map(ConsumerRecipeKey::recipeId).distinct().count());
            return ConsumerResult.success(new ConsumerInspectionTicket(ticket, player.getUUID(), CONSUMER,
                    request.recipes().size(), 32, 1));
        });
        orders.startAlternatives(player, List.of(new MissingMaterialOrders.MaterialNeed(
                List.of(new ItemStack(Items.DIAMOND), new ItemStack(Items.EMERALD)), 1)), link);
        orders.tick(player);
        assertTrue(orders.busy());
        verify(api).openInspection(eq(player), any());
    }

    private void configureCraftableDiamond() {
        var output = IntegrationResourceKey.item(new ItemStack(Items.DIAMOND));
        when(api.findRecipes(eq(player), any())).thenAnswer(call -> {
            ConsumerRecipeCatalogRequest request = call.getArgument(1);
            return ConsumerResult.success(new ConsumerRecipeCatalogPage(request.query(), 1, 0, null,
                    List.of(new ConsumerRecipeCandidate(RECIPE, RECIPE, RecipeExecutionKind.STANDARD_IO, List.of(output)))));
        });
        var key = new AtomicReference<ConsumerRecipeKey>();
        when(api.openInspection(eq(player), any())).thenAnswer(call -> {
            ConsumerInspectionRequest request = call.getArgument(1);
            key.set(request.recipes().get(0));
            return ConsumerResult.success(new ConsumerInspectionTicket(ticket, player.getUUID(), CONSUMER, 1, 32, 1));
        });
        when(api.nextInspectionPage(player, ticket)).thenAnswer(call -> ConsumerResult.success(
                new ConsumerInspectionPage(ticket, 0, ConsumerInspectionPageState.COMPLETE, null, 1,
                        List.of(new ConsumerCraftability(key.get(), ConsumerCraftabilityStatus.READY, 2, 2, List.of(),
                                new ConsumerPlanId(UUID.randomUUID()), List.of())))));
        when(api.submit(eq(player), any())).thenReturn(
                ConsumerResult.success(new ConsumerOrderReceipt(order, ConsumerOrderState.ACCEPTED)));
    }

    @Test
    void confirmedRecipeBranchAndPreferencesReachTheRecursiveInspection() {
        configureCraftableDiamond();
        var other = ResourceLocation.fromNamespaceAndPath("test", "other_material");
        var output = IntegrationResourceKey.item(new ItemStack(Items.DIAMOND));
        when(api.findRecipes(eq(player), any())).thenAnswer(call -> {
            ConsumerRecipeCatalogRequest request = call.getArgument(1);
            return ConsumerResult.success(new ConsumerRecipeCatalogPage(request.query(), 1, 0, null,
                    List.of(new ConsumerRecipeCandidate(other, other, RecipeExecutionKind.STANDARD_IO, List.of(output)),
                            new ConsumerRecipeCandidate(RECIPE, RECIPE, RecipeExecutionKind.STANDARD_IO, List.of(output)))));
        });
        var preferences = new ConsumerPlanningPreferences(Map.of("minecraft:diamond", RECIPE),
                Map.of(), Set.of(other));
        orders.startAlternatives(player, List.of(new MissingMaterialOrders.MaterialNeed(
                List.of(new ItemStack(Items.DIAMOND)), 1)), link, preferences);
        orders.tick(player);
        var inspection = ArgumentCaptor.forClass(ConsumerInspectionRequest.class);
        verify(api).openInspection(eq(player), inspection.capture());
        assertEquals(1, inspection.getValue().recipes().size());
        assertEquals(RECIPE, inspection.getValue().recipes().get(0).recipeId());
        assertEquals(preferences, inspection.getValue().recipes().get(0).preferences());
    }

    private ConsumerResult<ConsumerOrderStatus> status(ConsumerOrderState state) {
        return ConsumerResult.success(new ConsumerOrderStatus(order, CONSUMER, RECIPE, state,
                state == ConsumerOrderState.SUCCEEDED ? 1 : 0, 1, 0, List.of(), null, List.of()));
    }
}
