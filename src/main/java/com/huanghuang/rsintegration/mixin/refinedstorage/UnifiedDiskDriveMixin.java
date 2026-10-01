package com.huanghuang.rsintegration.mixin.refinedstorage;

import com.huanghuang.rsintegration.disk.rs.UnifiedDiskRoot;
import com.huanghuang.rsintegration.disk.rs.UnifiedMountCoordinator;
import com.huanghuang.rsintegration.disk.rs.UnifiedDiskItem;
import com.huanghuang.rsintegration.disk.rs.UnifiedDiskManager;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.refinedmods.refinedstorage.RS;
import com.refinedmods.refinedstorage.api.storage.disk.IStorageDisk;
import com.refinedmods.refinedstorage.apiimpl.network.node.DiskState;
import com.refinedmods.refinedstorage.apiimpl.network.node.diskdrive.DiskDriveNetworkNode;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Function;

@Mixin(value = DiskDriveNetworkNode.class, remap = false)
public abstract class UnifiedDiskDriveMixin {
    @WrapOperation(method = "lambda$new$2", at = @At(value = "INVOKE", target =
            "Lcom/refinedmods/refinedstorage/util/StackUtils;createStorages(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/item/ItemStack;I[Lcom/refinedmods/refinedstorage/api/storage/disk/IStorageDisk;[Lcom/refinedmods/refinedstorage/api/storage/disk/IStorageDisk;Ljava/util/function/Function;Ljava/util/function/Function;)V"), require = 1)
    private void rsi$scope(ServerLevel level, ItemStack stack, int slot, IStorageDisk<ItemStack>[] items,
                           IStorageDisk<FluidStack>[] fluids, Function<?, ?> itemWrapper, Function<?, ?> fluidWrapper,
                           Operation<Void> original) {
        UnifiedMountCoordinator.withCaller((DiskDriveNetworkNode) (Object) this, () -> {
            original.call(level, stack, slot, items, fluids, itemWrapper, fluidWrapper); return null;
        });
    }

    @Inject(method = "getEnergyUsage", at = @At("RETURN"), cancellable = true, require = 1)
    private void rsi$onePhysicalDisk(CallbackInfoReturnable<Integer> cir) {
        DiskDriveNetworkNode node = (DiskDriveNetworkNode) (Object) this;
        int duplicates = 0;
        for (int i = 0; i < node.getItemDisks().length; i++) {
            IStorageDisk<?> item = node.getItemDisks()[i], fluid = node.getFluidDisks()[i];
            if (item != null && fluid != null && UnifiedDiskRoot.FACTORY_ID.equals(item.getFactoryId())
                    && UnifiedDiskRoot.FACTORY_ID.equals(fluid.getFactoryId())) duplicates++;
        }
        if (duplicates > 0) cir.setReturnValue(cir.getReturnValue() - duplicates * RS.SERVER_CONFIG.getDiskDrive().getDiskUsage());
    }

    @Inject(method = "getDiskState", at = @At("RETURN"), cancellable = true, require = 1)
    private void rsi$twoCapacities(CallbackInfoReturnable<DiskState[]> cir) {
        DiskDriveNetworkNode node = (DiskDriveNetworkNode) (Object) this;
        DiskState[] states = cir.getReturnValue();
        for (int slot = 0; slot < states.length; slot++) {
            ItemStack physical = node.getDisks().getStackInSlot(slot);
            if (!(physical.getItem() instanceof UnifiedDiskItem item)) continue;
            if (node.getItemDisks()[slot] == null || node.getFluidDisks()[slot] == null) { states[slot] = DiskState.DISCONNECTED; continue; }
            if (states[slot] == DiskState.DISCONNECTED || !(node.getLevel() instanceof ServerLevel level)) continue;
            var entry = UnifiedDiskManager.get(level).entry(item.getId(physical));
            if (entry.core == null) { states[slot] = DiskState.DISCONNECTED; continue; }
            int itemEntries = entry.core.items.size(), fluidEntries = entry.core.fluids.size();
            boolean full = itemEntries == entry.core.limits.items() && fluidEntries == entry.core.limits.fluids();
            boolean near = itemEntries >= entry.core.limits.items() - entry.core.limits.items() / 10
                    || fluidEntries >= entry.core.limits.fluids() - entry.core.limits.fluids() / 10;
            states[slot] = full ? DiskState.FULL : near ? DiskState.NEAR_CAPACITY : DiskState.NORMAL;
        }
    }
}
