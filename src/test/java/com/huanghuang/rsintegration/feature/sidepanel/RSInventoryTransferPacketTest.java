package com.huanghuang.rsintegration.feature.sidepanel;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.CraftItemRecovery;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.DeepPlanningFixture;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.TaskOutputReception;
import com.huanghuang.rsintegration.network.RSIntegrationNetwork;
import com.huanghuang.rsintegration.storage.rs.RefinedStorageOutputReception;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.util.Action;
import com.refinedmods.refinedstorage.apiimpl.storage.tracker.ItemStorageTracker;
import com.refinedmods.refinedstorage.apiimpl.util.ItemStackList;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraftforge.network.NetworkEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RSInventoryTransferPacketTest extends BootstrapTest {
    private static final ResourceLocation RECIPE_ID = ResourceLocation.fromNamespaceAndPath("test", "sidepanel_return");
    private static final int DIAMONDS = 5;

    private final UUID owner = UUID.randomUUID();
    private final ItemStack diamonds = new ItemStack(Items.DIAMOND, DIAMONDS);
    private final AtomicInteger storedDiamonds = new AtomicInteger(DIAMONDS);
    private final CraftItemRecovery recovery = new CraftItemRecovery();
    private ServerPlayer player;
    private INetwork network;
    private Recipe<?> recipe;
    private Runnable trackerListener;
    private ItemStorageTracker tracker;

    @BeforeAll
    static void initializeMod() {
        DeepPlanningFixture.initializeMod();
    }

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        var config = CommentedConfig.inMemory();
        config.set("sidePanel.enableRSSidePanel", true);
        RSIntegrationConfig.COMMON_SPEC.setConfig(config);

        MinecraftServer server = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);
        when(server.isSameThread()).thenReturn(true);
        when(server.overworld()).thenReturn(level);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        DimensionDataStorage dataStorage = mock(DimensionDataStorage.class);
        when(level.getDataStorage()).thenReturn(dataStorage);
        when(dataStorage.computeIfAbsent(any(), any(), anyString())).thenReturn(recovery);

        recipe = mock(Recipe.class);
        RecipeManager recipeManager = mock(RecipeManager.class);
        when(level.getRecipeManager()).thenReturn(recipeManager);
        doReturn(Optional.of(recipe)).when(recipeManager).byKey(RECIPE_ID);

        player = mock(ServerPlayer.class);
        Field serverField = ServerPlayer.class.getField("server");
        serverField.setAccessible(true);
        serverField.set(player, server);
        player.connection = mock(ServerGamePacketListenerImpl.class);
        player.containerMenu = mock(AbstractContainerMenu.class);
        when(player.containerMenu.getCarried()).thenReturn(ItemStack.EMPTY);
        when(player.serverLevel()).thenReturn(level);
        when(player.getUUID()).thenReturn(owner);
        when(player.getName()).thenReturn(Component.literal("sidepanel-player"));
        Inventory inventory = new Inventory(player);
        inventory.items.replaceAll(stack -> new ItemStack(Items.DIRT, 64));
        inventory.offhand.set(0, new ItemStack(Items.DIRT, 64));
        when(player.getInventory()).thenReturn(inventory);

        network = mock(INetwork.class, RETURNS_DEEP_STUBS);
        when(network.getLevel()).thenReturn(level);
        when(network.getPosition()).thenReturn(BlockPos.ZERO);
        when(network.canRun()).thenReturn(true);
        when(network.getSecurityManager()).thenReturn(null);
        ItemStackList cache = new ItemStackList();
        cache.add(new ItemStack(Items.COBBLESTONE, 20), 20);
        cache.add(diamonds, DIAMONDS);
        when(network.getItemStorageCache().getList()).thenReturn(cache);
        trackerListener = mock(Runnable.class);
        tracker = new ItemStorageTracker(trackerListener);
        when(network.getItemStorageTracker()).thenReturn(tracker);
        when(network.extractItem(any(), anyInt(), eq(Action.PERFORM))).thenAnswer(call -> {
            ItemStack stack = call.getArgument(0);
            int count = call.getArgument(1);
            if (stack.is(Items.DIAMOND)) storedDiamonds.addAndGet(-count);
            return stack.copyWithCount(count);
        });
    }

    @AfterEach
    void tearDown() {
        RSIntegrationConfig.COMMON_SPEC.setConfig(null);
        SidePanelRequestRateLimiter.onPlayerLogout(owner);
    }

    @Test
    void overflowReturnsToStorageAndUpdatesModificationTimeWithoutFeedingTask() {
        stubReturn(DIAMONDS, false);
        long beforeReturn = System.currentTimeMillis();
        var key = TaskOutputReception.resource(diamonds);
        try (var task = TaskOutputReception.open()) {
            task.bindNetwork(network, () -> true);
            task.prepare(List.of(new TaskOutputReception.Demand("out", key, DIAMONDS, DIAMONDS))).activate();
            transferWithFullInventory();

            assertEquals(DIAMONDS, storedDiamonds.get());
            assertEquals(0, task.received(key));
            assertTrue(tracker.get(diamonds).getTime() >= beforeReturn);
            assertEquals("sidepanel-player", tracker.get(diamonds).getName());
            verify(trackerListener).run();
            assertFalse(recovery.hasPending(owner));
        }
    }

    @Test
    void retainsOnlyTheRejectedRemainderWithoutExceedingDropLimit() {
        stubReturn(3, false);
        transferWithFullInventory();

        assertEquals(3, storedDiamonds.get());
        var pending = recovery.save(new CompoundTag()).getList("pending", Tag.TAG_COMPOUND);
        assertEquals(1, pending.size());
        CompoundTag entry = pending.getCompound(0);
        assertEquals(owner, entry.getUUID("owner"));
        assertTrue(ItemStack.of(entry.getCompound("stack")).is(Items.DIAMOND));
        assertEquals(2, entry.getInt("amount"));
        verify(player).sendSystemMessage(Component.translatable("rsi.item_recovery.pending"));
    }

    @Test
    void doesNotRetryOrRetainWhenTheWriteMayAlreadyHaveSucceeded() {
        stubReturn(DIAMONDS, true);
        transferWithFullInventory();

        assertEquals(DIAMONDS, storedDiamonds.get());
        assertFalse(recovery.hasPending(owner));
    }

    private void stubReturn(int capacity, boolean failAfterMutation) {
        when(network.insertItem(any(), anyInt(), any())).thenAnswer(call -> {
            Action action = call.getArgument(2);
            return RefinedStorageOutputReception.insertItem(network, call.getArgument(0),
                    call.getArgument(1), action, (stack, count) -> {
                        int accepted = Math.min(count, capacity);
                        if (action == Action.PERFORM) {
                            storedDiamonds.addAndGet(accepted);
                            if (failAfterMutation) throw new IllegalStateException("写入结果未知");
                        }
                        return stack.copyWithCount(count - accepted);
                    });
        });
    }

    private void transferWithFullInventory() {
        var specs = new ArrayList<>(Collections.nCopies(20,
                new IngredientSpec(Ingredient.of(Items.COBBLESTONE), 1)));
        specs.add(new IngredientSpec(Ingredient.of(Items.DIAMOND), DIAMONDS));
        NetworkEvent.Context context = mock(NetworkEvent.Context.class);
        when(context.getSender()).thenReturn(player);
        when(context.enqueueWork(any(Runnable.class))).thenAnswer(call -> {
            call.<Runnable>getArgument(0).run();
            return CompletableFuture.completedFuture(null);
        });
        try (var networks = mockStatic(RSIntegrationNetwork.class);
             var ingredients = mockStatic(CraftPacketUtils.class)) {
            networks.when(() -> RSIntegrationNetwork.resolveNetworkFromPlayer(player)).thenReturn(network);
            ingredients.when(() -> CraftPacketUtils.extractIngredientSpecs(recipe)).thenReturn(specs);
            RSInventoryTransferPacket.handle(new RSInventoryTransferPacket(RECIPE_ID), () -> context);
        }
        verify(player, times(20)).drop(any(ItemStack.class), eq(false));
        verify(network).insertItem(any(ItemStack.class), eq(DIAMONDS), eq(Action.PERFORM));
    }
}
