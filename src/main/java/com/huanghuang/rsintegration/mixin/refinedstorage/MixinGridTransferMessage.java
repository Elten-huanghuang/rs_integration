package com.huanghuang.rsintegration.mixin.refinedstorage;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.GridTransferClassifier;
import com.refinedmods.refinedstorage.api.network.grid.GridType;
import com.refinedmods.refinedstorage.api.network.grid.IGrid;
import com.refinedmods.refinedstorage.container.GridContainerMenu;
import com.refinedmods.refinedstorage.container.slot.grid.CraftingGridSlot;
import com.refinedmods.refinedstorage.network.grid.GridTransferMessage;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = GridTransferMessage.class, remap = false)
public abstract class MixinGridTransferMessage {

    /**
     * After RS transfers a JEI recipe into the crafting grid, keep the items in
     * the grid only if the transfer is a vanilla crafting-table recipe; anything
     * else (furnace, smithing, mod machines) is spilled to the player inventory.
     * <p>
     * The crafting/non-crafting decision is made from {@code msg.recipe} — the
     * JEI candidate stacks, which carry the recipe's exact NBT and are complete
     * regardless of network stock — instead of the post-transfer matrix, whose
     * network-extracted contents can miss NBT (SlashBlade) or be empty when
     * materials are short, both of which used to misclassify valid recipes and
     * dump their materials into the inventory/backpack.
     */
    @Inject(
        method = "lambda$handle$0",
        at = @At(
            value = "INVOKE",
            target = "Lcom/refinedmods/refinedstorage/api/network/grid/IGrid;onRecipeTransfer(Lnet/minecraft/world/entity/player/Player;[[Lnet/minecraft/world/item/ItemStack;)V",
            shift = At.Shift.AFTER
        ),
        require = 0,
        remap = false
    )
    private static void rsi$redirectCraftingGridToInventory(
            Player player, GridTransferMessage msg, CallbackInfo ci) {
        if (!RSIntegrationConfig.ENABLE_RS_SIDE_PANEL.get()) return;
        if (!(player.containerMenu instanceof GridContainerMenu container)) return;

        // onRecipeTransfer also fires for pattern grids; only the crafting grid
        // should keep-or-spill its transferred items.
        IGrid grid = container.getGrid();
        if (grid.getGridType() != GridType.CRAFTING) return;

        ItemStack[][] recipe = ((GridTransferMessageAccessor) (Object) msg).rsi$getRecipe();
        if (GridTransferClassifier.isCraftingRecipe(recipe, player)) return;

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
}
