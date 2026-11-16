package com.huanghuang.rsintegration.mixin.refinedstorage;

import com.huanghuang.rsintegration.storage.rs.RefinedStorageOutputReception;
import com.huanghuang.rsintegration.crafting.TaskOutputReception;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.util.Action;
import com.refinedmods.refinedstorage.apiimpl.network.Network;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Network.class, remap = false)
public abstract class NetworkOutputReceptionMixin implements INetwork {
    // RS 1.12.4 在 INetwork 默认方法中实现 tracked；Network 自身没有实现。
    // 在具体类补充覆盖，避免向接口注入（Mixin 不支持），并在原生 track 之前分流。
    @Override
    public ItemStack insertItemTracked(ItemStack stack, int amount) {
        return RefinedStorageOutputReception.insertItemTracked(this, stack, amount);
    }

    @Override
    public FluidStack insertFluidTracked(FluidStack stack, int amount) {
        return RefinedStorageOutputReception.insertFluidTracked(this, stack, amount);
    }

    @WrapMethod(method = "insertItem")
    private ItemStack rsi$receiveItem(ItemStack stack, int amount, Action action, Operation<ItemStack> original) {
        return RefinedStorageOutputReception.insertItem((INetwork) (Object) this, stack, amount, action,
                (remaining, size) -> original.call(remaining, size, action));
    }

    @WrapMethod(method = "insertFluid")
    private FluidStack rsi$receiveFluid(FluidStack stack, int amount, Action action, Operation<FluidStack> original) {
        return RefinedStorageOutputReception.insertFluid((INetwork) (Object) this, stack, amount, action,
                (remaining, size) -> original.call(remaining, size, action));
    }

    @Inject(method = "onRemoved", at = @At("HEAD"), require = 1)
    private void rsi$removeReception(CallbackInfo callback) {
        TaskOutputReception.invalidateNetwork(this);
    }

    @Inject(method = "update", at = @At("TAIL"), require = 1)
    private void rsi$pruneReception(CallbackInfo callback) {
        if (!((INetwork) (Object) this).canRun()) TaskOutputReception.invalidateNetwork(this);
        else TaskOutputReception.pruneNetwork(this);
    }
}
