package com.huanghuang.rsintegration.mods.farmersdelight;

import com.huanghuang.rsintegration.crafting.plan.PlanStep;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CuttingBoardPlanCheckTest extends BootstrapTest {
    @Test
    void plannedToolOutputDoesNotBlockExecution() {
        PlanStep craftTool = new PlanStep(
                new ResourceLocation("test", "craft_cutting_tool"),
                new ItemStack(Items.IRON_SWORD), 1,
                List.of(new ItemStack(Items.IRON_INGOT)));

        CuttingBoardBatchDelegate.ToolPlanCheck check =
                CuttingBoardBatchDelegate.missingToolCheck(
                        Ingredient.of(Items.IRON_SWORD), List.of(craftTool));

        assertFalse(check.blocksExecution());
        assertEquals(1, check.warnings().size());
    }

    @Test
    void absentAndUnplannedToolStillBlocksExecution() {
        CuttingBoardBatchDelegate.ToolPlanCheck check =
                CuttingBoardBatchDelegate.missingToolCheck(
                        Ingredient.of(Items.IRON_SWORD), List.of());

        assertTrue(check.blocksExecution());
        assertEquals(1, check.warnings().size());
    }
}
