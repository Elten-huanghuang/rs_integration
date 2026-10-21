package com.huanghuang.rsintegration.crafting.tree;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

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
}
