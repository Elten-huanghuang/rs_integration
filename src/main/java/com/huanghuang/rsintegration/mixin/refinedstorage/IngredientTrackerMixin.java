package com.huanghuang.rsintegration.mixin.refinedstorage;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.refinedmods.refinedstorage.api.autocrafting.ICraftingPattern;
import com.refinedmods.refinedstorage.api.autocrafting.ICraftingPatternProvider;
import com.refinedmods.refinedstorage.api.network.grid.GridType;
import com.refinedmods.refinedstorage.api.util.IComparer;
import com.refinedmods.refinedstorage.apiimpl.API;
import com.refinedmods.refinedstorage.container.GridContainerMenu;
import com.refinedmods.refinedstorage.integration.jei.IngredientTracker;
import com.refinedmods.refinedstorage.item.PatternItem;
import com.refinedmods.refinedstorage.util.ItemStackKey;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.*;

@Mixin(value = IngredientTracker.class, remap = false)
public abstract class IngredientTrackerMixin {

    @Shadow
    private Map<ItemStackKey, Integer> storedItems;
    @Shadow
    private Map<ItemStackKey, Integer> patternItems;
    @Shadow
    private Map<ItemStackKey, UUID> craftableItems;

    @Unique
    private static boolean rsi$matches(ItemStack a, ItemStack b) {
        return API.instance().getComparer().isEqual(a, b, 0);
    }

    @Unique
    private static boolean rsi$matchesExact(ItemStack a, ItemStack b) {
        return API.instance().getComparer().isEqual(a, b, IComparer.COMPARE_NBT);
    }

    @Unique
    private static ItemStackKey rsi$keyOf(ItemStack stack) {
        ItemStack copy = stack.copy();
        copy.setTag(null);
        return new ItemStackKey(copy);
    }

    @Inject(method = "addStack", at = @At("HEAD"), cancellable = true)
    public void addStack(ItemStack stack, CallbackInfo ci) {
        if (stack.isEmpty()) {
            return;
        }
        if (stack.getItem() instanceof ICraftingPatternProvider) {
            ICraftingPattern pattern = PatternItem.fromCache(Minecraft.getInstance().level, stack);
            if (pattern.isValid()) {
                for (ItemStack outputStack : pattern.getOutputs()) {
                    patternItems.merge(rsi$keyOf(outputStack), 1, Integer::sum);
                }
            }
        } else {
            storedItems.merge(rsi$keyOf(stack), stack.getCount(), Integer::sum);
        }
        ci.cancel();
    }

    @Inject(method = "findBestMatch", at = @At("HEAD"), cancellable = true)
    public ItemStack findBestMatch(GridContainerMenu gridContainer, Player player, List<ItemStack> list,
                                    CallbackInfoReturnable<ItemStack> cir) {
        // Pass 1: exact NBT match — prefer specific-NBT items over generic ones
        int exactCount = 0;
        ItemStack exactResult = ItemStack.EMPTY;
        for (ItemStack listStack : list) {
            if (gridContainer.getGrid().getGridType().equals(GridType.CRAFTING)) {
                CraftingContainer craftingMatrix = gridContainer.getGrid().getCraftingMatrix();
                if (craftingMatrix != null) {
                    for (int matrixSlot = 0; matrixSlot < craftingMatrix.getContainerSize(); matrixSlot++) {
                        ItemStack stackInSlot = craftingMatrix.getItem(matrixSlot);
                        if (rsi$matchesExact(listStack, stackInSlot) && stackInSlot.getCount() > exactCount) {
                            exactCount = stackInSlot.getCount();
                            exactResult = stackInSlot.copy();
                        }
                    }
                }
            }
            for (int inventorySlot = 0; inventorySlot < player.getInventory().getContainerSize(); inventorySlot++) {
                ItemStack stackInSlot = player.getInventory().getItem(inventorySlot);
                if (rsi$matchesExact(listStack, stackInSlot) && stackInSlot.getCount() > exactCount) {
                    exactCount = stackInSlot.getCount();
                    exactResult = stackInSlot.copy();
                }
            }
        }
        if (!exactResult.isEmpty()) {
            cir.setReturnValue(exactResult);
            cir.cancel();
            return exactResult;
        }

        // Pass 2: NBT-agnostic fallback — handles generic-item recipes
        ItemStack resultStack = ItemStack.EMPTY;
        int count = 0;

        for (ItemStack listStack : list) {
            if (gridContainer.getGrid().getGridType().equals(GridType.CRAFTING)) {
                CraftingContainer craftingMatrix = gridContainer.getGrid().getCraftingMatrix();
                if (craftingMatrix != null) {
                    for (int matrixSlot = 0; matrixSlot < craftingMatrix.getContainerSize(); matrixSlot++) {
                        ItemStack stackInSlot = craftingMatrix.getItem(matrixSlot);
                        if (rsi$matches(listStack, stackInSlot) && stackInSlot.getCount() > count) {
                            count = stackInSlot.getCount();
                            resultStack = stackInSlot.copy();
                        }
                    }
                }
            }

            for (int inventorySlot = 0; inventorySlot < player.getInventory().getContainerSize(); inventorySlot++) {
                ItemStack stackInSlot = player.getInventory().getItem(inventorySlot);
                if (rsi$matches(listStack, stackInSlot) && stackInSlot.getCount() > count) {
                    count = stackInSlot.getCount();
                    resultStack = stackInSlot.copy();
                }
            }

            for (var entry : storedItems.entrySet()) {
                if (rsi$matches(listStack, entry.getKey().getStack()) && entry.getValue() > count) {
                    resultStack = listStack;
                    count = entry.getValue();
                }
            }
        }

        if (count == 0) {
            for (ItemStack itemStack : list) {
                boolean found = false;
                for (ItemStackKey key : craftableItems.keySet()) {
                    if (rsi$matches(itemStack, key.getStack())) {
                        resultStack = itemStack;
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    for (ItemStackKey key : patternItems.keySet()) {
                        if (rsi$matches(itemStack, key.getStack())) {
                            resultStack = itemStack;
                            found = true;
                            break;
                        }
                    }
                }
                if (found) {
                    break;
                }
            }
        }

        cir.setReturnValue(resultStack);
        cir.cancel();
        return resultStack;
    }

    @Redirect(
            method = "checkStack",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/refinedmods/refinedstorage/api/util/IComparer;isEqual"
                            + "(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;I)Z"
            ),
            remap = false
    )
    private boolean rsi$redirectIsEqual(IComparer comparer, ItemStack a, ItemStack b, int flags) {
        if (!RSIntegrationConfig.ENABLE_BINDING.get()) return comparer.isEqual(a, b, flags);
        // If the recipe ingredient has NBT, require exact match — prevents
        // wrongly accepting NBT-mutated items as "already present" in the grid.
        // If the ingredient has no NBT (generic recipe), NBT-agnostic is fine.
        if (a.hasTag()) return comparer.isEqual(a, b, flags);
        return comparer.isEqual(a, b, flags & ~IComparer.COMPARE_NBT);
    }
}
