package com.huanghuang.rsintegration.transfer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContainerTransferLogicTest {

    private static final String TETRA_WORKBENCH_MENU =
            "se.mickelus.tetra.blocks.workbench.WorkbenchContainer";

    @Test
    void identifiesWrappedPlayerInventoryAtEndOfTetraWorkbenchMenu() {
        assertFalse(ContainerTransferLogic.isTetraWorkbenchPlayerSlot(
                TETRA_WORKBENCH_MENU, 3, 40));
        assertTrue(ContainerTransferLogic.isTetraWorkbenchPlayerSlot(
                TETRA_WORKBENCH_MENU, 4, 40));
        assertTrue(ContainerTransferLogic.isTetraWorkbenchPlayerSlot(
                TETRA_WORKBENCH_MENU, 39, 40));
    }

    @Test
    void doesNotApplyTetraSlotLayoutToOtherMenus() {
        assertFalse(ContainerTransferLogic.isTetraWorkbenchPlayerSlot(
                "net.minecraft.world.inventory.ChestMenu", 4, 40));
    }

    @Test
    void blocksMenusWhoseStorageCanBeMountedByTheDestinationNetwork() {
        assertTrue(ContainerTransferLogic.isSelfNetworkStorageMenu(
                "com.refinedmods.refinedstorage.container.DiskDriveContainerMenu"));
        assertTrue(ContainerTransferLogic.isSelfNetworkStorageMenu(
                "com.huanghuang.rsintegration.resonance.backpack.ResonanceBackpackContainer"));
        assertFalse(ContainerTransferLogic.isSelfNetworkStorageMenu(
                "net.minecraft.world.inventory.ChestMenu"));
    }

    @Test
    void blocksBeyondDimensionsStorageMenusWhenBdIsTheDestination() {
        assertTrue(ContainerTransferLogic.isBeyondDimensionsSelfStorageMenu(
                "com.wintercogs.beyonddimensions.common.menu.DimensionsNetMenu"));
        assertTrue(ContainerTransferLogic.isBeyondDimensionsSelfStorageMenu(
                "com.wintercogs.beyonddimensions.common.menu.DimensionsCraftMenuTerminal"));
        assertFalse(ContainerTransferLogic.isBeyondDimensionsSelfStorageMenu(
                "com.wintercogs.beyonddimensions.common.menu.NetFurnaceMenu"));
        assertFalse(ContainerTransferLogic.isBeyondDimensionsSelfStorageMenu(
                "net.minecraft.world.inventory.ChestMenu"));
    }

    @Test
    void blocksBeyondDimensionsVirtualConfigurationMenus() {
        assertTrue(ContainerTransferLogic.isBeyondDimensionsVirtualMenu(
                "com.wintercogs.beyonddimensions.common.menu.NetFeederMenu"));
        assertTrue(ContainerTransferLogic.isBeyondDimensionsVirtualMenu(
                "com.wintercogs.beyonddimensions.common.menu.NetMagnetMenu"));
        assertTrue(ContainerTransferLogic.isBeyondDimensionsVirtualMenu(
                "com.wintercogs.beyonddimensions.common.menu.NetRestockerMenu"));
        assertFalse(ContainerTransferLogic.isBeyondDimensionsVirtualMenu(
                "com.wintercogs.beyonddimensions.common.menu.DimensionsCraftMenuTerminal"));
        assertFalse(ContainerTransferLogic.isBeyondDimensionsVirtualMenu(
                "com.example.OtherMenu"));
    }

    @Test
    void recognizesBetterBeyondDimensionsVirtualSlotsInjectedIntoAnyMenu() {
        assertTrue(ContainerTransferLogic.isBetterBeyondDimensionsNetworkSlotClass(
                "net.xuwu.betterbeyonddimensions.common.NetworkStorageSlot"));
        assertFalse(ContainerTransferLogic.isBetterBeyondDimensionsNetworkSlotClass(
                "net.minecraft.world.inventory.Slot"));
    }

    @Test
    void identifiesSophisticatedBackpackUpgradeSlots() {
        assertTrue(ContainerTransferLogic.isUpgradeSlotClass(
                "net.p3pp3rf1y.sophisticatedcore.common.gui.StorageContainerMenuBase$StorageUpgradeSlot"));
        assertTrue(ContainerTransferLogic.isUpgradeSlotClass(
                "net.p3pp3rf1y.sophisticatedbackpacks.common.gui.BackpackContainer$BackpackUpgradeSlot"));
        assertFalse(ContainerTransferLogic.isUpgradeSlotClass(
                "net.minecraft.world.inventory.Slot"));
    }

    @Test
    void protectsOnlyFullyBoundRsNetworkUpgrades() {
        assertTrue(ContainerTransferLogic.isProtectedBoundNetworkUpgrade(
                "rs_integration:rs_magnet_upgrade", true, true));
        assertTrue(ContainerTransferLogic.isProtectedBoundNetworkUpgrade(
                "rs_integration:rs_pickup_upgrade", true, true));
        assertTrue(ContainerTransferLogic.isProtectedBoundNetworkUpgrade(
                "rs_integration:rs_refill_upgrade", true, true));
        assertTrue(ContainerTransferLogic.isProtectedBoundNetworkUpgrade(
                "rs_integration:rs_feeding_upgrade", true, true));
        assertFalse(ContainerTransferLogic.isProtectedBoundNetworkUpgrade(
                "rs_integration:rs_magnet_upgrade", true, false));
        assertFalse(ContainerTransferLogic.isProtectedBoundNetworkUpgrade(
                "minecraft:diamond", true, true));
    }
}
