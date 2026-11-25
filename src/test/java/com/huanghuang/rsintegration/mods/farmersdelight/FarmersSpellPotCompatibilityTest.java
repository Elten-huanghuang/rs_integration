package com.huanghuang.rsintegration.mods.farmersdelight;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.DirectMaterialAllocator;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientMatcher;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.storage.StorageOperationMode;
import com.huanghuang.rsintegration.storage.StorageOperationResult;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
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
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.items.ItemStackHandler;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import vectorwing.farmersdelight.common.crafting.CookingPotRecipe;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class FarmersSpellPotCompatibilityTest extends BootstrapTest {
    private static final BlockPos POS = new BlockPos(1, 64, 2);
    private static final ResourceLocation SCHOOL = ResourceLocation.parse("irons_spellbooks:fire");
    private static final ResourceLocation RECIPE = ResourceLocation.parse("farmers_spell:test");

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
    void planningListsOneSchoolCatalystBeforeTheServingContainers() {
        Fixture fixture = new Fixture();
        try (var support = mockStatic(FarmersSpellPotSupport.class, CALLS_REAL_METHODS)) {
            support.when(() -> FarmersSpellPotSupport.isRecipe(fixture.recipe)).thenReturn(true);
            support.when(() -> FarmersSpellPotSupport.requiredSchool(fixture.recipe)).thenReturn(SCHOOL);
            support.when(() -> FarmersSpellPotSupport.scrollSpec(SCHOOL)).thenReturn(fixture.scrollSpec);
            var handler = new FarmersDelightRecipeHandler();
            var specs = handler.getIngredients(fixture.recipe);

            assertEquals(3, specs.size());
            assertEquals(DemandRole.CATALYST, specs.get(1).role());
            assertEquals(1, specs.get(1).count());
            ItemStack alternateScroll = new ItemStack(Items.PAPER);
            alternateScroll.getOrCreateTag().putInt("level", 5);
            assertTrue(IngredientMatcher.test(specs.get(1).ingredient(), alternateScroll));
            assertFalse(IngredientMatcher.test(specs.get(1).ingredient(), fixture.previousScroll));
            assertEquals(2, specs.get(2).count());
            assertTrue(specs.get(2).ingredient().test(new ItemStack(Items.BOWL)));
            assertTrue(handler.isCompatibleBinding(fixture.recipe, "farmersdelight_cooking_pot||block.farmers_spell.alchemist_pot"));
            assertFalse(handler.isCompatibleBinding(fixture.recipe, "farmersdelight_cooking_pot||block.farmersdelight.cooking_pot"));
        }
    }

    @Test
    void cottonCandyNeedsTheServingContainerAndSchoolScrollEvenWithoutDisplayCandidates() {
        ResourceLocation lightning = ResourceLocation.parse("irons_spellbooks:lightning");
        Ingredient caramel = Ingredient.of(Items.SUGAR);
        var recipe = new CookingPotRecipe(ResourceLocation.parse("farmers_spell:alchemist_cooking/thunder_cotton_candy"),
                "", null, NonNullList.of(Ingredient.EMPTY, caramel, caramel, caramel),
                new ItemStack(Items.APPLE), new ItemStack(Items.LIGHTNING_ROD), 0, 200);
        ItemStack lightningScroll = new ItemStack(Items.PAPER);
        lightningScroll.getOrCreateTag().putString("school", lightning.toString());
        var scroll = new IngredientSpec(new FarmersSpellPotSupport.ScrollIngredient(List.of(),
                stack -> stack.getTag() != null && lightning.toString().equals(stack.getTag().getString("school"))),
                1, DemandRole.CATALYST);
        try (var support = mockStatic(FarmersSpellPotSupport.class, CALLS_REAL_METHODS)) {
            support.when(() -> FarmersSpellPotSupport.isRecipe(recipe)).thenReturn(true);
            support.when(() -> FarmersSpellPotSupport.requiredSchool(recipe)).thenReturn(lightning);
            support.when(() -> FarmersSpellPotSupport.scrollSpec(lightning)).thenReturn(scroll);

            var specs = new FarmersDelightRecipeHandler().getIngredients(recipe);
            assertEquals(5, specs.size());
            assertEquals(DemandRole.CATALYST, specs.get(3).role());
            var sugar = StackKey.of(new ItemStack(Items.SUGAR), true);
            var rod = StackKey.of(new ItemStack(Items.LIGHTNING_ROD), true);
            var correctScroll = StackKey.of(lightningScroll, true);
            var blankScroll = StackKey.of(new ItemStack(Items.PAPER), true);
            assertFalse(DirectMaterialAllocator.allocate(specs, Map.of(sugar, 3, rod, 1, blankScroll, 1)).feasible());
            assertFalse(DirectMaterialAllocator.allocate(specs, Map.of(sugar, 3, correctScroll, 1)).feasible());
            assertTrue(DirectMaterialAllocator.allocate(specs, Map.of(sugar, 3, rod, 1, correctScroll, 1)).feasible());
        }
    }

    @Test
    void startReturnsOldScrollAndSuccessReturnsTheNewOneExactlyOnce() {
        Fixture fixture = new Fixture();
        try (var support = mockStatic(FarmersSpellPotSupport.class);
             var tickets = mockStatic(ForcedChunkTicketManager.class)) {
            fixture.allowSelectedScroll(support);
            assertTrue(fixture.delegate.validateAndInit(fixture.player, RECIPE, null, POS));
            assertTrue(ItemStack.matches(fixture.previousScroll, fixture.scroll.getStackInSlot(0)));
            assertTrue(fixture.returned.isEmpty());
            assertTrue(fixture.start());

            assertTrue(ItemStack.matches(fixture.selectedScroll, fixture.scroll.getStackInSlot(0)));
            assertEquals(1, fixture.returned.size());
            assertTrue(ItemStack.matches(fixture.previousScroll, fixture.returned.get(0)));
            assertTrue(fixture.food.getStackInSlot(0).is(Items.CARROT));
            assertEquals(2, fixture.food.getStackInSlot(7).getCount());
            verify(fixture.access).rsi$setManagedCooking(true);

            fixture.food.setStackInSlot(0, ItemStack.EMPTY);
            fixture.food.setStackInSlot(7, ItemStack.EMPTY);
            fixture.food.setStackInSlot(6, new ItemStack(Items.APPLE, 2));
            fixture.food.setStackInSlot(8, new ItemStack(Items.APPLE, 2));
            FarmersSpellPotAccess.finishTransfer(fixture.food, 2);
            assertEquals(2, fixture.delegate.outputSource().collectFinal(fixture.player).stacks().get(0).getCount());
            assertTrue(fixture.food.getStackInSlot(6).isEmpty());
            var returned = fixture.delegate.collectReusableMaterials(fixture.player);
            assertEquals(1, returned.size());
            assertTrue(ItemStack.matches(fixture.selectedScroll, returned.get(0)));
            assertTrue(fixture.delegate.collectReusableMaterials(fixture.player).isEmpty());
            fixture.delegate.cleanup(fixture.player);
            verify(fixture.access).rsi$setManagedCooking(false);
        }
    }

    @Test
    void cancellationRecoversTheActualScrollAndUnprocessedMaterials() {
        Fixture fixture = new Fixture();
        try (var support = mockStatic(FarmersSpellPotSupport.class);
             var tickets = mockStatic(ForcedChunkTicketManager.class)) {
            fixture.allowSelectedScroll(support);
            assertTrue(fixture.delegate.validateAndInit(fixture.player, RECIPE, null, POS));
            assertTrue(fixture.start());
            fixture.delegate.releaseReusableMaterials(fixture.player);
            fixture.delegate.recover(fixture.player, "cancelled");

            assertTrue(fixture.scroll.getStackInSlot(0).isEmpty());
            assertEquals(2, fixture.returned.size());
            assertTrue(ItemStack.matches(fixture.previousScroll, fixture.returned.get(0)));
            assertTrue(ItemStack.matches(fixture.selectedScroll, fixture.returned.get(1)));
            assertEquals(2, fixture.delegate.failureRecoveredInputs().size());
            verify(fixture.access).rsi$setManagedCooking(false);
        }
    }

    @Test
    void wrongSchoolLeavesTheOriginalScrollAndFoodUntouched() {
        Fixture fixture = new Fixture();
        try (var support = mockStatic(FarmersSpellPotSupport.class)) {
            support.when(() -> FarmersSpellPotSupport.requiredSchool(fixture.recipe)).thenReturn(SCHOOL);
            assertTrue(fixture.delegate.validateAndInit(fixture.player, RECIPE, null, POS));
            assertFalse(fixture.start());
            assertTrue(ItemStack.matches(fixture.previousScroll, fixture.scroll.getStackInSlot(0)));
            assertTrue(fixture.returned.isEmpty());
            assertTrue(fixture.food.getStackInSlot(0).isEmpty());
        }
    }

    private static final class Fixture {
        final BlockEntity machine = mock(BlockEntity.class, withSettings().extraInterfaces(FarmersSpellPotAccess.class));
        final FarmersSpellPotAccess access = (FarmersSpellPotAccess) machine;
        final ItemStackHandler food = new ItemStackHandler(9);
        final ItemStackHandler scroll = new ItemStackHandler(1);
        final ServerPlayer player = mock(ServerPlayer.class);
        final ServerLevel level = mock(ServerLevel.class);
        final ItemStack previousScroll = new ItemStack(Items.BOOK);
        final ItemStack selectedScroll = new ItemStack(Items.PAPER);
        final IngredientSpec scrollSpec = new IngredientSpec(new FarmersSpellPotSupport.ScrollIngredient(
                List.of(selectedScroll), stack -> stack.is(Items.PAPER)), 1, DemandRole.CATALYST);
        final CookingPotRecipe recipe = new CookingPotRecipe(RECIPE, "", null,
                NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.CARROT)),
                new ItemStack(Items.APPLE, 2), new ItemStack(Items.BOWL), 0, 20);
        final List<ItemStack> returned = new ArrayList<>();
        final CookingPotBatchDelegate delegate = new CookingPotBatchDelegate() {
            @Override
            protected boolean isSupportedRecipe(Recipe<?> candidate) {
                return candidate == Fixture.this.recipe;
            }

            @Override
            protected List<IngredientSpec> recipeSlots() {
                return List.of(new IngredientSpec(Ingredient.of(Items.CARROT), 1), scrollSpec,
                        new IngredientSpec(Ingredient.of(Items.BOWL), 2));
            }

            @Override
            protected ItemStack getRequiredContainer(Recipe<?> recipe, RegistryAccess access) {
                return new ItemStack(Items.BOWL, 2);
            }
        };

        Fixture() {
            scroll.setStackInSlot(0, previousScroll);
            when(machine.getBlockPos()).thenReturn(POS);
            when(access.rsi$foodInventory()).thenReturn(food);
            when(access.rsi$scrollInventory()).thenReturn(scroll);
            when(player.level()).thenReturn(level);
            when(player.serverLevel()).thenReturn(level);
            when(level.hasChunkAt(POS)).thenReturn(true);
            when(level.getBlockEntity(POS)).thenReturn(machine);
            when(level.dimension()).thenReturn(Level.OVERWORLD);
            when(level.registryAccess()).thenReturn(RegistryAccess.EMPTY);
            var manager = mock(RecipeManager.class);
            doReturn(Optional.of(recipe)).when(manager).byKey(RECIPE);
            when(level.getRecipeManager()).thenReturn(manager);
            var endpoint = mock(CraftStorageEndpoint.class);
            when(endpoint.insert(eq(player), any(ItemStack.class), eq(false))).thenAnswer(invocation -> {
                ItemStack stack = invocation.getArgument(1);
                returned.add(stack.copy());
                return StorageOperationResult.inserted(StorageOperationMode.PERFORM, stack, ItemStack.EMPTY);
            });
            delegate.setStorageEndpoint(endpoint);
        }

        boolean start() {
            return delegate.startPreparedOperation(player, List.of(new ItemStack(Items.CARROT),
                    selectedScroll, new ItemStack(Items.BOWL, 2)), mock(ExtractionLedger.class));
        }

        void allowSelectedScroll(MockedStatic<FarmersSpellPotSupport> support) {
            support.when(() -> FarmersSpellPotSupport.requiredSchool(recipe)).thenReturn(SCHOOL);
            support.when(() -> FarmersSpellPotSupport.schoolOf(selectedScroll)).thenReturn(SCHOOL);
            support.when(() -> FarmersSpellPotSupport.hasHeat(machine, level, POS, selectedScroll)).thenReturn(true);
            support.when(() -> FarmersSpellPotSupport.matchesNativeChoice(eq(level),
                    eq(recipe), any(), eq(selectedScroll), eq(true))).thenReturn(true);
        }
    }
}
