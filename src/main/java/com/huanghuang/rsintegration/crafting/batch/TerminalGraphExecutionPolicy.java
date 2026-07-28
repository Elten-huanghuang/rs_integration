package com.huanghuang.rsintegration.crafting.batch;

import net.minecraft.world.item.ItemStack;

import java.util.Objects;

/** Decides whether a terminal recipe has enough static information to become a DAG node. */
final class TerminalGraphExecutionPolicy {
    enum Reason {
        COMPOSABLE,
        INFER_MODE,
        DYNAMIC_INPUTS,
        UNKNOWN_OUTPUT,
        NONDETERMINISTIC_OUTPUT
    }

    record Decision(boolean composable, Reason reason) {}

    private TerminalGraphExecutionPolicy() {}

    static Decision decide(boolean inferMode, boolean hasStaticInputs, ItemStack output,
                           boolean deterministicOutput) {
        Objects.requireNonNull(output, "output");
        if (inferMode) return new Decision(false, Reason.INFER_MODE);
        if (!hasStaticInputs) return new Decision(false, Reason.DYNAMIC_INPUTS);
        if (output.isEmpty()) return new Decision(false, Reason.UNKNOWN_OUTPUT);
        if (!deterministicOutput) {
            return new Decision(false, Reason.NONDETERMINISTIC_OUTPUT);
        }
        return new Decision(true, Reason.COMPOSABLE);
    }
}
