package com.huanghuang.rsintegration.mods.embers;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.DirectMaterialAllocator;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.batch.MaterialPlan;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.reflection.probes.EmbersReflection;
import com.huanghuang.rsintegration.storage.StorageOperationResult;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.rekindled.embers.recipe.AlchemyRecipe;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.items.ItemStackHandler;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

class EreAlchemyRecipeHandlerTest extends BootstrapTest {

    @BeforeAll
    static void configureEnvironment() {
        CommentedConfig config = CommentedConfig.inMemory();
        RSIntegrationConfig.SERVER_SPEC.correct(config);
        RSIntegrationConfig.SERVER_SPEC.setConfig(config);
        CommentedConfig common = CommentedConfig.inMemory();
        RSIntegrationConfig.COMMON_SPEC.correct(common);
        RSIntegrationConfig.COMMON_SPEC.setConfig(common);
        FMLJavaModLoadingContext context = mock(FMLJavaModLoadingContext.class);
        when(context.getModEventBus()).thenReturn(mock(IEventBus.class));
        try (var loading = mockStatic(FMLJavaModLoadingContext.class)) {
            loading.when(FMLJavaModLoadingContext::get).thenReturn(context);
            assertNotNull(RSIntegrationMod.LOGGER);
        }
    }

    @Test
    void exposesRecursiveInputsAndCountedOutputForRealAlchemyRecipe() {
        ArrayList<Ingredient> aspects = new ArrayList<>();
        aspects.add(Ingredient.of(Items.COAL));
        ArrayList<Ingredient> inputs = new ArrayList<>();
        inputs.add(Ingredient.of(Items.COPPER_INGOT));
        inputs.add(Ingredient.of(Items.REDSTONE));
        inputs.add(Ingredient.of(Items.COPPER_INGOT));
        inputs.add(Ingredient.of(Items.REDSTONE));
        AlchemyRecipe recipe = new AlchemyRecipe(
                new ResourceLocation("embers", "alchemy/intelligent_apparatus"),
                Ingredient.of(Items.COPPER_INGOT),
                aspects,
                inputs,
                new ItemStack(Items.CLAY_BALL, 4),
                ItemStack.EMPTY);
        EreAlchemyRecipeHandler handler = new EreAlchemyRecipeHandler();
        EreAlchemyRSModule.INSTANCE.registerModType();

        assertTrue(handler.canHandle(recipe));
        assertSame(handler.modType(), ModType.classifyRecipe(recipe));
        ItemStack output = handler.getResultItem(recipe, RegistryAccess.EMPTY);
        assertEquals(Items.CLAY_BALL, output.getItem());
        assertEquals(4, output.getCount());
        List<IngredientSpec> specs = handler.getIngredients(recipe);
        assertNotNull(specs);
        assertEquals(5, specs.size());
    }

    @Test
    void executionMaterialsDeclareSelectedAspectsAsReusableCatalysts() {
        EreAlchemyMaterials materials = EreAlchemyMaterials.create(
                Ingredient.of(Items.COPPER_INGOT),
                List.of(Ingredient.of(Items.DIAMOND), Ingredient.of(Items.GOLD_INGOT)),
                List.of(Ingredient.of(Items.CLAY_BALL), Ingredient.of(Items.REDSTONE)));

        assertEquals(5, materials.allSpecs().size());
        assertTrue(materials.supplementalSpecs().isEmpty());
        assertEquals(DemandRole.CATALYST, materials.allSpecs().get(1).role());
        assertEquals(DemandRole.CONSUMED, materials.allSpecs().get(2).role());
        assertEquals(DemandRole.CATALYST, materials.allSpecs().get(3).role());
        assertEquals(DemandRole.CONSUMED, materials.allSpecs().get(4).role());
    }

