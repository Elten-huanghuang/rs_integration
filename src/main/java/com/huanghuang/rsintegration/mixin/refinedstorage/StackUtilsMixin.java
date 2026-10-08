package com.huanghuang.rsintegration.mixin.refinedstorage;

import com.huanghuang.rsintegration.resonance.disk.ResonanceDiskWrapper;
import com.huanghuang.rsintegration.crafting.fluid.FluidContainerBucketSupport;
import com.refinedmods.refinedstorage.api.storage.disk.IStorageDisk;
import com.refinedmods.refinedstorage.util.StackUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidType;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandlerItem;
import org.apache.commons.lang3.tuple.Pair;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Function;

@Mixin(value = StackUtils.class, remap = false)
public abstract class StackUtilsMixin {

    @Inject(method = "getFluid(Lnet/minecraft/world/item/ItemStack;Z)Lorg/apache/commons/lang3/tuple/Pair;",
            at = @At("HEAD"), cancellable = true, remap = false)
    private static void rsi$fluidFromBucketSubclass(ItemStack stack, boolean simulate,
            CallbackInfoReturnable<Pair<ItemStack, FluidStack>> cir) {
        if (!FluidContainerBucketSupport.needsFallback(stack)) return;
        ItemStack container = stack.copyWithCount(1);
        IFluidHandlerItem handler = FluidContainerBucketSupport.getHandler(container);
        FluidStack drained = handler.drain(FluidType.BUCKET_VOLUME, simulate
                ? IFluidHandler.FluidAction.SIMULATE : IFluidHandler.FluidAction.EXECUTE);
        cir.setReturnValue(Pair.of(handler.getContainer().copy(), drained));
    }

    @Inject(method = "createStorages", at = @At("TAIL"), remap = false)
    private static void rsi$enforceOneResonanceDisk(
            ServerLevel level,
            ItemStack stack,
            int slotIndex,
            IStorageDisk<ItemStack>[] itemDisks,
            IStorageDisk<?>[] fluidDisks,
            Function<IStorageDisk<ItemStack>, IStorageDisk<ItemStack>> itemWrapper,
            Function<?, ?> fluidWrapper,
            CallbackInfo ci) {

        IStorageDisk<ItemStack> current = itemDisks[slotIndex];
        if (current == null) return;
        if (!ResonanceDiskWrapper.FACTORY_ID.equals(current.getFactoryId())) return;

        // If another slot already has a resonance disk, disable the current slot.
        // Only clear the itemDisks entry; leave fluidDisks untouched since the
        // current slot might hold a separate fluid disk that should remain active.
        for (int i = 0; i < itemDisks.length; i++) {
            if (i == slotIndex) continue;
            IStorageDisk<ItemStack> other = itemDisks[i];
            if (other != null && ResonanceDiskWrapper.FACTORY_ID.equals(other.getFactoryId())) {
                itemDisks[slotIndex] = null;
                // fluidDisks[slotIndex] intentionally NOT cleared — resonance disk
                // is item-only; a separate fluid disk in this slot should stay active.
                return;
            }
        }
    }
}
