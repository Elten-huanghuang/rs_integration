package com.huanghuang.rsintegration.feature.resonance.bridge;

import com.huanghuang.rsintegration.feature.resonance.api.ResonanceStorageView;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraftforge.event.entity.living.LivingGetProjectileEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class ResonanceBowAmmoTest extends BootstrapTest {
    @AfterEach
    void clearClientState() {
        ClientDiskData.clear();
    }

    @Test
    void serverSuppliesOneNormalArrowWithoutConsumingDiskContents() {
        ServerPlayer player = mock(ServerPlayer.class);
        when(player.getAbilities()).thenReturn(new Abilities());
        ItemStack stored = new ItemStack(Items.ARROW, 16);
        ResonanceStorageView disk = mock(ResonanceStorageView.class);
        when(disk.storedStacks()).thenReturn(List.of(new ResonanceStorageView.StoredStack(0, stored)));
        doCallRealMethod().when(disk).hasItem(any());

        try (MockedStatic<ResonanceInventoryBridge> bridge = mockStatic(ResonanceInventoryBridge.class)) {
            bridge.when(() -> ResonanceInventoryBridge.getViews(player)).thenReturn(List.of(disk));
            bridge.when(() -> ResonanceInventoryBridge.hasItem(eq(player), any())).thenCallRealMethod();

            ItemStack ammo = resolve(player, infinityBow(), ItemStack.EMPTY);
            assertTrue(ammo.is(Items.ARROW));
            assertEquals(1, ammo.getCount());
            ammo.shrink(1);
            assertEquals(16, stored.getCount());
        }
    }

    @Test
    void serverRejectsMissingOrSpecialArrowsEvenWithAnOlderClientSnapshot() {
        ServerPlayer player = mock(ServerPlayer.class);
        when(player.getAbilities()).thenReturn(new Abilities());
        when(player.isLocalPlayer()).thenReturn(true);
        ClientDiskData.apply(0, 0, 0, 1L, List.of(), false, true);
        ResonanceStorageView disk = mock(ResonanceStorageView.class);
        doCallRealMethod().when(disk).hasItem(any());

        try (MockedStatic<ResonanceInventoryBridge> bridge = mockStatic(ResonanceInventoryBridge.class)) {
            bridge.when(() -> ResonanceInventoryBridge.hasItem(eq(player), any())).thenCallRealMethod();
            bridge.when(() -> ResonanceInventoryBridge.getViews(player)).thenReturn(List.of());
            assertTrue(resolve(player, infinityBow(), ItemStack.EMPTY).isEmpty());

            bridge.when(() -> ResonanceInventoryBridge.getViews(player)).thenReturn(List.of(disk));
            when(disk.storedStacks()).thenReturn(List.of(
                    new ResonanceStorageView.StoredStack(0, new ItemStack(Items.TIPPED_ARROW)),
                    new ResonanceStorageView.StoredStack(1, new ItemStack(Items.SPECTRAL_ARROW))));
            assertTrue(resolve(player, infinityBow(), ItemStack.EMPTY).isEmpty());
        }
    }

    @Test
    void localFallbackPreservesCarriedAmmoAndOnlyAppliesToTheVanillaInfinityBow() {
        Player player = mock(Player.class);
        when(player.getAbilities()).thenReturn(new Abilities());
        when(player.isLocalPlayer()).thenReturn(true);
        ItemStack bow = infinityBow();
        ItemStack carried = new ItemStack(Items.TIPPED_ARROW);
        ItemStack crossbow = new ItemStack(Items.CROSSBOW);
        crossbow.enchant(Enchantments.INFINITY_ARROWS, 1);
        assertTrue(resolve(player, bow, ItemStack.EMPTY).isEmpty());

        ClientDiskData.apply(0, 0, 0, 1L, List.of(), false, true);
        assertTrue(resolve(player, bow, ItemStack.EMPTY).is(Items.ARROW));
        assertSame(carried, resolve(player, bow, carried));
        assertTrue(resolve(player, new ItemStack(Items.BOW), ItemStack.EMPTY).isEmpty());
        assertTrue(resolve(player, crossbow, ItemStack.EMPTY).isEmpty());

        when(player.isLocalPlayer()).thenReturn(false);
        assertTrue(resolve(player, bow, ItemStack.EMPTY).isEmpty());
    }

    private static ItemStack infinityBow() {
        ItemStack bow = new ItemStack(Items.BOW);
        bow.enchant(Enchantments.INFINITY_ARROWS, 1);
        return bow;
    }

    private static ItemStack resolve(Player player, ItemStack bow, ItemStack carried) {
        LivingGetProjectileEvent event = new LivingGetProjectileEvent(player, bow, carried);
        ResonanceBowAmmo.onGetProjectile(event);
        return event.getProjectileItemStack();
    }
}
