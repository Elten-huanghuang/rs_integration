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
        // Two-stage: exact NBT first, then NBT-agnostic fallback.
        // Preserves deterministic extraction for specific-NBT items
        // (e.g. Sharpness 10 needed for Sharpness 15 book recipe).
        ItemStack result = network.extractItem(stack, size, flags, action);
        if (!result.isEmpty()) return result;
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
        // If the ingredient has NBT, require exact match.
        // If not, NBT-agnostic is fine (generic item recipe).
        if (a.hasTag()) return comparer.isEqual(a, b, flags);
        return comparer.isEqual(a, b, flags & ~IComparer.COMPARE_NBT);
    }
}
