package com.huanghuang.rsintegration.crafting.fluid;

import com.huanghuang.rsintegration.mixin.refinedstorage.StackUtilsMixin;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.fluids.capability.IFluidHandlerItem;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BucketStackUtilsTest extends BootstrapTest {
    private MockedStatic<FluidUtil> capabilities;

    @BeforeEach void supplyCapabilities() {
        capabilities = FluidContainerBucketTestFixtures.capabilities();
    }

    @AfterEach void releaseCapabilities() {
        capabilities.close();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void readsMissingCapabilityBucketWithoutMutatingOriginalStack(boolean simulate) throws Exception {
        var buckets = FluidContainerBucketTestFixtures.subclassBuckets("stackutils_poisonwater");
        ItemStack original = new ItemStack(buckets.filled(), 3);
        CallbackInfoReturnable<Pair<ItemStack, FluidStack>> callback = read(original, simulate);
        assertTrue(callback.isCancelled());
        Pair<ItemStack, FluidStack> result = callback.getReturnValue();
        assertEquals(1000, result.getRight().getAmount());
        assertSame(((BucketItem) buckets.filled()).getFluid(), result.getRight().getFluid());
        assertTrue(result.getLeft().is(simulate ? buckets.filled() : Items.BUCKET));
        assertEquals(1, result.getLeft().getCount());
        assertNotSame(original, result.getLeft());
        assertEquals(3, original.getCount());
        assertTrue(original.is(buckets.filled()));
        assertFalse(original.hasTag());
    }

    @Test
    void existingCapabilityAndNonBucketAreNotIntercepted() throws Exception {
        var buckets = FluidContainerBucketTestFixtures.subclassBuckets("stackutils_custom");
        IFluidHandlerItem handler = mock(IFluidHandlerItem.class);
        capabilities.when(() -> FluidUtil.getFluidHandler(any(ItemStack.class)))
                .thenReturn(LazyOptional.of(() -> handler));
        assertFalse(read(new ItemStack(buckets.filled()), false).isCancelled());
        verifyNoInteractions(handler);
        assertFalse(read(new ItemStack(Items.STONE), false).isCancelled());
        assertFalse(read(ItemStack.EMPTY, true).isCancelled());
    }

    private static CallbackInfoReturnable<Pair<ItemStack, FluidStack>> read(ItemStack stack, boolean simulate)
            throws Exception {
        Method method = StackUtilsMixin.class.getDeclaredMethod("rsi$fluidFromBucketSubclass",
                ItemStack.class, boolean.class, CallbackInfoReturnable.class);
        method.setAccessible(true);
        CallbackInfoReturnable<Pair<ItemStack, FluidStack>> callback = new CallbackInfoReturnable<>("getFluid", true);
        method.invoke(null, stack, simulate, callback);
        return callback;
    }
}
