package com.huanghuang.rsintegration.crafting.batch;

import net.minecraft.world.item.ItemStack;

import java.util.Objects;

/** Decides whether a terminal recipe has enough static information to become a DAG node. */
final class TerminalGraphExecutionPolicy {
    enum Reason {
        COMPOSABLE,
        DYNAMIC_INPUTS,
        UNKNOWN_OUTPUT
    }

    record Decision(boolean composable, Reason reason) {}

    private TerminalGraphExecutionPolicy() {}

    static Decision decide(boolean hasStaticInputs, boolean hasSyntheticContext,
                           ItemStack output) {
        Objects.requireNonNull(output, "output");
        if (!hasStaticInputs && !hasSyntheticContext) {
            return new Decision(false, Reason.DYNAMIC_INPUTS);
        }
        if (output.isEmpty()) return new Decision(false, Reason.UNKNOWN_OUTPUT);
        return new Decision(true, Reason.COMPOSABLE);
    }
}
