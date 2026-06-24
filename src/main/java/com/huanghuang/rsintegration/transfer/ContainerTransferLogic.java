package com.huanghuang.rsintegration.transfer;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.integration.RSIntegration;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.util.Action;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;
import net.p3pp3rf1y.sophisticatedbackpacks.api.CapabilityBackpackWrapper;
import net.p3pp3rf1y.sophisticatedcore.api.IStorageWrapper;

import javax.annotation.Nullable;

final class ContainerTransferLogic {

    private ContainerTransferLogic() {}

    // 0 = RS Network, 1 = Backpack
    static void transferAll(ServerPlayer player, AbstractContainerMenu menu, byte mode) {
        if (mode == 1) {
            transferToBackpack(player, menu);
        } else {
            transferToRS(player, menu);
        }
    }

    private static void transferToRS(ServerPlayer player, AbstractContainerMenu menu) {
        INetwork network = RSIntegration.resolveNetworkFromPlayer(player);
        if (network == null) {
            player.sendSystemMessage(
                    Component.translatable("rsi.transfer.no_network"), true);
            return;
        }

        int totalStacks = 0;
        int totalItems = 0;

        for (Slot slot : menu.slots) {
            if (slot.container == player.getInventory()) continue;

            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;
            if (!slot.mayPickup(player)) continue;

            int count = stack.getCount();
            ItemStack remaining = network.insertItem(stack.copy(), count, Action.PERFORM);

            if (remaining.isEmpty()) {
                slot.set(ItemStack.EMPTY);
                totalItems += count;
                totalStacks++;
            } else {
                long inserted = (long) count - remaining.getCount();
                if (inserted > 0) {
                    slot.set(remaining);
                    totalItems += (int) inserted;
                    totalStacks++;
                }
            }
        }

        menu.broadcastChanges();

        if (totalStacks > 0) {
            player.sendSystemMessage(
                    Component.translatable("rsi.transfer.success", totalItems, totalStacks), true);
        } else {
            player.sendSystemMessage(
                    Component.translatable("rsi.transfer.nothing"), true);
        }
    }

    private static void transferToBackpack(ServerPlayer player, AbstractContainerMenu menu) {
        IItemHandler backpackHandler = findBackpack(player);
        if (backpackHandler == null) {
            player.sendSystemMessage(
                    Component.translatable("rsi.transfer.no_backpack"), true);
            return;
        }

        int totalStacks = 0;
        int totalItems = 0;

        for (Slot slot : menu.slots) {
            if (slot.container == player.getInventory()) continue;

            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;
            if (!slot.mayPickup(player)) continue;

            int count = stack.getCount();
            ItemStack remaining = stack.copy();

            for (int bSlot = 0; bSlot < backpackHandler.getSlots() && !remaining.isEmpty(); bSlot++) {
                remaining = backpackHandler.insertItem(bSlot, remaining, false);
            }

            int inserted = count - remaining.getCount();
            if (inserted == count) {
                slot.set(ItemStack.EMPTY);
                totalItems += count;
                totalStacks++;
            } else if (inserted > 0) {
                slot.set(remaining);
                totalItems += inserted;
                totalStacks++;
            }
        }

        menu.broadcastChanges();

        if (totalStacks > 0) {
            player.sendSystemMessage(
                    Component.translatable("rsi.transfer.backpack_success", totalItems, totalStacks), true);
        } else {
            player.sendSystemMessage(
                    Component.translatable("rsi.transfer.nothing"), true);
        }
    }

    @Nullable
    private static IItemHandler findBackpack(ServerPlayer player) {
        // Priority 1: Curios "back" slot
        try {
            var opt = top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(player).resolve();
            if (opt.isPresent()) {
                var handler = opt.get();
                var backStacks = handler.getCurios().get("back");
                if (backStacks != null) {
                    var stacks = backStacks.getStacks();
                    for (int s = 0; s < stacks.getSlots(); s++) {
                        IItemHandler bh = getBackpackHandler(stacks.getStackInSlot(s));
                        if (bh != null) return bh;
                    }
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI] Curios scan error in findBackpack: {}", e.toString());
        }

        // Priority 2: Offhand
        IItemHandler bh = getBackpackHandler(player.getOffhandItem());
        if (bh != null) return bh;

        // Priority 3: Main inventory (hotbar first, then rest)
        var inv = player.getInventory();
        for (int i = 0; i < 9; i++) {
            bh = getBackpackHandler(inv.getItem(i));
            if (bh != null) return bh;
        }
        for (int i = 9; i < inv.getContainerSize(); i++) {
            bh = getBackpackHandler(inv.getItem(i));
            if (bh != null) return bh;
        }

        return null;
    }

    @Nullable
    private static IItemHandler getBackpackHandler(ItemStack stack) {
        if (stack.isEmpty()) return null;
        var opt = stack.getCapability(CapabilityBackpackWrapper.getCapabilityInstance()).resolve();
        if (opt.isPresent()) {
            IStorageWrapper wrapper = opt.get();
            return wrapper.getInventoryForUpgradeProcessing();
        }
        return null;
    }
}
