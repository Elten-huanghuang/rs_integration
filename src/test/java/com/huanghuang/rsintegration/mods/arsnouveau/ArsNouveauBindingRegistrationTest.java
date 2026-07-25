package com.huanghuang.rsintegration.mods.arsnouveau;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArsNouveauBindingRegistrationTest {
    @Test
    void imbuementBindingTargetsTheBlockAndStableRegistryId() {
        assertEquals(List.of("com.hollingsworth.arsnouveau.common.block.ImbuementBlock"),
                ArsNouveauRSModule.IMBUEMENT_BLOCK_CLASSES);
        assertEquals(List.of("ars_nouveau:imbuement_chamber"),
                ArsNouveauRSModule.IMBUEMENT_BLOCK_IDS);
        assertTrue(ArsNouveauRSModule.IMBUEMENT_BLOCK_CLASSES.stream()
                .noneMatch(name -> name.contains(".block.tile.")));
    }

    @Test
    void apparatusBindingTargetsTheBlockAndStableRegistryId() {
        assertEquals(List.of("com.hollingsworth.arsnouveau.common.block.EnchantingApparatusBlock"),
                ArsNouveauRSModule.APPARATUS_BLOCK_CLASSES);
        assertEquals(List.of("ars_nouveau:enchanting_apparatus"),
                ArsNouveauRSModule.APPARATUS_BLOCK_IDS);
        assertTrue(ArsNouveauRSModule.APPARATUS_BLOCK_CLASSES.stream()
                .noneMatch(name -> name.contains(".block.tile.")));
    }
}
