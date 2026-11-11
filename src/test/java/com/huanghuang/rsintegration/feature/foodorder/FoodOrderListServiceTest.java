package com.huanghuang.rsintegration.feature.foodorder;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.api.consumer.RSICraftConsumerApi;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.MaterialSources;
import com.huanghuang.rsintegration.crafting.RecipeIndex;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoints;
import com.huanghuang.rsintegration.feature.foodorder.network.FoodOrderListPacket;
import com.huanghuang.rsintegration.storage.StorageRestockSupport;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class FoodOrderListServiceTest extends BootstrapTest {
    private final AtomicLong time = new AtomicLong(1);
    private final List<FoodOrderSnapshot> sent = new ArrayList<>();
    private MockedStatic<FoodOrderFoods> foods;
    private MockedStatic<FoodOrderService> orders;
    private MockedStatic<RecipeIndex> recipes;
    private MockedStatic<RSICraftConsumerApi> api;
    private MockedStatic<StorageRestockSupport> storage;
    private MockedStatic<CraftStorageEndpoints> endpoints;
    private MockedStatic<MaterialSources> materials;
    private MockedStatic<FoodOrderListPacket> packets;
    private MinecraftServer server;
    private ServerPlayer player;
    private ServerLevel level;

    @BeforeEach
    void setUp() {
        CommentedConfig config = CommentedConfig.inMemory();
        RSIntegrationConfig.COMMON_SPEC.correct(config);
        RSIntegrationConfig.COMMON_SPEC.setConfig(config);
        server = mock(MinecraftServer.class);
        level = mock(ServerLevel.class);
        player = mock(ServerPlayer.class);
        PlayerList players = mock(PlayerList.class);
        UUID playerId = UUID.randomUUID();
        when(server.overworld()).thenReturn(level);
        when(server.getPlayerList()).thenReturn(players);
        when(level.getGameTime()).thenAnswer(ignored -> time.get());
        when(player.serverLevel()).thenReturn(level);
        when(player.getUUID()).thenReturn(playerId);
        when(players.getPlayer(playerId)).thenReturn(player);
        foods = mockStatic(FoodOrderFoods.class);
        foods.when(() -> FoodOrderFoods.uneaten(player)).thenReturn(List.of(Items.APPLE, Items.CARROT));
        orders = mockStatic(FoodOrderService.class);
        orders.when(() -> FoodOrderService.holding(player)).thenReturn(true);
        recipes = mockStatic(RecipeIndex.class);
        recipes.when(() -> RecipeIndex.get(level)).thenReturn(Map.of());
        api = mockStatic(RSICraftConsumerApi.class);
        api.when(RSICraftConsumerApi::get).thenReturn(Optional.empty());
        storage = mockStatic(StorageRestockSupport.class);
        endpoints = mockStatic(CraftStorageEndpoints.class);
        materials = mockStatic(MaterialSources.class);
        materials.when(() -> MaterialSources.countInventory(player)).thenReturn(Map.of());
        packets = mockStatic(FoodOrderListPacket.class);
        packets.when(() -> FoodOrderListPacket.send(eq(player), any(FoodOrderSnapshot.class)))
                .thenAnswer(call -> {
                    sent.add(call.getArgument(1));
                    return null;
                });
        FoodOrderListService.invalidate();
    }

    @AfterEach
    void tearDown() {
        FoodOrderListService.invalidate();
        packets.close();
        materials.close();
        endpoints.close();
        storage.close();
        api.close();
        recipes.close();
        orders.close();
        foods.close();
    }

    @Test
    void indexingAutomaticallyContinuesToFullCatalogWithoutAnotherRefresh() {
        foods.when(() -> FoodOrderFoods.uneaten(player))
                .thenReturn(Collections.nCopies(6001, Items.APPLE));
        FoodOrderListService.request(player);
        FoodOrderListService.tick(server);
        assertEquals(6001, sent.get(0).entries().size());
        assertTrue(sent.get(0).indexing());

        recipes.when(() -> RecipeIndex.isReady(level)).thenReturn(true);
        for (int tick = 2; tick < 500 && sent.get(sent.size() - 1).partial(); tick++) {
            time.set(tick);
            FoodOrderListService.tick(server);
        }
        FoodOrderSnapshot complete = sent.get(sent.size() - 1);
        assertEquals(6001, complete.entries().size());
        assertFalse(complete.indexing());
        assertFalse(complete.partial());
        assertTrue(complete.entries().stream().allMatch(entry -> entry.kind() == FoodOrderKind.NORECIPE));
        foods.verify(() -> FoodOrderFoods.uneaten(player), times(1));
        materials.verify(() -> MaterialSources.countInventory(player), times(1));
    }

    @Test
    void refreshDuringIndexingKeepsExistingBuild() {
        FoodOrderListService.request(player);
        FoodOrderListService.tick(server);
        time.set(11);
        FoodOrderListService.request(player);
        recipes.when(() -> RecipeIndex.isReady(level)).thenReturn(true);
        FoodOrderListService.tick(server);

        assertEquals(2, sent.size());
        assertFalse(sent.get(1).partial());
        foods.verify(() -> FoodOrderFoods.uneaten(player), times(1));
    }

    @Test
    void refreshWithinCooldownIsEventuallyAnsweredInsteadOfDropped() {
        recipes.when(() -> RecipeIndex.isReady(level)).thenReturn(true);
        FoodOrderListService.request(player);
        FoodOrderListService.tick(server);
        assertEquals(1, sent.size());

        time.set(2);
        FoodOrderListService.request(player);
        FoodOrderListService.tick(server);
        assertEquals(1, sent.size());
        time.set(11);
        FoodOrderListService.tick(server);
        assertEquals(2, sent.size());
        assertFalse(sent.get(1).partial());
        assertEquals(11, sent.get(1).epoch());
    }

    @Test
    void clearingPlayerCancelsPendingContinuation() {
        FoodOrderListService.request(player);
        FoodOrderListService.tick(server);
        FoodOrderListService.clearPlayer(player.getUUID());
        time.set(11);
        FoodOrderListService.tick(server);
        assertEquals(1, sent.size());
    }

    @Test
    void browsingLongerThanOneSecondKeepsOrderableCatalog() {
        prepareOrderableBread();
        FoodOrderListService.request(player);
        FoodOrderListService.tick(server);
        FoodOrderSnapshot displayed = sent.get(0);
        assertFalse(displayed.partial());
        assertEquals(1, displayed.totalOrderable());

        time.set(1201);
        FoodOrderSnapshot cached = FoodOrderListService.cached(player);
        assertSame(displayed, cached);
        assertTrue(cached.entries().get(0).orderable());
        foods.verify(() -> FoodOrderFoods.uneaten(player), times(1));
        materials.verify(() -> MaterialSources.countInventory(player), times(1));
    }

    @Test
    void refreshReplacesOldAvailabilityAndClearingPlayerDiscardsCatalog() {
        prepareOrderableBread();
        FoodOrderListService.request(player);
        FoodOrderListService.tick(server);
        assertEquals(1, sent.get(0).totalOrderable());

        time.set(1201);
        materials.when(() -> MaterialSources.countInventory(player)).thenReturn(Map.of());
        FoodOrderListService.request(player);
        FoodOrderListService.tick(server);
        FoodOrderSnapshot refreshed = sent.get(1);
        assertSame(refreshed, FoodOrderListService.cached(player));
        assertEquals(0, refreshed.totalOrderable());
        assertEquals(FoodOrderKind.MISSING, refreshed.entries().get(0).kind());

        FoodOrderListService.clearPlayer(player.getUUID());
        assertNull(FoodOrderListService.cached(player));
    }

    private void prepareOrderableBread() {
        ShapelessRecipe recipe = new ShapelessRecipe(new ResourceLocation("test", "bread"), "",
                CraftingBookCategory.MISC, new ItemStack(Items.BREAD),
                NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.WHEAT)));
        foods.when(() -> FoodOrderFoods.uneaten(player)).thenReturn(List.of(Items.BREAD));
        recipes.when(() -> RecipeIndex.isReady(level)).thenReturn(true);
        recipes.when(() -> RecipeIndex.get(level)).thenReturn(Map.of(Items.BREAD,
                List.of(new RecipeIndex.Entry(recipe, ModType.GENERIC,
                        new ResourceLocation("minecraft", "crafting")))));
        materials.when(() -> MaterialSources.countInventory(player))
                .thenReturn(Map.of(new StackKey(Items.WHEAT, null), 1L));
    }
}
