package com.huanghuang.rsintegration.mods.youkaishomecoming.steamer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SteamerStructurePolicyTest {
    private static final SteamerStructurePolicy.Layer OPEN_STRUCTURE =
            new SteamerStructurePolicy.Layer(true, false, false);
    private static final SteamerStructurePolicy.Layer EMBEDDED_LID =
            new SteamerStructurePolicy.Layer(true, false, true);
    private static final SteamerStructurePolicy.Layer LID_BLOCK =
            new SteamerStructurePolicy.Layer(false, true, false);
    private static final SteamerStructurePolicy.Layer GAP =
            new SteamerStructurePolicy.Layer(false, false, false);

    @Test
    void acceptsCompactOneLayerSteamerWithEmbeddedCap() {
        assertTrue(SteamerStructurePolicy.hasLid(List.of(EMBEDDED_LID)));
    }

    @Test
    void acceptsFullHeightSteamerWithSeparateLidBlock() {
        assertTrue(SteamerStructurePolicy.hasLid(List.of(
                OPEN_STRUCTURE, OPEN_STRUCTURE, LID_BLOCK)));
    }

    @Test
    void doesNotFindALidAcrossAnAirGap() {
        assertFalse(SteamerStructurePolicy.hasLid(List.of(
                OPEN_STRUCTURE, GAP, LID_BLOCK)));
    }

    @Test
    void lidWithoutSteamerStructureIsInvalid() {
        assertFalse(SteamerStructurePolicy.hasLid(List.of(LID_BLOCK)));
    }

    @Test
    void openRackStackStillRequiresALid() {
        assertFalse(SteamerStructurePolicy.hasLid(List.of(
                OPEN_STRUCTURE, OPEN_STRUCTURE)));
    }
}
