package com.huanghuang.rsintegration.mixin.refinedstorage;

import com.huanghuang.rsintegration.disk.rs.UnifiedMountCoordinator;
import com.huanghuang.rsintegration.disk.rs.UnifiedDiskRoot;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.refinedmods.refinedstorage.api.storage.disk.IStorageDisk;
import com.refinedmods.refinedstorage.apiimpl.network.node.diskmanipulator.DiskManipulatorNetworkNode;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Function;

@Mixin(value = DiskManipulatorNetworkNode.class, remap = false)
public abstract class UnifiedDiskManipulatorMixin {
    @WrapOperation(method = {"lambda$new$2", "lambda$new$5"}, at = @At(value = "INVOKE", target =
            "Lcom/refinedmods/refinedstorage/util/StackUtils;createStorages(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/item/ItemStack;I[Lcom/refinedmods/refinedstorage/api/storage/disk/IStorageDisk;[Lcom/refinedmods/refinedstorage/api/storage/disk/IStorageDisk;Ljava/util/function/Function;Ljava/util/function/Function;)V"), require = 2)
    private void rsi$scope(ServerLevel level, ItemStack stack, int slot, IStorageDisk<ItemStack>[] items,
                           IStorageDisk<FluidStack>[] fluids, Function<?, ?> itemWrapper, Function<?, ?> fluidWrapper,
                           Operation<Void> original) {
        UnifiedMountCoordinator.withCaller((DiskManipulatorNetworkNode) (Object) this, () -> {
            original.call(level, stack, slot, items, fluids, itemWrapper, fluidWrapper); return null;
        });
    }

    // 统一盘按 Key 种类限制容量，不能用总数量==容量判断已满。
    @Inject(method = {"isItemDiskDone", "isFluidDiskDone"}, at = @At("HEAD"), cancellable = true, require = 2)
    private void rsi$capacityByKeys(IStorageDisk<?> disk, int slot, CallbackInfoReturnable<Boolean> cir) {
        if (UnifiedDiskRoot.FACTORY_ID.equals(disk.getFactoryId())
                && ((DiskManipulatorNetworkNode) (Object) this).getIoMode() == DiskManipulatorNetworkNode.IO_MODE_EXTRACT) {
            cir.setReturnValue(false);
        }
    }
}
