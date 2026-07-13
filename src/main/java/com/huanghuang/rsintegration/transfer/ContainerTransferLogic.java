package com.huanghuang.rsintegration.transfer;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.network.RSIntegrationNetwork;
import com.huanghuang.rsintegration.util.ModIds;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.util.Action;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.items.IItemHandler;
import net.p3pp3rf1y.sophisticatedbackpacks.api.CapabilityBackpackWrapper;
import net.p3pp3rf1y.sophisticatedbackpacks.api.IItemHandlerInteractionUpgrade;
import net.p3pp3rf1y.sophisticatedbackpacks.upgrades.deposit.DepositUpgradeWrapper;
import net.p3pp3rf1y.sophisticatedcore.api.IStorageWrapper;

import java.util.ArrayList;
import java.util.List;

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
        INetwork network = RSIntegrationNetwork.resolveNetworkFromPlayer(player);
        if (network == null) {
            player.sendSystemMessage(
                    Component.translatable("rsi.transfer.no_network"), false);
            return;
        }

        // When the open menu is a backpack, respect the deposit upgrade's
        // filter so blacklisted items stay in the backpack.
        List<DepositUpgradeWrapper> depositUpgrades = null;
        if (isBackpackMenu(menu)) {
            IStorageWrapper wrapper = findBackpackWrapper(player);
            if (wrapper != null) {
                List<IItemHandlerInteractionUpgrade> wrappers =
                        wrapper.getUpgradeHandler().getWrappersThatImplement(
                                IItemHandlerInteractionUpgrade.class);
                depositUpgrades = new ArrayList<>();
                for (IItemHandlerInteractionUpgrade upg : wrappers) {
                    if (upg instanceof DepositUpgradeWrapper)
                        depositUpgrades.add((DepositUpgradeWrapper) upg);
                }
            }
        }

        int totalStacks = 0;
        int totalItems = 0;

        // Only skip result slots in crafting-type containers (workshop, crafting table).
        // Furnace output is also a ResultSlot and should still be extractable.
        boolean hasCrafting = hasCraftingContainer(menu);

        for (Slot slot : menu.slots) {
            if (slot.container == player.getInventory()) continue;
            if (hasCrafting && isResultSlot(slot)) continue;
            if (slot.container instanceof CraftingContainer) continue;

            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;
            if (!slot.mayPickup(player)) continue;

            // Skip items the deposit upgrade says should stay in the backpack.
            if (depositUpgrades != null && !depositUpgrades.isEmpty()) {
                boolean canDeposit = true;
                for (DepositUpgradeWrapper upg : depositUpgrades) {
                    if (!upg.getFilterLogic().matchesFilter(stack)) {
                        canDeposit = false;
                        break;
                    }
                }
                if (!canDeposit) continue;
            }

            int count = stack.getCount();
            var tracker = network.getItemStorageTracker();
            if (tracker != null) tracker.changed(player, stack.copy());
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
                    Component.translatable("rsi.transfer.success", totalItems, totalStacks), false);
        } else {
            player.sendSystemMessage(
                    Component.translatable("rsi.transfer.nothing"), false);
        }
    }

    private static void transferToBackpack(ServerPlayer player, AbstractContainerMenu menu) {
        if (isBackpackMenu(menu)) {
            player.sendSystemMessage(
                    Component.translatable("rsi.transfer.backpack_self"), false);
            return;
        }

        IItemHandler backpackHandler = findBackpack(player);
        if (backpackHandler == null) {
            player.sendSystemMessage(
                    Component.translatable("rsi.transfer.no_backpack"), false);
            return;
        }

        int totalStacks = 0;
        int totalItems = 0;

        boolean hasCrafting = hasCraftingContainer(menu);

        for (Slot slot : menu.slots) {
            if (slot.container == player.getInventory()) continue;
            if (hasCrafting && isResultSlot(slot)) continue;
            if (slot.container instanceof CraftingContainer) continue;

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
                    Component.translatable("rsi.transfer.backpack_success", totalItems, totalStacks), false);
        } else {
            player.sendSystemMessage(
                    Component.translatable("rsi.transfer.nothing"), false);
        }
    }

    private static IItemHandler findBackpack(ServerPlayer player) {
        if (!ModList.get().isLoaded(ModIds.SOPHISTICATED_BACKPACKS)) return null;

        // Priority 1: Curios slots (reflective — Curios is optional)
        if (ModList.get().isLoaded(ModIds.CURIOS)) {
            IItemHandler bh = findBackpackInCurios(player);
            if (bh != null) return bh;
        }

        IItemHandler bh;
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
    private static IItemHandler findBackpackInCurios(ServerPlayer player) {
        if (!ModList.get().isLoaded(ModIds.CURIOS)) return null;
        try {
            Class<?> curiosApiClass = Class.forName("top.theillusivec4.curios.api.CuriosApi");
            // getCuriosInventory takes LivingEntity (not Player — exact match required by reflection)
            Object result = curiosApiClass.getMethod("getCuriosInventory", net.minecraft.world.entity.LivingEntity.class)
                    .invoke(null, player);
            if (result == null) return null;

            // Curios API returns LazyOptional; resolve() -> Optional<ICuriosItemHandler>
            Object handler;
            try {
                Object opt = result.getClass().getMethod("resolve").invoke(result);
                if (opt instanceof java.util.Optional<?> o) {
                    handler = o.orElse(null);
                } else {
                    return null;
                }
            } catch (NoSuchMethodException e) {
                return null;
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
            RSIntegrationMod.LOGGER.warn("[RSI] Curios backpack scan failed", e);
        }
        return null;
    }

    // ── backpack wrapper (for accessing upgrade handlers) ────────────

    private static IStorageWrapper findBackpackWrapper(ServerPlayer player) {
        if (!ModList.get().isLoaded(ModIds.SOPHISTICATED_BACKPACKS)) return null;
        IStorageWrapper w = findBackpackWrapperInCurios(player);
        if (w != null) return w;
        for (ItemStack armor : player.getInventory().armor) {
            w = getBackpackWrapper(armor);
            if (w != null) return w;
        }
        w = getBackpackWrapper(player.getOffhandItem());
        if (w != null) return w;
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            w = getBackpackWrapper(inv.getItem(i));
            if (w != null) return w;
        }
        return null;
    }

    private static IStorageWrapper getBackpackWrapper(ItemStack stack) {
        if (stack.isEmpty()) return null;
        return stack.getCapability(CapabilityBackpackWrapper.getCapabilityInstance())
                .resolve().orElse(null);
    }

    private static IStorageWrapper findBackpackWrapperInCurios(ServerPlayer player) {
        if (!ModList.get().isLoaded(ModIds.CURIOS)) return null;
        try {
            Class<?> curiosApiClass = Class.forName("top.theillusivec4.curios.api.CuriosApi");
            Object result = curiosApiClass.getMethod("getCuriosInventory",
                    net.minecraft.world.entity.LivingEntity.class).invoke(null, player);
            if (result == null) return null;
            Object handler;
            try {
                Object opt = result.getClass().getMethod("resolve").invoke(result);
                if (opt instanceof java.util.Optional<?> o) {
                    handler = o.orElse(null);
                } else {
                    return null;
                }
            } catch (NoSuchMethodException e) {
                return null;
            }
            if (handler == null) return null;
            Object curios = handler.getClass().getMethod("getCurios").invoke(handler);
            @SuppressWarnings("unchecked")
            java.util.Map<String, ?> curiosMap = (java.util.Map<String, ?>) curios;
            for (var entry : curiosMap.values()) {
                Object stacks = entry.getClass().getMethod("getStacks").invoke(entry);
                if (stacks instanceof net.minecraftforge.items.IItemHandler itemHandler) {
                    for (int s = 0; s < itemHandler.getSlots(); s++) {
                        IStorageWrapper w = getBackpackWrapper(itemHandler.getStackInSlot(s));
                        if (w != null) return w;
                    }
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI] Curios backpack wrapper scan failed", e);
        }
        return null;
    }

    // Returns true when the open menu is a sophisticated-backpacks container,
    // meaning the player is looking at the inside of a backpack.
    private static boolean isBackpackMenu(AbstractContainerMenu menu) {
        String name = menu.getClass().getName();
        return name.contains(ModIds.SOPHISTICATED_BACKPACKS) || name.contains("sophisticated");
    }

    // Skip result/output slots so containers where input and output
    // coexist (e.g. TerraCurio workshop) don't get their output duped.
    private static boolean isResultSlot(Slot slot) {
        if (slot instanceof ResultSlot) return true;
        Class<?> clazz = slot.getClass();
        do {
            String name = clazz.getSimpleName().toLowerCase();
            if (name.contains("result") || name.contains("output") || name.contains("craftresult"))
                return true;
            clazz = clazz.getSuperclass();
        } while (clazz != null && clazz != Object.class);
        return false;
    }

    // Returns true when the menu contains a CraftingContainer,
    // which means this is a crafting-type GUI (workshop, crafting table)
    // where result slots must be skipped to prevent duping.
    private static boolean hasCraftingContainer(AbstractContainerMenu menu) {
        for (Slot slot : menu.slots) {
            if (slot.container instanceof CraftingContainer) return true;
        }
        return false;
    }

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
