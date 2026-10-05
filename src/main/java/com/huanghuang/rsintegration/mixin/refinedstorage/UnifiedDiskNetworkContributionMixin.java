package com.huanghuang.rsintegration.mixin.refinedstorage;

import com.huanghuang.rsintegration.disk.rs.IndexedStackList;
import com.huanghuang.rsintegration.util.ItemStackUtils;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.storage.IStorage;
import com.refinedmods.refinedstorage.api.storage.cache.IStorageCache;
import com.refinedmods.refinedstorage.api.storage.externalstorage.IExternalStorage;
import com.refinedmods.refinedstorage.api.util.Action;
import com.refinedmods.refinedstorage.apiimpl.network.Network;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(value = Network.class, remap = false)
public abstract class UnifiedDiskNetworkContributionMixin {
    @ModifyVariable(method = "insertItem", at = @At("HEAD"), argsOnly = true, require = 1)
    private ItemStack rsi$normalizeEmptyTag(ItemStack resource) {
        return ItemStackUtils.normalizeEmptyTag(resource);
    }

    @WrapOperation(method = {"insertItem", "insertFluid"}, at = @At(value = "INVOKE", target =
            "Lcom/refinedmods/refinedstorage/api/storage/IStorage;insert(Ljava/lang/Object;ILcom/refinedmods/refinedstorage/api/util/Action;)Ljava/lang/Object;"), require = 2)
    private Object rsi$insert(IStorage<Object> storage, Object resource, int amount, Action action, Operation<Object> original) {
        Object remainder = original.call(storage, resource, amount, action);
        if (action == Action.PERFORM) {
            rsi$record(storage, resource, amount - (remainder == null ? 0 : rsi$amount(remainder)));
        }
        return remainder;
    }
    @WrapOperation(method = {"extractItem", "extractFluid"}, at = @At(value = "INVOKE", target =
            "Lcom/refinedmods/refinedstorage/api/storage/IStorage;extract(Ljava/lang/Object;IILcom/refinedmods/refinedstorage/api/util/Action;)Ljava/lang/Object;"), require = 2)
    private Object rsi$extract(IStorage<Object> storage, Object resource, int amount, int flags, Action action, Operation<Object> original) {
        Object extracted = original.call(storage, resource, amount, flags, action);
        if (action == Action.PERFORM && extracted != null) {
            rsi$record(storage, extracted, -rsi$amount(extracted));
        }
        return extracted;
    }
    @Unique private int rsi$amount(Object stack) { return stack instanceof ItemStack item ? item.getCount() : ((FluidStack) stack).getAmount(); }
    @Unique @SuppressWarnings("unchecked") private void rsi$record(IStorage<Object> source, Object resource, int change) {
        INetwork network = (INetwork) (Object) this;
        IStorageCache<?> cache = resource instanceof ItemStack ? network.getItemStorageCache() : network.getFluidStorageCache();
        if (change != 0 && cache.getList() instanceof IndexedStackList<?> indexed) {
            ((IndexedStackList<Object>) indexed).changed(source, resource, change);
        }
    }
}