    @Test
    void adhesivePlanningIncludesTheSameCatalystsAsExecutionWithoutMultiplyingThem() {
        AlchemyRecipe recipe = adhesiveRecipe();
        EreAlchemyRecipeHandler handler = new EreAlchemyRecipeHandler();
        ServerPlayer player = mock(ServerPlayer.class);
        ServerLevel level = mock(ServerLevel.class);
        when(player.serverLevel()).thenReturn(level);
        when(level.getSeed()).thenReturn(42L);
        boolean original = RSIntegrationConfig.ENABLE_EMBERS_ALCHEMY_CALC.get();
        RSIntegrationConfig.ENABLE_EMBERS_ALCHEMY_CALC.set(true);
        try (var saved = mockStatic(KnownCodeSavedData.class)) {
            saved.when(() -> KnownCodeSavedData.get(level))
                    .thenReturn(mock(KnownCodeSavedData.class));
            List<IngredientSpec> planned = handler.getPlanningIngredients(
                    recipe, player, handler.getIngredients(recipe));
            assertEquals(5, planned.size());
            assertEquals(2, planned.stream().filter(s -> s.role() == DemandRole.CATALYST).count());
            List<IngredientSpec> batch = planned.stream()
                    .map(s -> new IngredientSpec(s.ingredient(),
                            CraftPacketUtils.requiredCount(s, 4), s.consumption()))
                    .toList();
            List<Ingredient> selected = recipe.getCode(42L);
            Map<StackKey, Integer> stock = Map.of(
                    new StackKey(Items.CLAY_BALL, null), 4,
                    new StackKey(Items.BONE_MEAL, null), 8,
                    new StackKey(Items.GOLD_INGOT, null), 2);
            assertTrue(DirectMaterialAllocator.allocate(batch, stock).feasible());
            assertFalse(DirectMaterialAllocator.allocate(batch, Map.of(
                    new StackKey(Items.CLAY_BALL, null), 4,
                    new StackKey(Items.BONE_MEAL, null), 8)).feasible());
            EreAlchemyMaterials execution = EreAlchemyMaterials.create(
                    recipe.tablet, selected, recipe.inputs);
            assertTrue(DirectMaterialAllocator.allocate(execution.graphSpecs(), stock).feasible());
            assertSame(planned, handler.getPlanningIngredients(recipe, player, planned));
        } finally {
            RSIntegrationConfig.ENABLE_EMBERS_ALCHEMY_CALC.set(original);
        }
    }

    @Test
    void inferencePlansUseOnlyDiscoveredCodes() {
        AlchemyRecipe recipe = adhesiveRecipe();
        EreAlchemyRecipeHandler handler = new EreAlchemyRecipeHandler();
        ServerPlayer player = mock(ServerPlayer.class);
        ServerLevel level = mock(ServerLevel.class);
        KnownCodeSavedData codes = mock(KnownCodeSavedData.class);
        when(player.serverLevel()).thenReturn(level);
        boolean original = RSIntegrationConfig.ENABLE_EMBERS_ALCHEMY_CALC.get();
        RSIntegrationConfig.ENABLE_EMBERS_ALCHEMY_CALC.set(false);
        try (var saved = mockStatic(KnownCodeSavedData.class)) {
            saved.when(() -> KnownCodeSavedData.get(level)).thenReturn(codes);
            List<IngredientSpec> declared = handler.getIngredients(recipe);
            assertSame(declared, handler.getPlanningIngredients(recipe, player, declared));
            when(codes.getCode(recipe.getId().toString())).thenReturn(new int[]{0, 0});
            List<IngredientSpec> known = handler.getPlanningIngredients(recipe, player, declared);
            assertEquals(5, known.size());
            assertEquals(2, known.stream().filter(s -> s.role() == DemandRole.CATALYST).count());
            when(codes.getCode(recipe.getId().toString())).thenReturn(new int[]{0, 2});
            assertSame(declared, handler.getPlanningIngredients(recipe, player, declared));
        } finally {
            RSIntegrationConfig.ENABLE_EMBERS_ALCHEMY_CALC.set(original);
        }
    }

    @Test
    void unknownInferenceReservesGuessedAspectsOutsideTheStableGraphAndKeepsPlacementOrder() {
        AlchemyRecipe recipe = adhesiveRecipe();
        EreAlchemyMaterials materials = EreAlchemyMaterials.createInference(
                recipe.tablet, List.of(Ingredient.of(Items.GOLD_INGOT),
                        Ingredient.of(Items.IRON_INGOT)), recipe.inputs);
        assertEquals(3, materials.graphSpecs().size());
        assertEquals(2, materials.supplementalSpecs().size());
        assertTrue(materials.supplementalSpecs().stream()
                .allMatch(s -> s.role() == DemandRole.CATALYST));
        MaterialPlan plan = MaterialPlan.fromLegacyPartitions(
                materials.allSpecs(), materials.graphSpecs(), materials.supplementalSpecs());
        List<ItemStack> graph = List.of(new ItemStack(Items.CLAY_BALL),
                new ItemStack(Items.BONE_MEAL), new ItemStack(Items.BONE_MEAL));
        List<ItemStack> supplemental = List.of(new ItemStack(Items.GOLD_INGOT),
                new ItemStack(Items.IRON_INGOT));
        assertEquals(List.of(Items.CLAY_BALL, Items.GOLD_INGOT, Items.BONE_MEAL,
                Items.IRON_INGOT, Items.BONE_MEAL),
                plan.mergeLegacyReservations(graph, supplemental).stream()
                        .map(ItemStack::getItem).toList());
    }

