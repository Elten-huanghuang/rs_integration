package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.CraftingResolver.ResolutionStep;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SequentialTerminalRepeatPolicyTest extends BootstrapTest {

    @Test
    void arcaneIteratorBatchIsReducedToOnePhysicalStart() {
        ResolutionStep lens = step("arcane_iterator/arcanum_lens", 2);

        assertTrue(CraftExecutionPolicy.requiresExternalTerminalRepeat(lens));

        ResolutionStep single = CraftExecutionPolicy.singleExecution(lens);
        assertEquals(1, single.executions());
        assertEquals(lens.recipeId(), single.recipeId());
        assertSame(lens.modType(), single.modType());
    }

    @Test
    void enchantmentRecipeUsesTheSameSequentialPolicy() {
        ResolutionStep enchantment = step("arcane_iterator/enchantments/aqua_affinity", 3);

        assertTrue(CraftExecutionPolicy.requiresExternalTerminalRepeat(enchantment));
        assertEquals(1,
                CraftExecutionPolicy.singleExecution(enchantment).executions());
    }

    @Test
    void singleRunAndOtherRecipesKeepTheExistingExecutionPath() {
        assertFalse(CraftExecutionPolicy.requiresExternalTerminalRepeat(
                step("arcane_iterator/arcanum_lens", 1)));
        assertFalse(CraftExecutionPolicy.requiresExternalTerminalRepeat(
                step("wissen_crystallizer/earth_crystal_seed", 2)));
        assertFalse(CraftExecutionPolicy.requiresExternalTerminalRepeat(null));
    }

    private static ResolutionStep step(String path, int executions) {
        return new ResolutionStep(
                new ResourceLocation("wizards_reborn", path),
                ModType.GENERIC,
                new ResourceLocation("wizards_reborn", path),
                List.of(), List.of(), false, executions,
                new ItemStack(Items.BOOK), new ItemStack(Items.ENCHANTED_BOOK));
    }
}
