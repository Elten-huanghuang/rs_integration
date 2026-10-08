package com.huanghuang.rsintegration.mixin.refinedstorage;

import com.huanghuang.rsintegration.craftingstation.CraftingStationAccess;
import com.huanghuang.rsintegration.craftingstation.CraftingStationInputSlot;
import com.huanghuang.rsintegration.craftingstation.CraftingStationMode;
import com.huanghuang.rsintegration.craftingstation.CraftingStationResultSlot;
import com.huanghuang.rsintegration.craftingstation.StonecutterTerminalState;
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
        if (source instanceof CraftingStationResultSlot result) {
            cir.setReturnValue(rsi$quickMoveResult(player, result));
        } else if (source instanceof CraftingStationInputSlot input) {
            cir.setReturnValue(rsi$quickMoveInput(player, input));
        } else if (source.container == player.getInventory() && source.hasItem()) {
            ItemStack stack = source.getItem();
            ItemStack original = stack.copy();
            int inputCount = CraftingStationAccess.access(menu).rsi$getCraftingStationState().inputCount();
            for (int index = 0; index < inputCount && !stack.isEmpty(); index++) {
                if (CraftingStationAccess.access(menu).rsi$getCraftingStationMode()
                        == CraftingStationMode.STONECUTTER
                        && CraftingStationAccess.access(menu).rsi$getCraftingStationState()
                        instanceof StonecutterTerminalState stonecutter
                        && !stonecutter.canQuickMoveInput(stack)) continue;
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
    private static ItemStack rsi$quickMoveResult(Player player, CraftingStationResultSlot result) {
        ItemStack output = result.getItem().copy();
        if (output.isEmpty() || !result.mayPickup(player) || !rsi$canFitInventory(player, output)
                || player.level().isClientSide) return ItemStack.EMPTY;
        player.getInventory().add(output.copy());
        result.onTake(player, output);
        return output;
    }

    @Unique
    private static ItemStack rsi$quickMoveInput(Player player, CraftingStationInputSlot input) {
        ItemStack moving = input.getItem().copy();
        if (moving.isEmpty()) return ItemStack.EMPTY;
        ItemStack remaining = moving.copy();
        player.getInventory().add(remaining);
        int transferred = moving.getCount() - remaining.getCount();
        // 原版会根据非空返回值重复 Shift 转移；背包满时必须结束，且保留输入和配方结果。
        if (transferred == 0) return ItemStack.EMPTY;
        input.remove(transferred);
        return moving;
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
