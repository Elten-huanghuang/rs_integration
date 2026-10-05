package com.huanghuang.rsintegration.disk;

import com.huanghuang.rsintegration.disk.core.FrozenKey;
import com.huanghuang.rsintegration.disk.core.SaturatedTree;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityDispatcher;
import net.minecraftforge.common.capabilities.CapabilityProvider;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fluids.FluidStack;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UnifiedDiskLookupTest extends BootstrapTest {
    @Test void lazyCapabilityInitializationCannotHideAnAddedTag() throws Exception {
        ItemStack source = new ItemStack(Items.STONE);
        var initialized = CapabilityProvider.class.getDeclaredField("initialized");
        initialized.setAccessible(true); initialized.setBoolean(source, false);
        var lazyParent = CapabilityProvider.class.getDeclaredField("lazyParentSupplier");
        lazyParent.setAccessible(true);
        Supplier<ICapabilityProvider> attach = () -> {
            source.getOrCreateTag().putInt("attached", 1);
            return null;
        };
        lazyParent.set(source, attach);
        assertFalse(FrozenKey.plainItem(source));
        var core = UnifiedDiskCoreTest.core(4);
        core.insertItem(new ItemStack(Items.STONE), 5, true);
        assertEquals(-1, core.items.exactSlot(source));
        core.insertItem(source, 7, true);
        assertEquals(2, core.items.size());
    }

    @Test void plainAndEmptyTagsMergeAcrossDeletionAndSlotReuse() {
        var core = UnifiedDiskCoreTest.core(3);
        ItemStack plain = new ItemStack(Items.STONE), emptyTag = new ItemStack(Items.STONE);
        emptyTag.setTag(new CompoundTag());
        ItemStack variant = UnifiedDiskCoreTest.variant(1).itemStack(1);
        core.insertItem(plain, 7, true); core.insertItem(emptyTag, 8, true); core.insertItem(variant, 9, true);
        int plainSlot = core.items.exactSlot(plain), emptySlot = core.items.exactSlot(emptyTag);
        assertEquals(plainSlot, emptySlot);
        assertEquals(15, core.items.amount(plainSlot));
        assertEquals(core.items.exactSlot(FrozenKey.item(variant)), core.items.exactSlot(variant));
        assertEquals(1, core.insertItem(new ItemStack(Items.DIAMOND), 1, true));
        assertEquals(1, core.insertItem(plain, 1, true));
        core.extract(FrozenKey.Kind.ITEM, plainSlot, 16, true);
        assertEquals(-1, core.items.exactSlot(plain));
        assertEquals(1, core.insertItem(new ItemStack(Items.DIAMOND), 1, true));
        assertEquals(-1, core.items.exactSlot(plain));
        core.extract(FrozenKey.Kind.ITEM, core.items.exactSlot(variant), 9, true);
        assertEquals(2, core.insertItem(plain, 2, true));
        assertEquals(2, core.items.amount(core.items.exactSlot(plain)));
        assertEquals(2, core.items.amount(core.items.exactSlot(emptyTag)));
    }

    @Test void queriesIgnoreTopLevelAmountsButObserveEveryTagMutation() {
        var core = UnifiedDiskCoreTest.core(16);
        ItemStack source = new ItemStack(Items.STONE, 64);
        source.getOrCreateTag().putInt("Count", 17);
        assertEquals(7, core.insertItem(source, 7, false));
        assertEquals(0, core.items.size()); assertEquals(0, core.items.revision()); assertEquals(0, core.payloadBytes());
        core.insertItem(source, 7, true);
        int bytes = core.payloadBytes(), slot = core.items.exactSlot(source);
        source.setCount(3);
        assertEquals(slot, core.items.exactSlot(source));
        core.insertItem(source, 5, true);
        assertEquals(bytes, core.payloadBytes());
        assertEquals(12, core.items.amount(slot));
        source.getTag().putInt("Count", 18);
        assertEquals(-1, core.items.exactSlot(source));
        core.insertItem(source, 2, true);
        assertEquals(2, core.items.size());
        assertEquals(17, core.items.key(slot).itemStack(1).getTag().getInt("Count"));
        source.getTag().putDouble("nan", Double.NaN);
        assertThrows(IllegalArgumentException.class, () -> core.items.exactSlot(source));
        assertThrows(IllegalArgumentException.class, () -> core.insertItem(source, 1, true));
        assertEquals(14, core.items.total());
    }

    @Test void fluidLookupPreservesEmptyTagsNestedAmountsAndReturnedTemplateIsolation() {
        var core = UnifiedDiskCoreTest.core(8);
        FluidStack plain = new FluidStack(Fluids.WATER, 1000), empty = plain.copy(), tagged = plain.copy();
        empty.setTag(new CompoundTag()); tagged.getOrCreateTag().putInt("Amount", 33);
        core.insertFluid(plain, 10, true); core.insertFluid(empty, 11, true); core.insertFluid(tagged, 12, true);
        int slot = core.fluids.exactSlot(tagged);
        tagged.setAmount(5); assertEquals(slot, core.fluids.exactSlot(tagged));
        core.insertFluid(tagged, 2, true);
        tagged.getTag().putInt("Amount", 34);
        assertEquals(-1, core.fluids.exactSlot(tagged));
        assertEquals(3, core.fluids.size());
        assertNotEquals(core.fluids.exactSlot(plain), core.fluids.exactSlot(empty));
        FluidStack returned = core.fluids.key(slot).fluidStack(1);
        returned.getTag().putInt("Amount", 90);
        assertEquals(33, core.fluids.key(slot).fluidStack(1).getTag().getInt("Amount"));
        core.extract(FrozenKey.Kind.FLUID, core.fluids.exactSlot(plain), 10, true);
        assertEquals(-1, core.fluids.exactSlot(plain));
    }

    /** 单测没有 Forge attach 事件；手动挂真实 dispatcher，并让复制也挂同种序列化能力。 */
    private ItemStack capped(CompoundTag data) throws Exception {
        ItemStack stack = spy(new ItemStack(Items.STONE));
        ICapabilitySerializable<CompoundTag> provider = new ICapabilitySerializable<>() {
            @Override public <T> LazyOptional<T> getCapability(Capability<T> cap, Direction side) { return LazyOptional.empty(); }
            @Override public CompoundTag serializeNBT() { return data.copy(); }
            @Override public void deserializeNBT(CompoundTag tag) { data.merge(tag); }
        };
        var dispatcher = new CapabilityDispatcher(Map.of(new ResourceLocation("test", "identity"), provider), List.of());
        var caps = CapabilityProvider.class.getDeclaredField("capabilities"); caps.setAccessible(true); caps.set(stack, dispatcher);
        var initialized = CapabilityProvider.class.getDeclaredField("initialized"); initialized.setAccessible(true); initialized.setBoolean(stack, true);
        doAnswer(invocation -> {
            ItemStack copy = capped(data.copy());
            copy.setCount(stack.getCount());
            if (stack.getTag() != null) copy.setTag(stack.getTag().copy());
            return copy;
        }).when(stack).copy();
        return stack;
    }

    @Test void untaggedSerializableCapabilitiesNeverUsePlainTypeLookup() throws Exception {
        var core = UnifiedDiskCoreTest.core(8);
        CompoundTag data = new CompoundTag(); data.putInt("identity", 1);
        ItemStack capped = capped(data), plain = new ItemStack(Items.STONE);
        assertFalse(FrozenKey.plainItem(capped)); assertTrue(FrozenKey.plainItem(plain));
        core.insertItem(plain, 5, true); core.insertItem(capped, 7, true);
        int first = core.items.exactSlot(capped);
        assertNotEquals(core.items.exactSlot(plain), first);
        data.putInt("identity", 2);
        assertEquals(-1, core.items.exactSlot(capped));
        core.insertItem(capped, 9, true);
        assertEquals(3, core.items.size());
        assertEquals(7, core.items.amount(first)); assertEquals(21, core.items.total());
        assertEquals(core.items.exactSlot(FrozenKey.item(capped)), core.items.exactSlot(capped));
    }

    @Test void temporaryQueryKeysCannotBeStoredAndRemainEqualToFullKeys() {
        var core = UnifiedDiskCoreTest.core(4);
        ItemStack stack = UnifiedDiskCoreTest.variant(9).itemStack(6);
        FrozenKey query = FrozenKey.queryItem(stack), full = FrozenKey.item(stack);
        assertEquals(full, query); assertEquals(full.hashCode(), query.hashCode());
        stack.getTag().putInt("variant", 10);
        assertEquals(full, query);
        assertThrows(IllegalArgumentException.class, () -> core.insert(query, 1, true));
        assertThrows(IllegalArgumentException.class, () -> core.items.insert(query, 1, true));
        FluidStack fluid = new FluidStack(Fluids.WATER, 1); fluid.getOrCreateTag().putInt("variant", 9);
        assertEquals(FrozenKey.fluid(fluid), FrozenKey.queryFluid(fluid));
    }

    @Test void earlyExitTreeUpdatesStillRecoverEverySaturatedSubtree() {
        SaturatedTree tree = new SaturatedTree(64);
        int[] reference = new int[64]; Random random = new Random(10012026);
        for (int i = 0; i < 10000; i++) {
            int slot = random.nextInt(reference.length);
            int value = random.nextBoolean() ? Integer.MAX_VALUE : random.nextInt(200);
            reference[slot] = value; tree.set(slot, value);
            long sum = 0; for (int amount : reference) sum += amount;
            assertEquals((int) Math.min(Integer.MAX_VALUE, sum), tree.total());
        }
        for (int i = 0; i < reference.length; i++) tree.set(i, 0);
        assertEquals(0, tree.total());
    }
}
