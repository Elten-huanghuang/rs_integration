package com.huanghuang.rsintegration.feature.experience;

import com.huanghuang.rsintegration.feature.resonance.api.ResonanceStorageView;
import com.huanghuang.rsintegration.feature.resonance.bd.BDResonanceDiskData;
import com.huanghuang.rsintegration.feature.resonance.bd.BDResonanceDiskView;
import com.huanghuang.rsintegration.feature.resonance.bridge.ResonanceInventoryBridge;
import com.huanghuang.rsintegration.feature.resonance.disk.ResonanceDiskWrapper;
import com.huanghuang.rsintegration.storage.StorageRestockSupport;
import com.huanghuang.rsintegration.storage.bd.BeyondDimensionsReflection;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.huanghuang.rsintegration.testutil.ExperienceWandTestItems;
import com.refinedmods.refinedstorage.api.IRSAPI;
import com.refinedmods.refinedstorage.api.storage.disk.IStorageDiskManager;
import com.refinedmods.refinedstorage.apiimpl.API;
import com.refinedmods.refinedstorage.apiimpl.storage.disk.ItemStorageDisk;
import com.refinedmods.refinedstorage.apiimpl.util.Comparer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExperienceWandDiskPersistenceTest extends BootstrapTest {
    private ServerPlayer player;
    private ServerLevel level;

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        player = mock(ServerPlayer.class);
        level = mock(ServerLevel.class);
        MinecraftServer server = mock(MinecraftServer.class);
        when(server.getAllLevels()).thenReturn(List.of(level));
        var field = ServerPlayer.class.getField("server");
        field.setAccessible(true);
        field.set(player, server);
        when(player.isAlive()).thenReturn(true);
        when(player.getInventory()).thenReturn(new Inventory(player));
        when(player.getMainHandItem()).thenReturn(ItemStack.EMPTY);
        when(player.getOffhandItem()).thenReturn(ItemStack.EMPTY);
        player.connection = mock(ServerGamePacketListenerImpl.class);
    }

    @Test
    void realRsDiskRunsThePlayerTickAndPersistsItsLongBuffer() {
        IRSAPI api = mock(IRSAPI.class);
        IStorageDiskManager manager = mock(IStorageDiskManager.class);
        when(api.getComparer()).thenReturn(new Comparer());
        when(api.getStorageDiskManager(level)).thenReturn(manager);
        try (MockedStatic<API> boundary = mockStatic(API.class)) {
            boundary.when(API::instance).thenReturn(api);
            ResonanceDiskWrapper disk = new ResonanceDiskWrapper(
                    new ItemStorageDisk(null, 2304, UUID.randomUUID()));

            runRealTick(disk);

            assertEquals(1, disk.getStored());
            assertEquals(4, disk.storedStacks().get(0).slot());
            verify(manager, atLeastOnce()).markForSaving();
        }
    }

    @Test
    void realBdDiskRunsThePlayerTickAndPreservesItsBufferAcrossSaveAndReload()
            throws ReflectiveOperationException {
        var constructor = BDResonanceDiskData.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        BDResonanceDiskData data = constructor.newInstance();
        UUID diskId = UUID.randomUUID();
        data.getOrCreate(diskId);
        try (MockedStatic<BeyondDimensionsReflection> boundary =
                     mockStatic(BeyondDimensionsReflection.class)) {
            boundary.when(() -> BeyondDimensionsReflection.isAuthorizedNetwork(player, 7))
                    .thenReturn(true);
            BDResonanceDiskView disk = new BDResonanceDiskView(player, diskId, data, 7);

            runRealTick(disk);

            var load = BDResonanceDiskData.class.getDeclaredMethod("load", CompoundTag.class);
            load.setAccessible(true);
            BDResonanceDiskData reloaded = (BDResonanceDiskData) load.invoke(null, data.save(new CompoundTag()));
            ItemStack wand = reloaded.snapshot(diskId).get(0).stack();
            assertTrue(ExperienceWandAccess.isWand(wand));
            assertEquals(5_000_000_000L - 140L, ExperienceWandData.buffer(wand));
            assertEquals(4, reloaded.snapshot(diskId).get(0).slot());
            assertEquals(1, reloaded.stored(diskId));
        }
    }

    private void runRealTick(ResonanceStorageView disk) {
        ItemStack wand = ExperienceWandTestItems.wand();
        new ExperienceWandSettings(ExperienceWandSettings.Mode.TAKE, 1, false, 1).save(wand);
        ExperienceWandData.buffer(wand, 5_000_000_000L);
        UUID id = ExperienceWandData.id(wand);
        assertTrue(disk.insertView(4, wand, 1, false).isEmpty());
        try (MockedStatic<ResonanceInventoryBridge> bridge = mockStatic(ResonanceInventoryBridge.class);
             MockedStatic<StorageRestockSupport> storage = mockStatic(StorageRestockSupport.class)) {
            bridge.when(() -> ResonanceInventoryBridge.getViews(player)).thenReturn(List.of(disk));
            bridge.when(() -> ResonanceInventoryBridge.hasItem(eq(player), any())).thenCallRealMethod();
            storage.when(() -> StorageRestockSupport.resolve(player)).thenReturn(Optional.empty());

            ExperienceWandService.onPlayerTick(new TickEvent.PlayerTickEvent(TickEvent.Phase.END, player));

            ItemStack persisted = disk.storedStacks().get(0).stack();
            assertEquals(1, player.experienceLevel);
            assertEquals(7L, ExperiencePoints.current(player));
            assertEquals(5_000_000_000L - 140L, ExperienceWandData.buffer(persisted));
            assertTrue(ExperienceWandData.matches(persisted, id));
            assertTrue(ExperienceWandAccess.hasWand(player));
            verify(player.connection).send(any(ClientboundSetExperiencePacket.class));
        }
    }
}
