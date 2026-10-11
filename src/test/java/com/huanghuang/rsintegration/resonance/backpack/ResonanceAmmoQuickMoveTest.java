package com.huanghuang.rsintegration.resonance.backpack;

import com.huanghuang.rsintegration.resonance.api.ResonanceStackRules;
import com.huanghuang.rsintegration.resonance.api.ResonanceStorageView;
import com.huanghuang.rsintegration.resonance.disk.ResonanceDiskWrapper;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.refinedmods.refinedstorage.api.IRSAPI;
import com.refinedmods.refinedstorage.apiimpl.API;
import com.refinedmods.refinedstorage.apiimpl.storage.disk.ItemStorageDisk;
import com.refinedmods.refinedstorage.apiimpl.util.Comparer;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.resource.index.CommonAmmoIndex;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.UUID;
import java.util.Optional;

import static com.huanghuang.rsintegration.testutil.TaczAmmoTestItems.ammo;
import static com.huanghuang.rsintegration.testutil.TaczAmmoTestItems.nativeAmmo;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResonanceAmmoQuickMoveTest extends BootstrapTest {
    private MockedStatic<API> apiMock;
    private ResonanceDiskWrapper disk;
    private Inventory inventory;

    @BeforeEach
    void setup() {
        IRSAPI api = mock(IRSAPI.class);
        when(api.getComparer()).thenReturn(new Comparer());
        apiMock = mockStatic(API.class);
        apiMock.when(API::instance).thenReturn(api);
        disk = spy(new ResonanceDiskWrapper(new ItemStorageDisk(null, 2304, UUID.randomUUID())));
        inventory = new Inventory(mock(Player.class));
    }

    @AfterEach
    void closeApi() { apiMock.close(); }

    @Test
    void taggedAmmoStacksButOtherTaggedPassiveItemsRemainSingle() {
        assertFalse(ResonanceStackRules.isLogicallyNonStackable(ammo("tacz:9mm", 64)));
        ItemStack passive = new ItemStack(Items.PAPER, 64);
        passive.getOrCreateTag().putString("effect", "passive");
        assertTrue(ResonanceStackRules.isLogicallyNonStackable(passive));
        assertTrue(ResonanceStackRules.isLogicallyNonStackable(new ItemStack(Items.DIAMOND_SWORD)));
    }

    @Test
    void realTaczAmmoWithDynamicStackSizeSplitsWithoutLosingRounds() {
        CommonAmmoIndex ammoIndex = mock(CommonAmmoIndex.class);
        when(ammoIndex.getStackSize()).thenReturn(128);
        try (MockedStatic<TimelessAPI> resources = mockStatic(TimelessAPI.class)) {
            resources.when(() -> TimelessAPI.getCommonAmmoIndex(new ResourceLocation("tacz", "9mm")))
                    .thenReturn(Optional.of(ammoIndex));
            ItemStack original = nativeAmmo("tacz:9mm", 128);
            assertEquals(128, original.getMaxStackSize());
            assertFalse(ResonanceStackRules.isLogicallyNonStackable(original));
            inventory.setItem(9, original);
            ResonanceBackpackContainer menu = menu();

            menu.quickMoveStack(null, 36);

            assertTrue(inventory.getItem(9).isEmpty());
            assertEquals(128, disk.getStored());
            assertEquals(2, disk.storedStacks().size());
            assertEquals(64, menu.getSlot(0).getItem().getCount());
            assertEquals(64, menu.getSlot(1).getItem().getCount());
        }
    }

    @Test
    void shiftStoresAndRetrievesTheWholeAmmoStackWithoutLosingNbt() {
        ItemStack original = ammo("tacz:9mm", 64);
        original.getOrCreateTag().putString("custom", "retained");
        inventory.setItem(9, original.copy());
        ResonanceBackpackContainer menu = menu();

        assertEquals(64, menu.quickMoveStack(null, 36).getCount());
        assertTrue(inventory.getItem(9).isEmpty());
        assertEquals(64, disk.getStored());
        assertEquals(1, disk.storedStacks().size());
        assertTrue(ItemStack.isSameItemSameTags(original, menu.getSlot(0).getItem()));
        assertEquals(64, menu.quickMoveStack(null, 0).getCount());
        assertEquals(0, disk.getStored());
        assertEquals(64, inventory.countItem(original.getItem()));
        assertTrue(ItemStack.isSameItemSameTags(original, inventory.getItem(8)));
    }

    @Test
    void shiftFillsExistingAmmoBeforeUsingAnotherSlotAndKeepsCalibersSeparate() {
        disk.insertView(7, ammo("tacz:9mm", 60), 60, false);
        disk.insertView(1, ammo("tacz:556x45", 20), 20, false);
        inventory.setItem(9, ammo("tacz:9mm", 10));
        ResonanceBackpackContainer menu = menu();

        menu.quickMoveStack(null, 36);

        assertEquals(64, menu.getSlot(7).getItem().getCount());
        assertEquals(6, menu.getSlot(0).getItem().getCount());
        assertEquals(20, menu.getSlot(1).getItem().getCount());
        assertEquals("tacz:556x45", menu.getSlot(1).getItem().getTag().getString("AmmoId"));
        assertEquals(90, disk.getStored());
    }

    @Test
    void fullLogicalSlotsKeepTheUnacceptedAmmoInThePlayerInventory() {
        for (int slot = 0; slot < 36; slot++) disk.insertView(slot, ammo("tacz:9mm", 64), 64, false);
        // 留出 4 个位置，但不留空槽。
        disk.extractExactView(0, ammo("tacz:9mm", 64), 4, false);
        inventory.setItem(9, ammo("tacz:9mm", 10));
        ResonanceBackpackContainer menu = menu();

        menu.quickMoveStack(null, 36);

        assertEquals(6, inventory.getItem(9).getCount());
        assertEquals(2304, disk.getStored());
        assertTrue(menu.quickMoveStack(null, 36).isEmpty());
        assertEquals(6, inventory.getItem(9).getCount());
    }

    @Test
    void rejectedDiskWritesLeaveTheSourceStackIntact() {
        inventory.setItem(9, ammo("tacz:9mm", 64));
        ResonanceBackpackContainer menu = menu();
        doReturn(ResonanceStorageView.SlotMutationResult.REJECTED)
                .when(disk).reconcileSlotView(anyInt(), any(), any());

        assertTrue(menu.quickMoveStack(null, 36).isEmpty());
        assertEquals(64, inventory.getItem(9).getCount());
        assertEquals(0, disk.getStored());
    }

    @Test
    void taggedSingleSlotItemsKeepOverflowInsteadOfTruncatingIt() {
        ItemStack stack = new ItemStack(Items.PAPER, 64);
        stack.getOrCreateTag().putString("effect", "passive");
        inventory.setItem(9, stack);
        ResonanceBackpackContainer menu = menu();

        menu.quickMoveStack(null, 36);

        assertEquals(36, disk.getStored());
        assertEquals(28, inventory.getItem(9).getCount());
        assertTrue(disk.storedStacks().stream().allMatch(stored -> stored.stack().getCount() == 1));
    }

    private ResonanceBackpackContainer menu() {
        return new ResonanceBackpackContainer(0, inventory, disk, null);
    }
}