    @Test
    void twoAshenFabricOperationsNeedOnlyTwoCopperAndTwoIronCatalysts() {
        EreAlchemyMaterials materials = EreAlchemyMaterials.create(
                Ingredient.of(Items.CLAY_BALL),
                List.of(Ingredient.of(Items.COPPER_INGOT), Ingredient.of(Items.IRON_INGOT),
                        Ingredient.of(Items.COPPER_INGOT), Ingredient.of(Items.IRON_INGOT)),
                List.of(Ingredient.of(Items.BONE_MEAL), Ingredient.of(Items.BONE_MEAL),
                        Ingredient.of(Items.BONE_MEAL), Ingredient.of(Items.BONE_MEAL)));
        List<IngredientSpec> twoOperations = materials.graphSpecs().stream()
                .map(s -> new IngredientSpec(s.ingredient(),
                        CraftPacketUtils.requiredCount(s, 2), s.consumption()))
                .toList();
        assertTrue(DirectMaterialAllocator.allocate(twoOperations, Map.of(
                new StackKey(Items.CLAY_BALL, null), 2,
                new StackKey(Items.BONE_MEAL, null), 8,
                new StackKey(Items.COPPER_INGOT, null), 2,
                new StackKey(Items.IRON_INGOT, null), 2)).feasible());
    }

    @Test
    void successiveOperationsHandOffActualCatalystsAndReturnThemOnlyOnce() throws Exception {
        try (AlchemyFixture fixture = new AlchemyFixture();
             var ignition = mockStatic(EmbersBeamCannonIgnition.class)) {
            ignition.when(() -> EmbersBeamCannonIgnition.ignite(
                    fixture.level, fixture.pos, fixture.tablet))
                    .thenReturn(EmbersBeamCannonIgnition.Result.STARTED);
            EreAlchemyBatchDelegate first = fixture.delegate();
            fixture.completeOperation();
            assertFalse(first.collectResult(fixture.player).isEmpty());
            verify(fixture.endpoint, never()).insert(eq(fixture.player), any(), eq(false));

            EreAlchemyBatchDelegate next = fixture.delegate();
            assertTrue(first.transferReusableMaterialsTo(next));
            assertTrue(first.collectReusableMaterials(fixture.player).isEmpty());
            assertTrue(next.startPreparedOperation(fixture.player, List.of(
                    new ItemStack(Items.CLAY_BALL), new ItemStack(Items.GOLD_INGOT),
                    new ItemStack(Items.BONE_MEAL), new ItemStack(Items.GOLD_INGOT),
                    new ItemStack(Items.BONE_MEAL)), new ExtractionLedger()));
            for (InventoryBlockEntity bottom : fixture.bottoms) {
                assertEquals("actual", bottom.inventory.getStackInSlot(0)
                        .getTag().getString("owner"));
            }
            fixture.completeOperation();
            assertFalse(next.collectResult(fixture.player).isEmpty());
            List<ItemStack> recovered = next.collectReusableMaterials(fixture.player);
            assertEquals(2, recovered.size());
            assertTrue(recovered.stream().allMatch(stack ->
                    "actual".equals(stack.getTag().getString("owner"))));
            assertTrue(next.collectReusableMaterials(fixture.player).isEmpty());
            verify(fixture.endpoint, never()).insert(eq(fixture.player), any(), eq(false));
        }
    }

    @Test
    void cancellationAfterHandoffRefundsOnlyTheTwoActualCatalysts() throws Exception {
        try (AlchemyFixture fixture = new AlchemyFixture()) {
            EreAlchemyBatchDelegate first = fixture.delegate();
            fixture.completeOperation();
            first.collectResult(fixture.player);
            EreAlchemyBatchDelegate next = fixture.delegate();
            assertTrue(first.transferReusableMaterialsTo(next));
            next.clearMachineState(fixture.tablet, fixture.player);
            next.clearMachineState(fixture.tablet, fixture.player);
            verify(fixture.endpoint, times(2)).insert(eq(fixture.player), any(), eq(false));
            assertTrue(first.collectReusableMaterials(fixture.player).isEmpty());
            assertTrue(next.collectReusableMaterials(fixture.player).isEmpty());
        }
    }

