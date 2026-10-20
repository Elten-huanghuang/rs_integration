package com.huanghuang.rsintegration.crafting;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.huanghuang.rsintegration.util.CraftLogContext;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class StableRecipeBatchTest extends BootstrapTest {

    @BeforeAll
    static void configureEnvironment() {
        CommentedConfig config = CommentedConfig.inMemory();
        RSIntegrationConfig.SERVER_SPEC.correct(config);
        RSIntegrationConfig.SERVER_SPEC.setConfig(config);
        FMLJavaModLoadingContext context = mock(FMLJavaModLoadingContext.class);
        when(context.getModEventBus()).thenReturn(mock(IEventBus.class));
        try (var loading = mockStatic(FMLJavaModLoadingContext.class)) {
            loading.when(FMLJavaModLoadingContext::get).thenReturn(context);
            assertNotNull(RSIntegrationMod.LOGGER);
        }
    }

    @Test
    void batchesConsecutiveCompressionStepsWithLongIntermediateCounts() {
        ResourceLocation tier1 = new ResourceLocation("test", "compressed_stone_1");
        ResourceLocation tier2 = new ResourceLocation("test", "compressed_stone_2");
        ResourceLocation tier3 = new ResourceLocation("test", "compressed_stone_3");
        ShapedRecipe first = compressionRecipe(tier1, Items.COBBLESTONE, Items.STONE);
        ShapedRecipe second = compressionRecipe(tier2, Items.STONE, Items.DEEPSLATE);
        ShapedRecipe third = compressionRecipe(tier3, Items.DEEPSLATE, Items.BEDROCK);
        Fixture fixture = fixture(Map.of(tier1, first, tier2, second, tier3, third));

        List<ItemStack> inventory = new ArrayList<>(List.of(new ItemStack(Items.COBBLESTONE, 729)));
        try (ExtractionLedger ledger = new ExtractionLedger()) {
            assertEquals(StableRecipeBatch.Outcome.SUCCESS, execute(
                    fixture, tier1, 81, inventory, ledger));
            assertEquals(StableRecipeBatch.Outcome.SUCCESS, execute(
                    fixture, tier2, 9, inventory, ledger));
            assertEquals(StableRecipeBatch.Outcome.SUCCESS, execute(
                    fixture, tier3, 1, inventory, ledger));
        }

        assertEquals(1, inventory.size());
        assertTrue(inventory.get(0).is(Items.BEDROCK));
        assertEquals(1, inventory.get(0).getCount());
    }

    @Test
    void keepsLargeIntermediateOutputAsOneAggregatedVirtualStack() {
        ResourceLocation tier1 = new ResourceLocation("test", "large_compressed_stone");
        ShapedRecipe recipe = compressionRecipe(tier1, Items.COBBLESTONE, Items.STONE);
        Fixture fixture = fixture(Map.of(tier1, recipe));
        List<ItemStack> inventory = new ArrayList<>(
                List.of(new ItemStack(Items.COBBLESTONE, 9000)));

        try (ExtractionLedger ledger = new ExtractionLedger()) {
            assertEquals(StableRecipeBatch.Outcome.SUCCESS, execute(
                    fixture, tier1, 1000, inventory, ledger));
        }

        assertEquals(1, inventory.size());
        assertTrue(inventory.get(0).is(Items.STONE));
        assertEquals(1000, inventory.get(0).getCount());
    }

    @Test
    void preservesMultiItemRecipeYieldAcrossEveryWoodPlankCraft() {
        ResourceLocation recipeId = new ResourceLocation("test", "test_planks");
        NonNullList<Ingredient> ingredients =
                NonNullList.withSize(9, Ingredient.EMPTY);
        ingredients.set(0, Ingredient.of(Items.OAK_LOG));
        ShapelessRecipe recipe = new ShapelessRecipe(
                recipeId, "", CraftingBookCategory.MISC,
                new ItemStack(Items.OAK_PLANKS, 4),
                ingredients);
        Fixture fixture = fixture(Map.of(recipeId, recipe));
        List<ItemStack> inventory = new ArrayList<>(
                List.of(new ItemStack(Items.OAK_LOG, 3)));

        try (ExtractionLedger ledger = new ExtractionLedger()) {
            assertEquals(StableRecipeBatch.Outcome.SUCCESS, execute(
                    fixture, recipeId, 3, inventory, ledger));
        }

        assertEquals(1, inventory.size());
        assertTrue(inventory.get(0).is(Items.OAK_PLANKS));
        assertEquals(12, inventory.get(0).getCount());
    }

    @Test
    void finalTargetDoesNotDisableStableBatchingForUpstreamTiers() {
        ResourceLocation tier1 = new ResourceLocation("test", "target_chain_1");
        ResourceLocation tier2 = new ResourceLocation("test", "target_chain_2");
        ShapedRecipe first = compressionRecipe(tier1, Items.COBBLESTONE, Items.STONE);
        ShapedRecipe second = compressionRecipe(tier2, Items.STONE, Items.DEEPSLATE);
        Fixture fixture = fixture(Map.of(tier1, first, tier2, second));
        CraftingResolver.ResolutionStep firstStep = new CraftingResolver.ResolutionStep(
                tier1, ModType.GENERIC, new ResourceLocation("minecraft", "crafting"),
                List.of(), List.of(), false, 81);
        CraftingResolver.ResolutionStep secondStep = new CraftingResolver.ResolutionStep(
                tier2, ModType.GENERIC, new ResourceLocation("minecraft", "crafting"),
                List.of(), List.of(), false, 9);
        ItemStack target = new ItemStack(Items.DEEPSLATE);
        FlatCraftExecutor.Host host = mock(FlatCraftExecutor.Host.class);
        FlatCraftExecutor.Context context = new FlatCraftExecutor.Context(
                fixture.level(), CraftLogContext.create(UUID.randomUUID(), tier2),
                List.of(firstStep, secondStep), target, host, Map.of());
        List<ItemStack> inventory = new ArrayList<>(
                List.of(new ItemStack(Items.COBBLESTONE, 729)));

        assertTrue(StableRecipeBatch.isEligible(
                fixture.level(), firstStep, target, Map.of()));
        try (ExtractionLedger ledger = new ExtractionLedger()) {
            assertEquals(StableRecipeBatch.Outcome.SUCCESS,
                    StableRecipeBatch.tryExecute(fixture.level(), firstStep,
                            fixture.player(), inventory, ledger, context));
            assertEquals(StableRecipeBatch.Outcome.SUCCESS,
                    StableRecipeBatch.tryExecute(fixture.level(), secondStep,
                            fixture.player(), inventory, ledger, context));
        }

        assertEquals(1, inventory.size());
        assertTrue(inventory.get(0).is(Items.DEEPSLATE));
        assertEquals(9, inventory.get(0).getCount());
    }

    @Test
    void rejectsCraftingRecipeSubclassesUsedByDynamicRecipeSystems() {
        ResourceLocation recipeId = new ResourceLocation("test", "dynamic_recipe");
        ShapelessRecipe dynamicRecipe = new ShapelessRecipe(
                recipeId, "", CraftingBookCategory.MISC, new ItemStack(Items.STONE),
                NonNullList.withSize(9, Ingredient.of(Items.COBBLESTONE))) {
            @Override
            public ItemStack assemble(CraftingContainer container,
                                      RegistryAccess registryAccess) {
                return new ItemStack(Items.STONE);
            }
        };
        Fixture fixture = fixture(Map.of(recipeId, dynamicRecipe));
        List<ItemStack> inventory = new ArrayList<>(
                List.of(new ItemStack(Items.COBBLESTONE, 9)));

        try (ExtractionLedger ledger = new ExtractionLedger()) {
            assertEquals(StableRecipeBatch.Outcome.NOT_APPLICABLE, execute(
                    fixture, recipeId, 1, inventory, ledger));
        }
        assertEquals(9, inventory.get(0).getCount());
    }

    @Test
    void statefulToolRemainderFallsBackToPerExecutionDamage() {
        ResourceLocation recipeId = new ResourceLocation("test", "stateful_tool_recipe");
        StatefulToolRecipe recipe = new StatefulToolRecipe(recipeId);
        Fixture fixture = fixture(Map.of(recipeId, recipe));
        List<ItemStack> inventory = new ArrayList<>(
                List.of(new ItemStack(Items.IRON_SWORD)));

        try (ExtractionLedger ledger = new ExtractionLedger()) {
            assertEquals(StableRecipeBatch.Outcome.NOT_APPLICABLE, execute(
                    fixture, recipeId, 64, inventory, ledger));
        }

        assertEquals(1, inventory.size());
        assertTrue(inventory.get(0).is(Items.IRON_SWORD));
        assertEquals(0, inventory.get(0).getDamageValue());
    }

    @Test
    void acceptsExplicitlyDeclaredStableRecipeImplementations() {
        ResourceLocation recipeId = new ResourceLocation("test", "opted_in_compression");
        OptedInRecipe recipe = new OptedInRecipe(recipeId);
        Fixture fixture = fixture(Map.of(recipeId, recipe));
        List<ItemStack> inventory = new ArrayList<>(
                List.of(new ItemStack(Items.COBBLESTONE, 90)));

        try (ExtractionLedger ledger = new ExtractionLedger()) {
            assertEquals(StableRecipeBatch.Outcome.SUCCESS, execute(
                    fixture, recipeId, 10, inventory, ledger));
        }
        assertTrue(inventory.stream().anyMatch(stack ->
                stack.is(Items.STONE) && stack.getCount() == 10));
        assertEquals(1, recipe.assembleCalls());
    }

    @Test
    void choosesOneConcreteVariantForTagLikeIngredients() {
        ResourceLocation recipeId = new ResourceLocation("test", "alternative_compression");
        ShapelessRecipe recipe = new ShapelessRecipe(
                recipeId, "", CraftingBookCategory.MISC, new ItemStack(Items.DEEPSLATE),
                NonNullList.withSize(9, Ingredient.of(Items.COBBLESTONE, Items.STONE)));
        Fixture fixture = fixture(Map.of(recipeId, recipe));
        List<ItemStack> inventory = new ArrayList<>(
                List.of(new ItemStack(Items.STONE, 90)));

        try (ExtractionLedger ledger = new ExtractionLedger()) {
            assertEquals(StableRecipeBatch.Outcome.SUCCESS, execute(
                    fixture, recipeId, 10, inventory, ledger));
        }
        assertTrue(inventory.stream().anyMatch(stack ->
                stack.is(Items.DEEPSLATE) && stack.getCount() == 10));
        assertTrue(inventory.stream().noneMatch(stack -> stack.is(Items.STONE)));
    }

    @Test
    void canCombineConcreteVariantsForTagLikeIngredients() {
        ResourceLocation recipeId = new ResourceLocation("test", "mixed_alternative_compression");
        ShapelessRecipe recipe = new ShapelessRecipe(
                recipeId, "", CraftingBookCategory.MISC, new ItemStack(Items.DEEPSLATE),
                NonNullList.withSize(9, Ingredient.of(Items.COBBLESTONE, Items.STONE)));
        Fixture fixture = fixture(Map.of(recipeId, recipe));
        List<ItemStack> inventory = new ArrayList<>(List.of(
                new ItemStack(Items.COBBLESTONE, 45),
                new ItemStack(Items.STONE, 45)));

        try (ExtractionLedger ledger = new ExtractionLedger()) {
            assertEquals(StableRecipeBatch.Outcome.SUCCESS, execute(
                    fixture, recipeId, 10, inventory, ledger));
        }
        assertEquals(1, inventory.size());
        assertTrue(inventory.get(0).is(Items.DEEPSLATE));
        assertEquals(10, inventory.get(0).getCount());
    }

    @Test
    void flatExecutorUsesStableBatchPathInsteadOfPerCraftLoop() {
        ResourceLocation recipeId = new ResourceLocation("test", "executor_compression");
        ShapedRecipe recipe = compressionRecipe(recipeId, Items.COBBLESTONE, Items.STONE);
        Fixture fixture = fixture(Map.of(recipeId, recipe));
        CraftingResolver.ResolutionStep step = new CraftingResolver.ResolutionStep(
                recipeId, ModType.GENERIC, new ResourceLocation("minecraft", "crafting"),
                List.of(), List.of(), false, 1000);
        List<ItemStack> inventory = new ArrayList<>(
                List.of(new ItemStack(Items.COBBLESTONE, 9000)));
        FlatCraftExecutor.Host host = mock(FlatCraftExecutor.Host.class);
        FlatCraftExecutor.Context context = new FlatCraftExecutor.Context(
                fixture.level(), CraftLogContext.create(UUID.randomUUID(), recipeId),
                List.of(step), null, host, Map.of());

        try (ExtractionLedger ledger = new ExtractionLedger()) {
            assertTrue(FlatCraftExecutor.execute(
                    List.of(step), fixture.player(), inventory, ledger, true, context));
        }
        assertEquals(1, inventory.size());
        assertEquals(1000, inventory.get(0).getCount());
        assertTrue(inventory.get(0).is(Items.STONE));
    }

    @Test
    void preservesEmptyShapedRecipeSlotsDuringBatchAssembly() {
        ResourceLocation recipeId = new ResourceLocation("test", "sparse_compression");
        NonNullList<Ingredient> ingredients = NonNullList.of(
                Ingredient.EMPTY,
                Ingredient.of(Items.COBBLESTONE),
                Ingredient.EMPTY);
        ShapedRecipe recipe = new ShapedRecipe(
                recipeId, "", CraftingBookCategory.MISC, 3, 1,
                ingredients, new ItemStack(Items.STONE));
        Fixture fixture = fixture(Map.of(recipeId, recipe));
        List<ItemStack> inventory = new ArrayList<>(
                List.of(new ItemStack(Items.COBBLESTONE, 300)));

        try (ExtractionLedger ledger = new ExtractionLedger()) {
            assertEquals(StableRecipeBatch.Outcome.SUCCESS, execute(
                    fixture, recipeId, 100, inventory, ledger));
        }
        assertEquals(2, inventory.size());
        assertTrue(inventory.stream().anyMatch(stack ->
                stack.is(Items.STONE) && stack.getCount() == 100));
        assertTrue(inventory.stream().anyMatch(stack ->
                stack.is(Items.COBBLESTONE) && stack.getCount() == 200));
    }

    private static StableRecipeBatch.Outcome execute(
            Fixture fixture, ResourceLocation recipeId, int executions,
            List<ItemStack> inventory, ExtractionLedger ledger) {
        CraftingResolver.ResolutionStep step = new CraftingResolver.ResolutionStep(
                recipeId, ModType.GENERIC, new ResourceLocation("minecraft", "crafting"),
                List.of(), List.of(), false, executions);
        FlatCraftExecutor.Host host = mock(FlatCraftExecutor.Host.class);
        FlatCraftExecutor.Context context = new FlatCraftExecutor.Context(
                fixture.level(), CraftLogContext.create(UUID.randomUUID(), recipeId),
                List.of(step), null, host, Map.of());
        return StableRecipeBatch.tryExecute(
                fixture.level(), step, fixture.player(), inventory, ledger, context);
    }

    private static Fixture fixture(
            Map<? extends ResourceLocation, ? extends CraftingRecipe> recipes) {
        ServerLevel level = mock(ServerLevel.class);
        RecipeManager recipesManager = mock(RecipeManager.class);
        when(level.getRecipeManager()).thenReturn(recipesManager);
        when(level.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        recipes.forEach((id, recipe) ->
                doReturn(Optional.of(recipe)).when(recipesManager).byKey(id));

        ServerPlayer player = mock(ServerPlayer.class);
        when(player.getInventory()).thenReturn(new Inventory(mock(Player.class)));
        return new Fixture(level, player);
    }

    private static ShapedRecipe compressionRecipe(ResourceLocation id,
                                                  Item input,
                                                  Item output) {
        NonNullList<Ingredient> ingredients =
                NonNullList.withSize(9, Ingredient.of(input));
        return new ShapedRecipe(id, "", CraftingBookCategory.MISC, 3, 3,
                ingredients, new ItemStack(output));
    }

    private record Fixture(ServerLevel level, ServerPlayer player) {}

    private static final class OptedInRecipe extends ShapelessRecipe
            implements StableBatchRecipe {
        private int assembleCalls;

        private OptedInRecipe(ResourceLocation id) {
            super(id, "", CraftingBookCategory.MISC, new ItemStack(Items.STONE),
                    NonNullList.withSize(9, Ingredient.of(Items.COBBLESTONE)));
        }

        @Override
        public ItemStack assemble(CraftingContainer container, RegistryAccess registryAccess) {
            assembleCalls++;
            return super.assemble(container, registryAccess);
        }

        private int assembleCalls() {
            return assembleCalls;
        }
    }

    private static final class StatefulToolRecipe extends ShapelessRecipe
            implements StableBatchRecipe {
        private StatefulToolRecipe(ResourceLocation id) {
            super(id, "", CraftingBookCategory.MISC, new ItemStack(Items.STONE),
                    NonNullList.of(Ingredient.of(Items.IRON_SWORD)));
        }

        @Override
        public NonNullList<ItemStack> getRemainingItems(CraftingContainer container) {
            NonNullList<ItemStack> remainders =
                    NonNullList.withSize(container.getContainerSize(), ItemStack.EMPTY);
            ItemStack tool = container.getItem(0).copy();
            if (!tool.isEmpty()) tool.setDamageValue(tool.getDamageValue() + 1);
            remainders.set(0, tool);
            return remainders;
        }
    }
}
