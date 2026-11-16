package com.huanghuang.rsintegration.mixin.refinedstorage;

import com.huanghuang.rsintegration.crafting.NetworkOutputReception;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.util.Action;
import com.refinedmods.refinedstorage.apiimpl.autocrafting.task.v6.CraftingTask;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 原生合成已经核销的产物及取消退款不能再被 RSI 等待项认领。 */
@Mixin(value = CraftingTask.class, remap = false)
public abstract class CraftingTaskOutputWritebackMixin {
    @WrapOperation(method = {"update", "onCancelled", "onTrackedInsert"}, at = @At(value = "INVOKE",
            target = "Lcom/refinedmods/refinedstorage/api/network/INetwork;insertItem(Lnet/minecraft/world/item/ItemStack;ILcom/refinedmods/refinedstorage/api/util/Action;)Lnet/minecraft/world/item/ItemStack;"), require = 3)
    private ItemStack rsi$writeItem(INetwork network, ItemStack stack, int amount, Action action,
                                   Operation<ItemStack> original) {
        return NetworkOutputReception.internal(() -> original.call(network, stack, amount, action));
    }

    @WrapOperation(method = {"update", "onCancelled", "onTrackedInsert"}, at = @At(value = "INVOKE",
            target = "Lcom/refinedmods/refinedstorage/api/network/INetwork;insertFluid(Lnet/minecraftforge/fluids/FluidStack;ILcom/refinedmods/refinedstorage/api/util/Action;)Lnet/minecraftforge/fluids/FluidStack;"), require = 3)
    private FluidStack rsi$writeFluid(INetwork network, FluidStack stack, int amount, Action action,
                                     Operation<FluidStack> original) {
        return NetworkOutputReception.internal(() -> original.call(network, stack, amount, action));
    }
}
