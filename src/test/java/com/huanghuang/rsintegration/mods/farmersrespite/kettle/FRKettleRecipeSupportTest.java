package com.huanghuang.rsintegration.mods.farmersrespite.kettle;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.DirectMaterialAllocator;
import com.huanghuang.rsintegration.crafting.batch.MachineOperation.CraftPhase;
import com.huanghuang.rsintegration.mods.farmersrespite.kettle.FRKettleRecipeSupport.Bottling;
import com.huanghuang.rsintegration.mods.farmersrespite.kettle.FRKettleRecipeSupport.Brewing;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.fluids.capability.IFluidHandler.FluidAction;
import net.minecraftforge.fluids.capability.IFluidHandlerItem;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.items.ItemStackHandler;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import umpaz.farmersrespite.common.crafting.KettlePouringRecipe;
import umpaz.farmersrespite.common.crafting.KettleRecipe;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

class FRKettleRecipeSupportTest extends BootstrapTest {

    @BeforeAll
    static void initializeModLogger() throws ClassNotFoundException {
        var context = mock(FMLJavaModLoadingContext.class);
        when(context.getModEventBus()).thenReturn(mock(IEventBus.class));
        try (var loading = mockStatic(FMLJavaModLoadingContext.class)) {
            loading.when(FMLJavaModLoadingContext::get).thenReturn(context);
            Class.forName(RSIntegrationMod.class.getName());
        }
    }

    @Test
    void observationAndEarlyCollectionLeaveIntermediateBottlesForTheNextPhase() throws Exception {
        var kettle = new FRKettleBatchDelegate();
        var inventory = new ItemStackHandler(5);
        var be = mock(BlockEntity.class);
        var level = mock(ServerLevel.class);
        var input = new FluidStack(Fluids.LAVA, 250);
        var brewed = new FluidStack(Fluids.WATER, 250);
        var bottle = new ItemStack(Items.GLASS_BOTTLE);
        var phase = FRKettleBatchDelegate.class.getDeclaredField("nativeInputPhase");
        phase.setAccessible(true);
        for (Object value : phase.getType().getEnumConstants()) {
            if ("EMPTYING_INPUT".equals(value.toString())) phase.set(kettle, value);
        }
        set(kettle, "recipe", mock(Recipe.class));
        set(kettle, "brewing", new Brewing(input, brewed,
                new Bottling(bottle, new ItemStack(Items.POTION)),
                new Bottling(bottle, new ItemStack(Items.HONEY_BOTTLE))));
        inventory.setStackInSlot(4, bottle.copy());
        kettle.advanceNativePhase(inventory, be, input);
        assertEquals("BREWING", phase.get(kettle).toString());
        for (int read = 0; read < 2; read++) {
            assertEquals(CraftPhase.WORKING, kettle.observeMachineCraft(level, be).phase());
            assertTrue(kettle.outputSource().collectFinal(null).stacks().isEmpty());
            assertTrue(inventory.getStackInSlot(4).is(Items.GLASS_BOTTLE));
            assertTrue(inventory.getStackInSlot(3).isEmpty());
        }
        kettle.advanceNativePhase(inventory, be, brewed);
        assertEquals("BOTTLING_OUTPUT", phase.get(kettle).toString());
        assertTrue(inventory.getStackInSlot(4).isEmpty());
        assertEquals(1, inventory.getStackInSlot(3).getCount());
        inventory.setStackInSlot(3, ItemStack.EMPTY);
        kettle.advanceNativePhase(inventory, be, brewed);
        assertTrue(inventory.getStackInSlot(3).isEmpty());
    }

    private static void set(FRKettleBatchDelegate kettle, String name, Object value) throws Exception {
        var field = FRKettleBatchDelegate.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(kettle, value);
    }

    @Test
    void waterIsFreeButDrinkFluidsAreDependencies() {
        assertTrue(FRKettleRecipeSupport.isWater(new FluidStack(Fluids.WATER, 1000)));
        assertTrue(FRKettleRecipeSupport.isWater(new FluidStack(Fluids.FLOWING_WATER, 1000)));
        assertFalse(FRKettleRecipeSupport.isWater(FluidStack.EMPTY));
        assertFalse(FRKettleRecipeSupport.isWater(new FluidStack(Fluids.LAVA, 1000)));
    }

