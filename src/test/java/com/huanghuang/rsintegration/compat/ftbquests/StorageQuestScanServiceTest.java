package com.huanghuang.rsintegration.compat.ftbquests;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
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

    @Test
    void repeatedTaskMatchingReusesTheIsolatedNbtSnapshot() {
        ItemStack source = new ItemStack(Items.DIAMOND, 3);
        CompoundTag contents = new CompoundTag();
        contents.putString("owner", "before");
        contents.putByteArray("payload", new byte[64 * 1024]);
        source.getOrCreateTag().put("contents", contents);
        var entry = new QuestScanItems.Entry(source, 3L);
        source.setCount(1);
        contents.putString("owner", "after");

        AtomicReference<ItemStack> seen = new AtomicReference<>();
        Predicate<ItemStack> matcher = stack -> {
            if (seen.get() == null) seen.set(stack);
            assertSame(seen.get(), stack, "不同任务应复用同一物品快照，不重复深复制 NBT");
            assertNotSame(source, stack);
            assertEquals(1, stack.getCount());
            return "before".equals(stack.getTag().getCompound("contents").getString("owner"));
        };
        for (int task = 0; task < 2_000; task++) {
            assertEquals(3L, QuestScanItems.countMatching(List.of(entry), matcher, 10L));
        }

        ItemStack exported = entry.stack();
        exported.getTag().getCompound("contents").putString("owner", "exported");
        assertEquals(3L, QuestScanItems.countMatching(List.of(entry), matcher, 10L),
                "外部读取并修改物品副本不能改变扫描快照");
    }

    @Test
    void matchingKeepsItemNbtAndAggregatesInventoryAndCuriosAmounts() {
        ItemStack matching = new ItemStack(Items.DIAMOND);
        matching.getOrCreateTag().putString("variant", "wanted");
        ItemStack otherNbt = matching.copy();
        otherNbt.getOrCreateTag().putString("variant", "other");
        List<QuestScanItems.Entry> items = List.of(
                new QuestScanItems.Entry(matching, 3L),
                new QuestScanItems.Entry(otherNbt, 64L),
                new QuestScanItems.Entry(new ItemStack(Items.EMERALD), 64L),
                new QuestScanItems.Entry(matching, 2L));

        assertEquals(5L, QuestScanItems.countMatching(items,
                stack -> ItemStack.isSameItemSameTags(stack, matching), 10L));
    }

    @Test
    void matchingStopsAtTheTaskTarget() {
        List<QuestScanItems.Entry> items = List.of(
                new QuestScanItems.Entry(new ItemStack(Items.DIAMOND), 8L),
                new QuestScanItems.Entry(new ItemStack(Items.EMERALD), 1L));

        assertEquals(4L, QuestScanItems.countMatching(items, stack -> {
            assertEquals(Items.DIAMOND, stack.getItem(), "达到目标后不能继续匹配后续物品");
            return true;
        }, 4L));
    }

    @Test
    void matchingLargeStorageAmountsDoesNotOverflow() {
        List<QuestScanItems.Entry> items = List.of(
                new QuestScanItems.Entry(new ItemStack(Items.DIAMOND), Long.MAX_VALUE - 2L),
                new QuestScanItems.Entry(new ItemStack(Items.DIAMOND), 10L));

        assertEquals(Long.MAX_VALUE, QuestScanItems.countMatching(items, stack -> true, Long.MAX_VALUE));
    }

    @Test
    void failingOptionalFiltersDoNotPreventMatchingOtherItems() {
        List<QuestScanItems.Entry> items = List.of(
                new QuestScanItems.Entry(new ItemStack(Items.DIAMOND), 8L),
                new QuestScanItems.Entry(new ItemStack(Items.EMERALD), 8L),
                new QuestScanItems.Entry(new ItemStack(Items.TORCH), 3L));

        assertEquals(3L, QuestScanItems.countMatching(items, stack -> {
            if (stack.is(Items.DIAMOND)) throw new IllegalStateException("broken filter");
            if (stack.is(Items.EMERALD)) throw new NoClassDefFoundError("optional filter");
            return stack.is(Items.TORCH);
        }, 10L));
    }
}
