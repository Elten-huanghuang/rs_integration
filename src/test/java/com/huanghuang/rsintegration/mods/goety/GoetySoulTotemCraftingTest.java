package com.huanghuang.rsintegration.mods.goety;

import com.Polarice3.Goety.common.items.magic.FullSpentTotem;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoetySoulTotemCraftingTest {
    @Test
    void recognizesTotemOfRootsThroughGoetysSoulTotemBaseClass() {
        assertTrue(GoetySoulTotemCrafting.isSoulTotemType(FullSpentTotem.class));
        assertFalse(GoetySoulTotemCrafting.isSoulTotemType(Object.class));
    }

    @Test
    void multipliesPerTickSoulCostByRitualDurationWithoutOverflow() {
        assertEquals(600, GoetySoulTotemCrafting.saturatingMultiply(3, 200));
        assertEquals(Integer.MAX_VALUE,
                GoetySoulTotemCrafting.saturatingMultiply(Integer.MAX_VALUE, 2));
    }
}
