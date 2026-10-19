package com.huanghuang.rsintegration.crafting.tree;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanTreeLayoutTest extends BootstrapTest {

    @Test
    void boxLookupUsesNodeIdentityAndDoesNotScanTheLayout() {
        ItemStack rootStack = new ItemStack(Items.DIAMOND);
        ItemStack childStack = new ItemStack(Items.STONE);
        PlanTreeNode root = new PlanTreeNode(IngredientKey.of(rootStack), rootStack, 1, 0, null);
        PlanTreeNode child = new PlanTreeNode(IngredientKey.of(childStack), childStack, 1, 1, null);
        root.children.add(child);
        PlanTreeLayout layout = new PlanTreeLayout();

        layout.rebuild(root);

        PlanTreeLayout.Box rootBox = layout.boxFor(root);
        PlanTreeLayout.Box childBox = layout.boxFor(child);
        assertNotNull(rootBox);
        assertNotNull(childBox);
        assertSame(root, rootBox.node());
        assertSame(child, childBox.node());
        assertNull(layout.boxFor(new PlanTreeNode(IngredientKey.of(rootStack), rootStack, 1, 0, null)));
    }

    @Test
    void viewportIntersectionRejectsNodesOutsideTheVisibleTreeArea() {
        assertTrue(PlanTreeRenderer.intersectsViewport(0, 0, 100, 100, 90, 90, 120, 120));
        assertFalse(PlanTreeRenderer.intersectsViewport(0, 0, 100, 100, 110, 10, 130, 30));
        assertFalse(PlanTreeRenderer.intersectsViewport(0, 0, 100, 100, 10, -30, 30, 0));
    }
}
