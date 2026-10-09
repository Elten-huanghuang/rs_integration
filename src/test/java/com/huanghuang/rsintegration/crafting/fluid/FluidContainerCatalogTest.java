package com.huanghuang.rsintegration.crafting.fluid;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.RecipeIndex;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraphProjector;
import com.huanghuang.rsintegration.crafting.planning.PureRecipePlanner;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidTestFixtures;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.fluids.capability.IFluidHandlerItem;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.wrappers.FluidBucketWrapper;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;

class FluidContainerCatalogTest extends BootstrapTest {
    static ItemStack token(FluidStack fluid) { return InkFluidSupport.token(InkFluidTestFixtures.tokenItem(), fluid); }
    static List<FluidContainerRecipe> buckets() {
        return FluidContainerCatalog.discover(List.of(new ItemStack(Items.BUCKET), new ItemStack(Items.WATER_BUCKET)),
                List.of(new FluidStack(Fluids.WATER, 1), new FluidStack(Fluids.LAVA, 1)),
                FluidContainerCatalogTest::bucketHandler, FluidContainerCatalogTest::token);
    }
    private static IFluidHandlerItem bucketHandler(ItemStack stack) {
        return stack.is(Items.BUCKET) || stack.is(Items.WATER_BUCKET) || stack.is(Items.LAVA_BUCKET)
                ? new FluidBucketWrapper(stack) : null;
    }
    static FluidContainerRecipe waterFill() {
        return buckets().stream().filter(r -> r.filling() && r.fluid().getFluid() == Fluids.WATER).findFirst().orElseThrow();
    }
    static FluidContainerRecipe waterDrain() {
        return buckets().stream().filter(r -> !r.filling() && r.fluid().getFluid() == Fluids.WATER).findFirst().orElseThrow();
    }

    @Test void probesRealForgeBucketsWithoutMutatingSamples() {
        ItemStack bucket = new ItemStack(Items.BUCKET, 6);
        List<FluidContainerRecipe> recipes = FluidContainerCatalog.discover(List.of(bucket, bucket.copy()),
                List.of(new FluidStack(Fluids.WATER, 1)), FluidContainerCatalogTest::bucketHandler,
                FluidContainerCatalogTest::token);
        assertEquals(2, recipes.size());
        FluidContainerRecipe fill = recipes.stream().filter(FluidContainerRecipe::filling).findFirst().orElseThrow();
        assertEquals(1000, fill.specs().get(1).count());
        assertTrue(fill.output().is(Items.WATER_BUCKET));
        assertEquals(6, bucket.getCount());
        assertFalse(bucket.hasTag());
        assertEquals(fill.getId(), waterFill().getId());
    }

    @Test void sourceFilledBucketDiscoversItsEmptyContainerAndFluid() {
        var recipes = FluidContainerCatalog.discover(List.of(new ItemStack(Items.LAVA_BUCKET)), List.of(),
                FluidContainerCatalogTest::bucketHandler, FluidContainerCatalogTest::token);
        assertEquals(2, recipes.size());
        var drain = recipes.stream().filter(r -> !r.filling()).findFirst().orElseThrow();
        assertEquals(1000, drain.output().getCount());
        assertEquals(Fluids.LAVA, InkFluidSupport.fluid(drain.output()).getFluid());
        assertEquals(1, drain.secondaryOutputs().size());
        assertTrue(drain.secondaryOutputs().get(0).is(Items.BUCKET));
    }

    @Test void duplicateConversionOnlyCreatesOneRecipePerId() {
        AtomicInteger tokensCreated = new AtomicInteger();
        var recipes = FluidContainerCatalog.discover(List.of(new ItemStack(Items.WATER_BUCKET)), List.of(),
                FluidContainerCatalogTest::bucketHandler, fluid -> {
                    tokensCreated.incrementAndGet();
                    return token(fluid);
                });
        assertEquals(2, recipes.size());
        assertEquals(recipes.size(), tokensCreated.get());
    }

