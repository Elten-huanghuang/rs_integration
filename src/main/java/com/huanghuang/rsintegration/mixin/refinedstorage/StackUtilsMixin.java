package com.huanghuang.rsintegration.mixin.refinedstorage;

import com.huanghuang.rsintegration.resonance.disk.ResonanceDiskWrapper;
import com.refinedmods.refinedstorage.api.storage.disk.IStorageDisk;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = com.refinedmods.refinedstorage.util.StackUtils.class, remap = false)
public abstract class StackUtilsMixin {

    @Inject(method = "createStorages", at = @At("TAIL"), remap = false)
    private static void rsi$enforceOneResonanceDisk(
            net.minecraft.server.level.ServerLevel level,
            ItemStack stack,
            int slotIndex,
            IStorageDisk<ItemStack>[] itemDisks,
            IStorageDisk<?>[] fluidDisks,
            java.util.function.Function<IStorageDisk<ItemStack>, IStorageDisk<ItemStack>> itemWrapper,
            java.util.function.Function<?, ?> fluidWrapper,
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
