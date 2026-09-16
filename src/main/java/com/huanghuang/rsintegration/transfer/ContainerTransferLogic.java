package com.huanghuang.rsintegration.transfer;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.network.RSIntegrationNetwork;
import com.huanghuang.rsintegration.util.InsertedStackDelta;
import com.huanghuang.rsintegration.util.ModIds;
import com.huanghuang.rsintegration.util.TrackedNetworkInsertion;
import com.huanghuang.rsintegration.storage.StorageOperationResult;
import com.huanghuang.rsintegration.storage.StoragePermission;
import com.huanghuang.rsintegration.storage.StorageSession;
import com.refinedmods.refinedstorage.api.network.INetwork;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.registries.ForgeRegistries;
import net.p3pp3rf1y.sophisticatedbackpacks.api.CapabilityBackpackWrapper;
import net.p3pp3rf1y.sophisticatedbackpacks.api.IItemHandlerInteractionUpgrade;
import net.p3pp3rf1y.sophisticatedbackpacks.upgrades.deposit.DepositUpgradeWrapper;
import net.p3pp3rf1y.sophisticatedcore.api.IStorageWrapper;

import java.util.ArrayList;
import java.util.List;

final class ContainerTransferLogic {

    private static final String TETRA_WORKBENCH_MENU =
            "se.mickelus.tetra.blocks.workbench.WorkbenchContainer";
    private static final String TETRA_FORGED_CONTAINER_MENU =
            "se.mickelus.tetra.blocks.forged.container.ForgedContainerMenu";
    private static final String DISK_DRIVE_MENU =
            "com.refinedmods.refinedstorage.container.DiskDriveContainerMenu";
    private static final String RESONANCE_BACKPACK_MENU =
            "com.huanghuang.rsintegration.resonance.backpack.ResonanceBackpackContainer";
    private static final String BD_STORAGE_MENU_PREFIX =
            "com.wintercogs.beyonddimensions.common.menu.Dimensions";
    /**
     * Better Beyond Dimensions injects these network-backed display slots into
     * otherwise ordinary menus (chests, backpacks, machines). They render a
     * copy of network contents, so treating them as source inventory slots
     * would insert the display stack into the destination without removing a
     * real container item.
     */
    private static final String BBD_NETWORK_SLOT =
            "net.xuwu.betterbeyonddimensions.common.NetworkStorageSlot";
    private static final String STORAGE_UPGRADE_SLOT =
            "net.p3pp3rf1y.sophisticatedcore.common.gui.StorageContainerMenuBase$StorageUpgradeSlot";
    private static final String BACKPACK_UPGRADE_SLOT =
            "net.p3pp3rf1y.sophisticatedbackpacks.common.gui.BackpackContainer$BackpackUpgradeSlot";
    private static final String RS_BLOCK_POS_TAG = "RSBlockPos";
    private static final String RS_BLOCK_DIMENSION_TAG = "RSBlockDimension";
    private static final int PLAYER_MAIN_INVENTORY_SLOTS = 36;

    private ContainerTransferLogic() {}

    // 0 = RS Network, 1 = Backpack, 2 = Beyond Dimensions
    static void transferAll(ServerPlayer player, AbstractContainerMenu menu, byte mode) {
        if (isEnderChestMenu(menu)) return;
        // BD handheld item configuration screens expose virtual slots (for
        // example the feeder/magnet filter item). They are not inventories;
        // treating their display stacks as source slots duplicates items.
        if (isBeyondDimensionsVirtualMenu(menu.getClass().getName())) {
            player.sendSystemMessage(Component.translatable("rsi.transfer.nothing"), false);
            return;
        }
        if (mode == 1) {
            transferToBackpack(player, menu);
        } else if (mode == 0) {
            transferToRS(player, menu);
        } else if (mode == 2) {
            transferToBeyondDimensions(player, menu);
        }
    }