    @Test void bucketSubclassWithoutCapabilitiesDiscoversBothConversionsFromFilledSample() {
        var buckets = FluidContainerBucketTestFixtures.subclassBuckets("poisonwater");
        ItemStack filled = new ItemStack(buckets.filled(), 3);
        assertNull(FluidContainerBucketTestFixtures.handler(filled.copyWithCount(1)));
        var recipes = FluidContainerCatalog.discover(List.of(filled), List.of(),
                FluidContainerBucketTestFixtures::handler, FluidContainerCatalogTest::token);
        assertEquals(2, recipes.size());
        assertTrue(recipes.stream().allMatch(r -> r.filledContainer().is(buckets.filled())
                && r.emptyContainer().is(Items.BUCKET) && r.fluid().getAmount() == 1000));
        var drain = recipes.stream().filter(r -> !r.filling()).findFirst().orElseThrow();
        assertEquals(((BucketItem) buckets.filled()).getFluid(), InkFluidSupport.fluid(drain.output()).getFluid());
        assertTrue(drain.secondaryOutputs().get(0).is(Items.BUCKET));
        assertEquals(3, filled.getCount());
        assertFalse(filled.hasTag());
    }

    @Test void fluidCandidateCanFillIntoBucketSubclassWithoutCapabilities() {
        var buckets = FluidContainerBucketTestFixtures.subclassBuckets("candidate_poisonwater");
        var recipes = FluidContainerCatalog.discover(List.of(new ItemStack(Items.BUCKET)),
                List.of(new FluidStack(((BucketItem) buckets.filled()).getFluid(), 1)),
                FluidContainerBucketTestFixtures::handler, FluidContainerCatalogTest::token);
        assertEquals(2, recipes.size());
        var fill = recipes.stream().filter(FluidContainerRecipe::filling).findFirst().orElseThrow();
        assertTrue(fill.output().is(buckets.filled()));
        assertEquals(1000, fill.specs().get(1).count());
    }

    @Test void bucketsWithoutCapabilitiesKeepTheirDeclaredEmptyContainers() {
        var buckets = FluidContainerBucketTestFixtures.buckets("missing_capability_wood");
        var recipes = FluidContainerCatalog.discover(List.of(new ItemStack(buckets.filled())), List.of(),
                stack -> null, FluidContainerCatalogTest::token);
        assertEquals(2, recipes.size());
        assertTrue(recipes.stream().allMatch(r -> r.filledContainer().is(buckets.filled())
                && r.emptyContainer().is(buckets.empty())));
    }

    @Test void thirdPartyBucketPairsKeepTheirOwnContainersInBothDirections() {
        var wood = FluidContainerBucketTestFixtures.buckets("wood");
        var bamboo = FluidContainerBucketTestFixtures.buckets("bamboo");
        ItemStack woodFilled = new ItemStack(wood.filled(), 3);
        IFluidHandlerItem raw = FluidContainerBucketTestFixtures.handler(woodFilled.copyWithCount(1));
        raw.drain(1000, IFluidHandler.FluidAction.EXECUTE);
        assertTrue(raw.getContainer().is(Items.BUCKET));

        var recipes = FluidContainerCatalog.discover(List.of(woodFilled, new ItemStack(bamboo.filled()),
                        new ItemStack(Items.WATER_BUCKET)), List.of(),
                FluidContainerBucketTestFixtures::handler, FluidContainerCatalogTest::token);
        assertEquals(6, recipes.size());
        for (var buckets : List.of(wood, bamboo)) {
            var pair = recipes.stream().filter(r -> r.filledContainer().is(buckets.filled())).toList();
            assertEquals(2, pair.size());
            assertTrue(pair.stream().allMatch(r -> r.emptyContainer().is(buckets.empty())));
            assertTrue(pair.stream().filter(r -> !r.filling())
                    .allMatch(r -> r.secondaryOutputs().get(0).is(buckets.empty())));
        }
        assertTrue(recipes.stream().filter(r -> r.filledContainer().is(Items.WATER_BUCKET))
                .allMatch(r -> r.emptyContainer().is(Items.BUCKET)));
        var result = PureRecipePlanner.resolve(graph(recipes),
                Map.of(material(new ItemStack(wood.empty())), 1,
                        material(token(new FluidStack(Fluids.WATER, 1))), 1000),
                List.of(demand(new ItemStack(bamboo.filled()), 1)), 20);
        assertFalse(result.feasible(), result.toString());
        assertEquals(3, woodFilled.getCount());
    }

    @Test void customBucketCapabilitiesKeepTheirDeclaredDrainBehavior() {
        var buckets = FluidContainerBucketTestFixtures.buckets("custom_capability");
        var recipes = FluidContainerCatalog.discover(List.of(new ItemStack(buckets.filled())), List.of(),
                stack -> stack.getItem() instanceof BucketItem ? new FluidBucketWrapper(stack) {} : null,
                FluidContainerCatalogTest::token);
        assertFalse(recipes.isEmpty());
        assertTrue(recipes.stream().filter(r -> !r.filling())
                .allMatch(r -> r.secondaryOutputs().get(0).is(Items.BUCKET)));
        assertFalse(recipes.stream().anyMatch(r -> r.emptyContainer().is(buckets.empty())));
    }

