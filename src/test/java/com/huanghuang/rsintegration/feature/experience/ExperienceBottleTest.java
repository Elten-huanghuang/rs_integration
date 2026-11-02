package com.huanghuang.rsintegration.feature.experience;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.fluid.FluidContainerCatalog;
import com.huanghuang.rsintegration.crafting.fluid.FluidContainerRecipe;
import com.huanghuang.rsintegration.crafting.fluid.FluidContainerTransfer;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraphProjector;
import com.huanghuang.rsintegration.crafting.planning.PureRecipePlanner;
import com.huanghuang.rsintegration.mods.ironsspellbooks.AlchemistBottleSupport;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkBottleFluidHandler;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidTestFixtures;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.fluids.capability.IFluidHandler.FluidAction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ExperienceBottleTest extends BootstrapTest {
    @BeforeAll
    static void registerExperience() throws ReflectiveOperationException {
        ExperienceFluidsTest.registerFluids();
    }

    @Test
    void bottleCostMatchesTheMeanOfVanillaExperienceDrops() {
        int total = 0;
        for (int first = 0; first < 5; first++) {
            for (int second = 0; second < 5; second++) total += 3 + first + second;
        }
        assertEquals(total / 25, ExperienceBottleSupport.EXPERIENCE_POINTS_PER_BOTTLE);
        assertEquals(7, ExperienceBottleSupport.EXPERIENCE_POINTS_PER_BOTTLE);
        assertEquals(140, ExperienceBottleSupport.BOTTLE_AMOUNT);
        assertTrue(AlchemistBottleSupport.canBottle(experience(1)));
    }

    @Test
    void localBottleHandlerSimulatesAndFillsExactly140Mb() {
        ItemStack source = new ItemStack(Items.GLASS_BOTTLE);
        var handler = InkBottleFluidHandler.create(source);
        assertNotNull(handler);
        FluidStack offered = experience(500);

        assertEquals(140, handler.fill(offered, FluidAction.SIMULATE));
        assertTrue(handler.getContainer().is(Items.GLASS_BOTTLE));
        assertTrue(handler.getFluidInTank(0).isEmpty());
        assertEquals(140, handler.fill(offered, FluidAction.EXECUTE));
        assertTrue(handler.getContainer().is(Items.EXPERIENCE_BOTTLE));
        assertFalse(handler.getContainer().hasTag());
        assertEquals(140, handler.getFluidInTank(0).getAmount());
        assertEquals(140, handler.getTankCapacity(0));
        assertEquals(0, handler.fill(offered, FluidAction.EXECUTE));
        assertEquals(500, offered.getAmount());
        assertTrue(source.is(Items.GLASS_BOTTLE));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 139})
    void incompleteBottleCannotConsumeFluidOrChangeTheContainer(int amount) {
        var handler = InkBottleFluidHandler.create(new ItemStack(Items.GLASS_BOTTLE));
        assertNotNull(handler);
        assertEquals(0, handler.fill(experience(amount), FluidAction.SIMULATE));
        assertEquals(0, handler.fill(experience(amount), FluidAction.EXECUTE));
        assertTrue(handler.getContainer().is(Items.GLASS_BOTTLE));
        assertTrue(handler.getFluidInTank(0).isEmpty());
    }

    @Test
    void customFluidOrBottleDataIsNotSilentlyDiscarded() {
        FluidStack tagged = experience(140);
        tagged.getOrCreateTag().putString("variant", "keep");
        var handler = InkBottleFluidHandler.create(new ItemStack(Items.GLASS_BOTTLE));
        assertNotNull(handler);
        assertFalse(ExperienceBottleSupport.canBottle(tagged));
        assertEquals(0, handler.fill(tagged, FluidAction.EXECUTE));
        assertFalse(ExperienceBottleSupport.canBottle(new FluidStack(Fluids.LAVA, 140)));
        assertFalse(ExperienceBottleSupport.canBottle(
                new FluidStack(ExperienceFluids.FLOWING_EXPERIENCE.get(), 140)));
        for (var item : List.of(Items.GLASS_BOTTLE, Items.EXPERIENCE_BOTTLE)) {
            ItemStack named = new ItemStack(item);
            named.getOrCreateTag().putString("custom", "keep");
            assertNull(InkBottleFluidHandler.create(named));
        }
    }

    @Test
    void experienceBottleCannotDrainItsAverageAsGuaranteedFluid() {
        var handler = InkBottleFluidHandler.create(new ItemStack(Items.EXPERIENCE_BOTTLE));
        assertNotNull(handler);
        assertTrue(handler.drain(140, FluidAction.SIMULATE).isEmpty());
        assertTrue(handler.drain(experience(140), FluidAction.EXECUTE).isEmpty());
        assertTrue(handler.drain(Integer.MAX_VALUE, FluidAction.EXECUTE).isEmpty());
        assertTrue(handler.getContainer().is(Items.EXPERIENCE_BOTTLE));
        Store store = new Store(0, 0);
        var result = FluidContainerTransfer.empty(store, new ItemStack(Items.EXPERIENCE_BOTTLE));
        assertEquals(0, result.transferred());
        assertTrue(result.cursor().is(Items.EXPERIENCE_BOTTLE));
        assertEquals(0, store.amount);
    }

    @Test
    void heldAndBorrowedBottlesConsumeOneContainerAndExactly140Mb() {
        Store held = new Store(420, 0);
        ItemStack cursor = new ItemStack(Items.GLASS_BOTTLE, 3);
        var result = FluidContainerTransfer.fill(held, cursor, experience(1));
        assertEquals(140, result.transferred());
        assertEquals(280, held.amount);
        assertTrue(result.cursor().is(Items.GLASS_BOTTLE));
        assertEquals(2, result.cursor().getCount());
        assertTrue(result.overflow().is(Items.EXPERIENCE_BOTTLE));
        assertEquals(1, result.overflow().getCount());
        assertEquals(3, cursor.getCount());

        Store borrowed = new Store(140, 1);
        result = FluidContainerTransfer.fill(borrowed, ItemStack.EMPTY, experience(1));
        assertTrue(result.cursor().is(Items.EXPERIENCE_BOTTLE));
        assertEquals(140, result.transferred());
        assertEquals(0, borrowed.amount);
        assertEquals(0, borrowed.bottles);
    }

    @Test
    void missingMaterialsAndPartialExtractionCannotCreateExperienceBottles() {
        Store insufficient = new Store(139, 1);
        var result = FluidContainerTransfer.fill(insufficient, ItemStack.EMPTY, experience(1));
        assertEquals(0, result.transferred());
        assertTrue(result.cursor().isEmpty());
        assertEquals(139, insufficient.amount);
        assertEquals(1, insufficient.bottles);

        Store missingBottle = new Store(140, 0);
        result = FluidContainerTransfer.fill(missingBottle, ItemStack.EMPTY, experience(1));
        assertEquals(0, result.transferred());
        assertEquals(140, missingBottle.amount);

        Store partial = new Store(140, 1);
        partial.extractLimit = 139;
        result = FluidContainerTransfer.fill(partial, ItemStack.EMPTY, experience(1));
        assertEquals(0, result.transferred());
        assertTrue(result.cursor().isEmpty());
        assertTrue(result.recovery().isEmpty());
        assertEquals(140, partial.amount);
        assertEquals(1, partial.bottles);
    }

    @Test
    void catalogPublishesOnlyFillingAndPlannerConsumes140MbPerBottle() {
        var tokenItem = InkFluidTestFixtures.tokenItem();
        ServerLevel level = mock(ServerLevel.class);
        RecipeManager manager = mock(RecipeManager.class);
        when(level.getRecipeManager()).thenReturn(manager);
        when(level.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        when(manager.getRecipes()).thenReturn(List.of());
        try (MockedStatic<FluidUtil> capabilities = mockStatic(FluidUtil.class);
             MockedStatic<InkFluidSupport> tokens = mockStatic(InkFluidSupport.class, CALLS_REAL_METHODS)) {
            capabilities.when(() -> FluidUtil.getFluidHandler(any(ItemStack.class)))
                    .thenReturn(LazyOptional.empty());
            tokens.when(() -> InkFluidSupport.token(any(FluidStack.class)))
                    .thenAnswer(call -> InkFluidSupport.token(tokenItem, call.getArgument(0)));
            FluidContainerCatalog.invalidate();
            List<FluidContainerRecipe> recipes = FluidContainerCatalog.allRecipes(level).stream()
                    .filter(recipe -> recipe.filledContainer().is(Items.EXPERIENCE_BOTTLE)).toList();
            assertEquals(1, recipes.size());
            FluidContainerRecipe recipe = recipes.get(0);
            assertTrue(recipe.filling());
            assertTrue(FluidContainerCatalog.isValid(recipe));
            assertTrue(recipe.emptyContainer().is(Items.GLASS_BOTTLE));
            assertEquals(140, recipe.fluid().getAmount());
            assertEquals(140, recipe.specs().get(1).count());
            assertTrue(recipe.secondaryOutputs().isEmpty());
            assertSame(recipe, FluidContainerCatalog.resolve(level, recipe.getId()));

            var node = ImmutableRecipeGraphProjector.projectRecipe(recipe.getId(), recipe.output(),
                    recipe.specs(), ModType.FLUID_CONTAINER.id(), FluidContainerRecipe.TYPE_ID);
            assertNotNull(node);
            var graph = new ImmutableRecipeGraph(Map.of(node.output(), List.of(node)));
            var bottle = ImmutableRecipeGraphProjector.material(new ItemStack(Items.GLASS_BOTTLE), true);
            var fluid = ImmutableRecipeGraphProjector.material(InkFluidSupport.token(experience(1)), true);
            var demand = new ImmutableRecipeGraph.IngredientRef(List.of(node.output()), 3,
                    ImmutableRecipeGraph.NbtMatchMode.EXACT);
            var plan = PureRecipePlanner.resolve(graph, Map.of(bottle, 3, fluid, 420), List.of(demand), 20);
            assertTrue(plan.feasible(), plan.toString());
            assertEquals(List.of(new PureRecipePlanner.PlannedStep(recipe.getId(), 3)), plan.steps());
            assertFalse(PureRecipePlanner.resolve(graph, Map.of(bottle, 3, fluid, 419),
                    List.of(demand), 20).feasible());
            assertFalse(PureRecipePlanner.resolve(graph, Map.of(bottle, 2, fluid, 420),
                    List.of(demand), 20).feasible());
        } finally {
            FluidContainerCatalog.invalidate();
        }
    }

    private static FluidStack experience(int amount) {
        return new FluidStack(ExperienceFluids.EXPERIENCE.get(), amount);
    }

    private static final class Store implements FluidContainerTransfer.Storage {
        int amount;
        int bottles;
        int extractLimit = Integer.MAX_VALUE;

        Store(int amount, int bottles) {
            this.amount = amount;
            this.bottles = bottles;
        }

        @Override public ItemStack extractItem(ItemStack stack, int requested, boolean simulate) {
            assertTrue(stack.is(Items.GLASS_BOTTLE));
            int extracted = Math.min(bottles, requested);
            if (!simulate) bottles -= extracted;
            return new ItemStack(Items.GLASS_BOTTLE, extracted);
        }

        @Override public ItemStack insertItem(ItemStack stack, int requested, boolean simulate) {
            assertTrue(stack.is(Items.GLASS_BOTTLE));
            if (!simulate) bottles += requested;
            return ItemStack.EMPTY;
        }

        @Override public FluidStack extractFluid(FluidStack fluid, int requested, boolean simulate) {
            assertSame(ExperienceFluids.EXPERIENCE.get(), fluid.getFluid());
            int extracted = Math.min(amount, requested);
            if (!simulate) {
                extracted = Math.min(extracted, extractLimit);
                amount -= extracted;
            }
            return experience(extracted);
        }

        @Override public FluidStack insertFluid(FluidStack fluid, int requested, boolean simulate) {
            assertSame(ExperienceFluids.EXPERIENCE.get(), fluid.getFluid());
            if (!simulate) amount += requested;
            return FluidStack.EMPTY;
        }
    }
}
