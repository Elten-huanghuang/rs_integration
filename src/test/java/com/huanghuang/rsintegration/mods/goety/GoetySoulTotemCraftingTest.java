package com.huanghuang.rsintegration.mods.goety;

import com.Polarice3.Goety.common.items.magic.FullSpentTotem;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoetySoulTotemCraftingTest {
    @Test
    void recognizesTotemOfRootsThroughGoetysSoulTotemBaseClass() {
        assertTrue(GoetySoulTotemCrafting.isSoulTotemType(FullSpentTotem.class));
        assertFalse(GoetySoulTotemCrafting.isSoulTotemType(Object.class));
    }
}
