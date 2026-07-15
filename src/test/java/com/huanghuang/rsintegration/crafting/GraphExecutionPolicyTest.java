package com.huanghuang.rsintegration.crafting;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphExecutionPolicyTest {

    @Test
    void appendedTerminalStepCannotUseIncompleteGraphExecutor() {
        assertFalse(GraphExecutionPolicy.useGraphExecutor(true));
    }

    @Test
    void selfContainedGraphUsesGraphExecutor() {
        assertTrue(GraphExecutionPolicy.useGraphExecutor(false));
    }
}
