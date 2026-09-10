package com.huanghuang.rsintegration.mods.malum;

import com.huanghuang.rsintegration.crafting.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.graph.GraphConcurrencyPolicy;
import com.huanghuang.rsintegration.storage.StorageOperationMode;
import com.huanghuang.rsintegration.storage.StorageOperationResult;
import com.huanghuang.rsintegration.storage.StorageSession;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.level.Level;
import net.minecraftforge.items.ItemStackHandler;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class MalumSpiritCrucibleRecipeRefreshTest extends BootstrapTest {
    @Test
    void refreshInvokesInitAndAcceptsTheExpectedRecipeId() {
        Recipe<?> expected = recipe("neutron_nugget");
        FakeCrucible crucible = new FakeCrucible(recipe("neutron_nugget"));

        assertTrue(MalumSpiritCrucibleBatchDelegate.refreshRecipeSelection(crucible, expected));
        assertTrue(crucible.initialized);
    }

    @Test
    void refreshSupportsNonPublicInitAndOptionalRecipeField() {
        Recipe<?> expected = recipe("neutron_nugget");
        PrivateInitCrucible crucible = new PrivateInitCrucible(expected);

        assertTrue(MalumSpiritCrucibleBatchDelegate.refreshRecipeSelection(crucible, expected));
        assertTrue(crucible.initialized);
    }

    @Test
    void refreshRejectsNoMatchInsteadOfWaitingForTimeout() {
        Recipe<?> expected = recipe("neutron_nugget");

        assertFalse(MalumSpiritCrucibleBatchDelegate.refreshRecipeSelection(
                new FakeCrucible(null), expected));
        assertFalse(MalumSpiritCrucibleBatchDelegate.refreshRecipeSelection(
                new FakeCrucible(recipe("different")), expected));
    }

    @Test
    void refreshSelectsRequestedRecipeWhenLowerCostMatchShadowsIt() {
        MatchingRecipe expected = new MatchingRecipe("higher_cost", true);
        OverlappingCrucible crucible = new OverlappingCrucible(recipe("lower_cost"));
        crucible.inventory.setStackInSlot(0, new ItemStack(Items.IRON_PICKAXE));
        crucible.spiritInventory.setStackInSlot(0, new ItemStack(Items.BLAZE_POWDER, 16));

        assertTrue(MalumSpiritCrucibleBatchDelegate.refreshRecipeSelection(crucible, expected));
        assertEquals(expected, crucible.recipe);
    }

    @Test
    void refreshDoesNotForceRequestedRecipeWhenItsInputsDoNotMatch() {
        MatchingRecipe expected = new MatchingRecipe("wrong_inputs", false);
        Recipe<?> lowerCost = recipe("lower_cost");
        OverlappingCrucible crucible = new OverlappingCrucible(lowerCost);
        crucible.inventory.setStackInSlot(0, new ItemStack(Items.IRON_PICKAXE));
        crucible.spiritInventory.setStackInSlot(0, new ItemStack(Items.BLAZE_POWDER, 16));

        assertFalse(MalumSpiritCrucibleBatchDelegate.refreshRecipeSelection(crucible, expected));
        assertEquals(lowerCost, crucible.recipe);
    }

    @Test
    void refreshesNearbyAcceleratorsAfterProgrammaticPlacement() {
        FakeCrucible crucible = new FakeCrucible(recipe("neutron_nugget"));

        assertTrue(MalumSpiritCrucibleBatchDelegate.refreshAccelerators(
                crucible, new Object(), new Object()));
        assertTrue(crucible.acceleratorsRefreshed);
    }

    @Test
    void refreshFindsAcceleratorMethodInheritedFromInterface() {
        InterfaceAcceleratedCrucible crucible = new InterfaceAcceleratedCrucible();
        Object level = new Object();
        Object pos = new Object();

        assertTrue(MalumSpiritCrucibleBatchDelegate.refreshAccelerators(
                crucible, level, pos));
        assertTrue(crucible.acceleratorsRefreshed);
    }

    @Test
    void placesEverySpiritBeforeTheCenterCatalyst() {
        List<String> writes = new ArrayList<>();
        RecordingHandler spirits = new RecordingHandler("spirit", 3, writes);
        RecordingHandler catalyst = new RecordingHandler("catalyst", 1, writes);

        MalumSpiritCrucibleBatchDelegate.placePreparedInputs(
                spirits,
                List.of(new ItemStack(Items.BLAZE_POWDER), new ItemStack(Items.REDSTONE)),
                catalyst,
                new ItemStack(Items.IRON_PICKAXE));

        assertEquals(List.of("spirit:0", "spirit:1", "catalyst:0"), writes);
    }

    @Test
    void missingAcceleratorApiRemainsCompatible() {
        assertFalse(MalumSpiritCrucibleBatchDelegate.refreshAccelerators(
                new Object(), new Object(), new Object()));
    }

    @Test
    void transformedCatalystIsReservedPerOperation() {
        List<IBatchDelegate.MaterialReservationScope> scopes =
                MalumSpiritCrucibleBatchDelegate.materialReservationScopes(List.of(
                        new IngredientSpec(Ingredient.of(Items.IRON_PICKAXE), 1,
                                DemandRole.CONTAINER_RETURNING),
                        new IngredientSpec(Ingredient.of(Items.BLAZE_POWDER), 4)));

        assertEquals(List.of(
                IBatchDelegate.MaterialReservationScope.PER_OPERATION,
                IBatchDelegate.MaterialReservationScope.PER_OPERATION), scopes);
    }

    @Test
    void unchangedCatalystRemainsWorkerReusable() {
        List<IBatchDelegate.MaterialReservationScope> scopes =
                MalumSpiritCrucibleBatchDelegate.materialReservationScopes(List.of(
                        new IngredientSpec(Ingredient.of(Items.IRON_PICKAXE), 1,
                                DemandRole.CATALYST)));

        assertEquals(List.of(IBatchDelegate.MaterialReservationScope.PER_WORKER_REUSABLE), scopes);
    }

    @Test
    void declaresIndependentWorldCaptureForParallelCrucibles() {
        MalumSpiritCrucibleBatchDelegate delegate =
                new MalumSpiritCrucibleBatchDelegate();
        BatchConcurrencyCapabilities capabilities = delegate.concurrencyCapabilities();

        assertEquals(BatchConcurrencyCapabilities.MaterialOwnership.CHAIN_RESERVED,
                capabilities.materials());
        assertEquals(BatchConcurrencyCapabilities.OutputOwnership.OWNED_WORLD_CAPTURE,
                capabilities.outputOwnership());
        assertTrue(delegate.supportsConcurrentNodeExecution());
        assertTrue(delegate.allowsOverlappingOutputCaptureOrigins());
        assertFalse(GraphConcurrencyPolicy.isExclusive("malum", delegate));
    }

    @Test
    void reusableCatalystReturnsAfterOperationStateWasReset() throws Exception {
        MalumSpiritCrucibleBatchDelegate delegate =
                new MalumSpiritCrucibleBatchDelegate();
        AtomicReference<ItemStack> returned = new AtomicReference<>(ItemStack.EMPTY);
        CraftStorageEndpoint endpoint = new CraftStorageEndpoint() {
            @Override
            public StorageSession session() {
                return mock(StorageSession.class);
            }

            @Override
            public StorageOperationResult insert(ServerPlayer player, ItemStack stack,
                                                 boolean simulate) {
                returned.set(stack.copy());
                return StorageOperationResult.inserted(
                        StorageOperationMode.PERFORM, stack, ItemStack.EMPTY);
            }
        };
        ItemStackHandler catalystInventory = new ItemStackHandler(1);
        catalystInventory.setStackInSlot(0, new ItemStack(Items.IRON_PICKAXE));

        setField(delegate, "invCatalyst", catalystInventory);
        setField(delegate, "catalystReturnEndpoint", endpoint);
        Method resetState = AbstractBatchDelegate.class.getDeclaredMethod("resetState");
        resetState.setAccessible(true);
        resetState.invoke(delegate);

        delegate.releaseReusableMaterials(mock(ServerPlayer.class));

        assertEquals(Items.IRON_PICKAXE, returned.get().getItem());
        assertTrue(catalystInventory.getStackInSlot(0).isEmpty());
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private interface AcceleratorApi {
        default void recalibrateAccelerators(Object level, Object pos) {
            ((InterfaceAcceleratedCrucible) this).acceleratorsRefreshed = true;
        }
    }

    private static final class InterfaceAcceleratedCrucible implements AcceleratorApi {
        private boolean acceleratorsRefreshed;
    }

    public static final class RecordingHandler extends ItemStackHandler {
        private final String name;
        private final List<String> writes;

        private RecordingHandler(String name, int slots, List<String> writes) {
            super(slots);
            this.name = name;
            this.writes = writes;
        }

        @Override
        public void setStackInSlot(int slot, ItemStack stack) {
            writes.add(name + ":" + slot);
            super.setStackInSlot(slot, stack);
        }
    }

    private static ShapedRecipe recipe(String path) {
        return new ShapedRecipe(new ResourceLocation("test", path), "",
                CraftingBookCategory.MISC, 1, 1,
                NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.IRON_INGOT)),
                new ItemStack(Items.IRON_NUGGET));
    }

    public static final class FakeCrucible {
        public Recipe<?> recipe;
        private final Recipe<?> selected;
        private boolean initialized;
        private boolean acceleratorsRefreshed;

        private FakeCrucible(Recipe<?> selected) {
            this.selected = selected;
        }

        public void init() {
            initialized = true;
            recipe = selected;
        }

        public void recalibrateAccelerators(Object level, Object pos) {
            acceleratorsRefreshed = level != null && pos != null;
        }
    }

    public static final class PrivateInitCrucible {
        private Object recipe;
        private final Recipe<?> selected;
        private boolean initialized;

        private PrivateInitCrucible(Recipe<?> selected) {
            this.selected = selected;
        }

        private void init() {
            initialized = true;
            recipe = java.util.Optional.ofNullable(selected);
        }
    }

    public static final class OverlappingCrucible {
        public Recipe<?> recipe;
        public final ItemStackHandler inventory = new ItemStackHandler(1);
        public final ItemStackHandler spiritInventory = new ItemStackHandler(4);
        private final Recipe<?> firstMatch;

        private OverlappingCrucible(Recipe<?> firstMatch) {
            this.firstMatch = firstMatch;
        }

        public void init() {
            recipe = firstMatch;
        }
    }

    private static final class MatchingRecipe implements Recipe<Container> {
        private final ResourceLocation id;
        private final boolean matches;

        private MatchingRecipe(String path, boolean matches) {
            this.id = new ResourceLocation("test", path);
            this.matches = matches;
        }

        public boolean doesInputMatch(ItemStack stack) {
            return matches && stack.is(Items.IRON_PICKAXE);
        }

        public boolean doSpiritsMatch(List<ItemStack> stacks) {
            return matches && stacks.size() == 1
                    && stacks.get(0).is(Items.BLAZE_POWDER)
                    && stacks.get(0).getCount() >= 16;
        }

        @Override public boolean matches(Container container, Level level) { return false; }
        @Override public ItemStack assemble(Container container, RegistryAccess access) {
            return new ItemStack(Items.IRON_NUGGET);
        }
        @Override public boolean canCraftInDimensions(int width, int height) { return true; }
        @Override public ItemStack getResultItem(RegistryAccess access) {
            return new ItemStack(Items.IRON_NUGGET);
        }
        @Override public ResourceLocation getId() { return id; }
        @Override public RecipeSerializer<?> getSerializer() { return RecipeSerializer.SHAPELESS_RECIPE; }
        @Override public RecipeType<?> getType() { return RecipeType.CRAFTING; }
    }
}