    private static void transferToBeyondDimensions(ServerPlayer player, AbstractContainerMenu menu) {
        if (isBeyondDimensionsSelfStorageMenu(menu.getClass().getName())) {
            player.sendSystemMessage(
                    Component.translatable("rsi.transfer.self_network_blocked"), false);
            return;
        }
        StorageSession session = RSIntegrationMod.STORAGE_BACKENDS.registry()
                .resolveDefaultSessionsForPlayer(player).stream()
                .filter(s -> "beyonddimensions".equals(s.reference().backendId().value()))
                .findFirst().orElse(null);
        if (session == null || !session.hasPermission(player, StoragePermission.INSERT)) {
            player.sendSystemMessage(Component.translatable("rsi.transfer.no_network"), false);
            return;
        }
        int totalStacks = 0;
        int totalItems = 0;
        boolean hasCrafting = hasCraftingContainer(menu);
        for (int slotIndex = 0; slotIndex < menu.slots.size(); slotIndex++) {
            Slot slot = menu.slots.get(slotIndex);
            if (!isTransferableSourceSlot(player, menu, slotIndex, slot, hasCrafting)) continue;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;
            if (isBoundToBeyondDimensionsNetwork(stack, session)) continue;
            ItemStack input = stack.copy();
            StorageOperationResult result = session.insert(player, input, false);
            ItemStack remainder = result.remainder().orElse(input);
            // Keep FTB Quests (and other external progress consumers) in sync
            // with items accepted by the backend-neutral BD path. The RS path
            // already reports this delta through TrackedNetworkInsertion;
            // without this call, pressing F in a container silently bypasses
            // quest detection even though the item is stored successfully.
            InsertedStackDelta.report(player, input, remainder);
            int inserted = input.getCount() - remainder.getCount();
            if (inserted <= 0) continue;
            slot.set(remainder.isEmpty() ? ItemStack.EMPTY : remainder);
            totalItems += inserted;
            totalStacks++;
        }
        menu.broadcastChanges();
        player.sendSystemMessage(totalStacks > 0
                ? Component.translatable("rsi.transfer.success", totalItems, totalStacks)
                : Component.translatable("rsi.transfer.nothing"), false);
    }

