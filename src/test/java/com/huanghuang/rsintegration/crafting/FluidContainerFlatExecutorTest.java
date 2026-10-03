package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.compat.historystages.HistoryStagesCompat;
import com.huanghuang.rsintegration.crafting.fluid.FluidContainerCatalog;
import com.huanghuang.rsintegration.crafting.fluid.FluidContainerRecipe;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidTestFixtures;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.huanghuang.rsintegration.util.CraftLogContext;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.fluids.capability.wrappers.FluidBucketWrapper;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FluidContainerFlatExecutorTest extends BootstrapTest {
    private MockedStatic<FluidUtil> fluidUtil;
    private MockedStatic<InkFluidSupport> tokens;
    private MockedStatic<HistoryStagesCompat> stages;
    private ServerLevel level;
    private List<FluidContainerRecipe> recipes;

    @BeforeAll static void initializeModLogger() throws ClassNotFoundException {
        FMLJavaModLoadingContext context = mock(FMLJavaModLoadingContext.class);
        when(context.getModEventBus()).thenReturn(mock(IEventBus.class));
        try (MockedStatic<FMLJavaModLoadingContext> loading = mockStatic(FMLJavaModLoadingContext.class)) {
            loading.when(FMLJavaModLoadingContext::get).thenReturn(context);
            Class.forName("com.huanghuang.rsintegration.RSIntegrationMod");
        }
    }

    @BeforeEach void setUp() {
        stages = mockStatic(HistoryStagesCompat.class);
        var tokenItem = InkFluidTestFixtures.tokenItem();
        fluidUtil = mockStatic(FluidUtil.class, CALLS_REAL_METHODS);
        // Forge 的能力转换器不在单元测试内运行，其他桶操作保留真实实现。
        fluidUtil.when(() -> FluidUtil.getFluidHandler(any(ItemStack.class))).thenAnswer(call -> {
            ItemStack stack = call.getArgument(0);
            return stack.is(Items.BUCKET) || stack.is(Items.WATER_BUCKET) || stack.is(Items.LAVA_BUCKET)
                    ? LazyOptional.of(() -> new FluidBucketWrapper(stack)) : LazyOptional.empty();
        });
        tokens = mockStatic(InkFluidSupport.class, CALLS_REAL_METHODS);
        tokens.when(() -> InkFluidSupport.token(any(FluidStack.class))).thenAnswer(call ->
                InkFluidSupport.token(tokenItem, call.getArgument(0)));
        level = mock(ServerLevel.class);
        RecipeManager manager = mock(RecipeManager.class);
        when(level.getRecipeManager()).thenReturn(manager);
        when(level.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        when(manager.getRecipes()).thenReturn(List.of());
        recipes = FluidContainerCatalog.allRecipes(level);
    }

    @AfterEach void tearDown() {
        tokens.close();
        fluidUtil.close();
        stages.close();
    }

    @Test void fillsFromSplitLiquidInventoryWithSyntheticRecipeLookup() {
        FluidContainerRecipe recipe = waterRecipe(true);
        List<ItemStack> inventory = new ArrayList<>(List.of(new ItemStack(Items.BUCKET, 2),
                InkFluidSupport.token(new FluidStack(Fluids.WATER, 600)),
                InkFluidSupport.token(new FluidStack(Fluids.WATER, 1400))));
        assertTrue(execute(recipe, 2, inventory));
        assertEquals(1, inventory.size());
        assertTrue(inventory.get(0).is(Items.WATER_BUCKET));
        assertEquals(2, inventory.get(0).getCount());
    }

    @Test void drainingReturnsOnlyOneBucketPerInputWithoutCraftingRemainderDuplication() {
        List<ItemStack> inventory = new ArrayList<>(List.of(new ItemStack(Items.WATER_BUCKET, 2)));
        assertTrue(execute(waterRecipe(false), 2, inventory));
        assertEquals(2000, inventory.stream().mapToInt(stack -> InkFluidSupport.fluid(stack).getAmount()).sum());
        assertEquals(2, inventory.stream().filter(stack -> stack.is(Items.BUCKET)).mapToInt(ItemStack::getCount).sum());
        assertEquals(2, inventory.size());
    }

    @Test void cannotFillWithInsufficientLiquid() {
        List<ItemStack> inventory = new ArrayList<>(List.of(new ItemStack(Items.BUCKET),
                InkFluidSupport.token(new FluidStack(Fluids.WATER, 999))));
        assertFalse(execute(waterRecipe(true), 1, inventory));
        assertTrue(inventory.stream().noneMatch(stack -> stack.is(Items.WATER_BUCKET)));
    }

    @Test void invalidationRebuildsTheCatalogForTheSameRecipeManager() {
        FluidContainerRecipe recipe = waterRecipe(true);
        assertNotNull(FluidContainerCatalog.byId(recipe.getId()));
        FluidContainerCatalog.invalidate();
        assertNull(FluidContainerCatalog.byId(recipe.getId()));
        assertNotSame(recipes, FluidContainerCatalog.allRecipes(level));
        assertNotNull(FluidContainerCatalog.resolve(level, recipe.getId()));
    }

    private FluidContainerRecipe waterRecipe(boolean filling) {
        return recipes.stream().filter(recipe -> recipe.filling() == filling
                && recipe.fluid().getFluid() == Fluids.WATER).findFirst().orElseThrow();
    }

    private boolean execute(FluidContainerRecipe recipe, int executions, List<ItemStack> inventory) {
        var step = new CraftingResolver.ResolutionStep(recipe.getId(), ModType.FLUID_CONTAINER,
                FluidContainerRecipe.TYPE_ID, List.of(), List.of(), false, executions);
        FlatCraftExecutor.Host host = mock(FlatCraftExecutor.Host.class);
        return FlatCraftExecutor.execute(List.of(step), mock(ServerPlayer.class), inventory,
                new ExtractionLedger(), false, new FlatCraftExecutor.Context(level,
                        CraftLogContext.create(UUID.randomUUID(), recipe.getId()), List.of(step), null, host));
    }
}
