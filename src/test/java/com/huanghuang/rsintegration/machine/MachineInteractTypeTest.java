package com.huanghuang.rsintegration.machine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MachineInteractTypeTest {
    @Test
    void onlyFurnaceBackedMachinesUseQuickActions() {
        assertQuick("block.minecraft.furnace");
        assertQuick("block.minecraft.blast_furnace");
        assertQuick("minecraft:smoker||block.minecraft.smoker");
        assertQuick("ironfurnaces_blast_furnace||block.ironfurnaces.blast_furnace");

        assertGui("farmersdelight:cooking_pot");
        assertGui("minecraft:campfire");
        assertGui("wizards_reborn:runic_workbench");
        assertGui("youkaishomecoming:fermentation_tank");
    }

    private static void assertQuick(String blockKey) {
        assertEquals(MachineInteractType.QUICK,
                MachineInteractType.fromBlockKey(blockKey));
    }

    private static void assertGui(String blockKey) {
        assertEquals(MachineInteractType.GUI,
                MachineInteractType.fromBlockKey(blockKey));
    }
}
