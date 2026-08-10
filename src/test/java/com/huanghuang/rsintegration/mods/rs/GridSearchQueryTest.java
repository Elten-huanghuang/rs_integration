package com.huanghuang.rsintegration.mods.rs;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GridSearchQueryTest {
    @Test
    void parsesAllSpecialPrefixesAcrossAndOrBranches() {
        GridSearchQuery query = GridSearchQuery.parse(
                "stone @Create | #Energy $forge:ingots/iron");

        assertEquals(GridSearchQuery.MOD | GridSearchQuery.TOOLTIP | GridSearchQuery.TAG,
                query.requiredModes());
        assertEquals(List.of(
                new GridSearchQuery.Term(GridSearchQuery.MOD, "create"),
                new GridSearchQuery.Term(GridSearchQuery.TOOLTIP, "energy"),
                new GridSearchQuery.Term(GridSearchQuery.TAG, "forge:ingots/iron")
        ), query.terms());
    }

    @Test
    void ignoresOrdinaryAndEmptyPrefixTokens() {
        GridSearchQuery query = GridSearchQuery.parse("iron @ # | ordinary");

        assertEquals(0, query.requiredModes());
        assertEquals(List.of(), query.terms());
    }

    @Test
    void removesDuplicateTermsWithoutChangingOrder() {
        GridSearchQuery query = GridSearchQuery.parse("@mekanism | @mekanism #gas #gas");

        assertEquals(List.of(
                new GridSearchQuery.Term(GridSearchQuery.MOD, "mekanism"),
                new GridSearchQuery.Term(GridSearchQuery.TOOLTIP, "gas")
        ), query.terms());
    }
}
