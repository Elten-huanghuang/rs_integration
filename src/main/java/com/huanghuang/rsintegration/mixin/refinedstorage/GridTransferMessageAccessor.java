package com.huanghuang.rsintegration.mixin.refinedstorage;

import com.refinedmods.refinedstorage.network.grid.GridTransferMessage;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@link GridTransferMessage}'s private {@code recipe} field — the
 * per-slot JEI candidate stacks (indexed by 3x3 grid slot) that RS feeds into
 * {@code onRecipeTransfer}. These carry the recipe's exact NBT and are
 * independent of network availability, so they are the correct source for
 * classifying whether a transfer is a crafting-table recipe.
 */
@Mixin(value = GridTransferMessage.class, remap = false)
public interface GridTransferMessageAccessor {

    @Accessor("recipe")
    ItemStack[][] rsi$getRecipe();
}
