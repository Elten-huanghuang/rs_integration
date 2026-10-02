package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.graph.CraftNode;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
import com.huanghuang.rsintegration.crafting.graph.OutputDeclaration;
import com.huanghuang.rsintegration.crafting.graph.OutputKind;
import com.huanghuang.rsintegration.crafting.graph.OutputPortId;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapedRecipe;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ProbabilityPlanningTest extends BootstrapTest {
    @Test
    void supplementalCandidatesExcludeWaitingParentsEvenWhenForced() {
        ResourceLocation id = new ResourceLocation("test", "waiting_parent");
        ShapedRecipe recipe = new ShapedRecipe(id, "", CraftingBookCategory.MISC, 1, 1,
                NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.STONE)), new ItemStack(Items.EGG));
        RecipeIndex.Entry entry = new RecipeIndex.Entry(recipe, ModType.GENERIC,
                new ResourceLocation("minecraft", "crafting"));
        ResolutionContext context = new ResolutionContext(null, Map.of(Items.EGG, List.of(entry)),
                List.of(new ItemStack(Items.STONE)), Map.of(new ResourceLocation("minecraft", "egg"), id));
        context.excludedRecipes = Set.of(id);
        assertTrue(CandidateEngine.findCandidates(Ingredient.of(Items.EGG), context).isEmpty());
        assertTrue(context.steps.isEmpty());
    }

    @Test
    void graphProjectionPreservesTargetIdentityAndTotalQuantity() {
        NodeId nodeId = new NodeId(4);
        ItemStack output = new ItemStack(Items.PAPER);
        output.getOrCreateTag().putString("quality", "rare");
        MaterialKey material = MaterialKey.of(output);
        CraftNode node = new CraftNode(nodeId, new ResourceLocation("test", "recycle"),
                ModType.GENERIC.id(), new ResourceLocation("test", "type"), 3,
                List.of(), List.of(), false, null, null, List.of(),
                List.of(new OutputDeclaration(new OutputPortId(nodeId, 0), material, 750, OutputKind.PRIMARY)));
        ProductionTarget target = ExecutionEquivalence.projectStep(node).productionTarget();
        assertNotNull(target);
        assertEquals(750, target.quantity());
        assertEquals(material, target.material());
        assertNull(new CraftingResolver.ResolutionStep(node.recipeId(), ModType.GENERIC,
                node.recipeTypeId()).productionTarget());
    }
}
