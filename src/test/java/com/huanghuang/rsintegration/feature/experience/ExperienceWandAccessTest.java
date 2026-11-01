package com.huanghuang.rsintegration.feature.experience;

import com.huanghuang.rsintegration.feature.resonance.api.ResonanceStorageMenu;
import com.huanghuang.rsintegration.feature.resonance.api.ResonanceStorageView;
import com.huanghuang.rsintegration.feature.resonance.bridge.ClientDiskData;
import com.huanghuang.rsintegration.feature.resonance.bridge.ResonanceInventoryBridge;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.huanghuang.rsintegration.testutil.ExperienceWandTestItems;
import com.huanghuang.rsintegration.util.PlayerUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExperienceWandAccessTest extends BootstrapTest {
    private ServerPlayer player;
    private Inventory inventory;
    private MockedStatic<ResonanceInventoryBridge> bridge;

    @BeforeEach
    void setUp() {
        player = mock(ServerPlayer.class);
        inventory = new Inventory(player);
        when(player.getInventory()).thenReturn(inventory);
        when(player.getMainHandItem()).thenAnswer(call -> inventory.getSelected());
        when(player.getOffhandItem()).thenAnswer(call -> inventory.offhand.get(0));
        bridge = mockStatic(ResonanceInventoryBridge.class, CALLS_REAL_METHODS);
        bridge.when(() -> ResonanceInventoryBridge.getViews(player)).thenReturn(List.of());
        ClientDiskData.clear();
    }

    @AfterEach
    void tearDown() {
        bridge.close();
        ClientDiskData.clear();
    }

    @Test
    void inactiveWandEnablesTaxFreeCostsWithoutRunningAutomaticTransfers() {
        inventory.setItem(12, ExperienceWandTestItems.wand());

        assertTrue(ExperienceWandAccess.hasWand(player));
        assertNull(ExperienceWandAccess.selected(player));

        inventory.setItem(12, ItemStack.EMPTY);
        assertFalse(ExperienceWandAccess.hasWand(player));
    }

    @Test
    void activeCarriedWandsTakePriorityOverTheDiskAndOnlyOneRuns() {
        ItemStack carried = activeWand();
        inventory.setItem(8, carried);
        Disk disk = disk(activeWand());
        bridge.when(() -> ResonanceInventoryBridge.getViews(player)).thenReturn(List.of(disk.view()));

        ExperienceWandAccess.Selection selected = ExperienceWandAccess.selected(player);
        assertNotNull(selected);
        assertSame(carried, selected.stack());
        selected.use(player, stack -> ExperienceWandData.buffer(stack, 500L));

        assertEquals(500L, ExperienceWandData.buffer(carried));
        assertEquals(0L, ExperienceWandData.buffer(disk.contents().get()));
        verify(disk.view(), never()).extractExactView(anyInt(), any(), anyInt(), anyBoolean());
    }

    @Test
    void mainHandThenOffhandThenInventoryDetermineCarriedPriority() {
        ItemStack main = activeWand();
        ItemStack offhand = activeWand();
        ItemStack backpack = activeWand();
        inventory.setItem(inventory.selected, main);
        inventory.offhand.set(0, offhand);
        inventory.setItem(12, backpack);

        assertSame(main, ExperienceWandAccess.selected(player).stack());
        inventory.setItem(inventory.selected, ItemStack.EMPTY);
        assertSame(offhand, ExperienceWandAccess.selected(player).stack());
        inventory.offhand.set(0, ItemStack.EMPTY);
        assertSame(backpack, ExperienceWandAccess.selected(player).stack());
    }

    @Test
    void diskWandPersistsLongBufferAndPendingTransferWithoutReusingTheSnapshot() {
        Disk disk = disk(activeWand());
        ExperienceWandData.buffer(disk.contents().get(), 5_000_000_000L);
        ExperienceWandData.tag(disk.contents().get()).putLong(ExperienceWandData.PENDING_TARGET, 5000L);
        bridge.when(() -> ResonanceInventoryBridge.getViews(player)).thenReturn(List.of(disk.view()));

        for (int i = 0; i < 2; i++) {
            ExperienceWandAccess.Selection selected = ExperienceWandAccess.selected(player);
            assertNotNull(selected);
            selected.use(player, stack -> {
                assertTrue(disk.contents().get().isEmpty(), "结算前必须取得真实杖，不能只修改快照");
                ExperienceWandData.buffer(stack, ExperienceWandData.buffer(stack) - 20L);
            });
        }

        assertEquals(5_000_000_000L - 40L, ExperienceWandData.buffer(disk.contents().get()));
        assertEquals(5000L, ExperienceWandData.tag(disk.contents().get())
                .getLong(ExperienceWandData.PENDING_TARGET));
        assertEquals(ExperienceWandSettings.Mode.KEEP,
                ExperienceWandSettings.fromStack(disk.contents().get()).mode());
        assertTrue(ExperienceWandAccess.hasWand(player));
        verify(disk.view(), times(2)).markDirty(player);
    }

    @Test
    void selectionCanUseAnyBackendAndSkipsInactiveDiskWands() {
        Disk first = disk(ExperienceWandTestItems.wand());
        Disk second = disk(activeWand());
        when(first.view().backendId()).thenReturn("refinedstorage");
        when(second.view().backendId()).thenReturn("beyonddimensions");
        bridge.when(() -> ResonanceInventoryBridge.getViews(player))
                .thenReturn(List.of(first.view(), second.view()));

        ExperienceWandAccess.Selection selected = ExperienceWandAccess.selected(player);

        assertNotNull(selected);
        assertSame(second.view(), selected.disk());
        selected.use(player, stack -> ExperienceWandData.buffer(stack, Long.MAX_VALUE));
        assertEquals(Long.MAX_VALUE, ExperienceWandData.buffer(second.contents().get()));
        assertEquals(0L, ExperienceWandData.buffer(first.contents().get()));
    }

    @Test
    void rejectedOrStaleDiskExtractionNeverRunsTheExperienceAction() {
        Disk disk = disk(activeWand());
        bridge.when(() -> ResonanceInventoryBridge.getViews(player)).thenReturn(List.of(disk.view()));
        ExperienceWandAccess.Selection selected = ExperienceWandAccess.selected(player);
        disk.contents().get().getOrCreateTag().putString("changed", "after_selection");
        AtomicInteger actions = new AtomicInteger();

        selected.use(player, stack -> actions.incrementAndGet());

        assertEquals(0, actions.get());
        verify(disk.view(), never()).insertView(anyInt(), any(), anyInt(), anyBoolean());
    }

    @Test
    void actionFailureStillReturnsTheUpdatedWandToItsOriginalSlot() {
        Disk disk = disk(activeWand());
        bridge.when(() -> ResonanceInventoryBridge.getViews(player)).thenReturn(List.of(disk.view()));
        ExperienceWandAccess.Selection selected = ExperienceWandAccess.selected(player);

        assertThrows(IllegalStateException.class, () -> selected.use(player, stack -> {
            ExperienceWandData.buffer(stack, 100L);
            throw new IllegalStateException("test action failure");
        }));

        assertEquals(100L, ExperienceWandData.buffer(disk.contents().get()));
        verify(disk.view()).markDirty(player);
    }

    @Test
    void failedDiskReturnUsesRecoveryWithTheUpdatedBuffer() {
        Disk disk = disk(activeWand());
        bridge.when(() -> ResonanceInventoryBridge.getViews(player)).thenReturn(List.of(disk.view()));
        ExperienceWandAccess.Selection selected = ExperienceWandAccess.selected(player);
        when(disk.view().insertView(eq(4), any(), eq(1), eq(false)))
                .thenAnswer(call -> call.<ItemStack>getArgument(1).copy());
        try (MockedStatic<PlayerUtils> recovery = mockStatic(PlayerUtils.class)) {
            selected.use(player, stack -> ExperienceWandData.buffer(stack, 5_000_000_000L));

            recovery.verify(() -> PlayerUtils.safeGiveToStorage(eq(player),
                    argThat(stack -> ExperienceWandData.buffer(stack) == 5_000_000_000L), isNull()));
        }
        assertTrue(disk.contents().get().isEmpty());
    }

    @Test
    void openingTheResonanceMenuPausesDiskMutationButNotTaxFreePresence() {
        Disk disk = disk(activeWand());
        bridge.when(() -> ResonanceInventoryBridge.getViews(player)).thenReturn(List.of(disk.view()));
        player.containerMenu = mock(AbstractContainerMenu.class,
                withSettings().extraInterfaces(ResonanceStorageMenu.class));

        assertNull(ExperienceWandAccess.selected(player));
        assertTrue(ExperienceWandAccess.hasWand(player));
        verify(disk.view(), never()).extractExactView(anyInt(), any(), anyInt(), anyBoolean());
    }

    @Test
    void clientUsesSyncedDiskPresenceButServerIgnoresClientState() {
        Player clientPlayer = mock(Player.class);
        when(clientPlayer.getInventory()).thenReturn(new Inventory(clientPlayer));
        when(clientPlayer.getMainHandItem()).thenReturn(ItemStack.EMPTY);
        when(clientPlayer.getOffhandItem()).thenReturn(ItemStack.EMPTY);
        ClientDiskData.apply(0, 0, 0, 1L, List.of(), true);

        assertTrue(ExperienceWandAccess.hasWand(clientPlayer));
        assertFalse(ExperienceWandAccess.hasWand(player));
        ClientDiskData.clear();
        assertFalse(ExperienceWandAccess.hasWand(clientPlayer));
        assertFalse(ExperienceWandAccess.hasWand(null));
        assertFalse(ExperienceWandAccess.isWand(new ItemStack(Items.STICK)));
    }

    private static ItemStack activeWand() {
        ItemStack stack = ExperienceWandTestItems.wand();
        new ExperienceWandSettings(ExperienceWandSettings.Mode.KEEP, 30, true, 1).save(stack);
        return stack;
    }

    private static Disk disk(ItemStack initial) {
        ResonanceStorageView view = mock(ResonanceStorageView.class, CALLS_REAL_METHODS);
        AtomicReference<ItemStack> contents = new AtomicReference<>(initial.copy());
        when(view.storedStacks()).thenAnswer(call -> contents.get().isEmpty()
                ? List.of() : List.of(new ResonanceStorageView.StoredStack(4, contents.get())));
        when(view.extractExactView(eq(4), any(), eq(1), eq(false))).thenAnswer(call -> {
            if (!ItemStack.isSameItemSameTags(contents.get(), call.getArgument(1))) return ItemStack.EMPTY;
            ItemStack claimed = contents.get().copy();
            contents.set(ItemStack.EMPTY);
            return claimed;
        });
        when(view.insertView(eq(4), any(), eq(1), eq(false))).thenAnswer(call -> {
            ItemStack stack = call.getArgument(1);
            if (!contents.get().isEmpty()) return stack.copy();
            contents.set(stack.copy());
            return ItemStack.EMPTY;
        });
        return new Disk(view, contents);
    }

    private record Disk(ResonanceStorageView view, AtomicReference<ItemStack> contents) {}
}
