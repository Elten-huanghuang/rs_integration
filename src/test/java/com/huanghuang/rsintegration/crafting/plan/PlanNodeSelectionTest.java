package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.crafting.tree.IngredientKey;
import com.huanghuang.rsintegration.crafting.tree.PlanTreeNode;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;

class PlanNodeSelectionTest extends BootstrapTest {
    @Test
    void changingVariantFollowsTheIngredientRatherThanAnUnrelatedSameItem() {
        PlanTreeNode before = node(Items.CRAFTING_TABLE, null);
        PlanTreeNode oak = node(Items.OAK_PLANKS, "table#planks");
        before.children.add(oak);
        PlanNodeSelection selection = PlanNodeSelection.capture(before, oak)
                .withMaterial(IngredientKey.of(new ItemStack(Items.BIRCH_PLANKS)));

        PlanTreeNode after = node(Items.CRAFTING_TABLE, null);
        PlanTreeNode unrelated = node(Items.BIRCH_PLANKS, "chest#planks");
        PlanTreeNode birch = node(Items.BIRCH_PLANKS, "table#planks");
        after.children.add(unrelated);
        after.children.add(birch);
        assertSame(birch, selection.resolve(after));
    }

    @Test
    void automaticMaterialChoiceStillFollowsTheOriginalRequirement() {
        PlanTreeNode before = node(Items.CRAFTING_TABLE, null);
        PlanTreeNode oak = node(Items.OAK_PLANKS, "table#planks");
        before.children.add(oak);
        PlanNodeSelection selection = PlanNodeSelection.capture(before, oak);
        PlanTreeNode after = node(Items.CRAFTING_TABLE, null);
        PlanTreeNode birch = node(Items.BIRCH_PLANKS, "table#planks");
        after.children.add(birch);
        assertSame(birch, selection.resolve(after));
    }

    @Test
    void repeatedSameKeyNodesKeepTheirOccurrenceAfterRebuild() {
        PlanTreeNode before = node(Items.CRAFTING_TABLE, null);
        before.children.add(node(Items.OAK_PLANKS, null));
        before.children.add(node(Items.OAK_PLANKS, null));
        PlanNodeSelection selection = PlanNodeSelection.capture(before, before.children.get(1));
        PlanTreeNode after = node(Items.CRAFTING_TABLE, null);
        after.children.add(node(Items.OAK_PLANKS, null));
        after.children.add(node(Items.OAK_PLANKS, null));
        assertSame(after.children.get(1), selection.resolve(after));
    }

    @Test
    void removedRequirementFallsBackToTargetInsteadOfAnotherRecipesMaterial() {
        PlanTreeNode before = node(Items.CRAFTING_TABLE, null);
        PlanTreeNode oak = node(Items.OAK_PLANKS, "table#planks");
        before.children.add(oak);
        PlanNodeSelection selection = PlanNodeSelection.capture(before, oak);
        PlanTreeNode after = node(Items.CRAFTING_TABLE, null);
        after.children.add(node(Items.OAK_PLANKS, "other#planks"));
        assertSame(after, selection.resolve(after));
    }

    private static PlanTreeNode node(Item item, String materialKey) {
        ItemStack stack = new ItemStack(item);
        PlanTreeNode node = new PlanTreeNode(IngredientKey.of(stack), stack, 1, 1, null);
        node.materialLockKey = materialKey;
        return node;
    }
}
