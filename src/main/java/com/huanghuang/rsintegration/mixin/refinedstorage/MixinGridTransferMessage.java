package com.huanghuang.rsintegration.mixin.refinedstorage;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.refinedmods.refinedstorage.container.GridContainerMenu;
import com.refinedmods.refinedstorage.container.slot.grid.CraftingGridSlot;
import com.refinedmods.refinedstorage.network.grid.GridTransferMessage;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = GridTransferMessage.class, remap = false)
public abstract class MixinGridTransferMessage {

    /**
     * After JEI transfers recipe items into the RS crafting grid, move them
     * to the player inventory only if they don't form a valid vanilla crafting
     * recipe.  Vanilla crafting recipes stay in the grid so RS can auto-craft;
     * everything else (furnace, smithing, mod machines) goes to inventory.
     */
    @Inject(
        method = "lambda$handle$0",
        at = @At(
            value = "INVOKE",
            target = "Lcom/refinedmods/refinedstorage/api/network/grid/IGrid;onRecipeTransfer(Lnet/minecraft/world/entity/player/Player;[[Lnet/minecraft/world/item/ItemStack;)V",
            shift = At.Shift.AFTER
        ),
        remap = false
    )
    private static void rsi$redirectCraftingGridToInventory(
            Player player, GridTransferMessage msg, CallbackInfo ci) {
        if (!RSIntegrationConfig.ENABLE_RS_SIDE_PANEL.get()) return;
        if (!(player.containerMenu instanceof GridContainerMenu container)) return;

        if (rsi$isValidCraftingRecipe(container, player)) return;

        boolean moved = false;
        for (Slot slot : container.slots) {
            if (slot instanceof CraftingGridSlot && slot.hasItem()) {
                ItemStack stack = slot.getItem().copy();
                slot.set(ItemStack.EMPTY);
                if (!player.getInventory().add(stack)) {
                    player.drop(stack, false);
                }
                moved = true;
            }
        }
        if (moved) {
            container.broadcastChanges();
        }
    }

    @Unique
    private static boolean rsi$isValidCraftingRecipe(GridContainerMenu container, Player player) {
        try {
            CraftingContainer matrix = container.getGrid().getCraftingMatrix();
            if (matrix == null) return false;
            return player.level().getRecipeManager()
                    .getRecipeFor(RecipeType.CRAFTING, matrix, player.level())
                    .isPresent();
        } catch (Exception e) {
            return false;
        }
    }
}
