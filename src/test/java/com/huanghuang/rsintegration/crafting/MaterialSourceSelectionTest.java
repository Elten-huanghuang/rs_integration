package com.huanghuang.rsintegration.crafting;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.planning.PlanningPreferences;
import com.huanghuang.rsintegration.storage.StorageBackendId;
import com.huanghuang.rsintegration.storage.StorageItemKey;
import com.huanghuang.rsintegration.storage.StorageOperationMode;
import com.huanghuang.rsintegration.storage.StorageOperationResult;
import com.huanghuang.rsintegration.storage.StorageReference;
import com.huanghuang.rsintegration.storage.StorageSession;
import com.huanghuang.rsintegration.storage.StorageSnapshot;
import com.huanghuang.rsintegration.storage.StorageSnapshotResult;
import com.huanghuang.rsintegration.storage.StoredItem;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.huanghuang.rsintegration.util.PlayerUtils;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.items.ItemStackHandler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MaterialSourceSelectionTest extends BootstrapTest {
    @BeforeAll
    static void configureEnvironment() {
        CommentedConfig config = CommentedConfig.inMemory();
        RSIntegrationConfig.SERVER_SPEC.correct(config);
        RSIntegrationConfig.SERVER_SPEC.setConfig(config);
        FMLJavaModLoadingContext context = mock(FMLJavaModLoadingContext.class);
        when(context.getModEventBus()).thenReturn(mock(IEventBus.class));
        try (var loading = mockStatic(FMLJavaModLoadingContext.class)) {
            loading.when(FMLJavaModLoadingContext::get).thenReturn(context);
            assertNotNull(RSIntegrationMod.LOGGER);
        }
    }

    @AfterAll
    static void unloadConfig() { RSIntegrationConfig.SERVER_SPEC.setConfig(null); }

    @Test
    void everyCombinationFiltersPlanningAndPhysicalExtraction() throws Exception {
        for (int mask = 0; mask < 8; mask++) {
            MaterialSourceSelection sources = MaterialSourceSelection.fromMask(mask);
            ServerPlayer player = mock(ServerPlayer.class);
            when(player.getUUID()).thenReturn(UUID.randomUUID());
            var serverField = ServerPlayer.class.getField("server");
            serverField.setAccessible(true);
            serverField.set(player, mock(MinecraftServer.class));
            Inventory inventory = new Inventory(player);
            inventory.items.set(0, new ItemStack(Items.STONE, 7));
            when(player.getInventory()).thenReturn(inventory);
            ItemStackHandler backpack = new ItemStackHandler(1);
            backpack.setStackInSlot(0, new ItemStack(Items.STONE, 5));
            AtomicLong stored = new AtomicLong(11L);
            StorageBackendId backend = new StorageBackendId("test");
            StorageSession session = mock(StorageSession.class, CALLS_REAL_METHODS);
            when(session.reference()).thenReturn(new StorageReference(backend, "source-test"));
            when(session.snapshotItems(eq(player))).thenAnswer(call -> StorageSnapshotResult.success(
                    new StorageSnapshot(backend, List.of(new StoredItem(
                            StorageItemKey.fromItemStack(backend, new ItemStack(Items.STONE)), stored.get())))));
            when(session.snapshotItems(eq(player), anySet())).thenAnswer(call -> session.snapshotItems(player));
            when(session.extractExact(eq(player), any(), anyLong(), eq(false))).thenAnswer(call -> {
                long amount = call.getArgument(2);
                stored.addAndGet(-amount);
                return StorageOperationResult.extracted(StorageOperationMode.PERFORM, amount,
                        List.of(new ItemStack(Items.STONE, (int) amount)));
            });
            CraftStorageEndpoint endpoint = () -> session;
            ModList mods = mock(ModList.class);
            when(mods.isLoaded("sophisticatedbackpacks")).thenReturn(true);
            try (var modLookup = mockStatic(ModList.class);
                 var ledgerLookup = mockStatic(ExtractionLedger.class, CALLS_REAL_METHODS);
                 var inventoryUpdates = mockStatic(PlayerUtils.class);
                 var scope = sources.enter()) {
                modLookup.when(ModList::get).thenReturn(mods);
                ledgerLookup.when(() -> ExtractionLedger.findAllBackpackInventories(player)).thenReturn(List.of(backpack));
                long expected = (sources.inventory() ? 7 : 0) + (sources.storageNetwork() ? 11 : 0)
                        + (sources.backpacks() ? 5 : 0);
                StackKey key = StackKey.of(new ItemStack(Items.STONE), true);
                assertEquals(expected, MaterialSources.listAllAvailable(player, endpoint).getOrDefault(key, 0L));
                assertEquals(expected, MaterialSources.listAvailableForTypes(player, endpoint, Set.of(Items.STONE))
                        .getOrDefault(key, 0L));
                try (var opposite = MaterialSourceSelection.fromMask(mask ^ 7).enter()) {
                    assertEquals(23L - expected,
                            MaterialSources.listAllAvailable(player, endpoint).getOrDefault(key, 0L),
                            "同一 tick 切换来源不能复用另一组选项的库存缓存");
                }
                try (ExtractionLedger ledger = new ExtractionLedger()) {
                    if (expected == 0) {
                        assertTrue(ledger.reserveExactAcrossNetworkAndInventory(
                                new ItemStack(Items.STONE), 1, endpoint, player).isEmpty());
                    } else {
                        assertEquals(expected, ledger.reserveExactAcrossNetworkAndInventory(
                                new ItemStack(Items.STONE), (int) expected, endpoint, player).getCount());
                        assertTrue(ledger.commitToEndpoint(endpoint, player));
                        ledger.settleAllCommitted();
                    }
                }
                assertEquals(sources.inventory() ? 0 : 7, inventory.items.get(0).getCount());
                assertEquals(sources.backpacks() ? 0 : 5, backpack.getStackInSlot(0).getCount());
                assertEquals(sources.storageNetwork() ? 0 : 11, stored.get());
            } finally { MaterialSources.invalidateFor(player); }
        }
    }

    @Test
    void preferencesCodecPreservesAllCombinationsAndScopeRestoresAfterFailure() {
        for (int mask = 0; mask < 8; mask++) {
            var sources = MaterialSourceSelection.fromMask(mask);
            var preferences = new PlanningPreferences(Map.of(), Map.of(), Set.of(), sources);
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            try {
                preferences.write(buf);
                assertEquals(sources, PlanningPreferences.read(buf).materialSources());
                assertEquals(0, buf.readableBytes());
            } finally { buf.release(); }
        }
        assertThrows(IllegalStateException.class, () -> {
            try (var outer = MaterialSourceSelection.fromMask(0).enter()) {
                try (var inner = MaterialSourceSelection.fromMask(1).enter()) {
                    assertTrue(MaterialSourceSelection.current().inventory());
                }
                assertFalse(MaterialSourceSelection.current().inventory());
                throw new IllegalStateException("测试异常退出");
            }
        });
        assertEquals(MaterialSourceSelection.ALL, MaterialSourceSelection.current());
    }
}
