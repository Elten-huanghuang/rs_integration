package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalGraphExecutionPolicyTest extends BootstrapTest {
    @Test
    void repeatedStaticRecipeCanBeComposed() {
        TerminalGraphExecutionPolicy.Decision decision =
                TerminalGraphExecutionPolicy.decide(false, true,
                        new ItemStack(Items.IRON_INGOT), true, false);

        assertTrue(decision.composable());
        assertEquals(TerminalGraphExecutionPolicy.Reason.COMPOSABLE, decision.reason());
    }

    @Test
    void preservesEvidenceBasedFallbackReasons() {
        assertEquals(TerminalGraphExecutionPolicy.Reason.INFER_MODE,
                TerminalGraphExecutionPolicy.decide(true, true,
                        new ItemStack(Items.IRON_INGOT), true, false).reason());
        assertEquals(TerminalGraphExecutionPolicy.Reason.DYNAMIC_INPUTS,
                TerminalGraphExecutionPolicy.decide(false, false,
                        new ItemStack(Items.IRON_INGOT), true, false).reason());
        TerminalGraphExecutionPolicy.Decision unknownOutput =
                TerminalGraphExecutionPolicy.decide(false, true, ItemStack.EMPTY, true, false);
        assertFalse(unknownOutput.composable());
        assertEquals(TerminalGraphExecutionPolicy.Reason.UNKNOWN_OUTPUT, unknownOutput.reason());
        assertEquals(TerminalGraphExecutionPolicy.Reason.NONDETERMINISTIC_OUTPUT,
                TerminalGraphExecutionPolicy.decide(false, true,
                        new ItemStack(Items.IRON_INGOT), false, false).reason());
    }

    @Test
    void selfAmplifyingTerminalUsesSequentialExecution() {
        TerminalGraphExecutionPolicy.Decision decision =
                TerminalGraphExecutionPolicy.decide(false, true,
                        new ItemStack(Items.IRON_INGOT, 2), true, true);

        assertFalse(decision.composable());
        assertEquals(TerminalGraphExecutionPolicy.Reason.SELF_AMPLIFYING_INPUT,
                decision.reason());
    }
}