    @Test
    void aMissingCatalystCannotBeRecreatedFromTheNextOperationTemplate() throws Exception {
        try (AlchemyFixture fixture = new AlchemyFixture()) {
            EreAlchemyBatchDelegate first = fixture.delegate();
            fixture.bottoms.get(0).inventory.setStackInSlot(0, ItemStack.EMPTY);
            fixture.completeOperation();
            first.collectResult(fixture.player);
            assertFalse(first.transferReusableMaterialsTo(fixture.delegate()));
            assertEquals(1, first.collectReusableMaterials(fixture.player).size());
        }
    }

    private static AlchemyRecipe adhesiveRecipe() {
        return new AlchemyRecipe(new ResourceLocation("embers", "alchemy/adhesive"),
                Ingredient.of(Items.CLAY_BALL),
                new ArrayList<>(List.of(Ingredient.of(Items.GOLD_INGOT))),
                new ArrayList<>(List.of(Ingredient.of(Items.BONE_MEAL),
                        Ingredient.of(Items.BONE_MEAL))),
                new ItemStack(Items.SLIME_BALL), ItemStack.EMPTY);
    }

    private static final class AlchemyFixture implements AutoCloseable {
        final ServerLevel level = mock(ServerLevel.class);
        final ServerPlayer player = mock(ServerPlayer.class);
        final CraftStorageEndpoint endpoint = mock(CraftStorageEndpoint.class);
        final BlockPos pos = new BlockPos(0, 64, 0);
        final InventoryBlockEntity tablet = new InventoryBlockEntity(pos);
        final List<InventoryBlockEntity> tops = List.of(
                new InventoryBlockEntity(pos.offset(1, 1, 0)),
                new InventoryBlockEntity(pos.offset(-1, 1, 0)));
        final List<InventoryBlockEntity> bottoms = tops.stream()
                .map(top -> new InventoryBlockEntity(top.getBlockPos().below())).toList();
        final AlchemyRecipe recipe = adhesiveRecipe();
        final Class<?> previousPedestalClass = EmbersReflection.alchemyPedestalBEClass;

        AlchemyFixture() {
            EmbersReflection.alchemyPedestalBEClass = InventoryBlockEntity.class;
            when(level.isLoaded(any(BlockPos.class))).thenReturn(true);
            when(level.getBlockEntity(pos)).thenReturn(tablet);
            for (InventoryBlockEntity bottom : bottoms) {
                when(level.getBlockEntity(bottom.getBlockPos())).thenReturn(bottom);
                ItemStack aspect = new ItemStack(Items.GOLD_INGOT);
                aspect.getOrCreateTag().putString("owner", "actual");
                bottom.inventory.setStackInSlot(0, aspect);
            }
            StorageOperationResult inserted = mock(StorageOperationResult.class);
            when(inserted.remainder()).thenReturn(Optional.of(ItemStack.EMPTY));
            when(endpoint.insert(eq(player), any(), eq(false))).thenReturn(inserted);
        }

        EreAlchemyBatchDelegate delegate() throws Exception {
            EreAlchemyBatchDelegate delegate = new EreAlchemyBatchDelegate();
            set(delegate, "level", level);
            set(delegate, "player", player);
            set(delegate, "machinePos", pos);
            set(delegate, "recipe", recipe);
            set(delegate, "code", recipe.getCode(0L));
            set(delegate, "tablet", tablet);
            set(delegate, "pedestals", tops.stream().map(top ->
                    new EreAlchemyBatchDelegate.PedestalInfo(top.getBlockPos(), top)).toList());
            set(delegate, "materials", EreAlchemyMaterials.create(
                    recipe.tablet, recipe.getCode(0L), recipe.inputs));
            delegate.setStorageEndpoint(endpoint);
            return delegate;
        }

        void completeOperation() {
            tablet.inventory.setStackInSlot(0, ItemStack.EMPTY);
            tops.forEach(top -> top.inventory.setStackInSlot(0, ItemStack.EMPTY));
            tablet.outputHandler.setStackInSlot(0, new ItemStack(Items.SLIME_BALL));
        }

        @Override
        public void close() {
            EmbersReflection.alchemyPedestalBEClass = previousPedestalClass;
        }
    }

    private static final class InventoryBlockEntity extends BlockEntity {
        public final ItemStackHandler inventory = new ItemStackHandler(1);
        public final ItemStackHandler outputHandler = new ItemStackHandler(1);

        InventoryBlockEntity(BlockPos pos) {
            super(BlockEntityType.FURNACE, pos, Blocks.FURNACE.defaultBlockState());
        }
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = EreAlchemyBatchDelegate.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
