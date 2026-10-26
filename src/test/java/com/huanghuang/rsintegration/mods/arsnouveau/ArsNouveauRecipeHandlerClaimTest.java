package com.huanghuang.rsintegration.mods.arsnouveau;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

class ArsNouveauRecipeHandlerClaimTest {

    @Test
    void claimDependsOnRecipeTypeAndCannotCacheByClass() {
        assertFalse(new ArsNouveauRecipeHandler().cacheByRecipeClass());
    }
}