    private static void transferToRS(ServerPlayer player, AbstractContainerMenu menu) {
        if (isSelfNetworkStorageMenu(menu.getClass().getName())) {
            player.sendSystemMessage(
                    Component.translatable("rsi.transfer.self_network_blocked"), false);
            return;
        }

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

        for (int slotIndex = 0; slotIndex < menu.slots.size(); slotIndex++) {
            Slot slot = menu.slots.get(slotIndex);
            if (!isTransferableSourceSlot(player, menu, slotIndex, slot, hasCrafting)) continue;

            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;
            if (isBoundNetworkUpgrade(stack)) continue;

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
            ItemStack input = stack.copy();
            ItemStack remaining = TrackedNetworkInsertion.insert(network, player, input);
            InsertedStackDelta.report(player, input, remaining);

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

        for (int slotIndex = 0; slotIndex < menu.slots.size(); slotIndex++) {
            Slot slot = menu.slots.get(slotIndex);
            if (!isTransferableSourceSlot(player, menu, slotIndex, slot, hasCrafting)) continue;

            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;

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
            InsertedStackDelta.report(player, toMove, remaining);
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

    private static boolean isPlayerInventorySlot(ServerPlayer player, AbstractContainerMenu menu,
                                                 int slotIndex, Slot slot) {
        if (slot.container == player.getInventory()) return true;

        // Tetra wraps the player's inventory in InvWrapper, so container identity cannot
        // distinguish it from workbench storage. WorkbenchContainer always appends the
        // 27 main-inventory and 9 hotbar slots after its own slots.
        return isTetraWrappedPlayerSlot(menu.getClass().getName(), slotIndex, menu.slots.size());
    }

    static boolean isTetraWorkbenchPlayerSlot(String menuClassName, int slotIndex, int slotCount) {
        return isTetraWrappedPlayerSlot(menuClassName, slotIndex, slotCount);
    }

    static boolean isTetraWrappedPlayerSlot(String menuClassName, int slotIndex, int slotCount) {
        return (TETRA_WORKBENCH_MENU.equals(menuClassName)
                || TETRA_FORGED_CONTAINER_MENU.equals(menuClassName))
                && slotCount >= PLAYER_MAIN_INVENTORY_SLOTS
                && slotIndex >= slotCount - PLAYER_MAIN_INVENTORY_SLOTS;
    }

    private static boolean isTransferableSourceSlot(ServerPlayer player, AbstractContainerMenu menu,
                                                     int slotIndex, Slot slot, boolean hasCrafting) {
        if (slot == null || !slot.isActive() || !slot.mayPickup(player)) return false;
        if (isVirtualSlot(slot) || isUpgradeSlot(slot)) return false;
        if (isPlayerInventorySlot(player, menu, slotIndex, slot)) return false;
        if (hasCrafting && isResultSlot(slot)) return false;
        if (isFurnaceLikeMenu(menu.getClass().getName()) && !isResultSlot(slot)) return false;
        return !(slot.container instanceof CraftingContainer);
    }

    static boolean isEnderChestMenu(AbstractContainerMenu menu) {
        if (menu == null) return false;
        for (Slot slot : menu.slots) {
            if (slot != null && slot.container != null
                    && "net.minecraft.world.inventory.PlayerEnderChestContainer".equals(
                    slot.container.getClass().getName())) return true;
        }
        return false;
    }

    static boolean isFurnaceLikeMenu(String menuClassName) {
        String name = simpleClassName(menuClassName);
        return name.contains("furnace") || name.contains("smoker")
                || name.contains("smelter") || name.contains("kiln");
    }

    static boolean isSelfNetworkStorageMenu(String menuClassName) {
        return DISK_DRIVE_MENU.equals(menuClassName)
                || RESONANCE_BACKPACK_MENU.equals(menuClassName);
    }

    static boolean isBeyondDimensionsSelfStorageMenu(String menuClassName) {
        return menuClassName != null && menuClassName.startsWith(BD_STORAGE_MENU_PREFIX)
                && (menuClassName.endsWith("DimensionsNetMenu")
                || menuClassName.endsWith("DimensionsCraftMenu")
                || menuClassName.endsWith("DimensionsCraftMenuTerminal"));
    }

    static boolean isBeyondDimensionsVirtualMenu(String menuClassName) {
        if (menuClassName == null
                || !menuClassName.startsWith("com.wintercogs.beyonddimensions.common.menu.")) {
            return false;
        }
        // Actual network storage screens are handled by the existing
        // destination-specific self-storage guard.
        return !menuClassName.endsWith("DimensionsNetMenu")
                && !menuClassName.endsWith("DimensionsCraftMenu")
                && !menuClassName.endsWith("DimensionsCraftMenuTerminal");
    }

    /** Identifies BBD's virtual network slot without linking the optional jar. */
    static boolean isBetterBeyondDimensionsNetworkSlot(Slot slot) {
        if (slot == null) return false;
        Class<?> type = slot.getClass();
        while (type != null && type != Object.class) {
            if (isBetterBeyondDimensionsNetworkSlotClass(type.getName())) return true;
            type = type.getSuperclass();
        }
        return false;
    }

    static boolean isBetterBeyondDimensionsNetworkSlotClass(String className) {
        return BBD_NETWORK_SLOT.equals(className);
    }

    /**
     * Fake filter/display slots contain representative stacks rather than owned items.
     * Treat unfamiliar slot implementations conservatively so pressing F cannot copy a
     * displayed stack into a storage backend. Known marker interfaces are detected by
     * name to avoid hard links to every optional menu mod.
     */
    private static boolean isVirtualSlot(Slot slot) {
        if (isBetterBeyondDimensionsNetworkSlot(slot)) return true;
        if (hasVirtualSlotType(slot.getClass())) return true;
        return slot.container != null && hasVirtualBackingType(slot.container.getClass());
    }

    private static boolean hasVirtualSlotType(Class<?> type) {
        while (type != null && type != Object.class) {
            if (isVirtualSlotClass(type.getName())) return true;
            for (Class<?> marker : type.getInterfaces()) {
                if (isVirtualSlotClass(marker.getName())) return true;
            }
            type = type.getSuperclass();
        }
        return false;
    }

    private static boolean hasVirtualBackingType(Class<?> type) {
        while (type != null && type != Object.class) {
            if (isVirtualBackingClass(type.getName())) return true;
            type = type.getSuperclass();
        }
        return false;
    }

    static boolean isVirtualSlotClass(String className) {
        String name = simpleClassName(className);
        return name.contains("fakeslot") || name.contains("slotfake")
                || name.contains("ghostslot") || name.contains("slotghost")
                || name.contains("phantomslot") || name.contains("slotphantom")
                || name.contains("dummyslot") || name.contains("slotdummy")
                || name.contains("filterslot") || name.contains("slotfilter")
                || name.contains("templateslot") || name.contains("slottemplate")
                || name.contains("previewslot") || name.contains("slotpreview");
    }

    static boolean isVirtualBackingClass(String className) {
        String name = simpleClassName(className);
        boolean backingType = name.contains("inventory") || name.contains("container")
                || name.contains("handler");
        return backingType && (name.contains("fake") || name.contains("ghost")
                || name.contains("phantom") || name.contains("dummy"));
    }

    private static String simpleClassName(String className) {
        if (className == null) return "";
        int separator = Math.max(className.lastIndexOf('.'), className.lastIndexOf('$'));
        return className.substring(separator + 1).toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean isBoundToBeyondDimensionsNetwork(ItemStack stack,
                                                             StorageSession session) {
        if (stack.isEmpty() || session == null
                || !"beyonddimensions".equals(session.reference().backendId().value())) {
            return false;
        }
        var itemId = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (itemId == null || !"beyonddimensions".equals(itemId.getNamespace())) return false;
        try {
            Class<?> netedItem = Class.forName(
                    "com.wintercogs.beyonddimensions.common.item.NetedItem", false,
                    ContainerTransferLogic.class.getClassLoader());
            if (!netedItem.isInstance(stack.getItem())) return false;
            int networkId = ((Number) netedItem.getMethod("getNetId", ItemStack.class)
                    .invoke(null, stack)).intValue();
            return networkId >= 0
                    && Integer.toString(networkId).equals(session.reference().networkId());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // Unknown BD item revisions retain the existing insertion path;
            // source slots still change only after a known remainder returns.
            return false;
        }
    }

    private static boolean isUpgradeSlot(Slot slot) {
        Class<?> type = slot.getClass();
        while (type != null && type != Object.class) {
            if (isUpgradeSlotClass(type.getName())) return true;
            type = type.getSuperclass();
        }
        return false;
    }

    static boolean isUpgradeSlotClass(String slotClassName) {
        return STORAGE_UPGRADE_SLOT.equals(slotClassName)
                || BACKPACK_UPGRADE_SLOT.equals(slotClassName)
                || slotClassName.endsWith("$StorageUpgradeSlot")
                || slotClassName.endsWith("$BackpackUpgradeSlot");
    }

    private static boolean isBoundNetworkUpgrade(ItemStack stack) {
        var itemId = ForgeRegistries.ITEMS.getKey(stack.getItem());
        var tag = stack.getTag();
        return itemId != null && tag != null
                && isProtectedBoundNetworkUpgrade(
                        itemId.toString(),
                        tag.contains(RS_BLOCK_POS_TAG),
                        tag.contains(RS_BLOCK_DIMENSION_TAG));
    }

    static boolean isProtectedBoundNetworkUpgrade(String itemId,
                                                   boolean hasBlockPos,
                                                   boolean hasDimension) {
        if (!hasBlockPos || !hasDimension) return false;
        return itemId.equals("rs_integration:rs_magnet_upgrade")
                || itemId.equals("rs_integration:rs_pickup_upgrade")
                || itemId.equals("rs_integration:rs_refill_upgrade")
                || itemId.equals("rs_integration:rs_feeding_upgrade");
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
