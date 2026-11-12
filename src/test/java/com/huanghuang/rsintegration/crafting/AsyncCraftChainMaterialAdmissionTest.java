package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.storage.StorageItemChangeListener;
import com.huanghuang.rsintegration.storage.StorageItemSubscription;
import com.huanghuang.rsintegration.storage.StorageSession;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidTestFixtures;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AsyncCraftChainMaterialAdmissionTest extends BootstrapTest {
    private final AtomicInteger tick = new AtomicInteger();
    private final AtomicInteger inventoryRevision = new AtomicInteger();
    private final AtomicReference<StorageItemChangeListener> listener = new AtomicReference<>();
    private ServerPlayer player;
    private RuntimeMaterialAccount account;
    private AsyncCraftChain chain;
    private StorageItemSubscription subscription;

    @BeforeAll static void configure() { RuntimeMaterialAccountTest.configure(); }

    @BeforeEach
    void setup() throws Exception {
        MinecraftServer server = mock(MinecraftServer.class);
        PlayerList players = mock(PlayerList.class);
        player = mock(ServerPlayer.class);
        UUID playerId = UUID.randomUUID();
        when(player.getUUID()).thenReturn(playerId);
        Inventory inventory = mock(Inventory.class);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getTimesChanged()).thenAnswer(ignored -> inventoryRevision.get());
        when(server.getTickCount()).thenAnswer(ignored -> tick.get());
        when(server.getPlayerList()).thenReturn(players);
        when(players.getPlayer(playerId)).thenReturn(player);
        StorageSession session = mock(StorageSession.class);
        subscription = mock(StorageItemSubscription.class);
        when(subscription.isValid()).thenReturn(true);
        when(session.subscribeItemChanges(any())).thenAnswer(call -> {
            listener.set(call.getArgument(0));
            return Optional.of(subscription);
        });
        CraftStorageEndpoint endpoint = () -> session;
        chain = new AsyncCraftChain(playerId, server, endpoint, List.of());
        chain.enableMaterialAccount(new GlobalMaterialCoordinator());
        account = mock(RuntimeMaterialAccount.class);
        set("materialAccount", account);
        set("initialMaterialPlan", new InitialMaterialPlan(Map.of(
                MaterialKey.of(new ItemStack(Items.IRON_INGOT)), 2L), List.of()));
    }

    @Test
    void matchingStorageChangeWakesBeforeFallbackAndUnrelatedChangeDoesNot() throws Exception {
        assertFalse(acquire());
        tick.set(1);
        listener.get().onChanged(new ItemStack(Items.GOLD_INGOT), 10L);
        assertFalse(acquire());
        verify(account, times(1)).ensure(anyMap(), anyList(), eq(player));

        listener.get().onChanged(new ItemStack(Items.IRON_INGOT), 2L);
        assertFalse(acquire());
        verify(account, times(2)).ensure(anyMap(), anyList(), eq(player));
        verify(subscription, never()).close();
    }

    @Test
    void rollbackNotificationsDoNotCauseATightRetryLoop() throws Exception {
        when(account.ensure(anyMap(), anyList(), eq(player))).thenAnswer(ignored -> {
            listener.get().onChanged(new ItemStack(Items.IRON_INGOT), 2L);
            inventoryRevision.incrementAndGet();
            return false;
        });
        assertFalse(acquire());
        tick.set(1);
        assertFalse(acquire());
        verify(account, times(1)).ensure(anyMap(), anyList(), eq(player));
        tick.set(100);
        assertFalse(acquire());
        verify(account, times(2)).ensure(anyMap(), anyList(), eq(player));
    }

    @Test
    void playerInventoryChangeWakesWithoutWaitingForStorageFallback() throws Exception {
        assertFalse(acquire());
        inventoryRevision.incrementAndGet();
        tick.set(1);
        assertFalse(acquire());
        verify(account, times(2)).ensure(anyMap(), anyList(), eq(player));
    }

    @Test
    void fluidNotificationsMatchExactIdentity() throws Exception {
        FluidStack required = new FluidStack(Fluids.WATER, 1);
        required.getOrCreateTag().putString("grade", "pure");
        ItemStack token = InkFluidSupport.token(InkFluidTestFixtures.tokenItem(), required);
        set("initialMaterialPlan", new InitialMaterialPlan(Map.of(MaterialKey.of(token), 1000L), List.of()));
        assertFalse(acquire());
        tick.set(1);
        listener.get().onFluidChanged(new FluidStack(Fluids.WATER, 1), 1000L);
        assertFalse(acquire());
        verify(account, times(1)).ensure(anyMap(), anyList(), eq(player));
        listener.get().onFluidChanged(required, 1000L);
        assertFalse(acquire());
        verify(account, times(2)).ensure(anyMap(), anyList(), eq(player));
    }

    private boolean acquire() throws Exception {
        Method method = AsyncCraftChain.class.getDeclaredMethod("acquireInitialMaterials");
        method.setAccessible(true);
        return (boolean) method.invoke(chain);
    }

    private void set(String name, Object value) throws Exception {
        Field field = AsyncCraftChain.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(chain, value);
    }
}
