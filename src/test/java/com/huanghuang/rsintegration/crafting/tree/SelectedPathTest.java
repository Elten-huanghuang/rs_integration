package com.huanghuang.rsintegration.crafting.tree;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelectedPathTest extends BootstrapTest {
    private static final ResourceLocation FIRST = new ResourceLocation("test", "first");
    private static final ResourceLocation SECOND = new ResourceLocation("test", "second");

    @Test
    void failedPreviewCanRestoreThePreviouslyExplicitBranch() {
        IngredientKey key = IngredientKey.of(new ItemStack(Items.IRON_INGOT));
        SelectedPath path = new SelectedPath();
        path.selectBranch(key, 0, FIRST);
        SelectedPath.BranchSnapshot previous = path.snapshotBranch(key);

        path.selectBranch(key, 1, SECOND);
        path.restoreBranch(key, previous);

        assertEquals(FIRST, path.selectedRecipe(key));
        assertEquals(FIRST, path.exportSelections().get(key));
    }

    @Test
    void failedPreviewDoesNotTurnAServerDefaultIntoAnExplicitPreference() {
        IngredientKey key = IngredientKey.of(new ItemStack(Items.IRON_INGOT));
        SelectedPath path = new SelectedPath();
        SelectedPath.BranchSnapshot previous = path.snapshotBranch(key);

        path.selectBranch(key, 1, SECOND);
        path.restoreBranch(key, previous);

        assertFalse(path.isSelected(key));
        assertNull(path.selectedRecipe(key));
    }

    @Test
    void serverPreviewKeepsTheConstraintsUsedToRequestItWhenDisplayNbtChanges() {
        ItemStack previous = new ItemStack(Items.NETHERITE_SWORD);
        IngredientKey selectedKey = IngredientKey.of(previous);
        SelectedPath path = new SelectedPath();
        path.bindTree(tree(previous));
        path.selectBranch(selectedKey, 1, SECOND);

        ItemStack refreshed = previous.copy();
        refreshed.getOrCreateTag().putBoolean("Unbreakable", true);
        path.bindTree(tree(refreshed));

        assertEquals(SECOND, path.exportSelections().get(selectedKey),
                "确认必须保留这次预览请求使用的配方约束，不能因展示 NBT 改变变成 forced=0");
    }

    @Test
    void serverPreviewAcknowledgesTheSelectedRouteInsteadOfLeavingAnUnpreviewedDraft() {
        ItemStack stack = new ItemStack(Items.NETHERITE_SWORD);
        IngredientKey key = IngredientKey.of(stack);
        SelectedPath path = new SelectedPath();
        path.bindTree(tree(stack));
        path.selectBranch(key, 1, SECOND);
        assertTrue(path.isDirty());

        path.bindTree(tree(stack));

        assertFalse(path.isDirty(), "服务端已经返回这条路线后，确认应复用该预览凭据");
        assertEquals(SECOND, path.exportSelections().get(key));
    }

    private static PlanTreeModel tree(ItemStack stack) {
        return new PlanTreeModel(new PlanTreeNode(IngredientKey.of(stack), stack, 1, 0, null));
    }
}