    @Test
    void ciderUsesTheActualBottlingItemAsResultAndAsUpgradeMaterial() throws Exception {
        // 原版测试注册表没有模组流体，使用与物品 ID 不同的流体复现苹果酒装瓶关系。
        ItemStack baseCider = new ItemStack(Items.HONEY_BOTTLE);
        ItemStack longCider = new ItemStack(Items.POTION);
        ItemStack bottle = new ItemStack(Items.GLASS_BOTTLE);
        Fluid baseFluid = Fluids.LAVA;
        Fluid longFluid = Fluids.FLOWING_WATER;
        var base = kettleRecipe("apple_cider_from_water", Fluids.WATER, baseFluid,
                Ingredient.of(Items.APPLE), Ingredient.of(Items.SUGAR));
        var upgrade = kettleRecipe("long_apple_cider_from_apple_cider", baseFluid, longFluid,
                Ingredient.of(Items.MILK_BUCKET));
        var basePouring = new KettlePouringRecipe(
                new ResourceLocation("farmersrespite", "pouring/apple_cider"),
                baseFluid, bottle, baseCider, 250);
        var longPouring = new KettlePouringRecipe(
                new ResourceLocation("farmersrespite", "pouring/long_apple_cider"),
                longFluid, bottle, longCider, 250);
        var pouring = List.of(basePouring, longPouring);

        Brewing basePlan = FRKettleRecipeSupport.brewing(base, pouring, KettlePouringRecipe.class);
        assertNotNull(basePlan);
        assertTrue(basePlan.result().is(baseCider.getItem()));
        assertEquals(4, basePlan.result().getCount());
        assertEquals(3, basePlan.ingredients(base).size());
        assertTrue(basePlan.ingredients(base).get(2).ingredient().test(bottle));
        assertEquals(4, basePlan.ingredients(base).get(2).count());

        Brewing upgradePlan = FRKettleRecipeSupport.brewing(upgrade, pouring, KettlePouringRecipe.class);
        assertNotNull(upgradePlan);
        assertTrue(upgradePlan.result().is(longCider.getItem()));
        assertEquals(4, upgradePlan.result().getCount());
        var materials = upgradePlan.ingredients(upgrade);
        var milk = StackKey.of(new ItemStack(Items.MILK_BUCKET), true);
        assertEquals(2, materials.size());
        assertTrue(materials.get(1).ingredient().test(basePlan.result()));
        assertEquals(4, materials.get(1).count());
        assertFalse(DirectMaterialAllocator.allocate(materials, Map.of(milk, 1L)).feasible());
        assertTrue(DirectMaterialAllocator.allocate(materials,
                Map.of(milk, 1L, StackKey.of(basePlan.result(), true), 4L)).feasible());

        var delegate = new FRKettleBatchDelegate();
        set(delegate, "recipe", upgrade);
        set(delegate, "brewing", upgradePlan);
        var graphInputs = delegate.materialPlan().graphEntries();
        assertEquals(2, graphInputs.size());
        assertTrue(graphInputs.get(0).spec().ingredient().test(milk.toStack()));
        assertTrue(graphInputs.get(1).spec().ingredient().test(basePlan.result()));
        assertEquals(4, graphInputs.get(1).spec().count());
        try (var containers = mockStatic(FluidUtil.class)) {
            containers.when(() -> FluidUtil.getFluidHandler(any(ItemStack.class)))
                    .thenReturn(LazyOptional.empty());
            assertNull(FRKettleRecipeSupport.brewing(upgrade, List.of(longPouring), KettlePouringRecipe.class));
        }
    }

