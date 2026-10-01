package com.huanghuang.rsintegration.mixin.refinedstorage;

import com.huanghuang.rsintegration.disk.rs.UnifiedDiskMounts;
import com.refinedmods.refinedstorage.api.storage.disk.IStorageDisk;
import com.refinedmods.refinedstorage.util.StackUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Function;

@Mixin(value = StackUtils.class, remap = false)
public abstract class UnifiedDiskStackUtilsMixin {
    @Inject(method = "createStorages", at = @At("HEAD"), cancellable = true, require = 1)
    private static void rsi$mountBoth(ServerLevel level, ItemStack stack, int slot,
                                    IStorageDisk<ItemStack>[] items, IStorageDisk<FluidStack>[] fluids,
                                    Function<IStorageDisk<ItemStack>, IStorageDisk> itemWrapper,
                                    Function<IStorageDisk<FluidStack>, IStorageDisk> fluidWrapper, CallbackInfo ci) {
        if (UnifiedDiskMounts.create(level, stack, slot, items, fluids, itemWrapper, fluidWrapper)) ci.cancel();
    }
}
