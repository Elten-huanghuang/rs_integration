package com.huanghuang.rsintegration.compat.ftbquests;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StorageQuestScanServiceTest extends BootstrapTest {

    @Test
    void automaticScanOnlyKeepsNewlyUnlockedTasks() {
        assertEquals(List.of(3L, 4L),
                QuestTaskAvailability.newlyAvailableTaskIds(
                        List.of(1L, 2L), List.of(1L, 3L, 2L, 4L)));
    }

    @Test
    void resetTaskIsNotTreatedAsNewlyUnlocked() {
        assertEquals(List.of(),
                QuestTaskAvailability.newlyAvailableTaskIds(
                        List.of(10L), List.of(10L)));
    }

    @Test
    void inventorySnapshotIncludesEveryVanillaAndProvidedCuriosSlot() {
        ServerPlayer player = mock(ServerPlayer.class);
        Inventory inventory = new Inventory(player);
        inventory.items.set(0, new ItemStack(Items.DIAMOND, 3));
        inventory.armor.set(0, new ItemStack(Items.IRON_BOOTS));
        inventory.offhand.set(0, new ItemStack(Items.TORCH, 7));
        when(player.getInventory()).thenReturn(inventory);

        ItemStack curio = new ItemStack(Items.EMERALD, 2);
        var snapshot = QuestScanItems.fromPlayer(player, List.of(curio));

        assertEquals(List.of(Items.DIAMOND, Items.IRON_BOOTS, Items.TORCH, Items.EMERALD),
                snapshot.stream().map(item -> item.stack().getItem()).toList());
        assertEquals(List.of(3L, 1L, 7L, 2L),
                snapshot.stream().map(QuestScanItems.Entry::amount).toList());

        inventory.items.get(0).setCount(1);
        curio.setCount(1);
        assertEquals(List.of(3L, 1L, 7L, 2L),
                snapshot.stream().map(QuestScanItems.Entry::amount).toList());
    }
}
