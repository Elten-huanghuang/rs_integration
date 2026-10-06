package com.huanghuang.rsintegration.disk.rs;

import com.huanghuang.rsintegration.config.RSStorageConfig;
import com.huanghuang.rsintegration.disk.persistence.DiskFileStore;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.refinedmods.refinedstorage.api.IRSAPI;
import com.refinedmods.refinedstorage.api.storage.disk.IStorageDiskManager;
import com.refinedmods.refinedstorage.apiimpl.API;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UnifiedDiskInventoryInitializationTest extends BootstrapTest {
    @TempDir Path world;
    private MinecraftServer server;
    private ServerLevel level;
    private ServerPlayer player;
    private PlayerList players;
    private Inventory inventory;
    private UnifiedDiskItem item;
    private ItemStack disk;

    @BeforeEach void setup() {
        Thread mainThread = Thread.currentThread();
        server = mock(MinecraftServer.class);
        when(server.isSameThread()).thenAnswer(ignored -> Thread.currentThread() == mainThread);
        when(server.getWorldPath(LevelResource.ROOT)).thenReturn(world);
        level = mock(ServerLevel.class);
        when(level.getServer()).thenReturn(server);
        when(level.getGameTime()).thenReturn(100L);
        players = mock(PlayerList.class);
        when(server.getPlayerList()).thenReturn(players);
        player = mock(ServerPlayer.class);
        when(player.getUUID()).thenReturn(UUID.randomUUID());
        when(player.level()).thenReturn(level);
        when(player.serverLevel()).thenReturn(level);
        when(players.getPlayer(player.getUUID())).thenReturn(player);
        inventory = new Inventory(player);
        when(player.getInventory()).thenReturn(inventory);
        item = mock(UnifiedDiskItem.class, CALLS_REAL_METHODS);
        disk = blankDisk();
        inventory.items.set(0, disk);
    }

    @AfterEach void shutdown() {
        UnifiedDiskEvents.stop(new ServerStoppedEvent(server));
        RSStorageConfig.SPEC.setConfig(null);
    }

    private ItemStack blankDisk() {
        // 保留真实数量和 NBT，模拟已冻结注册表中的模组物品。
        ItemStack stack = spy(new ItemStack(Items.STONE));
        doReturn(item).when(stack).getItem();
        return stack;
    }

    private void request() {
        item.inventoryTick(disk, level, player, 0, false);
    }

    private void tick() {
        UnifiedDiskEvents.tick(new TickEvent.ServerTickEvent(TickEvent.Phase.END, () -> true, server));
    }

    private void simulateInitialization() {
        doAnswer(invocation -> {
            assertTrue(server.isSameThread());
            item.setIdentity(invocation.getArgument(0), UUID.randomUUID(), UUID.randomUUID());
            return true;
        }).when(item).initialize(any(), any(), any());
    }

    @Test void asynchronousTicksCreateExactlyOnePersistentDiskFromServerTickEnd() throws Exception {
        var workers = Executors.newFixedThreadPool(4);
        try {
            var tasks = new ArrayList<Future<?>>();
            for (int i = 0; i < 100; i++) tasks.add(workers.submit(this::request));
            for (var task : tasks) task.get(10, TimeUnit.SECONDS);
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
        }
        verify(item, never()).initialize(any(), any(), any());
        assertNull(UnifiedDiskManager.existing(server));
        assertFalse(disk.hasTag());
        UnifiedDiskEvents.tick(new TickEvent.ServerTickEvent(TickEvent.Phase.START, () -> true, server));
        assertNull(UnifiedDiskManager.existing(server));

        IRSAPI api = mock(IRSAPI.class);
        IStorageDiskManager rsDisks = mock(IStorageDiskManager.class);
        when(api.getStorageDiskManager(level)).thenReturn(rsDisks);
        try (var staticApi = mockStatic(API.class)) {
            staticApi.when(API::instance).thenReturn(api);
            tick();
            UUID id = item.getId(disk);
            UnifiedDiskManager manager = UnifiedDiskManager.existing(server);
            assertNotNull(id);
            assertEquals(manager.worldId(), item.worldId(disk));
            assertEquals(1, disk.getTag().getInt("Format"));
            assertEquals(List.of(id), manager.savedDiskIds());
            DiskFileStore files = new DiskFileStore(world.resolve("data/rs_integration/unified_disks"));
            assertEquals(player.getUUID(), files.manifest(id).owner());
            assertEquals(0, files.load(manager.worldId(), id).items.total());
            verify(item, times(1)).initialize(disk, level, player.getUUID());
            verify(rsDisks, times(1)).set(eq(id), any(UnifiedDiskRoot.class));
            verify(rsDisks, times(1)).markForSaving();
            request();
            tick();
            assertEquals(List.of(id), manager.savedDiskIds());
            verify(item, times(1)).initialize(any(), any(), any());
        }
    }

    @Test void mainThreadAlsoDefersInitializationAndScansEachCurrentSlotOnce() {
        simulateInitialization();
        ItemStack second = blankDisk(), offhand = blankDisk(), armor = blankDisk();
        inventory.items.set(1, second);
        inventory.offhand.set(0, offhand);
        inventory.armor.set(0, armor);
        request();
        item.inventoryTick(second, level, player, 1, false);
        verify(item, never()).initialize(any(), any(), any());
        tick();
        for (ItemStack stack : List.of(disk, second, offhand, armor)) {
            verify(item, times(1)).initialize(stack, level, player.getUUID());
            assertTrue(item.isValid(stack));
        }
        tick();
        verify(item, times(4)).initialize(any(), any(), any());
    }

    @Test void discardedDiskIsNeverInitializedFromAnOldReference() {
        simulateInitialization();
        request();
        inventory.items.set(0, ItemStack.EMPTY);
        tick();
        verify(item, never()).initialize(any(), any(), any());
        assertFalse(disk.hasTag());
    }

    @Test void movedDiskUsesItsCurrentStackAndSlot() {
        simulateInitialization();
        request();
        ItemStack moved = blankDisk();
        inventory.items.set(0, ItemStack.EMPTY);
        inventory.offhand.set(0, moved);
        tick();
        verify(item).initialize(moved, level, player.getUUID());
        verify(item, never()).initialize(eq(disk), any(), any());
        assertFalse(disk.hasTag());
        assertTrue(item.isValid(moved));
    }

    @Test void diskInitializedBeforeProcessingKeepsItsOriginalIdentity() {
        request();
        item.setIdentity(disk, UUID.randomUUID(), UUID.randomUUID());
        CompoundTag original = disk.getTag().copy();
        tick();
        verify(item, never()).initialize(any(), any(), any());
        assertEquals(original, disk.getTag());
    }

    @ParameterizedTest @ValueSource(strings = {"Id", "World"})
    void malformedIdentityIsNotReplacedWithANewDisk(String key) {
        disk.getOrCreateTag().putString(key, "malformed");
        CompoundTag original = disk.getTag().copy();
        request();
        tick();
        assertEquals(original, disk.getTag());
        assertNull(UnifiedDiskManager.existing(server));
    }

    @ParameterizedTest @ValueSource(strings = {"offline", "disconnected", "removed"})
    void unavailablePlayersDoNotInitializeTheirInventory(String state) {
        request();
        switch (state) {
            case "offline" -> when(players.getPlayer(player.getUUID())).thenReturn(null);
            case "disconnected" -> when(player.hasDisconnected()).thenReturn(true);
            case "removed" -> when(player.isRemoved()).thenReturn(true);
        }
        tick();
        verify(item, never()).initialize(any(), any(), any());
        assertFalse(disk.hasTag());
    }

    @Test void oldLoginSessionCannotInitializeInventoryAfterReconnect() {
        simulateInitialization();
        request();
        ServerPlayer replacement = mock(ServerPlayer.class);
        UUID playerId = player.getUUID();
        when(replacement.getUUID()).thenReturn(playerId);
        when(replacement.serverLevel()).thenReturn(level);
        when(players.getPlayer(player.getUUID())).thenReturn(replacement);
        tick();
        verify(item, never()).initialize(any(), any(), any());

        Inventory newInventory = new Inventory(replacement);
        ItemStack newDisk = blankDisk();
        newInventory.items.set(0, newDisk);
        when(replacement.getInventory()).thenReturn(newInventory);
        item.inventoryTick(newDisk, level, replacement, 0, false);
        // 旧会话的退出清理不能删除新会话请求。
        UnifiedDiskEvents.logout(new PlayerEvent.PlayerLoggedOutEvent(player));
        tick();
        verify(item).initialize(newDisk, level, replacement.getUUID());
        assertFalse(disk.hasTag());
    }

    @Test void dimensionChangeUsesCurrentLevel() {
        simulateInitialization();
        request();
        ServerLevel destination = mock(ServerLevel.class);
        when(destination.getServer()).thenReturn(server);
        when(player.serverLevel()).thenReturn(destination);
        tick();
        verify(item).initialize(disk, destination, player.getUUID());
    }

    @Test void logoutAndServerStopDiscardPendingRequests() {
        request();
        UnifiedDiskEvents.logout(new PlayerEvent.PlayerLoggedOutEvent(player));
        tick();
        verify(item, never()).initialize(any(), any(), any());
        request();
        UnifiedDiskEvents.stop(new ServerStoppedEvent(server));
        tick();
        verify(item, never()).initialize(any(), any(), any());
        when(server.isStopped()).thenReturn(true);
        request();
        tick();
        verify(item, never()).initialize(any(), any(), any());
    }

    @Test void initializationCadenceStillSkipsOtherTicks() {
        when(level.getGameTime()).thenReturn(101L);
        request();
        tick();
        verify(item, never()).initialize(any(), any(), any());
    }

    @Test void failedInitializationCanBeRetriedOnTheNextScheduledTick() {
        UUID owner = player.getUUID();
        doReturn(false).when(item).initialize(disk, level, owner);
        request();
        tick();
        assertFalse(disk.hasTag());
        simulateInitialization();
        when(level.getGameTime()).thenReturn(200L);
        request();
        tick();
        verify(item, times(2)).initialize(disk, level, player.getUUID());
        assertTrue(item.isValid(disk));
    }

    @Test void stoppingAnotherServerDoesNotRemoveThisServersRequests() {
        simulateInitialization();
        MinecraftServer otherServer = mock(MinecraftServer.class);
        when(otherServer.isSameThread()).thenReturn(true);
        ServerLevel otherLevel = mock(ServerLevel.class);
        when(otherLevel.getServer()).thenReturn(otherServer);
        UnifiedDiskInventoryInitialization.request(otherLevel, player);
        request();
        UnifiedDiskEvents.stop(new ServerStoppedEvent(otherServer));
        tick();
        verify(item).initialize(disk, level, player.getUUID());
        UnifiedDiskEvents.tick(new TickEvent.ServerTickEvent(TickEvent.Phase.END, () -> true, otherServer));
        verify(item, times(1)).initialize(any(), any(), any());
    }

    @Test void workerCannotDrainQueueOrAccessManager() throws Exception {
        simulateInitialization();
        request();
        var worker = Executors.newSingleThreadExecutor();
        try {
            worker.submit(() -> {
                assertThrows(IllegalStateException.class, () -> UnifiedDiskInventoryInitialization.process(server));
                assertThrows(IllegalStateException.class, () -> UnifiedDiskManager.get(level));
            }).get(10, TimeUnit.SECONDS);
        } finally {
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS));
        }
        tick();
        verify(item).initialize(disk, level, player.getUUID());
    }
}
