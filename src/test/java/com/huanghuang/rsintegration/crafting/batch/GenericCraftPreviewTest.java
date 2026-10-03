package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.CraftingResolver.ResolutionStep;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.plan.MissingMaterialBookmarkList;
import com.huanghuang.rsintegration.crafting.plan.PlanMaterialBill;
import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import com.huanghuang.rsintegration.crafting.plan.PlanStep;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraphProjector;
import com.huanghuang.rsintegration.crafting.planning.PurePlanAdapter;
import com.huanghuang.rsintegration.crafting.planning.PureRecipePlanner;
import com.huanghuang.rsintegration.crafting.tree.IngredientKey;
import com.huanghuang.rsintegration.crafting.tree.PlanTreeModel;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidTestFixtures;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.common.crafting.StrictNBTIngredient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenericCraftPreviewTest extends BootstrapTest {
    @ParameterizedTest
    @CsvSource({"0, 0", "1, 0", "0, 2000", "1, 1000", "0, 1000"})
    void purePlanKeepsFluidProductionAndFillingBatchesWithoutIntermediateShortages(
            int filledStock, int fluidStock) {
        int executions = 2 - filledStock;
        int production = executions - fluidStock / 1000;
        ItemStack fluid = InkFluidSupport.token(InkFluidTestFixtures.tokenItem(),
                new FluidStack(Fluids.WATER, 1000));
        List<IngredientSpec> fillInputs = List.of(new IngredientSpec(Ingredient.of(Items.BUCKET), 1),
                new IngredientSpec(StrictNBTIngredient.of(fluid.copyWithCount(1)), 1000));
        ShapelessRecipe fill = new ShapelessRecipe(new ResourceLocation("test", "fill_water"), "",
                CraftingBookCategory.MISC, new ItemStack(Items.WATER_BUCKET),
                NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.BUCKET),
                        StrictNBTIngredient.of(fluid.copyWithCount(1))));
        ResourceLocation producerId = new ResourceLocation("test", "produce_fluid");
        ShapelessRecipe producer = new ShapelessRecipe(producerId, "", CraftingBookCategory.MISC,
                fluid, NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.REDSTONE),
                Ingredient.of(Items.FEATHER), Ingredient.of(Items.FEATHER), Ingredient.of(Items.FEATHER)));
        List<IngredientSpec> producerInputs = List.of(
                new IngredientSpec(Ingredient.of(Items.REDSTONE), 1),
                new IngredientSpec(Ingredient.of(Items.FEATHER), 3));
        var producerNode = ImmutableRecipeGraphProjector.projectRecipe(producerId, fluid,
                producerInputs, "generic", new ResourceLocation("minecraft", "crafting"));
        var fillNode = ImmutableRecipeGraphProjector.projectRecipe(fill.getId(),
                fill.getResultItem(RegistryAccess.EMPTY), fillInputs, ModType.FLUID_CONTAINER.id(),
                new ResourceLocation("rs_integration", "fluid_container"));
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(Map.of(
                producerNode.output(), List.of(producerNode), fillNode.output(), List.of(fillNode)));
        Map<StackKey, Integer> stock = Map.of(new StackKey(Items.BUCKET, null), 3,
                new StackKey(Items.REDSTONE, null), 59, new StackKey(Items.FEATHER, null), 49,
                new StackKey(Items.WATER_BUCKET, null), filledStock,
                new StackKey(fluid.getItem(), fluid.getTag().toString()), fluidStock);
        var planned = PureRecipePlanner.resolve(graph,
                ImmutableRecipeGraphProjector.projectAvailability(stock),
                List.of(ImmutableRecipeGraphProjector.projectIngredient(
                        new IngredientSpec(Ingredient.of(Items.WATER_BUCKET), 2))), 20);
        assertTrue(planned.feasible(), planned.toString());
        List<ResolutionStep> projected = PurePlanAdapter.toResolutionSteps(planned, graph);
        Map<ResourceLocation, Recipe<?>> recipes = Map.of(producerId, producer, fill.getId(), fill);
        var preview = GenericCraftPacket.mergePreviewSteps(projected, recipes::get, RegistryAccess.EMPTY);
        assertEquals(production > 0 ? List.of(production, executions) : List.of(executions),
                preview.stream().map(GenericCraftPacket.PreviewStep::batches).toList());

        List<PlanStep> steps = new ArrayList<>();
        Map<Item, Integer> needed = new LinkedHashMap<>();
        Map<Item, Ingredient> sources = new LinkedHashMap<>();
        for (var step : preview) {
            Recipe<?> recipe = recipes.get(step.step().recipeId());
            List<IngredientSpec> inputs = recipe == fill ? fillInputs : producerInputs;
            List<ItemStack> displays = new ArrayList<>();
            for (IngredientSpec input : inputs) {
                ItemStack display = input.ingredient().getItems()[0].copyWithCount(input.count());
                displays.add(display);
                needed.merge(display.getItem(), input.count() * step.batches(), Integer::sum);
                sources.putIfAbsent(display.getItem(), input.ingredient());
            }
            ItemStack output = recipe.getResultItem(RegistryAccess.EMPTY);
            needed.merge(output.getItem(), -output.getCount() * step.batches(), Integer::sum);
            steps.add(new PlanStep(recipe.getId(), output, step.batches(), displays));
        }
        ItemStack target = new ItemStack(Items.DIAMOND);
        ResourceLocation targetId = new ResourceLocation("test", "target");
        steps.add(new PlanStep(targetId, target, 1, List.of(new ItemStack(Items.WATER_BUCKET, 2))));
        needed.merge(Items.WATER_BUCKET, 2, Integer::sum);
        sources.put(Items.WATER_BUCKET, Ingredient.of(Items.WATER_BUCKET));
        var bill = PlanMaterialBill.summarize(needed, sources,
                Map.of(Items.BUCKET, 3, Items.REDSTONE, 59, Items.FEATHER, 49,
                        Items.WATER_BUCKET, filledStock, fluid.getItem(), fluidStock),
                stock, target, steps, 1, null, false);
        assertTrue(bill.feasible(), bill.toString());
        assertEquals(new PlanResponse.Availability(2, filledStock, 0),
                bill.materials().get(IngredientKey.of(new ItemStack(Items.WATER_BUCKET))));
        assertEquals(new PlanResponse.Availability(1000 * executions, fluidStock, 0),
                bill.materials().get(IngredientKey.of(fluid)));
        if (production > 0) {
            assertEquals(new PlanResponse.Availability(production, 59),
                    bill.materials().get(IngredientKey.of(new ItemStack(Items.REDSTONE))));
            assertEquals(new PlanResponse.Availability(3 * production, 49),
                    bill.materials().get(IngredientKey.of(new ItemStack(Items.FEATHER))));
        }
        PlanResponse response = new PlanResponse(true, "", target, steps, bill.materials(),
                List.of(), targetId.toString());
        assertTrue(MissingMaterialBookmarkList.from(response).isEmpty());
        var tree = PlanTreeModel.from(response);
        var filled = tree.findByKey(IngredientKey.of(new ItemStack(Items.WATER_BUCKET)));
        assertEquals(2, filled.amount);
        assertEquals(executions, filled.step.batches());
        assertEquals(executions, tree.findByKey(IngredientKey.of(new ItemStack(Items.BUCKET))).amount);
        var fluidNode = tree.findByKey(IngredientKey.of(fluid));
        assertEquals(1000 * executions, fluidNode.amount);
        assertEquals(fluidStock, fluidNode.available);
        if (production > 0) {
            assertEquals(production, fluidNode.step.batches());
            assertEquals(production, tree.findByKey(IngredientKey.of(new ItemStack(Items.REDSTONE))).amount);
            assertEquals(3 * production, tree.findByKey(IngredientKey.of(new ItemStack(Items.FEATHER))).amount);
        } else {
            assertNull(fluidNode.step);
            assertTrue(fluidNode.isLeaf());
            assertNull(tree.findByKey(IngredientKey.of(new ItemStack(Items.REDSTONE))));
            assertNull(tree.findByKey(IngredientKey.of(new ItemStack(Items.FEATHER))));
        }
    }

    @Test
    void selfAmplifyingPreviewKeepsDependentStagesSeparate() {
        ResourceLocation id = new ResourceLocation("test", "amplify_redstone");
        ShapelessRecipe recipe = new ShapelessRecipe(id, "", CraftingBookCategory.MISC,
                new ItemStack(Items.REDSTONE, 2),
                NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.REDSTONE)));
        List<ResolutionStep> stages = List.of(1, 2, 4).stream()
                .map(runs -> new ResolutionStep(id, ModType.GENERIC, null,
                        List.of(), List.of(), false, runs))
                .toList();

        var preview = GenericCraftPacket.mergePreviewSteps(stages,
                ignored -> recipe, RegistryAccess.EMPTY);

        assertEquals(List.of(1, 2, 4),
                preview.stream().map(GenericCraftPacket.PreviewStep::batches).toList());
    }

    @Test
    void mergingRepeatedPreviewStepsPreservesExecutionsAndStateVariants() {
        ResourceLocation id = new ResourceLocation("test", "stateful");
        ItemStack first = new ItemStack(Items.POTION);
        first.getOrCreateTag().putString("Potion", "minecraft:water");
        ItemStack second = new ItemStack(Items.POTION);
        second.getOrCreateTag().putString("Potion", "minecraft:awkward");
        var firstStep = new ResolutionStep(id, ModType.GENERIC, null,
                List.of(), List.of(), false, 2, null, first);
        var secondStep = new ResolutionStep(id, ModType.GENERIC, null,
                List.of(), List.of(), false, 3, null, second);
        var merged = GenericCraftPacket.mergePreviewSteps(List.of(firstStep, firstStep, secondStep),
                ignored -> null, RegistryAccess.EMPTY);
        assertEquals(List.of(4, 3), merged.stream().map(GenericCraftPacket.PreviewStep::batches).toList());
        assertEquals(first.getTag(), merged.get(0).step().syntheticOutput().getTag());
        assertEquals(second.getTag(), merged.get(1).step().syntheticOutput().getTag());
        assertFalse(first.getTag().equals(second.getTag()));
    }
}