    @Test void ordinaryBucketCannotSilentlyStripFluidNbt() {
        FluidStack tagged = new FluidStack(Fluids.WATER, 1000);
        tagged.getOrCreateTag().putString("variant", "special");
        var recipes = FluidContainerCatalog.discover(List.of(new ItemStack(Items.BUCKET)), List.of(tagged),
                FluidContainerCatalogTest::bucketHandler, FluidContainerCatalogTest::token);
        assertTrue(recipes.isEmpty());
    }

    @Test void customCapacityAndFluidNbtAreKeptInBothDirections() {
        for (int capacity : List.of(250, 2000)) {
            ItemStack empty = new ItemStack(Items.FLINT);
            empty.getOrCreateTag().putInt("capacity", capacity);
            FluidStack tagged = new FluidStack(Fluids.WATER, 1);
            tagged.getOrCreateTag().putString("variant", "special");
            var recipes = FluidContainerCatalog.discover(List.of(empty), List.of(tagged),
                    NbtContainer::new, FluidContainerCatalogTest::token);
            assertEquals(2, recipes.size());
            FluidContainerRecipe fill = recipes.stream().filter(FluidContainerRecipe::filling).findFirst().orElseThrow();
            FluidContainerRecipe drain = recipes.stream().filter(r -> !r.filling()).findFirst().orElseThrow();
            assertEquals(capacity, fill.specs().get(1).count());
            assertEquals(capacity, fill.fluid().getAmount());
            assertEquals("special", fill.fluid().getTag().getString("variant"));
            assertEquals(capacity, drain.output().getCount());
            assertEquals("special", InkFluidSupport.fluid(drain.output()).getTag().getString("variant"));
            assertTrue(ItemStack.isSameItemSameTags(empty, drain.secondaryOutputs().get(0)));
            assertFalse(empty.getTag().contains("content"));
        }
    }

    @Test void nonBucketValidationOnlyProbesTheRequiredRuntimeHandler() {
        FluidContainerRecipe recipe = new FluidContainerRecipe(id("missing_capability"), true,
                new ItemStack(Items.FLINT), new ItemStack(Items.STICK),
                new FluidStack(Fluids.WATER, 250), token(new FluidStack(Fluids.WATER, 250)));
        try (MockedStatic<FluidUtil> capabilities = mockStatic(FluidUtil.class)) {
            capabilities.when(() -> FluidUtil.getFluidHandler(any(ItemStack.class)))
                    .thenReturn(LazyOptional.empty());
            assertFalse(FluidContainerCatalog.isValid(recipe));
            capabilities.verify(() -> FluidUtil.getFluidHandler(any(ItemStack.class)), times(1));
        }
    }

    @Test void multiTankContainersAreNotReducedToOneFluid() {
        ItemStack empty = new ItemStack(Items.FLINT);
        empty.getOrCreateTag().putInt("capacity", 250);
        assertTrue(FluidContainerCatalog.discover(List.of(empty), List.of(new FluidStack(Fluids.WATER, 1)),
                stack -> new NbtContainer(stack) { @Override public int getTanks() { return 2; } },
                FluidContainerCatalogTest::token).isEmpty());
    }

    @Test void waterAndBucketBecomeARealRecipeNode() {
        FluidContainerRecipe fill = waterFill();
        ImmutableRecipeGraph graph = graph(buckets());
        var result = PureRecipePlanner.resolve(graph,
                Map.of(material(new ItemStack(Items.BUCKET)), 1, material(token(new FluidStack(Fluids.WATER, 1))), 1000),
                List.of(demand(new ItemStack(Items.WATER_BUCKET), 1)), 20);
        assertTrue(result.feasible(), result.toString());
        assertEquals(List.of(new PureRecipePlanner.PlannedStep(fill.getId(), 1)), result.steps());
        assertEquals(0, result.remaining().getOrDefault(material(new ItemStack(Items.BUCKET)), 0));
        assertEquals(0, result.remaining().getOrDefault(material(token(new FluidStack(Fluids.WATER, 1))), 0));
        assertTrue(ModType.FLUID_CONTAINER.isVirtual());
        assertEquals(ModType.GraphExecutionAudit.GRAPH_SAFE, ModType.FLUID_CONTAINER.graphExecutionAudit());
    }