    @Test
    void bucketIsPlannedAsFluidInputAndItsEmptyBucketIsReturnedOnlyOnce() throws Exception {
        // 单测没有注册 Forge 的牛奶流体，使用已注册的岩浆验证相同的容器交接。
        FluidStack inputBatch = new FluidStack(Fluids.LAVA, 1000);
        FluidStack outputBatch = new FluidStack(Fluids.FLOWING_WATER, 1000);
        var recipe = kettleRecipe("hot_cocoa_from_milk", Fluids.LAVA, Fluids.FLOWING_WATER,
                Ingredient.of(Items.COCOA_BEANS), Ingredient.of(Items.SUGAR));
        var pouring = new KettlePouringRecipe(
                ResourceLocation.fromNamespaceAndPath("farmersrespite", "pouring/hot_cocoa"),
                Fluids.FLOWING_WATER, new ItemStack(Items.GLASS_BOTTLE), new ItemStack(Items.POTION), 250);
        var bucketHandler = mock(IFluidHandlerItem.class);
        when(bucketHandler.drain(Integer.MAX_VALUE, FluidAction.EXECUTE)).thenReturn(inputBatch);
        when(bucketHandler.getContainer()).thenReturn(new ItemStack(Items.BUCKET));
        Brewing brewing;
        try (var containers = mockStatic(FluidUtil.class)) {
            containers.when(() -> FluidUtil.getFluidHandler(any(ItemStack.class))).thenAnswer(call -> {
                ItemStack stack = call.getArgument(0);
                assertTrue(stack.is(Items.LAVA_BUCKET));
                return LazyOptional.of(() -> bucketHandler);
            });
            brewing = FRKettleRecipeSupport.brewing(recipe, List.of(pouring), KettlePouringRecipe.class);
        }
        assertNotNull(brewing);
        assertTrue(brewing.ingredients(recipe).get(2).ingredient().test(new ItemStack(Items.LAVA_BUCKET)));
        assertEquals(4, brewing.extraContainers());
        assertTrue(brewing.returnedContainer().is(Items.BUCKET));

        var delegate = new FRKettleBatchDelegate();
        var inventory = new ItemStackHandler(5);
        var be = mock(BlockEntity.class);
        var phase = FRKettleBatchDelegate.class.getDeclaredField("nativeInputPhase");
        phase.setAccessible(true);
        for (Object value : phase.getType().getEnumConstants()) {
            if ("EMPTYING_INPUT".equals(value.toString())) phase.set(delegate, value);
        }
        set(delegate, "recipe", recipe);
        set(delegate, "brewing", brewing);
        set(delegate, "deferredOutputContainers", new ItemStack(Items.GLASS_BOTTLE, 4));
        inventory.setStackInSlot(4, new ItemStack(Items.BUCKET));
        delegate.advanceNativePhase(inventory, be, inputBatch);
        delegate.advanceNativePhase(inventory, be, outputBatch);
        assertEquals(4, inventory.getStackInSlot(3).getCount());
        assertTrue(inventory.getStackInSlot(4).isEmpty());
        var returned = FRKettleBatchDelegate.class.getDeclaredField("returnedInputContainers");
        returned.setAccessible(true);
        assertEquals(1, ((ItemStack) returned.get(delegate)).getCount());
        delegate.advanceNativePhase(inventory, be, outputBatch);
        assertEquals(1, ((ItemStack) returned.get(delegate)).getCount());
    }

    private static KettleRecipe kettleRecipe(String name, Fluid input, Fluid output, Ingredient... solids) {
        return new KettleRecipe(new ResourceLocation("farmersrespite", "brewing/" + name),
                NonNullList.of(Ingredient.EMPTY, solids), new FluidStack(input, 1000),
                new FluidStack(output, 1000), 0.35F, 2400);
    }

    @Test
    void pouringInputUsesRecipeItemSemanticsInsteadOfExactRuntimeTags() {
        ItemStack expected = new ItemStack(Items.POTION);
        ItemStack actual = new ItemStack(Items.POTION, 4);
        CompoundTag runtimeTag = new CompoundTag();
        runtimeTag.putString("runtime", "variant");
        actual.setTag(runtimeTag);

        assertTrue(FRKettleBatchDelegate.matchesPouringItem(expected, actual));
        assertFalse(ItemStack.isSameItemSameTags(expected, actual));
    }

    @Test
    void ownedOutputSlotIsClearedWithoutDependingOnExtractionRules() {
        ItemStackHandler inventory = new ItemStackHandler(5) {
            @Override
            public ItemStack extractItem(int slot, int amount, boolean simulate) {
                return ItemStack.EMPTY;
            }
        };
        inventory.setStackInSlot(4, new ItemStack(Items.APPLE, 4));

        ItemStack result = FRKettleBatchDelegate.takeOwnedSlot(inventory, 4);

        assertEquals(4, result.getCount());
        assertTrue(result.is(Items.APPLE));
        assertTrue(inventory.getStackInSlot(4).isEmpty());
    }

    @Test
    void bottledFluidIsRemovedAfterSolidIngredientsWithoutLosingRuntimeTag() {
        ItemStack honey = new ItemStack(Items.HONEY_BOTTLE);
        ItemStack coffee = new ItemStack(Items.POTION, 4);
        CompoundTag purity = new CompoundTag();
        purity.putInt("Purity", 3);
        coffee.setTag(purity);
        List<ItemStack> pool = new ArrayList<>(List.of(honey.copy(), coffee.copy()));

        List<ItemStack> solids = FRKettleBatchDelegate.takeMatchingMaterials(
                pool, Ingredient.of(Items.HONEY_BOTTLE), 1);
        List<ItemStack> fluids = FRKettleBatchDelegate.takeMatchingMaterials(
                pool, Ingredient.of(Items.POTION), 4);

        assertEquals(1, solids.size());
        assertTrue(solids.get(0).is(Items.HONEY_BOTTLE));
        assertEquals(4, fluids.get(0).getCount());
        assertEquals(3, fluids.get(0).getTag().getInt("Purity"));
        assertTrue(pool.stream().allMatch(ItemStack::isEmpty));
    }
}
