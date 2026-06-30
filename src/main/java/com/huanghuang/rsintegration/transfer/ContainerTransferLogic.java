package com.huanghuang.rsintegration.transfer;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.network.RSIntegration;
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
            network.getItemStorageTracker().changed(player, stack.copy());
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
        // If the open container IS a backpack, transferring "backpack → backpack"
        // is a no-op at best and item-destroying at worst. Tell the user.
        if (isBackpackMenu(menu)) {
            player.sendSystemMessage(
                    Component.translatable("rsi.transfer.backpack_self"), true);
            return;
        }

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
            ItemStack toMove = stack.copy();

            // Remove from source BEFORE inserting into destination.
            // This is safe even if source == destination: removal creates
            // room, insertion fills it, and any remainder goes back.
            slot.set(ItemStack.EMPTY);

            ItemStack remaining = toMove;
            for (int bSlot = 0; bSlot < backpackHandler.getSlots() && !remaining.isEmpty(); bSlot++) {
                remaining = backpackHandler.insertItem(bSlot, remaining, false);
            }

            int inserted = count - remaining.getCount();
            if (remaining.isEmpty()) {
                totalItems += count;
                totalStacks++;
            } else if (inserted > 0) {
                slot.set(remaining);
                totalItems += inserted;
                totalStacks++;
            } else {
                // Nothing was inserted — put everything back
                slot.set(toMove);
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
        // Priority 1: Curios slots (reflective — Curios is optional)
        IItemHandler bh = findBackpackInCurios(player);
        if (bh != null) return bh;

        // Priority 2: Armor slots
        for (ItemStack armor : player.getInventory().armor) {
            bh = getBackpackHandler(armor);
            if (bh != null) return bh;
        }

        // Priority 3: Offhand
        bh = getBackpackHandler(player.getOffhandItem());
        if (bh != null) return bh;

        // Priority 4: Main inventory (hotbar first, then rest)
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

    /**
     * Use reflection to scan ALL Curios slots for a backpack.
     * Curios is an optional dependency — this must not link directly.
     */
    @Nullable
    private static IItemHandler findBackpackInCurios(ServerPlayer player) {
        try {
            Class<?> curiosApiClass = Class.forName("top.theillusivec4.curios.api.CuriosApi");
            Object result = curiosApiClass.getMethod("getCuriosInventory", net.minecraft.world.entity.player.Player.class)
                    .invoke(null, player);
            if (result == null) return null;

            // Curios 5.x returns java.util.Optional; older versions return LazyOptional
            Object handler;
            if (result instanceof java.util.Optional<?> opt) {
                handler = opt.orElse(null);
            } else {
                try {
                    handler = result.getClass().getMethod("resolve").invoke(result);
                } catch (NoSuchMethodException e) {
                    return null;
                }
            }
            if (handler == null) return null;
            // handler.getCurios() -> Map<String, ICurioStacksHandler>
            Object curios = handler.getClass().getMethod("getCurios").invoke(handler);
            @SuppressWarnings("unchecked")
            java.util.Map<String, ?> curiosMap = (java.util.Map<String, ?>) curios;
            // Scan every curio slot type (back, belt, charm, necklace, ring, head, etc.)
            for (var entry : curiosMap.values()) {
                Object stacks = entry.getClass().getMethod("getStacks").invoke(entry);
                if (stacks instanceof net.minecraftforge.items.IItemHandler itemHandler) {
                    for (int s = 0; s < itemHandler.getSlots(); s++) {
                        IItemHandler bh = getBackpackHandler(itemHandler.getStackInSlot(s));
                        if (bh != null) return bh;
                    }
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI] Curios scan error in findBackpack: {}", e.toString());
        }
        return null;
    }

    // Returns true when the open menu is a sophisticated-backpacks container,
    // meaning the player is looking at the inside of a backpack.
    private static boolean isBackpackMenu(AbstractContainerMenu menu) {
        String name = menu.getClass().getName();
        return name.contains("sophisticatedbackpacks") || name.contains("sophisticated");
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
