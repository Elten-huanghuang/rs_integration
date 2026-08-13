package com.huanghuang.rsintegration.crafting.tree;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import com.huanghuang.rsintegration.crafting.plan.PlanStep;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanTreeModelLegacyTest extends BootstrapTest {

    @Test
    void rootKeepsTargetStepAndAlternativesWithoutGraphNodes() {
        ResourceLocation selected = new ResourceLocation("crafttweaker", "refined_mod.moon_slash");
        ResourceLocation alternative = new ResourceLocation(
                "rs_integration", "irons_spellbooks/scroll_forge/refined_mod/moon_slash/1");
        PlanStep targetStep = new PlanStep(selected, new ItemStack(Items.PAPER), 1,
                List.of(new ItemStack(Items.INK_SAC)), List.of(alternative),
                ModType.byId("generic"), 0, true, 0, 0,
                List.of("irons_spellbooks_scroll_forge"));
        PlanResponse plan = new PlanResponse(true, "Moon Slash", new ItemStack(Items.PAPER),
                List.of(targetStep), Map.of(), List.of(), selected.toString());

        PlanTreeNode root = PlanTreeModel.from(plan).root;

        assertNotNull(root.step);
        assertEquals(selected, root.step.recipeId());
        assertEquals(List.of(alternative), root.step.alternatives());
        assertTrue(root.hasAlternatives());
    }
}
