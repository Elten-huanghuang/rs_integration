package com.huanghuang.rsintegration.disk;

import com.huanghuang.rsintegration.disk.core.FrozenKey;
import com.huanghuang.rsintegration.disk.core.ResourceTable;
import com.huanghuang.rsintegration.disk.core.SaturatedTree;
import com.huanghuang.rsintegration.disk.core.UnifiedDiskCore;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class UnifiedDiskCoreTest extends BootstrapTest {
    static UnifiedDiskCore core(int capacity) {
        return new UnifiedDiskCore(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new UnifiedDiskCore.Limits(capacity, capacity, 1048576, 268435456));
    }

    @Test
    void emptyRootTagIsNormalizedDuringItemInsertion() {
        var core = core(4);
        ItemStack plain = new ItemStack(Items.REDSTONE_BLOCK);
        ItemStack emptyTag = plain.copy();
        emptyTag.setTag(new CompoundTag());

        assertEquals(32, core.insertItem(plain, 32, true));
        assertEquals(21, core.insertItem(emptyTag, 21, true));
        assertEquals(1, core.items.size());
        assertEquals(53, core.items.total());
    }
    static FrozenKey variant(int id) {
        ItemStack stack = new ItemStack(Items.STONE);
        stack.getOrCreateTag().putInt("variant", id);
        return FrozenKey.item(stack);
    }

    @Test void frozenIdentityCoversOrderZerosTypesAndDefensiveCopies() throws Exception {
        ItemStack first = new ItemStack(Items.DIAMOND, 20), second = new ItemStack(Items.DIAMOND, 2);
        // 原版 valueOf 和普通 NBT 读取都折叠负零；构造模组可能产生的非缓存实例。
        var constructor = DoubleTag.class.getDeclaredConstructor(double.class);
        constructor.setAccessible(true);
        CompoundTag raw = new CompoundTag();
        raw.put("zero", constructor.newInstance(-0.0));
        first.setTag(raw); first.getTag().putInt("Count", 1);
        second.getOrCreateTag().putDouble("zero", 0.0); second.getOrCreateTag().putInt("Count", 1);
        FrozenKey a = FrozenKey.item(first), b = FrozenKey.item(second);
        assertEquals(a, b); assertEquals(a.hashCode(), b.hashCode());
        first.getTag().putInt("Count", 2);
        assertEquals(1, a.itemStack(1).getTag().getInt("Count"));
        a.itemStack(1).getTag().putInt("Count", 5);
        assertEquals(1, a.itemStack(1).getTag().getInt("Count"));
        assertEquals(Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits(a.itemStack(1).getTag().getDouble("zero")));
        second.getTag().putByte("Count", (byte) 1);
        assertNotEquals(a, FrozenKey.item(second));
        assertEquals(a, FrozenKey.load(FrozenKey.Kind.ITEM, a.payload()));
        assertArrayEquals(a.payload(), FrozenKey.load(FrozenKey.Kind.ITEM, a.payload()).payload());
        second.getTag().putDouble("nan", Double.NaN);
        assertThrows(IllegalArgumentException.class, () -> FrozenKey.item(second));
    }

    @Test void listOrderAndFluidTagsRemainSignificant() {
        ItemStack one = new ItemStack(Items.STONE), two = new ItemStack(Items.STONE);
        ListTag listOne = new ListTag(), listTwo = new ListTag();
        listOne.add(StringTag.valueOf("a")); listOne.add(StringTag.valueOf("b"));
        listTwo.add(StringTag.valueOf("b")); listTwo.add(StringTag.valueOf("a"));
        one.getOrCreateTag().put("list", listOne); two.getOrCreateTag().put("list", listTwo);
        assertNotEquals(FrozenKey.item(one), FrozenKey.item(two));
        FluidStack water = new FluidStack(Fluids.WATER, 1000);
        FrozenKey plain = FrozenKey.fluid(water);
        water.setAmount(Integer.MAX_VALUE);
        assertEquals(plain, FrozenKey.fluid(water));
        water.setTag(new CompoundTag()); water.getTag().putInt("temperature", 2);
        assertNotEquals(plain, FrozenKey.fluid(water));
    }

    @Test void intLimitsSimulationAndSaturationRecovery() {
        UnifiedDiskCore core = core(2);
        FrozenKey stone = variant(1), other = variant(2), third = variant(3);
        assertEquals(Integer.MAX_VALUE, core.insert(stone, Integer.MAX_VALUE, false));
        assertEquals(0, core.items.size()); assertEquals(0, core.items.revision()); assertEquals(0, core.payloadBytes());
        core.insert(stone, Integer.MAX_VALUE, true); core.insert(other, 20, true);
        assertEquals(Integer.MAX_VALUE, core.items.total());
        assertEquals(0, core.insert(stone, 10, true)); assertEquals(0, core.insert(third, 1, true));
        core.extract(FrozenKey.Kind.ITEM, core.items.exactSlot(stone), Integer.MAX_VALUE, true);
        assertEquals(20, core.items.total()); assertEquals(1, core.items.size());
        assertEquals(1, core.insert(third, 1, true));
        assertEquals(21, core.items.total());
    }

    @Test void handleCannotAliasReusedSlotAndBucketsKeepInsertionOrder() {
        UnifiedDiskCore core = core(3);
        FrozenKey first = variant(1), second = variant(2), third = variant(3);
        core.insert(first, 1, true); core.insert(second, 1, true);
        ResourceTable.Handle handle = core.items.handle(first);
        core.extract(FrozenKey.Kind.ITEM, handle.slot(), 1, true);
        core.insert(third, 1, true);
        assertFalse(core.items.valid(handle));
        assertEquals(handle.slot(), core.items.handle(third).slot());
        assertEquals(core.items.exactSlot(second), core.items.first(Items.STONE));
        assertTrue(core.items.valid(core.items.handle(third)));
    }

    @Test void amountOnlySnapshotsDoNotSerializeKeysAndOldAckLeavesNewDirty() {
        UnifiedDiskCore core = core(1024);
        FrozenKey key = variant(1); core.insert(key, 1, true);
        List<ResourceTable.PageSnapshot> initial = core.items.snapshot(false);
        core.items.acknowledge(initial);
        assertFalse(core.dirty());
        core.insert(key, 2, true);
        List<ResourceTable.PageSnapshot> amounts = core.items.snapshot(false);
        assertNull(amounts.get(0).keys());
        core.insert(key, 3, true);
        core.items.acknowledge(amounts);
        assertTrue(core.dirty()); assertNull(core.items.snapshot(false).get(0).keys());
    }

    @Test void fiftyThousandNbtVariantsSupportDirectLookup() {
        UnifiedDiskCore core = core(65536);
        for (int i = 0; i < 50000; i++) assertEquals(1, core.insert(variant(i), 1, true));
        assertEquals(50000, core.items.size());
        for (int i = 49999; i >= 0; i -= 13) {
            FrozenKey key = variant(i);
            assertEquals(1, core.items.amount(key));
            assertEquals(1, core.extract(FrozenKey.Kind.ITEM, core.items.exactSlot(key), 1, true));
        }
    }

    @Test void randomOperationsMatchReferenceModel() {
        UnifiedDiskCore core = core(128);
        Map<FrozenKey, Integer> reference = new HashMap<>();
        FrozenKey[] keys = new FrozenKey[64]; for (int i = 0; i < keys.length; i++) keys[i] = variant(i);
        Random random = new Random(7102026);
        for (int step = 0; step < 20000; step++) {
            FrozenKey key = keys[random.nextInt(keys.length)];
            int amount = random.nextBoolean() ? random.nextInt(500) + 1 : Integer.MAX_VALUE;
            int before = reference.getOrDefault(key, 0);
            boolean perform = random.nextInt(5) != 0;
            if (random.nextBoolean()) {
                int expected = Math.min(amount, Integer.MAX_VALUE - before);
                assertEquals(expected, core.insert(key, amount, perform));
                if (perform && expected > 0) reference.put(key, before + expected);
            } else {
                int expected = Math.min(amount, before);
                assertEquals(expected, core.extract(FrozenKey.Kind.ITEM, core.items.exactSlot(key), amount, perform));
                if (perform) { if (before == expected) reference.remove(key); else reference.put(key, before - expected); }
            }
            assertEquals(reference.size(), core.items.size());
            int total = 0; for (int value : reference.values()) total = SaturatedTree.add(total, value);
            assertEquals(total, core.items.total()); assertEquals(reference.getOrDefault(key, 0), core.items.amount(key));
            assertEquals(reference.values().stream().mapToLong(Integer::longValue).sum(), core.items.displayTotal());
        }
    }

    @Test void crossThreadAccessIsRejected() throws Exception {
        UnifiedDiskCore core = core(1);
        Throwable[] failure = new Throwable[1];
        Thread thread = new Thread(() -> { try { core.insert(variant(1), 1, true); } catch (Throwable e) { failure[0] = e; } });
        thread.start(); thread.join(); assertInstanceOf(IllegalStateException.class, failure[0]);
    }
}
