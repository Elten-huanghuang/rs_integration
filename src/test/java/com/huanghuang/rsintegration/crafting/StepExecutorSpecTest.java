package com.huanghuang.rsintegration.crafting;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.recipe.ModRecipeHandler;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.core.RegistryAccess;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class StepExecutorSpecTest extends BootstrapTest {

    @BeforeAll
    static void loadDefaultServerConfig() {
        CommentedConfig config = CommentedConfig.inMemory();
        RSIntegrationConfig.SERVER_SPEC.correct(config);
        RSIntegrationConfig.SERVER_SPEC.setConfig(config);
    }

    @Test
    void coalescesRepeatedSlotsWithoutFlatteningCatalystRole() {
        Ingredient nuggets = Ingredient.of(Items.IRON_NUGGET);
        Ingredient block = Ingredient.of(Items.IRON_BLOCK);

        List<IngredientSpec> merged = StepExecutor.coalesceSpecsForGraph(List.of(
                new IngredientSpec(nuggets, 1),
                new IngredientSpec(nuggets, 1),
                new IngredientSpec(nuggets, 1),
                new IngredientSpec(block, 1, DemandRole.CATALYST),
                new IngredientSpec(block, 1, DemandRole.CATALYST),
                new IngredientSpec(block, 1, DemandRole.CONSUMED)));

        assertEquals(3, merged.size());
        assertEquals(3, merged.get(0).count());
        assertEquals(DemandRole.CONSUMED, merged.get(0).role());
        assertEquals(2, merged.get(1).count());
        assertEquals(DemandRole.CATALYST, merged.get(1).role());
        assertEquals(1, merged.get(2).count());
        assertEquals(DemandRole.CONSUMED, merged.get(2).role());
        assertEquals(12, CraftPacketUtils.requiredCount(merged.get(0), 4));
        assertEquals(2, CraftPacketUtils.requiredCount(merged.get(1), 4));
    }

    @Test
    void mapsCompactShapedRecipesIntoThreeByThreeRows() {
        ShapedRecipe recipe = new ShapedRecipe(
                new ResourceLocation("test", "two_by_three"), "", CraftingBookCategory.MISC,
                2, 3, NonNullList.withSize(6, Ingredient.of(Items.GLASS)),
                new ItemStack(Items.GLASS_BOTTLE));

        assertEquals(List.of(0, 1, 3, 4, 6, 7),
                java.util.stream.IntStream.range(0, 6)
                        .map(i -> CraftPacketUtils.craftingGridSlot(recipe, i))
                        .boxed().toList());
    }

    @Test
    void rootResolutionCoalescesSlotsAndKeepsCatalystsUnscaled() {
        Ingredient nuggets = Ingredient.of(Items.IRON_NUGGET);
        Ingredient block = Ingredient.of(Items.IRON_BLOCK);
        List<IngredientSpec> roots = new java.util.ArrayList<>();
        for (int i = 0; i < 9; i++) roots.add(new IngredientSpec(nuggets, 1));
        roots.add(new IngredientSpec(block, 1, DemandRole.CATALYST));

        List<IngredientSpec> merged = CraftingResolver.coalesceRootSpecs(roots);

        assertEquals(2, merged.size());
        assertEquals(9, merged.get(0).count());
        assertEquals(DemandRole.CONSUMED, merged.get(0).role());
        assertEquals(1, CraftPacketUtils.requiredCount(merged.get(1), 64));
        assertEquals(DemandRole.CATALYST, merged.get(1).role());
    }

    @Test
    void machineGraphKeepsEqualPhysicalSlotsIndependent() {
        Ingredient wool = Ingredient.of(Items.WHITE_WOOL, Items.RED_WOOL);

        List<IngredientSpec> slots = StepExecutor.machineSpecsForGraph(List.of(
                new IngredientSpec(wool, 1),
                new IngredientSpec(wool, 1)));

        assertEquals(2, slots.size());
        assertEquals(1, slots.get(0).count());
        assertEquals(1, slots.get(1).count());
    }

    @Test
    void resolutionOrdersExactInputsBeforeBroadInputsAndCatalystsLast() {
        Ingredient broad = Ingredient.of(Items.WHITE_WOOL, Items.GREEN_WOOL);
        Ingredient exact = Ingredient.of(Items.WHITE_WOOL);
        IngredientSpec broadSpec = new IngredientSpec(broad, 1);
        IngredientSpec exactSpec = new IngredientSpec(exact, 1);
        IngredientSpec catalyst = new IngredientSpec(exact, 1, DemandRole.CATALYST);
        List<IngredientSpec> ordered = new java.util.ArrayList<>(
                List.of(broadSpec, catalyst, exactSpec));

        ordered.sort(StepExecutor.resolutionComparator());

        assertEquals(List.of(exactSpec, broadSpec, catalyst), ordered);
    }

    @Test
    void machineSlotChoosesOneVariantThatSatisfiesTheWholeTagDemand() {
        ResolutionContext context = new ResolutionContext(null, Map.of(), List.of(
                new ItemStack(Items.WHITE_WOOL, 64),
                new ItemStack(Items.RED_WOOL, 1),
                new ItemStack(Items.BLUE_WOOL, 1)), null);
        Ingredient wool = Ingredient.of(Items.RED_WOOL, Items.BLUE_WOOL, Items.WHITE_WOOL);
        List<ResolutionContext.SupplySlice> consumed = new java.util.ArrayList<>();

        Ingredient selected = StepExecutor.ensureSingleVariantMachineInput(
                wool, 2, context, 0, new CraftingResolver.EdgeTracker(), null, consumed);

        assertEquals(Items.WHITE_WOOL, selected.getItems()[0].getItem());
        assertEquals(1, consumed.size());
        assertEquals(Items.WHITE_WOOL, consumed.get(0).material().item());
        assertEquals(2, consumed.get(0).quantity());
        assertEquals(62, context.countMatching(Ingredient.of(Items.WHITE_WOOL)));
        assertEquals(1, context.countMatching(Ingredient.of(Items.RED_WOOL)));
        assertEquals(1, context.countMatching(Ingredient.of(Items.BLUE_WOOL)));
    }

    @Test
    void machineSlotUsesCompleteFallbackStockBeforeCraftingPreferredVariant() {
        ResolutionContext context = new ResolutionContext(null, Map.of(), List.of(
                new ItemStack(Items.WHITE_WOOL, 1),
                new ItemStack(Items.GREEN_WOOL, 2)), null);
        Ingredient wool = Ingredient.of(Items.WHITE_WOOL, Items.GREEN_WOOL);

        Ingredient selected = StepExecutor.ensureSingleVariantMachineInput(
                wool, 2, context, 0, new CraftingResolver.EdgeTracker(), null,
                new java.util.ArrayList<>());

        assertNotNull(selected);
        assertEquals(Items.GREEN_WOOL, selected.getItems()[0].getItem());
        assertEquals(1, context.countMatching(Ingredient.of(Items.WHITE_WOOL)));
        assertEquals(0, context.countMatching(Ingredient.of(Items.GREEN_WOOL)));
    }

    @Test
    void machineSlotNeverCombinesDifferentVariantsToReachOneStackCount() {
        ResolutionContext context = new ResolutionContext(null, Map.of(), List.of(
                new ItemStack(Items.RED_WOOL, 1),
                new ItemStack(Items.BLUE_WOOL, 1)), null);
        // Stop before recursive candidate lookup: this test only verifies that
        // direct inventory supply is never combined across stack variants.
        context.ensureCalls = RSIntegrationConfig.CRAFTING_MAX_ENSURE_CALLS.get();
        Ingredient wool = Ingredient.of(Items.RED_WOOL, Items.BLUE_WOOL);

        Ingredient selected = StepExecutor.ensureSingleVariantMachineInput(
                wool, 2, context, 0, new CraftingResolver.EdgeTracker(), null,
                new java.util.ArrayList<>());

        assertNull(selected);
        assertEquals(1, context.countMatching(Ingredient.of(Items.RED_WOOL)));
        assertEquals(1, context.countMatching(Ingredient.of(Items.BLUE_WOOL)));
    }

    @Test
    void machineSlotPreservesNonStrictSemanticsOfTaggedDisplayCandidate() {
        ItemStack recipeDisplay = new ItemStack(Items.IRON_HELMET);
        recipeDisplay.setDamageValue(10);
        ItemStack stored = new ItemStack(Items.IRON_HELMET);
        stored.setDamageValue(80);
        stored.getOrCreateTag().putString("modifier", "hasty");
        ResolutionContext context = new ResolutionContext(null, Map.of(), List.of(stored), null);

        Ingredient selected = StepExecutor.ensureSingleVariantMachineInput(
                Ingredient.of(recipeDisplay), 1, context, 0,
                new CraftingResolver.EdgeTracker(), null, new java.util.ArrayList<>());

        assertNotNull(selected);
        assertFalse(IngredientMatcher.requiresNbt(selected));
        assertEquals(0, context.countMatching(Ingredient.of(Items.IRON_HELMET)));
    }

    @Test
    void runtimeDependentOutputDropsOnlyThePlanningNbtConstraint() {
        ShapedRecipe recipe = new ShapedRecipe(
                new ResourceLocation("test", "dynamic_output"), "", CraftingBookCategory.MISC,
                1, 1, NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.IRON_INGOT)),
                new ItemStack(Items.DIAMOND));
        ItemStack staticResult = new ItemStack(Items.DIAMOND, 2);
        staticResult.getOrCreateTag().putString("owner", "template");

        ItemStack declared = CraftingResolver.resolveDeclaredOutput(
                recipe, staticResult, new RuntimeNbtHandler());

        assertEquals(Items.DIAMOND, declared.getItem());
        assertEquals(2, declared.getCount());
        assertFalse(declared.hasTag());
        assertEquals("template", staticResult.getTag().getString("owner"));
    }

    private static final class RuntimeNbtHandler implements ModRecipeHandler {
        @Override public ModType modType() { return ModType.GENERIC; }
        @Override public boolean canHandle(Recipe<?> recipe) { return true; }
        @Override public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
            return ItemStack.EMPTY;
        }
        @Override public List<IngredientSpec> getIngredients(Recipe<?> recipe) { return List.of(); }
        @Override public boolean hasRuntimeDependentPrimaryNbt(Recipe<?> recipe) { return true; }
    }
}