    @Test void recursivelyProducesFluidAndMissingBucketBeforeFilling() {
        FluidContainerRecipe fill = waterFill();
        var water = material(token(new FluidStack(Fluids.WATER, 1)));
        var bucket = material(new ItemStack(Items.BUCKET));
        var produceWater = new ImmutableRecipeGraph.RecipeNode(id("produce_water"), water, 500,
                List.of(demand(new ItemStack(Items.ICE), 1)), "generic", id("test_machine"));
        var makeBucket = new ImmutableRecipeGraph.RecipeNode(id("make_bucket"), bucket, 1,
                List.of(demand(new ItemStack(Items.IRON_INGOT), 3)), "generic", id("crafting"));
        Map<ImmutableRecipeGraph.MaterialRef, List<ImmutableRecipeGraph.RecipeNode>> projected = new HashMap<>(graph(buckets()).recipesByOutput());
        List<ImmutableRecipeGraph.RecipeNode> waterRecipes = new ArrayList<>(projected.getOrDefault(water, List.of()));
        waterRecipes.add(produceWater);
        projected.put(water, waterRecipes);
        projected.put(bucket, List.of(makeBucket));
        var result = PureRecipePlanner.resolve(new ImmutableRecipeGraph(projected),
                Map.of(material(new ItemStack(Items.ICE)), 2, material(new ItemStack(Items.IRON_INGOT)), 3),
                List.of(demand(new ItemStack(Items.WATER_BUCKET), 1)), 25);
        assertTrue(result.feasible(), result.toString());
        assertEquals(3, result.steps().size());
        assertTrue(result.steps().contains(new PureRecipePlanner.PlannedStep(produceWater.recipeId(), 2)));
        assertTrue(result.steps().contains(new PureRecipePlanner.PlannedStep(makeBucket.recipeId(), 1)));
        assertEquals(fill.getId(), result.steps().get(2).recipeId());
    }

    @Test void existingFilledBucketNeedsNoConversion() {
        var result = PureRecipePlanner.resolve(graph(buckets()), Map.of(material(new ItemStack(Items.WATER_BUCKET)), 1),
                List.of(demand(new ItemStack(Items.WATER_BUCKET), 1)), 20);
        assertTrue(result.feasible());
        assertTrue(result.steps().isEmpty());
    }

    @Test void downstreamRecipeRecursivelyProducesFluidAndFillsItsBucket() {
        var water = material(token(new FluidStack(Fluids.WATER, 1)));
        var clay = material(new ItemStack(Items.CLAY_BALL));
        var produceWater = new ImmutableRecipeGraph.RecipeNode(id("produce_water_for_clay"), water, 500,
                List.of(demand(new ItemStack(Items.ICE), 1)), "generic", id("test_machine"));
        var useBucket = new ImmutableRecipeGraph.RecipeNode(id("use_water_bucket"), clay, 4,
                List.of(demand(new ItemStack(Items.WATER_BUCKET), 1), demand(new ItemStack(Items.DIRT), 1)),
                "generic", id("crafting"));
        Map<ImmutableRecipeGraph.MaterialRef, List<ImmutableRecipeGraph.RecipeNode>> projected =
                new HashMap<>(graph(buckets()).recipesByOutput());
        List<ImmutableRecipeGraph.RecipeNode> waterRecipes = new ArrayList<>(projected.get(water));
        waterRecipes.add(produceWater);
        projected.put(water, waterRecipes);
        projected.put(clay, List.of(useBucket));
        var result = PureRecipePlanner.resolve(new ImmutableRecipeGraph(projected),
                Map.of(material(new ItemStack(Items.ICE)), 2, material(new ItemStack(Items.BUCKET)), 1,
                        material(new ItemStack(Items.DIRT)), 1),
                List.of(demand(new ItemStack(Items.CLAY_BALL), 4)), 25);
        assertTrue(result.feasible(), result.toString());
        assertEquals(List.of(new PureRecipePlanner.PlannedStep(produceWater.recipeId(), 2),
                new PureRecipePlanner.PlannedStep(waterFill().getId(), 1),
                new PureRecipePlanner.PlannedStep(useBucket.recipeId(), 1)), result.steps());
    }

