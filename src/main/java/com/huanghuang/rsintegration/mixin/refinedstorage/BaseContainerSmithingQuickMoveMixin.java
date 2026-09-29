package com.huanghuang.rsintegration.mixin.refinedstorage;

import com.huanghuang.rsintegration.craftingstation.CraftingStationAccess;
import com.huanghuang.rsintegration.craftingstation.CraftingStationInputSlot;
import com.huanghuang.rsintegration.craftingstation.CraftingStationMode;
import com.huanghuang.rsintegration.craftingstation.CraftingStationResultSlot;
import com.refinedmods.refinedstorage.container.BaseContainerMenu;
import com.refinedmods.refinedstorage.container.GridContainerMenu;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** RS 的 Shift 点击实现位于 BaseContainerMenu，内嵌工作站槽必须在这里拦截。 */
@Mixin(value = BaseContainerMenu.class, remap = false)
public abstract class BaseContainerSmithingQuickMoveMixin {
    @Inject(method = "m_7648_", at = @At("HEAD"), cancellable = true, remap = false)
    private void rsi$quickMoveSmithing(Player player, int slotNumber,
                                       CallbackInfoReturnable<ItemStack> cir) {
        if (!((Object) this instanceof GridContainerMenu menu)
                || CraftingStationAccess.access(menu).rsi$getCraftingStationMode() == CraftingStationMode.CRAFTING
                || slotNumber < 0 || slotNumber >= menu.slots.size()) return;
        Slot source = menu.slots.get(slotNumber);
        if (source instanceof CraftingStationResultSlot) {
            ItemStack output = source.getItem().copy();
            if (output.isEmpty() || !rsi$canFitInventory(player, output)) {
                cir.setReturnValue(ItemStack.EMPTY);
                return;
            }
            if (player.level().isClientSide) {
                cir.setReturnValue(ItemStack.EMPTY);
                return;
            }
            player.getInventory().add(output.copy());
            CraftingStationAccess.access(menu).rsi$getCraftingStationState().takeResult(player);
            cir.setReturnValue(output);
        } else if (source instanceof CraftingStationInputSlot input) {
            ItemStack stack = input.getItem();
            if (stack.isEmpty()) {
                cir.setReturnValue(ItemStack.EMPTY);
                return;
            }
            ItemStack original = stack.copy();
            ItemStack moving = stack.copy();
            player.getInventory().add(moving);
            input.set(moving);
            cir.setReturnValue(moving.getCount() == original.getCount() ? ItemStack.EMPTY : original);
        } else if (source.container == player.getInventory() && source.hasItem()) {
            ItemStack stack = source.getItem();
            ItemStack original = stack.copy();
            int inputCount = CraftingStationAccess.access(menu).rsi$getCraftingStationState().inputCount();
            for (int index = 0; index < inputCount && !stack.isEmpty(); index++) {
                Slot target = rsi$stationInput(menu, index);
                if (target == null || !target.mayPlace(stack)) continue;
                int before = stack.getCount();
                target.safeInsert(stack);
                if (stack.getCount() != before) break;
            }
            cir.setReturnValue(stack.getCount() == original.getCount() ? ItemStack.EMPTY : original);
        }
    }

    @Unique
    private static Slot rsi$stationInput(GridContainerMenu menu, int index) {
        for (Slot slot : menu.slots) {
            if (slot instanceof CraftingStationInputSlot input && input.isActive()
                    && input.getSlotIndex() == index) return input;
        }
        return null;
    }

    @Unique
    private static boolean rsi$canFitInventory(Player player, ItemStack stack) {
        int space = 0;
        for (ItemStack current : player.getInventory().items) {
            if (current.isEmpty()) space += stack.getMaxStackSize();
            else if (ItemStack.isSameItemSameTags(current, stack)) {
                space += Math.max(0, Math.min(current.getMaxStackSize(),
                        player.getInventory().getMaxStackSize()) - current.getCount());
            }
            if (space >= stack.getCount()) return true;
        }
        return false;
    }
}
