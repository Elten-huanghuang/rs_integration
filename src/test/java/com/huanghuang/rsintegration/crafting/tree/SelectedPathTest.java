package com.huanghuang.rsintegration.crafting.tree;

import com.huanghuang.rsintegration.crafting.plan.PlanStep;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelectedPathTest extends BootstrapTest {

    @Test
    void rebindingDropsSelectionsWhoseMaterialLeftThePlan() {
        ItemStack rootStack = new ItemStack(Items.DIAMOND);
        ItemStack branchStack = new ItemStack(Items.STONE);
        IngredientKey rootKey = IngredientKey.of(rootStack);
        IngredientKey branchKey = IngredientKey.of(branchStack);
        ResourceLocation selectedRecipe = id("selected_stone_recipe");
        PlanTreeNode root = new PlanTreeNode(rootKey, rootStack, 1, 0, null);
        root.children.add(new PlanTreeNode(
                branchKey, branchStack, 1, 1,
                new PlanStep(id("stone_recipe"), branchStack, 1,
                        List.of(), List.of(id("alternative")), null, 1, true)));
        SelectedPath path = new SelectedPath();
        path.bindTree(new PlanTreeModel(root));
        path.selectBranch(branchKey, 1, selectedRecipe);

        assertEquals(selectedRecipe, path.exportSelections().get(branchKey));

        root.children.clear();
        path.bindTree(new PlanTreeModel(root));

        assertTrue(path.exportSelections().isEmpty());
    }

    @Test
    void rebindingKeepsSelectionWhenTheMaterialStillExistsWithoutAlternatives() {
        ItemStack rootStack = new ItemStack(Items.DIAMOND);
        ItemStack branchStack = new ItemStack(Items.STONE);
        IngredientKey rootKey = IngredientKey.of(rootStack);
        IngredientKey branchKey = IngredientKey.of(branchStack);
        ResourceLocation selectedRecipe = id("selected_stone_recipe");
        PlanTreeNode root = new PlanTreeNode(rootKey, rootStack, 1, 0, null);
        root.children.add(new PlanTreeNode(
                branchKey, branchStack, 1, 1,
                new PlanStep(id("forced_stone_recipe"), branchStack, 1, List.of())));
        SelectedPath path = new SelectedPath();
        path.bindTree(new PlanTreeModel(root));
        path.selectBranch(branchKey, 1, selectedRecipe);

        path.bindTree(new PlanTreeModel(root));

        assertEquals(selectedRecipe, path.exportSelections().get(branchKey));
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("test", path);
    }
}
