package com.huanghuang.rsintegration.mods.cthulhucreatures;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate.CraftPhase;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate.PreparationState;
import com.huanghuang.rsintegration.crafting.batch.InputBufferPlan;
import com.huanghuang.rsintegration.crafting.batch.OperationStartContext;
import com.huanghuang.rsintegration.crafting.batch.OutputAccounting;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.huanghuang.rsintegration.util.ModIds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class FleshAltarIntegrationTest extends BootstrapTest {
    private static final FleshAltarRecipeHandler HANDLER = new FleshAltarRecipeHandler();

    @Test
    void countedIngredientsPreserveBothSlotsAndRecursiveDemand() {
        CountedRecipe recipe = new CountedRecipe(64, 32, 2);
        List<IngredientSpec> specs = HANDLER.getIngredients(recipe);
        assertNotNull(specs);
        assertEquals(List.of(64, 32), specs.stream().map(IngredientSpec::count).toList());
        assertTrue(specs.get(0).ingredient().test(new ItemStack(Items.NETHERRACK)));
        assertEquals(192, HANDLER.requiredIngredientCount(recipe, specs.get(0), 0, 3));
        assertTrue(HANDLER.supportsBackgroundPlanning(recipe));
        assertEquals(2, HANDLER.getResultItem(recipe, RegistryAccess.EMPTY).getCount());
    }

    @Test
    void invalidCountsDoNotBecomeOneItemRecipes() {
        assertNull(HANDLER.getIngredients(new CountedRecipe(0, 1, 1)));
        assertNull(HANDLER.getIngredients(new CountedRecipe(1, 65, 1)));
    }

    @Test
    void moduleConnectsJeiBindingAndGraphExecution() {
        CthulhuCreaturesRSModule.INSTANCE.registerModType();
        ModType type = ModType.byId(ModIds.ID_CTHULHU_FLESH_ALTAR);
        assertEquals(ModType.GraphExecutionAudit.GRAPH_SAFE, type.graphExecutionAudit());
        assertSame(type, ModType.fromBlockKey("block.cthulhu_creatures.flesh_altar"));
        assertSame(type, ModType.fromBlockKey(ModIds.ID_CTHULHU_FLESH_ALTAR
                + "||block.cthulhu_creatures.flesh_altar"));
        assertEquals(type.id(), ModType.filterForJeiUid(CthulhuCreaturesRSModule.BLOCK_ID));
        assertEquals(type.id(), ModType.filterForRecipeClass(FleshAltarRecipeHandler.RECIPE_CLASS));
        assertInstanceOf(FleshAltarBatchDelegate.class, type.createDelegate());
        assertEquals(BatchConcurrencyCapabilities.OutputOwnership.MACHINE_SLOT,
                type.createDelegate().concurrencyCapabilities().outputOwnership());
    }

    @Test
    void bufferUsesInputCountsOutputCapacityAndWorkerShare() throws Exception {
        Fixture first = fixture(new CountedRecipe(2, 4, 1));
        InputBufferPlan plan = first.delegate.inputBufferPlan(64);
        assertEquals(16, plan.operations());
        assertEquals(List.of(32, 64), plan.inputs().stream().map(input -> input.stack().getCount()).toList());
        assertEquals(8, first.delegate.preferredParallelBatchSize(32, 4));
        assertEquals(16, fixture(new CountedRecipe(1, 1, 4)).delegate.inputBufferPlan(64).operations());
        assertEquals(1, fixture(new CountedRecipe(64, 1, 1)).delegate.inputBufferPlan(64).operations());
    }

    @Test
    void fullStackInputIsPlacedWithoutTruncation() throws Exception {
        Fixture fixture = fixture(new CountedRecipe(64, 32, 1));
        assertTrue(startSingle(fixture, 64, 32));
        assertEquals(64, fixture.inventory.getItem(0).getCount());
        assertEquals(32, fixture.inventory.getItem(1).getCount());
        assertEquals(CraftPhase.WORKING, fixture.delegate.observeCraft(fixture.level).phase());
    }

    @Test
    void bufferedDispatchWaitsForAllCyclesAndSettlesActualOutput() throws Exception {
        Fixture fixture = fixture(new CountedRecipe(2, 4, 2));
        InputBufferPlan plan = fixture.delegate.inputBufferPlan(3);
        assertTrue(fixture.delegate.startOperation(OperationStartContext.chainReserved(
                mock(ServerPlayer.class), new ExtractionLedger(), fixture.delegate.materialPlan(),
                List.of(new ItemStack(Items.NETHERRACK, 6), new ItemStack(Items.DIAMOND, 12)), plan)));
        assertEquals(6, fixture.delegate.getExpectedProduction().count());
        fixture.inventory.setItem(0, new ItemStack(Items.NETHERRACK, 4));
        fixture.inventory.setItem(1, new ItemStack(Items.DIAMOND, 8));
        fixture.inventory.setItem(2, new ItemStack(Items.GOLD_INGOT, 2));
        assertEquals(CraftPhase.WORKING, fixture.delegate.observeCraft(fixture.level).phase());
        fixture.inventory.setItem(0, ItemStack.EMPTY);
        fixture.inventory.setItem(1, ItemStack.EMPTY);
        fixture.inventory.setItem(2, new ItemStack(Items.GOLD_INGOT, 6));
        assertEquals(CraftPhase.DONE, fixture.delegate.observeCraft(fixture.level).phase());
        List<OutputAccounting.CollectedOutput> outputs = fixture.delegate.collectStructuredResults(mock(ServerPlayer.class));
        assertTrue(OutputAccounting.assess(fixture.delegate.outputContract(), 3, outputs).complete());
        assertEquals(6, outputs.get(0).stack().getCount());
        assertTrue(fixture.inventory.getItem(2).isEmpty());
        assertTrue(fixture.delegate.collectResult(mock(ServerPlayer.class)).isEmpty());
    }

    @Test
    void occupiedMachineAndInsufficientOrExcessMaterialsAreRejectedWithoutMutation() throws Exception {
        Fixture fixture = fixture(new CountedRecipe(64, 1, 1));
        assertFalse(startSingle(fixture, 63, 1));
        assertFalse(startSingle(fixture, 65, 1));
        assertTrue(fixture.inventory.isEmpty());
        fixture.inventory.setItem(2, new ItemStack(Items.APPLE));
        assertFalse(startSingle(fixture, 64, 1));
        assertEquals(Items.APPLE, fixture.inventory.getItem(2).getItem());
        fixture.delegate.clearMachineState(fixture.machine, null);
        assertEquals(Items.APPLE, fixture.inventory.getItem(2).getItem());
    }

    @Test
    void occupiedAltarRetriesPreparationBeforeMaterialsAreCommitted() throws Exception {
        Fixture fixture = fixture(new CountedRecipe(1, 1, 1));
        FleshAltarBatchDelegate delegate = spy(fixture.delegate);
        ServerPlayer player = mock(ServerPlayer.class);
        ResourceLocation id = new CountedRecipe(1, 1, 1).getId();
        doReturn(true).when(delegate).validateAndInit(player, id, null, BlockPos.ZERO);
        fixture.inventory.setItem(2, new ItemStack(Items.APPLE));
        assertEquals(PreparationState.RETRY, delegate.prepare(player, id, null, BlockPos.ZERO).state());
        assertEquals(Items.APPLE, fixture.inventory.getItem(2).getItem());
        fixture.inventory.setItem(2, ItemStack.EMPTY);
        assertEquals(PreparationState.READY, delegate.prepare(player, id, null, BlockPos.ZERO).state());
    }

    @Test
    void externalExtractionAndInjectedOutputCannotCompleteTheOperation() throws Exception {
        Fixture fixture = fixture(new CountedRecipe(2, 4, 1));
        assertTrue(startSingle(fixture, 2, 4));
        fixture.inventory.setItem(2, new ItemStack(Items.GOLD_INGOT));
        assertEquals(CraftPhase.FAILED, fixture.delegate.observeCraft(fixture.level).phase());
        fixture.inventory.setItem(2, ItemStack.EMPTY);
        fixture.inventory.setItem(0, ItemStack.EMPTY);
        assertEquals(CraftPhase.FAILED, fixture.delegate.observeCraft(fixture.level).phase());
    }

    @Test
    void cancellationRecoversOnlyRemainingOwnedInputsAndKeepsPartialOutput() throws Exception {
        Fixture fixture = fixture(new CountedRecipe(2, 4, 1));
        assertTrue(fixture.delegate.tryStartWithInputBuffer(mock(ServerPlayer.class),
                fixture.delegate.inputBufferPlan(3), new ExtractionLedger()));
        fixture.inventory.setItem(0, new ItemStack(Items.NETHERRACK, 4));
        fixture.inventory.setItem(1, new ItemStack(Items.DIAMOND, 8));
        fixture.inventory.setItem(2, new ItemStack(Items.GOLD_INGOT));
        fixture.delegate.clearMachineState(fixture.machine, null);
        assertEquals(List.of(4, 8), fixture.delegate.failureRecoveredInputs().stream().map(ItemStack::getCount).toList());
        assertTrue(fixture.inventory.getItem(0).isEmpty());
        assertTrue(fixture.inventory.getItem(1).isEmpty());
        assertEquals(1, fixture.inventory.getItem(2).getCount());
    }

    @Test
    void replacementOrUnloadedMachineDoesNotCollectOutput() throws Exception {
        Fixture fixture = fixture(new CountedRecipe(1, 1, 1));
        assertTrue(startSingle(fixture, 1, 1));
        when(fixture.level.getBlockEntity(BlockPos.ZERO)).thenReturn(mock(BlockEntity.class));
        assertEquals(CraftPhase.FAILED, fixture.delegate.observeCraft(fixture.level).phase());
        assertTrue(fixture.delegate.collectResult(mock(ServerPlayer.class)).isEmpty());
        when(fixture.level.hasChunkAt(BlockPos.ZERO)).thenReturn(false);
        assertEquals(CraftPhase.FAILED, fixture.delegate.observeCraft(fixture.level).phase());
    }

    @Test
    void remainderInputsUseSingleCyclesAndCollectPhysicalContainers() throws Exception {
        CountedRecipe recipe = new CountedRecipe(1, 1, 1);
        recipe.first = Ingredient.of(Items.MILK_BUCKET);
        Fixture fixture = fixture(recipe);
        assertFalse(fixture.delegate.supportsInputBuffer());
        assertTrue(fixture.delegate.tryStartWithMaterials(mock(ServerPlayer.class),
                List.of(new ItemStack(Items.MILK_BUCKET), new ItemStack(Items.DIAMOND)), new ExtractionLedger()));
        fixture.inventory.setItem(0, new ItemStack(Items.BUCKET));
        fixture.inventory.setItem(1, ItemStack.EMPTY);
        fixture.inventory.setItem(2, new ItemStack(Items.GOLD_INGOT));
        assertEquals(CraftPhase.DONE, fixture.delegate.observeCraft(fixture.level).phase());
        List<ItemStack> outputs = fixture.delegate.collectAllResults(mock(ServerPlayer.class));
        assertEquals(List.of(Items.GOLD_INGOT, Items.BUCKET), outputs.stream().map(ItemStack::getItem).toList());
        assertTrue(fixture.inventory.isEmpty());
    }

    private static boolean startSingle(Fixture fixture, int first, int second) {
        return fixture.delegate.tryStartWithMaterials(mock(ServerPlayer.class),
                List.of(new ItemStack(Items.NETHERRACK, first), new ItemStack(Items.DIAMOND, second)),
                new ExtractionLedger());
    }

    private static Fixture fixture(CountedRecipe recipe) throws Exception {
        FleshAltarBatchDelegate delegate = new FleshAltarBatchDelegate();
        ServerLevel level = mock(ServerLevel.class);
        BlockEntity machine = mock(BlockEntity.class);
        SimpleContainer inventory = new SimpleContainer(3);
        RecipeManager recipes = new RecipeManager();
        recipes.replaceRecipes(List.of(recipe));
        when(level.hasChunkAt(BlockPos.ZERO)).thenReturn(true);
        when(level.getBlockEntity(BlockPos.ZERO)).thenReturn(machine);
        when(level.getRecipeManager()).thenReturn(recipes);
        set(delegate, "level", level);
        set(delegate, "pos", BlockPos.ZERO);
        set(delegate, "machine", machine);
        set(delegate, "inventory", inventory);
        set(delegate, "recipe", recipe);
        set(delegate, "specs", HANDLER.getIngredients(recipe));
        set(delegate, "result", recipe.getResultItem(RegistryAccess.EMPTY));
        return new Fixture(delegate, level, machine, inventory);
    }

    private static void set(FleshAltarBatchDelegate delegate, String name, Object value) throws Exception {
        Field field = FleshAltarBatchDelegate.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(delegate, value);
    }

    private record Fixture(FleshAltarBatchDelegate delegate, ServerLevel level,
                           BlockEntity machine, SimpleContainer inventory) {}

    /** 与 1.3.0 公开配方接口一致的测试配方，无需加载可选模组的实体和注册器。 */
    public static final class CountedRecipe implements Recipe<Container> {
        private static final RecipeType<CountedRecipe> TYPE = new RecipeType<>() {};
        private Ingredient first = Ingredient.of(Items.NETHERRACK);
        private final Ingredient second = Ingredient.of(Items.DIAMOND);
        private final int firstCount;
        private final int secondCount;
        private final int outputCount;

        CountedRecipe(int firstCount, int secondCount, int outputCount) {
            this.firstCount = firstCount;
            this.secondCount = secondCount;
            this.outputCount = outputCount;
        }

        public int consumed(int slot, int order) { return slot == order ? firstCount : secondCount; }
        public int getCraftingTime() { return 60; }
        @Override public boolean matches(Container container, Level level) {
            return first.test(container.getItem(0)) && container.getItem(0).getCount() >= firstCount
                    && second.test(container.getItem(1)) && container.getItem(1).getCount() >= secondCount;
        }
        @Override public ItemStack assemble(Container container, RegistryAccess access) { return getResultItem(access); }
        @Override public boolean canCraftInDimensions(int width, int height) { return width * height >= 2; }
        @Override public ItemStack getResultItem(RegistryAccess access) { return new ItemStack(Items.GOLD_INGOT, outputCount); }
        @Override public NonNullList<Ingredient> getIngredients() { return NonNullList.of(Ingredient.EMPTY, first, second); }
        @Override public ResourceLocation getId() { return new ResourceLocation("cthulhu_creatures", "test_altar"); }
        @Override public RecipeSerializer<?> getSerializer() { return RecipeSerializer.SHAPELESS_RECIPE; }
        @Override public RecipeType<?> getType() { return TYPE; }
    }
}
