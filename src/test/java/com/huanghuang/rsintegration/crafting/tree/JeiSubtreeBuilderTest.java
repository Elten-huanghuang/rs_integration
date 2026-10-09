package com.huanghuang.rsintegration.crafting.tree;

import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.MaterialLocks;
import com.huanghuang.rsintegration.crafting.plan.PlanStep;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class JeiSubtreeBuilderTest extends BootstrapTest {
    @Test
    void recipeWithoutVanillaInputsShowsHandlerCandidatesAndSelectedMaterial() {
        ResourceLocation recipeId = new ResourceLocation("malum", "void_favor/umbral_spirit_copy");
        Recipe<?> recipe = mock(Recipe.class);
        when(recipe.getIngredients()).thenReturn(NonNullList.create());
        Ingredient semanticInput = Ingredient.of(Items.CHARCOAL, Items.IRON_INGOT);
        ItemStack selected = new ItemStack(Items.IRON_INGOT);
        String lockKey = MaterialLocks.key(recipeId, semanticInput);
        PlanStep step = new PlanStep(recipeId, new ItemStack(Items.COAL), 1,
                List.of(new ItemStack(Items.CHARCOAL)));
        PlanTreeNode root = new PlanTreeNode(IngredientKey.of(step.output()),
                step.output(), 1, 0, step);
        PlanTreeNode child = new PlanTreeNode(IngredientKey.of(new ItemStack(Items.CHARCOAL)),
                new ItemStack(Items.CHARCOAL), 1, 1, null);
        root.children.add(child);

        try (MockedStatic<CraftPacketUtils> extraction = mockStatic(CraftPacketUtils.class)) {
            extraction.when(() -> CraftPacketUtils.extractIngredientSpecs(recipe))
                    .thenReturn(List.of(new IngredientSpec(semanticInput, 1)));
            JeiSubtreeBuilder.enrichCarousels(root, Map.of(lockKey, selected), id -> recipe);
        }

        assertNotNull(child.ingredient);
        assertEquals(lockKey, child.materialLockKey);
        assertEquals(2, child.materialOptions.size());
        assertTrue(child.materialOptions.stream().anyMatch(stack -> stack.is(Items.IRON_INGOT)));
        assertTrue(child.materialOptions.stream().anyMatch(stack -> stack.is(Items.CHARCOAL)));
        assertEquals(Items.IRON_INGOT, child.lockedMaterial.getItem());
    }
}
