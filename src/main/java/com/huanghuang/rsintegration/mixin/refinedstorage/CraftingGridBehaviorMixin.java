package com.huanghuang.rsintegration.mixin.refinedstorage;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.util.Action;
import com.refinedmods.refinedstorage.api.util.IComparer;
import com.refinedmods.refinedstorage.apiimpl.network.grid.CraftingGridBehavior;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = CraftingGridBehavior.class, remap = false)
public class CraftingGridBehaviorMixin {

    @Redirect(
            method = "onRecipeTransfer",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/refinedmods/refinedstorage/api/network/INetwork;extractItem"
                            + "(Lnet/minecraft/world/item/ItemStack;IILcom/refinedmods/refinedstorage/api/util/Action;)"
                            + "Lnet/minecraft/world/item/ItemStack;"
            ),
            remap = false
    )
    private ItemStack rsi$redirectExtract(
            INetwork network,
            ItemStack stack, int size, int flags, Action action
    ) {
        if (!RSIntegrationConfig.ENABLE_BINDING.get()) return network.extractItem(stack, size, flags, action);
        return network.extractItem(stack, size, flags & ~IComparer.COMPARE_NBT, action);
    }

    @Redirect(
            method = "onRecipeTransfer",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/refinedmods/refinedstorage/api/util/IComparer;isEqual"
                            + "(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;I)Z"
            ),
            remap = false
    )
    private boolean rsi$redirectIsEqual(
            IComparer comparer,
            ItemStack a, ItemStack b, int flags
    ) {
        if (!RSIntegrationConfig.ENABLE_BINDING.get()) return comparer.isEqual(a, b, flags);
        return comparer.isEqual(a, b, flags & ~IComparer.COMPARE_NBT);
    }
}
