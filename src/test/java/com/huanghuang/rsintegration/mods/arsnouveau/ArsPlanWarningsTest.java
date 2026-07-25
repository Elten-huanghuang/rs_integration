package com.huanghuang.rsintegration.mods.arsnouveau;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ArsPlanWarningsTest {
    @Test
    void readsBothApparatusAndImbuementSourceFields() {
        assertEquals(2500, ArsPlanWarnings.rawSourceCost(new ApparatusCost()));
        assertEquals(2000, ArsPlanWarnings.rawSourceCost(new ImbuementCost()));
    }

    private static final class ApparatusCost {
        private final int sourceCost = 2500;
    }

    private static final class ImbuementCost {
        private final int source = 2000;
    }
}
