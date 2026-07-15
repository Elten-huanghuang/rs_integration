package com.huanghuang.rsintegration.crafting;

/** Selects the executor shape for a resolved crafting plan. */
final class GraphExecutionPolicy {

    private GraphExecutionPolicy() {}

    static boolean useGraphExecutor(boolean hasAppendedTerminalStep) {
        return !hasAppendedTerminalStep;
    }
}
