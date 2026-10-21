package com.huanghuang.rsintegration.crafting.plan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeCandidateRulesTest {

    @Test
    void rootNodeCanNeverBeExcluded() {
        assertFalse(RecipeCandidateRules.canExclude(0, 2));
        assertFalse(RecipeCandidateRules.canExclude(0, 9));
    }

    @Test
    void intermediateNodeNeedsMoreThanOneCandidate() {
        assertFalse(RecipeCandidateRules.canExclude(1, 1));
        assertTrue(RecipeCandidateRules.canExclude(1, 2));
    }

    @Test
    void stockOnlyDisableNeverAppearsOnRootOrSingleCandidateNodes() {
        assertFalse(RecipeCandidateRules.canChooseStockOnly(0, 2));
        assertFalse(RecipeCandidateRules.canChooseStockOnly(1, 1));
        assertTrue(RecipeCandidateRules.canChooseStockOnly(1, 2));
    }

    @Test
    void forgetBelongsToNodeTabOnly() {
        assertTrue(RecipeCandidateRules.showsForget(false));
        assertFalse(RecipeCandidateRules.showsForget(true));
    }

    @Test
    void bookmarkAllBelongsToDiagnosticsOnly() {
        assertFalse(RecipeCandidateRules.showsBookmarkAll(false));
        assertTrue(RecipeCandidateRules.showsBookmarkAll(true));
    }
}
