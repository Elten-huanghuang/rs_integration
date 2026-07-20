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
}
