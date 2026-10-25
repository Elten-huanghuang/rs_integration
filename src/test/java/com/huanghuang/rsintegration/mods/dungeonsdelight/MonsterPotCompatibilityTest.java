package com.huanghuang.rsintegration.mods.dungeonsdelight;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.InputBufferPlan;
import com.huanghuang.rsintegration.crafting.batch.OutputAccounting;
import com.huanghuang.rsintegration.crafting.batch.OutputContract;
import com.huanghuang.rsintegration.mods.dungeonsdelight.DungeonsDelightRecipeHandler;
import com.huanghuang.rsintegration.mods.farmersdelight.FarmersDelightRecipeHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.network.binding.BindingEventHandler;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.huanghuang.rsintegration.util.ModIds;
import com.huanghuang.rsintegration.util.ForcedChunkTicketManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.yirmiri.dungeonsdelight.common.block.entity.MonsterPotBlockEntity;
import net.yirmiri.dungeonsdelight.common.block.entity.container.MonsterPotRecipe;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import vectorwing.farmersdelight.common.crafting.CookingPotRecipe;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MonsterPotCompatibilityTest extends BootstrapTest {
    @BeforeAll
    static void registerIntegration() throws ClassNotFoundException {
        FMLJavaModLoadingContext context = mock(FMLJavaModLoadingContext.class);
        when(context.getModEventBus()).thenReturn(mock(IEventBus.class));
        try (MockedStatic<FMLJavaModLoadingContext> loading = mockStatic(FMLJavaModLoadingContext.class)) {
            loading.when(FMLJavaModLoadingContext::get).thenReturn(context);
            Class.forName(RSIntegrationMod.class.getName());
        }
        DungeonsDelightRSModule.INSTANCE.registerModType();
        DungeonsDelightRSModule.INSTANCE.registerBindingTargets();
        DungeonsDelightRSModule.INSTANCE.registerRecipeHandler();
        CommentedConfig config = CommentedConfig.inMemory();
        RSIntegrationConfig.SERVER_SPEC.correct(config);
        RSIntegrationConfig.SERVER_SPEC.setConfig(config);
    }

    @BeforeEach
    void clearRecipeCache() {
        ModRecipeHandlers.clearResultCaches();
    }

    @AfterAll
    static void clearConfig() {
        RSIntegrationConfig.SERVER_SPEC.setConfig(null);
    }

    @Test
    void nativeRecipeAndJeiCategoryResolveToGraphEnabledMonsterPot() {
        ModType type = ModType.byId(ModIds.ID_DD_MONSTER_POT);
        assertEquals(ModType.GraphExecutionAudit.GRAPH_SAFE, type.graphExecutionAudit());
        assertInstanceOf(MonsterPotBatchDelegate.class, type.createDelegate());
        assertEquals(ModIds.ID_DD_MONSTER_POT,
                ModType.filterForJeiUid("dungeonsdelight:monster_cooking"));
        assertEquals(type, ModType.findByRecipeClass(DungeonsDelightRecipeHandler.RECIPE_CLASS));
        assertEquals(type, ModType.fromBlockKey(
                "dungeonsdelight_monster_pot||block.dungeonsdelight.monster_pot"));
        assertEquals(ModIds.ID_DD_MONSTER_POT,
                ModType.filterForRecipeClass(DungeonsDelightRecipeHandler.RECIPE_CLASS));
        BindingEventHandler.MachineBindingTarget target = BindingEventHandler.findTargetByClass(
                "net.yirmiri.dungeonsdelight.common.block.MonsterPotBlock");
        assertNotNull(target);
        assertTrue(target.supportsGui);
    }

    @Test
    void plansEveryFoodSlotAndOneContainerPerServing() {
        MonsterPotRecipe recipe = recipe(Ingredient.of(Items.CARROT), 2);
        DungeonsDelightRecipeHandler handler = new DungeonsDelightRecipeHandler();
        List<IngredientSpec> specs = handler.getIngredients(recipe);
        assertNotNull(specs);
        assertTrue(handler.canHandle(recipe));
        assertFalse(new FarmersDelightRecipeHandler().canHandle(recipe));
        assertTrue(handler.supportsBackgroundPlanning(recipe));
        assertEquals(2, specs.size());
        assertEquals(1, specs.get(0).count());
        assertTrue(specs.get(0).ingredient().test(new ItemStack(Items.CARROT)));
        assertEquals(2, specs.get(1).count());
        assertTrue(specs.get(1).ingredient().test(new ItemStack(Items.BOWL)));
        assertEquals(2, handler.getResultItem(recipe, RegistryAccess.EMPTY).getCount());
        assertEquals(80, FarmersDelightRecipeHandler.getCookTime(recipe));
        assertEquals(1, recipe.getOutputContainer().getCount(), "规划不能修改原生配方容器数量");
    }

    @Test
    void ordinaryCookingRecipesCannotUseMonsterPotDelegate() {
        MonsterPotBatchDelegate delegate = new MonsterPotBatchDelegate();
        assertFalse(delegate.isSupportedRecipe(mock(CookingPotRecipe.class)));
        assertTrue(delegate.isSupportedRecipe(recipe(Ingredient.of(Items.CARROT), 1)));
    }

    @Test
    void preloadsIngredientsAndContainersAndSettlesExactNativeOutput() {
        Fixture fixture = new Fixture(Ingredient.of(Items.CARROT), 2);
        assertTrue(fixture.prepare());
        verify(fixture.inventory, never()).extractItem(anyInt(), anyInt(), eq(false));
        assertTrue(fixture.delegate.supportsInputBuffer());
        InputBufferPlan plan = fixture.delegate.inputBufferPlan(64);
        assertEquals(32, plan.operations(), "双份产物与双份碗限制单批操作数");
        assertEquals(7, plan.inputs().get(1).slot());
        assertEquals(8, plan.outputs().get(0).physicalPort());
        assertEquals(10, fixture.delegate.preferredParallelBatchSize(30, 3));
        assertEquals(32, fixture.delegate.prepareFlatBatch(64));
        plan = fixture.delegate.inputBufferPlan(4);

        try (ExtractionLedger ledger = new ExtractionLedger();
             MockedStatic<ForcedChunkTicketManager> tickets = mockStatic(ForcedChunkTicketManager.class)) {
            assertTrue(fixture.delegate.startBufferedOperation(fixture.player, plan, ledger));
        }
        assertEquals(4, fixture.inventory.getStackInSlot(0).getCount());
        assertEquals(8, fixture.inventory.getStackInSlot(7).getCount());
        fixture.inventory.setStackInSlot(0, ItemStack.EMPTY);
        fixture.inventory.setStackInSlot(7, ItemStack.EMPTY);
        fixture.inventory.setStackInSlot(8, new ItemStack(Items.APPLE, 7));
        assertFalse(fixture.delegate.isCraftComplete(fixture.level));
        fixture.inventory.setStackInSlot(8, new ItemStack(Items.APPLE, 8));
        assertTrue(fixture.delegate.isCraftComplete(fixture.level));
        OutputContract contract = fixture.delegate.outputContract();
        List<OutputAccounting.CollectedOutput> outputs =
                fixture.delegate.collectStructuredResults(fixture.player);
        assertEquals(1, outputs.size());
        assertEquals("dungeonsdelight:monster_pot:output", outputs.get(0).portId());
        assertEquals(8, outputs.get(0).stack().getCount());
        assertTrue(OutputAccounting.assess(contract, 4, outputs).complete());
        assertTrue(fixture.inventory.getStackInSlot(8).isEmpty());
    }

    @Test
    void ingredientRemaindersDisablePreloadingButKeepSingleCraftExecution() {
        Fixture fixture = new Fixture(Ingredient.of(Items.MILK_BUCKET), 1);
        assertTrue(fixture.prepare());
        assertFalse(fixture.delegate.supportsInputBuffer());
        assertEquals(1, fixture.delegate.prepareFlatBatch(20));
        assertFalse(fixture.delegate.inputBufferPlan(20).enabled());
        try (ExtractionLedger ledger = new ExtractionLedger();
             MockedStatic<ForcedChunkTicketManager> tickets = mockStatic(ForcedChunkTicketManager.class)) {
            assertTrue(fixture.delegate.startPreparedOperation(fixture.player,
                    List.of(new ItemStack(Items.MILK_BUCKET), new ItemStack(Items.BOWL)), ledger));
        }
        assertEquals(Items.MILK_BUCKET, fixture.inventory.getStackInSlot(0).getItem());
    }

    @Test
    void missingNativeHeatPreventsIngredientInsertion() {
        Fixture fixture = new Fixture(Ingredient.of(Items.CARROT), 1);
        assertTrue(fixture.prepare());
        when(fixture.machine.isHeated()).thenReturn(false);
        InputBufferPlan plan = fixture.delegate.inputBufferPlan(2);
        try (ExtractionLedger ledger = new ExtractionLedger();
             MockedStatic<ForcedChunkTicketManager> tickets = mockStatic(ForcedChunkTicketManager.class)) {
            assertFalse(fixture.delegate.startBufferedOperation(fixture.player, plan, ledger));
        }
        assertTrue(fixture.inventory.getStackInSlot(0).isEmpty());
        assertTrue(fixture.inventory.getStackInSlot(7).isEmpty());
        verify(fixture.machine).isHeated();
    }

    @Test
    void failedBatchRecoversOnlyRemainingIngredientsAndContainers() {
        Fixture fixture = new Fixture(Ingredient.of(Items.CARROT), 2);
        assertTrue(fixture.prepare());
        InputBufferPlan plan = fixture.delegate.inputBufferPlan(4);
        try (ExtractionLedger ledger = new ExtractionLedger();
             MockedStatic<ForcedChunkTicketManager> tickets = mockStatic(ForcedChunkTicketManager.class)) {
            assertTrue(fixture.delegate.startBufferedOperation(fixture.player, plan, ledger));
        }
        fixture.inventory.setStackInSlot(0, new ItemStack(Items.CARROT, 3));
        fixture.inventory.setStackInSlot(7, new ItemStack(Items.BOWL, 6));
        fixture.delegate.onBatchFailed(fixture.player, "测试取消批次");
        List<ItemStack> recovered = fixture.delegate.failureRecoveredInputs();
        assertEquals(9, recovered.stream().mapToInt(ItemStack::getCount).sum());
        assertTrue(fixture.inventory.getStackInSlot(0).isEmpty());
        assertTrue(fixture.inventory.getStackInSlot(7).isEmpty());
    }

    @Test
    void concurrentPotsKeepTheirInputsAndOutputsSeparate() {
        Fixture first = new Fixture(Ingredient.of(Items.CARROT), 2, new BlockPos(0, 64, 0));
        Fixture second = new Fixture(Ingredient.of(Items.CARROT), 2, new BlockPos(4, 64, 0));
        assertTrue(first.prepare());
        assertTrue(second.prepare());
        try (ExtractionLedger ledger = new ExtractionLedger();
             MockedStatic<ForcedChunkTicketManager> tickets = mockStatic(ForcedChunkTicketManager.class)) {
            assertTrue(first.delegate.startBufferedOperation(first.player,
                    first.delegate.inputBufferPlan(3), ledger));
            assertTrue(second.delegate.startBufferedOperation(second.player,
                    second.delegate.inputBufferPlan(2), ledger));
        }
        assertEquals(3, first.inventory.getStackInSlot(0).getCount());
        assertEquals(2, second.inventory.getStackInSlot(0).getCount());
        first.inventory.setStackInSlot(0, ItemStack.EMPTY);
        first.inventory.setStackInSlot(7, ItemStack.EMPTY);
        first.inventory.setStackInSlot(8, new ItemStack(Items.APPLE, 6));
        assertTrue(first.delegate.isCraftComplete(first.level));
        assertFalse(second.delegate.isCraftComplete(second.level));
        assertEquals(6, first.delegate.collectResult(first.player).getCount());
        assertEquals(2, second.inventory.getStackInSlot(0).getCount());
        assertEquals(4, second.inventory.getStackInSlot(7).getCount());
    }

    private static MonsterPotRecipe recipe(Ingredient food, int servings) {
        return new MonsterPotRecipe(new ResourceLocation("dungeonsdelight", "test"), "", null,
                NonNullList.of(Ingredient.EMPTY, food), new ItemStack(Items.APPLE, servings),
                new ItemStack(Items.BOWL), 0, 80);
    }

    private static final class Fixture {
        final MonsterPotBatchDelegate delegate = new MonsterPotBatchDelegate();
        final MonsterPotBlockEntity machine = mock(MonsterPotBlockEntity.class);
        final ItemStackHandler inventory = spy(new ItemStackHandler(9));
        final ServerLevel level = mock(ServerLevel.class);
        final ServerPlayer player = mock(ServerPlayer.class);
        final BlockPos pos;
        final MonsterPotRecipe recipe;

        Fixture(Ingredient food, int servings) {
            this(food, servings, new BlockPos(0, 64, 0));
        }

        Fixture(Ingredient food, int servings, BlockPos pos) {
            this.pos = pos;
            recipe = recipe(food, servings);
            when(player.level()).thenReturn(level);
            when(player.serverLevel()).thenReturn(level);
            when(machine.getInventory()).thenReturn(inventory);
            when(machine.isHeated()).thenReturn(true);
            when(level.hasChunkAt(pos)).thenReturn(true);
            when(level.getBlockEntity(pos)).thenReturn(machine);
            when(level.registryAccess()).thenReturn(RegistryAccess.EMPTY);
            when(level.dimension()).thenReturn(Level.OVERWORLD);
            RecipeManager manager = mock(RecipeManager.class);
            doReturn(Optional.of(recipe)).when(manager).byKey(recipe.getId());
            when(level.getRecipeManager()).thenReturn(manager);
            delegate.setStorageEndpoint(mock(CraftStorageEndpoint.class));
        }

        boolean prepare() {
            try (MockedStatic<CraftPacketUtils> context = mockStatic(CraftPacketUtils.class)) {
                context.when(() -> CraftPacketUtils.resolveLevel(player.server, null, player)).thenReturn(level);
                return delegate.validateAndInit(player, recipe.getId(), null, pos);
            }
        }
    }
}
