package com.huanghuang.rsintegration.crafting;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
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
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
