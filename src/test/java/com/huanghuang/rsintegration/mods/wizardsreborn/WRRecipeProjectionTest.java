package com.huanghuang.rsintegration.mods.wizardsreborn;

import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanValidator;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraphProjector;
import com.huanghuang.rsintegration.crafting.planning.PureRecipePlanner;
import com.huanghuang.rsintegration.crafting.planning.PurePlanGraphAdapter;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WRRecipeProjectionTest extends BootstrapTest {
    private final WRRecipeHandler handler = new WRRecipeHandler();

    @Test
    void staticIteratorOutputCanBeExpandedAsAnIntermediate() {
        ArcaneIteratorRecipe recipe = new ArcaneIteratorRecipe(
                new ItemStack(Items.DIAMOND), false, false);

        assertTrue(handler.supportsBackgroundPlanning(recipe));
        assertEquals(Items.DIAMOND, handler.getResultItem(recipe, RegistryAccess.EMPTY).getItem());
    }

    @Test
    void intermediateCoreExpandsItsGlassAndRepeatedCopyDependencies() {
        ArcaneIteratorRecipe core = new ArcaneIteratorRecipe(
                new ItemStack(Items.DIAMOND), false, false);
        assertTrue(handler.supportsBackgroundPlanning(core));
        RecipeNode coreNode = ImmutableRecipeGraphProjector.projectRecipe(
                core.getId(), handler.getResultItem(core, RegistryAccess.EMPTY),
                handler.getIngredients(core), "wizards_reborn",
                new ResourceLocation("wizards_reborn", "arcane_iterator"), true);
        MaterialRef calx = material(Items.CLAY_BALL);
        MaterialRef fuel = material(Items.COAL);
        MaterialRef glass = material(Items.GLASS);
        MaterialRef lens = material(Items.DIAMOND);
        MaterialRef target = material(Items.EMERALD);
        RecipeNode copy = new RecipeNode(new ResourceLocation("test", "copy_calx"), calx, 2,
                List.of(new IngredientRef(List.of(calx), 1),
                        new IngredientRef(List.of(fuel), 1)));
        RecipeNode glassNode = new RecipeNode(new ResourceLocation("test", "alchemy_glass"),
                glass, 1, List.of(new IngredientRef(List.of(calx), 4)));
        RecipeNode targetNode = new RecipeNode(new ResourceLocation("test", "uses_core"),
                target, 1, List.of(new IngredientRef(List.of(lens), 1)));
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(Map.of(
                calx, List.of(copy), glass, List.of(glassNode),
                lens, List.of(coreNode), target, List.of(targetNode)));

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(
                graph, Map.of(calx, 1, fuel, 4),
                List.of(new IngredientRef(List.of(target), 1)), 32);

        assertTrue(result.feasible(), result.toString());
        assertTrue(result.steps().stream().anyMatch(step -> step.recipeId().equals(core.getId())));
        assertTrue(result.steps().stream().anyMatch(step -> step.recipeId().equals(glassNode.recipeId())));
        assertEquals(4, result.steps().stream()
                .filter(step -> step.recipeId().equals(copy.recipeId()))
                .mapToLong(PureRecipePlanner.PlannedStep::batches).sum());
        CraftPlanGraph execution = PurePlanGraphAdapter.toOutputGraph(result, Map.of(
                new StackKey(Items.CLAY_BALL, null), 1L,
                new StackKey(Items.COAL, null), 4L));
        CraftPlanValidator.validate(execution);
        assertTrue(execution.unresolvedDemands().isEmpty());
        assertTrue(execution.rootDemands().stream()
                .allMatch(root -> root.unresolvedQuantity() == 0));
        assertTrue(execution.nodes().stream().anyMatch(node ->
                node.recipeId().equals(core.getId()) && node.modTypeId().equals("wizards_reborn")));
    }

    @Test
    void runtimeEnchantmentAndRitualOutputsRemainOutsideTheStaticGraph() {
        assertFalse(handler.supportsBackgroundPlanning(
                new ArcaneIteratorRecipe(new ItemStack(Items.ENCHANTED_BOOK), true, false)));
        assertFalse(handler.supportsBackgroundPlanning(
                new ArcaneIteratorRecipe(new ItemStack(Items.ENCHANTED_BOOK), false, true)));
        assertFalse(handler.supportsBackgroundPlanning(
                new ArcaneIteratorRecipe(ItemStack.EMPTY, false, false)));
        assertFalse(handler.supportsBackgroundPlanning(new ShapelessRecipe(
                new ResourceLocation("wizards_reborn", "crystal_ritual/test"), "",
                CraftingBookCategory.MISC, new ItemStack(Items.DIAMOND),
                NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.IRON_INGOT)))));
    }

    public static final class ArcaneIteratorRecipe extends ShapelessRecipe {
        private final ItemStack output;
        private final boolean enchantment;
        private final boolean arcaneEnchantment;

        ArcaneIteratorRecipe(ItemStack output, boolean enchantment, boolean arcaneEnchantment) {
            super(new ResourceLocation("wizards_reborn", "arcane_iterator/arcanum_lens"), "",
                    CraftingBookCategory.MISC, output,
                    NonNullList.of(Ingredient.EMPTY,
                            Ingredient.of(Items.GLASS), Ingredient.of(Items.CLAY_BALL)));
            this.output = output;
            this.enchantment = enchantment;
            this.arcaneEnchantment = arcaneEnchantment;
        }

        public boolean hasEnchantment() {
            return enchantment;
        }

        public boolean hasArcaneEnchantment() {
            return arcaneEnchantment;
        }
    }

    private static MaterialRef material(Item item) {
        return new MaterialRef(BuiltInRegistries.ITEM.getKey(item), "");
    }
}
