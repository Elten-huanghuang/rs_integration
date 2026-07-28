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
                        new ItemStack(Items.IRON_INGOT), true);

        assertTrue(decision.composable());
        assertEquals(TerminalGraphExecutionPolicy.Reason.COMPOSABLE, decision.reason());
    }

    @Test
    void preservesEvidenceBasedFallbackReasons() {
        assertEquals(TerminalGraphExecutionPolicy.Reason.INFER_MODE,
                TerminalGraphExecutionPolicy.decide(true, true,
                        new ItemStack(Items.IRON_INGOT), true).reason());
        assertEquals(TerminalGraphExecutionPolicy.Reason.DYNAMIC_INPUTS,
                TerminalGraphExecutionPolicy.decide(false, false,
                        new ItemStack(Items.IRON_INGOT), true).reason());
        TerminalGraphExecutionPolicy.Decision unknownOutput =
                TerminalGraphExecutionPolicy.decide(false, true, ItemStack.EMPTY, true);
        assertFalse(unknownOutput.composable());
        assertEquals(TerminalGraphExecutionPolicy.Reason.UNKNOWN_OUTPUT, unknownOutput.reason());
        assertEquals(TerminalGraphExecutionPolicy.Reason.NONDETERMINISTIC_OUTPUT,
                TerminalGraphExecutionPolicy.decide(false, true,
                        new ItemStack(Items.IRON_INGOT), false).reason());
    }
}
