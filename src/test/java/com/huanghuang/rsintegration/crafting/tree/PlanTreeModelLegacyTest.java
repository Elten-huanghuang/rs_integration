package com.huanghuang.rsintegration.crafting.tree;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import com.huanghuang.rsintegration.crafting.plan.PlanStep;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
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

    @Test
    void intermediateStepReceivesItsOwnPrerequisiteIssue() {
        ResourceLocation rootId = new ResourceLocation("test", "root");
        ResourceLocation altarId = new ResourceLocation("goety", "dark_altar_step");
        PlanStep altarStep = new PlanStep(altarId, new ItemStack(Items.EMERALD), 1,
                List.of(new ItemStack(Items.COAL)), List.of(), ModType.byId("goety"));
        PlanStep rootStep = new PlanStep(rootId, new ItemStack(Items.DIAMOND), 1,
                List.of(new ItemStack(Items.EMERALD)), List.of(), ModType.byId("generic"));
        PlanResponse plan = new PlanResponse(true, "Diamond", new ItemStack(Items.DIAMOND),
                List.of(altarStep, rootStep), Map.of(), List.of(), rootId.toString(),
                null, null, 0, 0, 0, List.of(), 1,
                null, null, null, 0, false, false, false, null,
                java.util.Set.of(), Map.of(), null, null, true, List.of(),
                Map.of(altarId, new PlanResponse.StepIssue(
                        List.of(Component.literal("Missing altar structure")), true)));

        PlanTreeNode altarNode = PlanTreeModel.from(plan).root.children.get(0);

        assertEquals(altarId, altarNode.step.recipeId());
        assertTrue(altarNode.prerequisiteBlocked);
        assertEquals("Missing altar structure", altarNode.warnings.get(0).getString());
    }
}
