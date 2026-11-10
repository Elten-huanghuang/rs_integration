package com.huanghuang.rsintegration.crafting;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.storage.StorageBackendId;
import com.huanghuang.rsintegration.storage.StorageItemKey;
import com.huanghuang.rsintegration.storage.StorageSession;
import com.huanghuang.rsintegration.storage.StorageSnapshot;
import com.huanghuang.rsintegration.storage.StorageSnapshotResult;
import com.huanghuang.rsintegration.storage.StoredItem;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class MaterialSourcesFreshSnapshotTest extends BootstrapTest {

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
    static void unloadConfig() {
        RSIntegrationConfig.SERVER_SPEC.setConfig(null);
    }

    @Test
    void narrowPlayerInventoryKeepsExactVariantsAndExcludesUnrelatedItems() {
        ServerPlayer player = mock(ServerPlayer.class);
        Inventory inventory = new Inventory(mock(Player.class));
        when(player.getInventory()).thenReturn(inventory);
        ItemStack tagged = new ItemStack(Items.BOOK, 3);
        tagged.getOrCreateTag().putString("variant", "specific");
        inventory.items.set(0, tagged);
        inventory.items.set(1, new ItemStack(Items.BOOK, 5));
        inventory.items.set(2, new ItemStack(Items.DIAMOND, 64));
        try (var mods = mockStatic(ModList.class)) {
            mods.when(ModList::get).thenReturn(mock(ModList.class));
            var counts = MaterialSources.countInventoryForTypes(player, Set.of(Items.BOOK));
            assertEquals(2, counts.size());
            assertEquals(3, counts.get(StackKey.of(tagged, true)));
            assertEquals(5, counts.get(new StackKey(Items.BOOK, null)));
        }
    }

    @Test
    void previewValidationReadsPipeChangesWithinTheSameTickAndRetryInvalidatesTheOldCache() {
        ServerPlayer player = mock(ServerPlayer.class);
        when(player.getUUID()).thenReturn(UUID.randomUUID());
        when(player.getInventory()).thenReturn(new Inventory(mock(Player.class)));
        StorageBackendId backend = new StorageBackendId("test");
        StorageSession session = mock(StorageSession.class);
        CraftStorageEndpoint endpoint = mock(CraftStorageEndpoint.class);
        when(endpoint.session()).thenReturn(session);
        StackKey coal = new StackKey(Items.COAL, null);
        StorageSnapshotResult empty = StorageSnapshotResult.success(new StorageSnapshot(backend, List.of()));
        StorageSnapshotResult supplied = StorageSnapshotResult.success(new StorageSnapshot(backend,
                List.of(new StoredItem(StorageItemKey.fromItemStack(backend, new ItemStack(Items.COAL)), 8L))));
        when(endpoint.snapshot(player)).thenReturn(empty, supplied);
        when(endpoint.snapshot(player, Set.of(Items.COAL))).thenReturn(supplied, empty);
        ModList modList = mock(ModList.class);
        try (var mods = mockStatic(ModList.class)) {
            mods.when(ModList::get).thenReturn(modList);
            // 先捕获缺料快照，然后模拟管道在同一 tick 输入 8 个煤。
            assertEquals(0L, MaterialSources.listAllAvailable(player, endpoint).getOrDefault(coal, 0L));
            assertEquals(8L, MaterialSources.listAvailableForTypes(player, endpoint, Set.of(Items.COAL)).get(coal));
            assertEquals(0L, MaterialSources.listAllAvailable(player, endpoint).getOrDefault(coal, 0L));
            // 自动重算清理旧缓存后，后续规划必须使用刚补入的材料。
            MaterialSources.invalidateFor(player);
            assertEquals(8L, MaterialSources.listAllAvailable(player, endpoint).get(coal));
            // 管道再次抽空时，窄范围复验也不能沿用成功快照。
            assertEquals(0L, MaterialSources.listAvailableForTypes(player, endpoint, Set.of(Items.COAL)).getOrDefault(coal, 0L));
            verify(endpoint, times(2)).snapshot(player);
            verify(endpoint, times(2)).snapshot(player, Set.of(Items.COAL));
        } finally {
            MaterialSources.invalidateFor(player);
        }
    }

    @Test
    void executionRefreshBypassesAnEmptySameTickCache() {
        ServerPlayer player = mock(ServerPlayer.class);
        when(player.getInventory()).thenReturn(new Inventory(mock(Player.class)));
        StorageSession session = mock(StorageSession.class);
        StorageBackendId backend = new StorageBackendId("test");
        when(session.countDerivedContainer(any(), any()))
                .thenReturn(0L);
        CraftStorageEndpoint endpoint = mock(CraftStorageEndpoint.class);
        when(endpoint.session()).thenReturn(session);
        when(endpoint.snapshot(player)).thenReturn(
                StorageSnapshotResult.success(
                        new StorageSnapshot(backend, List.of())),
                StorageSnapshotResult.success(
                        new StorageSnapshot(backend, List.of(
                                new StoredItem(
                                        StorageItemKey.fromItemStack(
                                                backend,
                                                new ItemStack(Items.BRICK, 2)),
                                        2L)))));

        ModList modList = mock(ModList.class);
        when(modList.isLoaded("sophisticatedbackpacks")).thenReturn(false);
        try (var mods = mockStatic(ModList.class)) {
            mods.when(ModList::get).thenReturn(modList);
            assertEquals(0L, MaterialSources.listAllAvailable(player, endpoint)
                    .getOrDefault(CraftingResolver.StackKey.of(
                            new ItemStack(Items.BRICK), true), 0L));
            assertEquals(2L, MaterialSources.listAllAvailableFresh(player, endpoint)
                    .getOrDefault(CraftingResolver.StackKey.of(
                            new ItemStack(Items.BRICK), true), 0L));
        }
    }
}
