package com.huanghuang.rsintegration.disk;

import com.huanghuang.rsintegration.disk.core.FrozenKey;
import com.huanghuang.rsintegration.disk.core.SaturatedTree;
import com.huanghuang.rsintegration.storage.StorageIdentityBytes;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class UnifiedDiskStructuralIdentityTest extends BootstrapTest {
    private ItemStack item(CompoundTag tag) {
        ItemStack item = new ItemStack(Items.STONE); item.setTag(tag); return item;
    }

    private CompoundTag data(Random random, int variant) {
        CompoundTag tag = new CompoundTag();
        tag.putByte("byte", (byte) variant); tag.putShort("short", (short) random.nextInt());
        tag.putInt("int", random.nextInt()); tag.putLong("long", random.nextLong());
        tag.putFloat("float", random.nextFloat()); tag.putDouble("double", random.nextDouble());
        tag.putString("text", "中文😃/" + variant);
        tag.putByteArray("bytes", new byte[] {(byte) variant, 3});
        tag.putIntArray("ints", new int[] {variant, random.nextInt()});
        tag.putLongArray("longs", new long[] {variant, random.nextLong()});
        ListTag list = new ListTag();
        list.add(StringTag.valueOf("a")); list.add(StringTag.valueOf("b" + variant));
        tag.put("list", list);
        CompoundTag nested = new CompoundTag(); nested.putInt("Amount", variant); tag.put("nested", nested);
        return tag;
    }

    @Test void allNbtTypesAndReorderedCompoundsMatchCanonicalReference() {
        Random random = new Random(10012026);
        for (int i = 0; i < 1000; i++) {
            CompoundTag a = data(random, i), b = new CompoundTag();
            var names = a.getAllKeys().stream().sorted().toList();
            for (int j = names.size() - 1; j >= 0; j--) b.put(names.get(j), a.get(names.get(j)).copy());
            FrozenKey full = FrozenKey.item(item(a)), query = FrozenKey.queryItem(item(b));
            assertArrayEquals(StorageIdentityBytes.exact(a), StorageIdentityBytes.exact(b));
            assertEquals(full, query); assertEquals(full.hashCode(), query.hashCode());
            b.getCompound("nested").putInt("Amount", i + 1);
            assertFalse(Arrays.equals(StorageIdentityBytes.exact(a), StorageIdentityBytes.exact(b)));
            assertNotEquals(full, FrozenKey.queryItem(item(b)));
        }
    }

    @Test void collisionsNeverMergeResources() {
        CompoundTag a = new CompoundTag(), b = new CompoundTag();
        a.putString("name", "Aa"); b.putString("name", "BB");
        FrozenKey first = FrozenKey.item(item(a)), second = FrozenKey.item(item(b));
        assertEquals(first.hashCode(), second.hashCode()); assertNotEquals(first, second);
        var core = UnifiedDiskCoreTest.core(4);
        core.insertItem(item(a), 7, true); core.insertItem(item(b), 9, true);
        assertEquals(2, core.items.size()); assertEquals(7, core.items.amount(first)); assertEquals(9, core.items.amount(second));
    }

    @Test void signedZerosAndMalformedUnicodeKeepPreviousEncodingSemantics() throws Exception {
        var constructor = DoubleTag.class.getDeclaredConstructor(double.class); constructor.setAccessible(true);
        CompoundTag a = new CompoundTag(), b = new CompoundTag();
        a.put("zero", constructor.newInstance(-0.0)); b.putDouble("zero", 0);
        a.putString("string", "\uD800"); b.putString("string", "?");
        assertArrayEquals(StorageIdentityBytes.exact(a), StorageIdentityBytes.exact(b));
        assertEquals(FrozenKey.item(item(a)), FrozenKey.queryItem(item(b)));
        assertEquals(FrozenKey.item(item(a)).hashCode(), FrozenKey.queryItem(item(b)).hashCode());
        a = new CompoundTag(); b = new CompoundTag(); a.putInt("\uD800", 1); b.putInt("?", 1);
        assertEquals(FrozenKey.item(item(a)), FrozenKey.item(item(b)));
    }

    @Test void fluidQueriesAndPromotionNeverRetainCallerMutableArrays() {
        FluidStack source = new FluidStack(Fluids.WATER, 1000);
        source.getOrCreateTag().putIntArray("array", new int[] {1, 2});
        FrozenKey query = FrozenKey.queryFluid(source), stored = query.freezeFluid(source);
        source.getTag().getIntArray("array")[0] = 9;
        assertEquals(query, stored); assertNotEquals(query, FrozenKey.queryFluid(source));
        stored.fluidStack(1).getTag().getIntArray("array")[0] = 7;
        assertEquals(1, stored.fluidStack(1).getTag().getIntArray("array")[0]);
    }

    @Test void promotionRechecksAnIdentityChangedAfterQuery() {
        ItemStack source = item(data(new Random(2), 1));
        FrozenKey query = FrozenKey.queryItem(source);
        source.getTag().putInt("int", 99);
        assertEquals(FrozenKey.item(source), query.freezeItem(source));
        assertNotEquals(query, query.freezeItem(source));
    }

    @Test void queryStillRejectsOversizedAndDeepPayloadsAndNaN() {
        ItemStack source = item(new CompoundTag());
        source.getTag().putByteArray("payload", new byte[FrozenKey.MAX_BYTES]);
        assertThrows(IllegalArgumentException.class, () -> FrozenKey.queryItem(source));
        CompoundTag deep = new CompoundTag(), tail = deep;
        for (int i = 0; i < 514; i++) { CompoundTag next = new CompoundTag(); tail.put("x", next); tail = next; }
        source.setTag(deep); assertThrows(IllegalArgumentException.class, () -> FrozenKey.queryItem(source));
        source.setTag(new CompoundTag()); source.getTag().putFloat("nan", Float.NaN);
        assertThrows(IllegalArgumentException.class, () -> FrozenKey.queryItem(source));
    }

    @Test void stackPathRandomBatchesSimulationAndDeletionMatchReference() {
        var core = UnifiedDiskCoreTest.core(64);
        var reference = new HashMap<Integer, Integer>();
        Random random = new Random(22102026);
        for (int step = 0; step < 15000; step++) {
            int id = random.nextInt(96), before = reference.getOrDefault(id, 0);
            int requested = switch (random.nextInt(4)) { case 0 -> 1; case 1 -> 64; case 2 -> 1000; default -> Integer.MAX_VALUE; };
            boolean perform = random.nextBoolean();
            ItemStack item = new ItemStack(Items.STONE); item.getOrCreateTag().putInt("variant", id);
            FluidStack fluid = new FluidStack(Fluids.WATER, 1000); fluid.setTag(item.getTag().copy());
            int expected;
            if (random.nextBoolean()) {
                expected = before == 0 && reference.size() >= 64 ? 0 : Math.min(requested, Integer.MAX_VALUE - before);
                assertEquals(expected, core.insertItem(item, requested, perform));
                assertEquals(expected, core.insertFluid(fluid, requested, perform));
                if (perform && expected > 0) reference.put(id, before + expected);
            } else {
                expected = Math.min(requested, before);
                assertEquals(expected, core.extract(FrozenKey.Kind.ITEM, core.items.exactSlot(item), requested, perform));
                assertEquals(expected, core.extract(FrozenKey.Kind.FLUID, core.fluids.exactSlot(fluid), requested, perform));
                if (perform) { if (expected == before) reference.remove(id); else reference.put(id, before - expected); }
            }
            int total = 0; for (int amount : reference.values()) total = SaturatedTree.add(total, amount);
            assertEquals(reference.size(), core.items.size()); assertEquals(reference.size(), core.fluids.size());
            assertEquals(total, core.items.total()); assertEquals(total, core.fluids.total());
        }
    }

    @Test void dirtyQueueSurvivesOldAcknowledgementsAndOutOfOrderPageRemoval() {
        var core = UnifiedDiskCoreTest.core(4096);
        for (int i = 0; i < 3000; i++) core.insert(UnifiedDiskCoreTest.variant(i), 1, true);
        core.items.acknowledge(core.items.snapshot(false)); assertFalse(core.dirty());
        for (int i : new int[] {2048, 0, 1024}) core.insert(UnifiedDiskCoreTest.variant(i), 1, true);
        var old = core.items.snapshot(false); assertEquals(3, old.size());
        core.insert(UnifiedDiskCoreTest.variant(0), 1, true);
        core.items.acknowledge(old); assertTrue(core.dirty());
        var remaining = core.items.snapshot(false); assertEquals(1, remaining.size());
        assertEquals(0, remaining.get(0).index()); assertNull(remaining.get(0).keys());
        core.items.acknowledge(remaining); assertFalse(core.dirty());
        assertTrue(core.items.snapshot(false).isEmpty()); assertEquals(6, core.items.snapshot(true).size());
    }
}
