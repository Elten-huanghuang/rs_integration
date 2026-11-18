package com.huanghuang.rsintegration.recipe;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.client.Minecraft;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.level.Level;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModRecipeHandlersOutputTest extends BootstrapTest {
    private static final RegistryAccess ACCESS = RegistryAccess.EMPTY;

    @BeforeAll
    static void initializeModLogging() throws ClassNotFoundException {
        FMLJavaModLoadingContext context = mock(FMLJavaModLoadingContext.class);
        when(context.getModEventBus()).thenReturn(mock(IEventBus.class));
        try (var loading = mockStatic(FMLJavaModLoadingContext.class)) {
            loading.when(FMLJavaModLoadingContext::get).thenReturn(context);
            Class.forName("com.huanghuang.rsintegration.RSIntegrationMod");
        }
    }

    @Test
    void nonGridHandlerEmptyResultDoesNotProbeRecipe() {
        Recipe<?> recipe = mock(Recipe.class);
        ModRecipeHandler handler = mock(ModRecipeHandler.class);
        when(handler.getResultItem(recipe, ACCESS)).thenReturn(ItemStack.EMPTY);

        try (var ignored = claim(recipe, handler)) {
            assertTrue(ModRecipeHandlers.tryGetUncachedResultItem(recipe, ACCESS).isEmpty());
        }
        verify(recipe, never()).getResultItem(ACCESS);
    }

    @Test
    void handlerCanProbeWithoutRedispatchOrPopulatingResultCache() {
        TestRecipe recipe = new TestRecipe("handler_probe", new ItemStack(Items.DIAMOND));
        ModRecipeHandler handler = mock(ModRecipeHandler.class);
        when(handler.getResultItem(recipe, ACCESS)).thenAnswer(call -> {
            ItemStack result = ModRecipeHandlers.probeResultItem(recipe, ACCESS);
            return result.copyWithCount(4);
        });

        try (var ignored = claim(recipe, handler)) {
            assertEquals(4, ModRecipeHandlers.tryGetUncachedResultItem(recipe, ACCESS).getCount());
            recipe.primary = new ItemStack(Items.EMERALD);
            ItemStack result = ModRecipeHandlers.tryGetResultItem(recipe, ACCESS);
            assertEquals(Items.EMERALD, result.getItem());
            assertEquals(4, result.getCount());
        }
        verify(handler, times(2)).getResultItem(recipe, ACCESS);
    }

    @Test
    void handlerCallbackCannotCacheTheRawResultDuringAnUncachedRead() {
        TestRecipe recipe = new TestRecipe("handler_callback", new ItemStack(Items.DIAMOND));
        ModRecipeHandler handler = mock(ModRecipeHandler.class);
        when(handler.getResultItem(recipe, ACCESS)).thenAnswer(call ->
                ModRecipeHandlers.tryGetResultItem(recipe, ACCESS).copyWithCount(3));

        try (var ignored = claim(recipe, handler)) {
            assertEquals(3, ModRecipeHandlers.tryGetUncachedResultItem(recipe, ACCESS).getCount());
            ItemStack cachedResult = ModRecipeHandlers.tryGetResultItem(recipe, ACCESS);
            assertEquals(3, cachedResult.getCount());
            cachedResult.setCount(1);
            assertEquals(3, ModRecipeHandlers.tryGetResultItem(recipe, ACCESS).getCount());
        }
        verify(handler, times(2)).getResultItem(recipe, ACCESS);
    }

    @Test
    void directRecipeContractTakesPriorityAndUncachedLookupReadsCurrentInstance() {
        TestRecipe recipe = new TestRecipe("direct", new ItemStack(Items.DIAMOND));
        try (var ignored = claim(recipe, null)) {
            assertEquals(Items.DIAMOND, ModRecipeHandlers.tryGetResultItem(recipe, ACCESS).getItem());
            recipe.primary = new ItemStack(Items.EMERALD, 2);
            ItemStack output = ModRecipeHandlers.tryGetUncachedResultItem(recipe, ACCESS);
            assertEquals(Items.EMERALD, output.getItem());
            output.setCount(1);
            assertEquals(2, recipe.primary.getCount());
            assertEquals(Items.DIAMOND, ModRecipeHandlers.tryGetResultItem(recipe, ACCESS).getItem());
        }
    }

    @Test
    void deprecatedNoArgumentResultIsNotUsedAsARecipeOutput() {
        TestRecipe recipe = new TestRecipe("no_arg", ItemStack.EMPTY);
        try (var ignored = claim(recipe, null)) {
            assertTrue(ModRecipeHandlers.tryGetUncachedResultItem(recipe, ACCESS).isEmpty());
        }
    }

    @Test
    void sharedArrayAndListAreReadOnceAndSuccessfulGettersSuppressFieldFallback() {
        ItemStack bucket = new ItemStack(Items.BUCKET);
        ItemStack paper = new ItemStack(Items.PAPER, 2);
        TestRecipe recipe = new TestRecipe("aliased_outputs", new ItemStack(Items.DIAMOND)) {
            private final List<ItemStack> byproducts = List.of(paper);
            private final ItemStack[] remainders = {bucket, null, ItemStack.EMPTY};
            private final ItemStack bonus = new ItemStack(Items.STICK);

            public ItemStack[] getRemainingItems() { return remainders; }
            public ItemStack[] getOutputs() { return remainders; }
            public List<ItemStack> getByproducts() { return byproducts; }
            public List<ItemStack> getRollResults() { return byproducts; }
        };

        List<ItemStack> secondaries = ModRecipeHandlers.tryGetSecondaryOutputs(recipe, ACCESS);

        assertEquals(List.of(Items.BUCKET, Items.PAPER), secondaries.stream().map(ItemStack::getItem).toList());
        secondaries.get(1).shrink(1);
        assertEquals(2, paper.getCount());
    }

    @Test
    void allOutputsDropsExactlyOneStackWithTheDeclaredPrimaryTag() {
        ItemStack differentTag = taggedPaper(1);
        ItemStack primary = taggedPaper(2);
        ItemStack duplicate = taggedPaper(2).copyWithCount(5);
        TestRecipe recipe = new TestRecipe("tagged_outputs", primary) {
            public List<ItemStack> getOutputs() {
                return List.of(differentTag, primary, duplicate, new ItemStack(Items.BUCKET));
            }
        };

        try (var ignored = claim(recipe, null)) {
            List<ItemStack> secondaries = ModRecipeHandlers.tryGetSecondaryOutputs(recipe, ACCESS);

            assertEquals(3, secondaries.size());
            assertTrue(ItemStack.matches(differentTag, secondaries.get(0)));
            assertTrue(ItemStack.matches(duplicate, secondaries.get(1)));
            assertEquals(Items.BUCKET, secondaries.get(2).getItem());
        }
    }

    @Test
    void gridRecipeDoesNotProbeTheNoArgumentRemainderGetter() {
        CraftingRecipe recipe = new ShapelessRecipe(id("grid_remainders"), "", CraftingBookCategory.MISC,
                new ItemStack(Items.PAPER), NonNullList.create()) {
            public List<ItemStack> getRemainingItems() {
                return List.of(new ItemStack(Items.BUCKET));
            }
        };

        assertTrue(ModRecipeHandlers.tryGetSecondaryOutputs(recipe, ACCESS).isEmpty());
    }

    @Test
    void dedicatedServerDoesNotReenterClientOnlyContractThroughGenericHandler() {
        CraftingRecipe recipe = new ShapelessRecipe(id("client_only"), "", CraftingBookCategory.MISC,
                new ItemStack(Items.PAPER, 2), NonNullList.create()) {
            @Override
            public ItemStack getResultItem(RegistryAccess access) {
                throw new AssertionError(Minecraft.class.getName());
            }
        };
        ModRecipeHandler handler = new GenericRecipeHandler();

        try (var ignored = claim(recipe, handler)) {
            ItemStack output = ModRecipeHandlers.tryGetCraftingResultItem(recipe, ACCESS, true);

            assertEquals(Items.PAPER, output.getItem());
            assertEquals(2, output.getCount());
        }
    }

    private static MockedStatic<ModRecipeHandlers> claim(Recipe<?> recipe, ModRecipeHandler handler) {
        var handlers = mockStatic(ModRecipeHandlers.class, CALLS_REAL_METHODS);
        handlers.when(() -> ModRecipeHandlers.claimFor(recipe))
                .thenReturn(new ModRecipeHandlers.Claim(handler, handler == null ? List.of() : List.of(handler)));
        return handlers;
    }

    private static ItemStack taggedPaper(int model) {
        ItemStack paper = new ItemStack(Items.PAPER);
        paper.getOrCreateTag().putInt("CustomModelData", model);
        return paper;
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("test", "output/" + path);
    }

    private static class TestRecipe implements Recipe<Container> {
        private final ResourceLocation recipeId;
        private ItemStack primary;

        TestRecipe(String path, ItemStack primary) {
            this.recipeId = id(path);
            this.primary = primary;
        }

        @Override public boolean matches(Container container, Level level) { return false; }
        @Override public boolean canCraftInDimensions(int width, int height) { return true; }
        @Override public ItemStack assemble(Container container, RegistryAccess access) { return primary.copy(); }
        @Override public ItemStack getResultItem(RegistryAccess access) { return primary; }
        @Override public ResourceLocation getId() { return recipeId; }
        @Override public RecipeSerializer<?> getSerializer() { return null; }
        @Override public RecipeType<?> getType() { return RecipeType.CRAFTING; }

        public ItemStack getResultItem() { return new ItemStack(Items.FURNACE); }
    }
}
