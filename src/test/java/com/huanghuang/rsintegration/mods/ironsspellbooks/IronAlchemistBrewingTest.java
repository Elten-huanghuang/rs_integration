package com.huanghuang.rsintegration.mods.ironsspellbooks;

import com.huanghuang.rsintegration.crafting.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.storage.StorageBackendId;
import com.huanghuang.rsintegration.storage.StorageOperationMode;
import com.huanghuang.rsintegration.storage.StorageOperationResult;
import com.huanghuang.rsintegration.storage.StorageReference;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler.FluidAction;
import net.minecraftforge.fluids.capability.templates.FluidTank;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IronAlchemistBrewingTest extends BootstrapTest {
    @BeforeAll static void registerPotionFluid() { InkFluidTestFixtures.ink("potion", 250); }

    private static ItemStack token(FluidStack fluid) {
        return InkFluidSupport.token(InkFluidTestFixtures.tokenItem(), fluid);
    }

    @Test void indexesNativePotionBrewingForRsAndKeepsBottlingSeparate() {
        var recipes = new LinkedHashMap<ResourceLocation, IronSpellBooksRecipe>();
        IronAlchemistRecipeCatalog.indexPotionRecipes(recipes, IronAlchemistBrewingTest::token, true);
        FluidStack awkward = AlchemistPotionSupport.fluid(PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.AWKWARD));
        IronSpellBooksRecipe brew = recipes.values().stream().filter(recipe -> recipe.isBrewing()
                && recipe.inputIngredients().get(1).test(new ItemStack(Items.NETHER_WART))
                && InkFluidSupport.fluid(recipe.inputs().get(0)).getFluid() == Fluids.WATER
                && InkFluidSupport.fluid(recipe.getResultItem(RegistryAccess.EMPTY)).isFluidEqual(awkward))
                .findFirst().orElseThrow();
        assertFalse(brew.isBottling());
        assertEquals(250, new IronSpellBooksRecipeHandler().getIngredients(brew).get(0).count());
        assertTrue(new IronSpellBooksRecipeHandler().hasDeterministicPrimaryOutput(brew));
        assertTrue(recipes.values().stream().anyMatch(recipe -> recipe.isBrewing()
                && recipe.inputIngredients().get(1).test(new ItemStack(Items.GUNPOWDER))
                && AlchemistPotionSupport.bottle(InkFluidSupport.fluid(recipe.getResultItem(RegistryAccess.EMPTY))).is(Items.SPLASH_POTION)));
        var disabled = new LinkedHashMap<ResourceLocation, IronSpellBooksRecipe>();
        IronAlchemistRecipeCatalog.indexPotionRecipes(disabled, IronAlchemistBrewingTest::token, false);
        assertFalse(disabled.values().stream().anyMatch(IronSpellBooksRecipe::isBrewing));
        assertTrue(disabled.values().stream().anyMatch(IronSpellBooksRecipe::isBottling));
    }

    @Test void brewIngredientRetainsFluidNbtAndNative1000To250Ratio() {
        FluidStack input = AlchemistPotionSupport.fluid(AlchemistPotionSupportTest.potion("REGULAR"));
        input.setAmount(1000);
        FluidStack output = InkFluidTestFixtures.ink("greater_healing_elixir", 250);
        IronSpellBooksRecipe recipe = IronSpellBooksRecipe.brewRecipe(new ResourceLocation("test", "brew"),
                token(input), Ingredient.of(Items.AMETHYST_SHARD), List.of(token(output), new ItemStack(Items.GLASS_BOTTLE)));
        var specs = new IronSpellBooksRecipeHandler().getIngredients(recipe);
        assertEquals(1000, specs.get(0).count());
        assertEquals(1, specs.get(1).count());
        assertTrue(specs.get(0).ingredient().test(token(input)));
        assertFalse(specs.get(0).ingredient().test(token(AlchemistPotionSupport.fluid(AlchemistPotionSupportTest.potion("SPLASH")))));
        assertEquals(250, recipe.getResultItem(RegistryAccess.EMPTY).getCount());
        assertEquals(1, new IronSpellBooksRecipeHandler().getSecondaryOutputs(recipe, RegistryAccess.EMPTY).size());
        assertSame(recipe, IronAlchemistRecipeCatalog.find(Ingredient.of(Items.AMETHYST_SHARD), input,
                List.of(output), new ItemStack(Items.GLASS_BOTTLE), new ItemStack(Items.AMETHYST_SHARD), List.of(recipe)));
    }

    @Test void existingWaterMovesToRsBeforePreparingBrewAndRetainsNbt() {
        FluidTank tank = new FluidTank(1000);
        FluidStack water = new FluidStack(Fluids.WATER, 750);
        water.getOrCreateTag().putString("custom", "old-fluid");
        tank.fill(water, FluidAction.EXECUTE);
        CraftStorageEndpoint endpoint = endpoint();
        ServerPlayer player = mock(ServerPlayer.class);
        assertTrue(IronAlchemistBatchDelegate.emptyTank(tank, endpoint, player, IronAlchemistBrewingTest::token));
        assertTrue(tank.isEmpty());
        verify(endpoint).insert(eq(player), argThat(stack -> InkFluidSupport.fluid(stack).isFluidStackIdentical(water)), eq(false));
        IronSpellBooksRecipe recipe = IronSpellBooksRecipe.brewRecipe(new ResourceLocation("test", "brew"),
                token(new FluidStack(Fluids.WATER, 250)), Ingredient.of(Items.NETHER_WART),
                List.of(token(AlchemistPotionSupport.fluid(PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.AWKWARD)))));
        assertTrue(IronAlchemistBatchDelegate.canPrepareBrew(tank, recipe));
    }

    @Test void nativeTaggedWaterDisplayMatchesPlainWaterBrewingInput() {
        FluidStack display = InkFluidTestFixtures.ink("potion", 250);
        display.getOrCreateTag().putString("Potion", "minecraft:water");
        display.getOrCreateTag().putString("irons_spellbooks:bottle_type", "REGULAR");
        FluidStack output = AlchemistPotionSupport.fluid(PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.AWKWARD));
        IronSpellBooksRecipe recipe = IronSpellBooksRecipe.brewRecipe(new ResourceLocation("test", "water_brew"),
                token(new FluidStack(Fluids.WATER, 250)), Ingredient.of(Items.NETHER_WART), List.of(token(output)));

        assertSame(recipe, IronAlchemistRecipeCatalog.find(Ingredient.of(Items.NETHER_WART), display,
                List.of(output), ItemStack.EMPTY, new ItemStack(Items.NETHER_WART), List.of(recipe)));
        assertNull(IronAlchemistRecipeCatalog.find(Ingredient.of(Items.NETHER_WART), display,
                List.of(output), ItemStack.EMPTY, new ItemStack(Items.GUNPOWDER), List.of(recipe)));
    }

    @Test void fullRsDoesNotDrainOldFluidAndPartialRealInsertReturnsRemainder() {
        FluidTank tank = new FluidTank(1000);
        tank.fill(new FluidStack(Fluids.WATER, 750), FluidAction.EXECUTE);
        CraftStorageEndpoint endpoint = endpoint();
        ServerPlayer player = mock(ServerPlayer.class);
        when(endpoint.insert(any(ServerPlayer.class), any(ItemStack.class), eq(true))).thenAnswer(call -> {
            ItemStack stack = call.getArgument(1);
            return StorageOperationResult.inserted(StorageOperationMode.SIMULATE, stack, stack.copy());
        });
        assertFalse(IronAlchemistBatchDelegate.emptyTank(tank, endpoint, player, IronAlchemistBrewingTest::token));
        assertEquals(750, tank.getFluidAmount());
        verify(endpoint, never()).insert(any(ServerPlayer.class), any(ItemStack.class), eq(false));
        endpoint = endpoint();
        when(endpoint.insert(any(ServerPlayer.class), any(ItemStack.class), eq(false))).thenAnswer(call -> {
            ItemStack stack = call.getArgument(1);
            return StorageOperationResult.inserted(StorageOperationMode.PERFORM, stack, stack.copyWithCount(500));
        });
        assertFalse(IronAlchemistBatchDelegate.emptyTank(tank, endpoint, player, IronAlchemistBrewingTest::token));
        assertEquals(500, tank.getFluidAmount());
    }

    @Test void actualBrewOutputMustMatchAllFluidAmountsTagsAndByproducts() {
        FluidStack output = AlchemistPotionSupport.fluid(AlchemistPotionSupportTest.potion("REGULAR"));
        FluidTank tank = new FluidTank(1000);
        tank.fill(output, FluidAction.EXECUTE);
        SimpleContainer inventory = new SimpleContainer(new ItemStack(Items.GLASS_BOTTLE));
        List<ItemStack> outputs = List.of(token(output), new ItemStack(Items.GLASS_BOTTLE));
        assertTrue(IronAlchemistBatchDelegate.matchesBrewOutputs(tank, inventory, outputs));
        assertFalse(IronAlchemistBatchDelegate.matchesBrewOutputs(tank, new SimpleContainer(1), outputs));
        tank.fill(output, FluidAction.EXECUTE);
        assertFalse(IronAlchemistBatchDelegate.matchesBrewOutputs(tank, inventory, outputs));
    }

    @Test void allOldFluidsReachRsWhenDrainingCompactsTankSlots() {
        FluidStack water = new FluidStack(Fluids.WATER, 500);
        FluidStack potion = AlchemistPotionSupport.fluid(AlchemistPotionSupportTest.potion("LINGERING"));
        IFluidHandler tank = compactingTank(water, potion);
        CraftStorageEndpoint endpoint = endpoint();
        ServerPlayer player = mock(ServerPlayer.class);

        assertTrue(IronAlchemistBatchDelegate.emptyTank(tank, endpoint, player, IronAlchemistBrewingTest::token));

        for (int slot = 0; slot < tank.getTanks(); slot++) assertTrue(tank.getFluidInTank(slot).isEmpty());
        verify(endpoint).insert(eq(player), argThat(stack -> InkFluidSupport.fluid(stack).isFluidStackIdentical(water)), eq(false));
        verify(endpoint).insert(eq(player), argThat(stack -> InkFluidSupport.fluid(stack).isFluidStackIdentical(potion)), eq(false));
    }

    @Test void sharedRsCapacityReturnsSecondFluidRemainderWithoutLosingResources() {
        FluidStack water = new FluidStack(Fluids.WATER, 500);
        FluidStack potion = AlchemistPotionSupport.fluid(AlchemistPotionSupportTest.potion("SPLASH"));
        potion.setAmount(500);
        IFluidHandler tank = compactingTank(water, potion);
        CraftStorageEndpoint endpoint = endpoint();
        ServerPlayer player = mock(ServerPlayer.class);
        AtomicInteger remainingCapacity = new AtomicInteger(750);
        when(endpoint.insert(any(ServerPlayer.class), any(ItemStack.class), eq(false))).thenAnswer(call -> {
            ItemStack stack = call.getArgument(1);
            int inserted = Math.min(remainingCapacity.get(), stack.getCount());
            remainingCapacity.addAndGet(-inserted);
            return StorageOperationResult.inserted(StorageOperationMode.PERFORM, stack,
                    stack.copyWithCount(stack.getCount() - inserted));
        });

        assertFalse(IronAlchemistBatchDelegate.emptyTank(tank, endpoint, player, IronAlchemistBrewingTest::token));

        assertEquals(0, remainingCapacity.get());
        FluidStack returned = potion.copy();
        returned.setAmount(250);
        assertTrue(tank.getFluidInTank(0).isFluidStackIdentical(returned));
        assertTrue(tank.getFluidInTank(1).isEmpty());
    }

    @Test void rejectedSharedStartStillReportsAllCommittedInputsForRefund() {
        IronAlchemistBatchDelegate delegate = new IronAlchemistBatchDelegate();
        ServerPlayer player = mock(ServerPlayer.class);
        List<ItemStack> materials = List.of(token(new FluidStack(Fluids.WATER, 250)), new ItemStack(Items.NETHER_WART));

        assertFalse(delegate.tryStartWithMaterials(player, materials, mock(ExtractionLedger.class)));
        delegate.clearMachineState(null, player);

        assertEquals(materials.size(), delegate.failureRecoveredInputs().size());
        for (int i = 0; i < materials.size(); i++) {
            assertTrue(ItemStack.matches(materials.get(i), delegate.failureRecoveredInputs().get(i)));
        }
    }

    @Test void failedInjectionRefundsOnlyFluidActuallyOutsideTheCauldron() {
        FluidStack input = new FluidStack(Fluids.WATER, 1000);
        FluidTank tank = spy(new FluidTank(1000));
        doAnswer(call -> {
            FluidStack limited = ((FluidStack) call.getArgument(0)).copy();
            limited.setAmount(600);
            tank.setFluid(limited);
            return 600;
        }).when(tank).fill(any(FluidStack.class), eq(FluidAction.EXECUTE));
        doAnswer(call -> tank.drain(200, FluidAction.EXECUTE))
                .when(tank).drain(any(FluidStack.class), eq(FluidAction.EXECUTE));

        var prepared = IronAlchemistBatchDelegate.fillBrewInput(tank, input);

        assertFalse(prepared.ready());
        assertEquals(600, prepared.refundable().getAmount());
        assertEquals(400, tank.getFluidAmount());
        assertEquals(input.getAmount(), prepared.refundable().getAmount() + tank.getFluidAmount());
    }

    @Test void collectingMultipleFluidsRestoresEarlierOutputWhenLaterCollectionFails() {
        FluidStack water = new FluidStack(Fluids.WATER, 500);
        FluidStack potion = AlchemistPotionSupport.fluid(AlchemistPotionSupportTest.potion("SPLASH"));
        IFluidHandler tank = compactingTank(water, potion);
        SimpleContainer container = new SimpleContainer(new ItemStack(Items.GLASS_BOTTLE));
        List<ItemStack> outputs = List.of(token(water), token(potion), new ItemStack(Items.GLASS_BOTTLE));
        CraftStorageEndpoint endpoint = endpoint();
        AtomicInteger potionChecks = new AtomicInteger();
        when(endpoint.insert(any(ServerPlayer.class), any(ItemStack.class), eq(true))).thenAnswer(call -> {
            ItemStack stack = call.getArgument(1);
            boolean reject = InkFluidSupport.fluid(stack).isFluidEqual(potion) && potionChecks.incrementAndGet() > 1;
            return StorageOperationResult.inserted(StorageOperationMode.SIMULATE, stack,
                    reject ? stack.copy() : ItemStack.EMPTY);
        });

        assertTrue(IronAlchemistBatchDelegate.collectBrewOutputs(tank, container, endpoint,
                mock(ServerPlayer.class), outputs).isEmpty());

        assertTrue(IronAlchemistBatchDelegate.matchesBrewOutputs(tank, container, outputs));
        assertTrue(container.getItem(0).is(Items.GLASS_BOTTLE));
        verify(endpoint, never()).insert(any(ServerPlayer.class), any(ItemStack.class), eq(false));
    }

    private static IFluidHandler compactingTank(FluidStack... fluids) {
        List<FluidTank> slots = new ArrayList<>();
        for (FluidStack fluid : fluids) {
            FluidTank slot = new FluidTank(1000);
            slot.fill(fluid, FluidAction.EXECUTE);
            slots.add(slot);
        }
        IFluidHandler tank = mock(IFluidHandler.class);
        when(tank.getTanks()).thenReturn(4);
        when(tank.getTankCapacity(anyInt())).thenReturn(1000);
        when(tank.getFluidInTank(anyInt())).thenAnswer(call -> {
            int slot = call.getArgument(0);
            return slot < slots.size() ? slots.get(slot).getFluid().copy() : FluidStack.EMPTY;
        });
        when(tank.drain(any(FluidStack.class), any(FluidAction.class))).thenAnswer(call -> {
            FluidStack requested = call.getArgument(0);
            FluidAction action = call.getArgument(1);
            for (FluidTank slot : List.copyOf(slots)) {
                FluidStack drained = slot.drain(requested, action);
                if (drained.isEmpty()) continue;
                if (action.execute() && slot.isEmpty()) slots.remove(slot);
                return drained;
            }
            return FluidStack.EMPTY;
        });
        when(tank.fill(any(FluidStack.class), any(FluidAction.class))).thenAnswer(call -> {
            FluidTank slot = new FluidTank(1000);
            int filled = slot.fill(call.getArgument(0), call.getArgument(1));
            if (!slot.isEmpty()) slots.add(slot);
            return filled;
        });
        return tank;
    }

    private static CraftStorageEndpoint endpoint() {
        CraftStorageEndpoint endpoint = mock(CraftStorageEndpoint.class, RETURNS_DEEP_STUBS);
        when(endpoint.session().reference()).thenReturn(new StorageReference(new StorageBackendId("refinedstorage"), "test"));
        when(endpoint.insert(any(ServerPlayer.class), any(ItemStack.class), anyBoolean())).thenAnswer(call ->
                StorageOperationResult.inserted(call.getArgument(2) ? StorageOperationMode.SIMULATE : StorageOperationMode.PERFORM,
                        call.getArgument(1), ItemStack.EMPTY));
        return endpoint;
    }
}