    @Test void twoDemandsCannotShareOneBucketOrTheSameFluid() {
        for (Map<ImmutableRecipeGraph.MaterialRef, Integer> stock : List.of(
                Map.of(material(new ItemStack(Items.BUCKET)), 1, material(token(new FluidStack(Fluids.WATER, 1))), 2000),
                Map.of(material(new ItemStack(Items.BUCKET)), 2, material(token(new FluidStack(Fluids.WATER, 1))), 1000))) {
            var result = PureRecipePlanner.resolve(graph(buckets()), stock,
                    List.of(demand(new ItemStack(Items.WATER_BUCKET), 1), demand(new ItemStack(Items.WATER_BUCKET), 1)), 20);
            assertFalse(result.feasible(), result.toString());
        }
    }

    @Test void reverseConversionsCannotCreateResourcesWithoutStock() {
        var result = PureRecipePlanner.resolve(graph(buckets()), Map.of(),
                List.of(demand(new ItemStack(Items.WATER_BUCKET), 1)), 20);
        assertFalse(result.feasible());
    }

    @Test void filledBucketCanSupplyLiquidDemand() {
        var result = PureRecipePlanner.resolve(graph(buckets()), Map.of(material(new ItemStack(Items.WATER_BUCKET)), 1),
                List.of(demand(token(new FluidStack(Fluids.WATER, 1)), 750)), 20);
        assertTrue(result.feasible(), result.toString());
        assertEquals(List.of(new PureRecipePlanner.PlannedStep(waterDrain().getId(), 1)), result.steps());
        assertEquals(250, result.remaining().get(material(token(new FluidStack(Fluids.WATER, 1)))));
    }

    static ImmutableRecipeGraph graph(List<FluidContainerRecipe> recipes) {
        Map<Item, List<RecipeIndex.Entry>> index = new HashMap<>();
        Map<ImmutableRecipeGraph.MaterialRef, List<ImmutableRecipeGraph.RecipeNode>> projected = new HashMap<>();
        FluidContainerCatalog.publish(recipes, index, new HashSet<>(), projected);
        for (var recipe : recipes) assertTrue(index.get(recipe.output().getItem()).stream()
                .anyMatch(entry -> entry.recipe().getId().equals(recipe.getId())));
        return new ImmutableRecipeGraph(projected);
    }
    static ImmutableRecipeGraph.MaterialRef material(ItemStack stack) { return ImmutableRecipeGraphProjector.material(stack, true); }
    static ImmutableRecipeGraph.IngredientRef demand(ItemStack stack, int count) {
        return new ImmutableRecipeGraph.IngredientRef(List.of(material(stack)), count, ImmutableRecipeGraph.NbtMatchMode.EXACT);
    }
    private static ResourceLocation id(String path) { return new ResourceLocation("test", path); }

    private static class NbtContainer implements IFluidHandlerItem {
        private ItemStack stack;
        NbtContainer(ItemStack stack) { this.stack = stack; }
        @Override public ItemStack getContainer() { return stack; }
        @Override public int getTanks() { return 1; }
        @Override public FluidStack getFluidInTank(int tank) {
            return stack.hasTag() && stack.getTag().contains("content")
                    ? FluidStack.loadFluidStackFromNBT(stack.getTag().getCompound("content")) : FluidStack.EMPTY;
        }
        @Override public int getTankCapacity(int tank) { return stack.getOrCreateTag().getInt("capacity"); }
        @Override public boolean isFluidValid(int tank, FluidStack resource) { return true; }
        @Override public int fill(FluidStack resource, FluidAction action) {
            if (!getFluidInTank(0).isEmpty()) return 0;
            int amount = Math.min(resource.getAmount(), getTankCapacity(0));
            if (action.execute() && amount > 0) {
                CompoundTag tag = stack.getTag().copy();
                FluidStack filled = resource.copy();
                filled.setAmount(amount);
                tag.put("content", filled.writeToNBT(new CompoundTag()));
                stack = new ItemStack(Items.STICK);
                stack.setTag(tag);
            }
            return amount;
        }
        @Override public FluidStack drain(FluidStack resource, FluidAction action) {
            return resource.isFluidEqual(getFluidInTank(0)) ? drain(resource.getAmount(), action) : FluidStack.EMPTY;
        }
        @Override public FluidStack drain(int amount, FluidAction action) {
            FluidStack fluid = getFluidInTank(0);
            if (fluid.isEmpty() || amount < fluid.getAmount()) return FluidStack.EMPTY;
            if (action.execute()) {
                CompoundTag tag = stack.getTag().copy();
                tag.remove("content");
                stack = new ItemStack(Items.FLINT);
                stack.setTag(tag);
            }
            return fluid;
        }
    }
}
