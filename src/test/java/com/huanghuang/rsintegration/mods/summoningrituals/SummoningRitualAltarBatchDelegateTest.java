package com.huanghuang.rsintegration.mods.summoningrituals;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate.CraftPhase;
import com.huanghuang.rsintegration.storage.StorageOperationMode;
import com.huanghuang.rsintegration.storage.StorageOperationResult;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SummoningRitualAltarBatchDelegateTest extends BootstrapTest {
    @BeforeAll
    static void initializeModContext() throws ClassNotFoundException {
        initializeForgeCapabilities();
        FMLJavaModLoadingContext context = mock(FMLJavaModLoadingContext.class);
        when(context.getModEventBus()).thenReturn(mock(IEventBus.class));
        try (MockedStatic<FMLJavaModLoadingContext> loading = mockStatic(FMLJavaModLoadingContext.class)) {
            loading.when(FMLJavaModLoadingContext::get).thenReturn(context);
            Class.forName(RSIntegrationMod.class.getName());
        }
    }

    @Test
    void successfulPreparationLeavesMaterialsOnIdleAltar() throws Exception {
        Fixture fixture = new Fixture();
        assertTrue(fixture.start());

        assertEquals(CraftPhase.DONE, fixture.delegate.observeCraft(fixture.level).phase());
        assertTrue(fixture.delegate.isCraftComplete(fixture.level));
        assertTrue(fixture.delegate.collectResult(fixture.player).isEmpty());
        fixture.delegate.onBatchFinished(fixture.player);

        assertEquals(Items.DIAMOND, fixture.inventory.getStackInSlot(0).getItem());
        assertEquals(Items.STICK, fixture.inventory.getStackInSlot(2).getItem());
        verify(fixture.altar, times(1)).getCapability(ForgeCapabilities.ITEM_HANDLER);
        verify(fixture.altar).setChanged();
        verify(fixture.level).sendBlockUpdated(BlockPos.ZERO,
                Blocks.STONE.defaultBlockState(), Blocks.STONE.defaultBlockState(), 3);
    }

    @Test
    void ritualLockingItemCapabilityAfterPlacementDoesNotFailPreparation() throws Exception {
        Fixture fixture = new Fixture();
        assertTrue(fixture.start());
        fixture.hideItemCapability();

        assertEquals(CraftPhase.DONE, fixture.delegate.observeCraft(fixture.level).phase());
        assertTrue(fixture.delegate.isCraftComplete(fixture.level));
        fixture.delegate.onBatchFinished(fixture.player);

        assertEquals(1, fixture.inventory.getStackInSlot(0).getCount());
        assertEquals(1, fixture.inventory.getStackInSlot(2).getCount());
        verify(fixture.altar, times(1)).getCapability(ForgeCapabilities.ITEM_HANDLER);
    }

    @Test
    void ritualMayConsumePreparedMaterialsBeforeCompletionObservation() throws Exception {
        Fixture fixture = new Fixture();
        assertTrue(fixture.start());
        fixture.hideItemCapability();
        fixture.inventory.setStackInSlot(0, ItemStack.EMPTY);
        fixture.inventory.setStackInSlot(2, ItemStack.EMPTY);

        assertEquals(CraftPhase.DONE, fixture.delegate.observeCraft(fixture.level).phase());
        assertTrue(fixture.delegate.collectResult(fixture.player).isEmpty());
        assertFalse(fixture.delegate.publishesDeclaredGraphOutputs());
        verify(fixture.altar, times(1)).getCapability(ForgeCapabilities.ITEM_HANDLER);
    }

    @Test
    void unavailableCapabilityBeforePlacementStillRejectsPreparation() throws Exception {
        Fixture fixture = new Fixture();
        fixture.hideItemCapability();

        assertFalse(fixture.start());
        assertEquals(CraftPhase.FAILED, fixture.delegate.observeCraft(fixture.level).phase());
        assertTrue(fixture.inventory.getStackInSlot(0).isEmpty());
        assertTrue(fixture.inventory.getStackInSlot(2).isEmpty());
    }

    @Test
    void rejectedMaterialReservationCannotCompletePreparation() throws Exception {
        Fixture fixture = new Fixture();

        assertFalse(fixture.delegate.tryStartWithMaterials(fixture.player,
                List.of(new ItemStack(Items.STICK)), new ExtractionLedger()));
        assertEquals(CraftPhase.FAILED, fixture.delegate.observeCraft(fixture.level).phase());
        assertFalse(fixture.delegate.isCraftComplete(fixture.level));
    }

    @Test
    void replacedAltarCannotCompletePreparationOrLoseItsOwnMaterials() throws Exception {
        Fixture fixture = new Fixture();
        assertTrue(fixture.start());
        BlockEntity replacement = mock(BlockEntity.class);
        when(replacement.getCapability(ForgeCapabilities.ITEM_HANDLER))
                .thenReturn(fixture.itemCapability);
        when(fixture.level.getBlockEntity(BlockPos.ZERO)).thenReturn(replacement);

        assertEquals(CraftPhase.FAILED, fixture.delegate.observeCraft(fixture.level).phase());
        assertFalse(fixture.delegate.isCraftComplete(fixture.level));
        fixture.delegate.onBatchFailed(fixture.player, "altar replaced");

        assertEquals(1, fixture.inventory.getStackInSlot(0).getCount());
        assertEquals(1, fixture.inventory.getStackInSlot(2).getCount());
    }

    @Test
    void failureOnOriginalIdleAltarStillRecoversPlacedMaterials() throws Exception {
        Fixture fixture = new Fixture();
        assertTrue(fixture.start());

        fixture.delegate.onBatchFailed(fixture.player, "cancelled before completion");

        assertTrue(fixture.inventory.getStackInSlot(0).isEmpty());
        assertTrue(fixture.inventory.getStackInSlot(2).isEmpty());
        assertEquals(2, fixture.delegate.failureRecoveredInputs().size());
        assertTrue(fixture.delegate.physicalFailureCleanupCompleted());
        assertFalse(fixture.delegate.isCraftComplete(fixture.level));
    }

    @Test
    void missingOrRemovedAltarStillFailsAfterPlacement() throws Exception {
        Fixture missing = new Fixture();
        assertTrue(missing.start());
        when(missing.level.getBlockEntity(BlockPos.ZERO)).thenReturn(null);
        assertEquals(CraftPhase.FAILED, missing.delegate.observeCraft(missing.level).phase());

        Fixture removed = new Fixture();
        assertTrue(removed.start());
        when(removed.altar.isRemoved()).thenReturn(true);
        assertEquals(CraftPhase.FAILED, removed.delegate.observeCraft(removed.level).phase());
    }

    @Test
    void unloadedAltarStillFailsAfterPlacement() throws Exception {
        Fixture fixture = new Fixture();
        assertTrue(fixture.start());
        when(fixture.level.hasChunkAt(BlockPos.ZERO)).thenReturn(false);

        assertEquals(CraftPhase.FAILED, fixture.delegate.observeCraft(fixture.level).phase());
        assertFalse(fixture.delegate.isCraftComplete(fixture.level));
    }

    @Test
    void switchingFromUnactivatedRecipeReturnsOldCatalystToStorage() throws Exception {
        InteractiveInventory inventory = prepareRecipeA();
        Fixture next = new Fixture(inventory);
        next.configureRecipe("recipe_b", Ingredient.of(Items.BONE),
                List.of(new IngredientSpec(Ingredient.of(Items.EMERALD), 1)));

        assertTrue(next.start(List.of(new ItemStack(Items.BONE), new ItemStack(Items.EMERALD))));

        assertEquals(2, next.recycled.size());
        assertEquals(1, countItem(next.recycled, Items.STICK));
        assertEquals(1, countItem(next.recycled, Items.DIAMOND));
        assertTrue(inventory.getStackInSlot(0).is(Items.EMERALD));
        assertTrue(inventory.getStackInSlot(2).is(Items.BONE));
        assertEquals(1, inventory.getStackInSlot(2).getCount());
        assertFalse(inventory.ritualStarted);
        assertEquals(0, inventory.interactiveInsertions);
    }

    @Test
    void switchingRecipesCannotStartThirdRecipeMatchingOldInputsAndNewCatalyst() throws Exception {
        InteractiveInventory inventory = prepareRecipeA();
        Fixture next = new Fixture(inventory);
        next.configureRecipe("recipe_b", Ingredient.of(Items.BONE), List.of(
                new IngredientSpec(Ingredient.of(Items.DIAMOND), 1),
                new IngredientSpec(Ingredient.of(Items.EMERALD), 1)));

        assertTrue(next.start(List.of(new ItemStack(Items.BONE), new ItemStack(Items.EMERALD))));

        assertEquals(1, countItem(next.recycled, Items.STICK));
        assertTrue(inventory.getStackInSlot(0).is(Items.DIAMOND));
        assertTrue(inventory.getStackInSlot(1).is(Items.EMERALD));
        assertTrue(inventory.getStackInSlot(2).is(Items.BONE));
        assertFalse(inventory.ritualStarted);
        assertEquals(0, inventory.interactiveInsertions);
    }

    @Test
    void orderingMatchingRecipeCOnlyPreparesItWithoutActivation() throws Exception {
        InteractiveInventory inventory = prepareRecipeA();
        Fixture next = new Fixture(inventory);
        next.configureRecipe("recipe_c", Ingredient.of(Items.BONE),
                List.of(new IngredientSpec(Ingredient.of(Items.DIAMOND), 1)));

        assertTrue(next.start(List.of(new ItemStack(Items.BONE))));
        assertEquals(CraftPhase.DONE, next.delegate.observeCraft(next.level).phase());

        assertEquals(1, countItem(next.recycled, Items.STICK));
        assertTrue(inventory.getStackInSlot(0).is(Items.DIAMOND));
        assertEquals(1, inventory.getStackInSlot(0).getCount());
        assertTrue(inventory.getStackInSlot(2).is(Items.BONE));
        assertFalse(inventory.ritualStarted);
        assertEquals(0, inventory.interactiveInsertions);
    }

    @Test
    void rejectedCatalystRecycleRestoresRecipeAAndRejectsNewOrder() throws Exception {
        InteractiveInventory inventory = prepareRecipeA();
        Fixture next = new Fixture(inventory);
        next.configureRecipe("recipe_c", Ingredient.of(Items.BONE),
                List.of(new IngredientSpec(Ingredient.of(Items.DIAMOND), 1)));
        when(next.endpoint.insert(any(ServerPlayer.class), any(ItemStack.class), anyBoolean()))
                .thenAnswer(call -> StorageOperationResult.inserted(
                        call.getArgument(2) ? StorageOperationMode.SIMULATE : StorageOperationMode.PERFORM,
                        call.getArgument(1), call.getArgument(2) ? ItemStack.EMPTY : call.getArgument(1)));

        assertFalse(next.start(List.of(new ItemStack(Items.BONE))));

        assertTrue(inventory.getStackInSlot(2).is(Items.STICK));
        assertEquals(1, inventory.getStackInSlot(0).getCount());
        assertTrue(next.recycled.isEmpty());
        assertFalse(inventory.ritualStarted);
        assertEquals(0, inventory.interactiveInsertions);
    }

    @Test
    void partialRecycleFailureRestoresAllRemainingMaterialStacks() throws Exception {
        InteractiveInventory inventory = new InteractiveInventory();
        inventory.setStackInSlot(0, new ItemStack(Items.DIAMOND, 130));
        inventory.setStackInSlot(2, new ItemStack(Items.STICK));
        Fixture next = new Fixture(inventory);
        next.configureRecipe("recipe_b", Ingredient.of(Items.BONE),
                List.of(new IngredientSpec(Ingredient.of(Items.EMERALD), 1)));
        when(next.endpoint.insert(any(ServerPlayer.class), any(ItemStack.class), anyBoolean()))
                .thenAnswer(call -> {
                    ItemStack stack = call.getArgument(1);
                    boolean simulate = call.getArgument(2);
                    boolean accepted = simulate || next.recycled.isEmpty();
                    if (!simulate && accepted) next.recycled.add(stack.copy());
                    return StorageOperationResult.inserted(
                            simulate ? StorageOperationMode.SIMULATE : StorageOperationMode.PERFORM,
                            stack, accepted ? ItemStack.EMPTY : stack);
                });

        assertFalse(next.start(List.of(new ItemStack(Items.BONE), new ItemStack(Items.EMERALD))));

        assertEquals(64, countItem(next.recycled, Items.DIAMOND));
        assertEquals(66, inventory.getStackInSlot(0).getCount());
        assertTrue(inventory.getStackInSlot(2).is(Items.STICK));
        assertFalse(inventory.ritualStarted);
    }

    private static InteractiveInventory prepareRecipeA() throws Exception {
        InteractiveInventory inventory = new InteractiveInventory();
        Fixture first = new Fixture(inventory);
        assertTrue(first.start());
        first.delegate.onBatchFinished(first.player);
        assertEquals(1, inventory.getStackInSlot(0).getCount());
        assertTrue(inventory.getStackInSlot(2).is(Items.STICK));
        return inventory;
    }

    private static int countItem(List<ItemStack> stacks, Item item) {
        return stacks.stream().filter(stack -> stack.is(item)).mapToInt(ItemStack::getCount).sum();
    }

    /** 模拟 SRfix 的模拟写入、末槽提取失败及原祭坛的催化剂交互启动。 */
    private static final class InteractiveInventory extends ItemStackHandler {
        boolean ritualStarted;
        int interactiveInsertions;

        InteractiveInventory() {
            super(3);
        }

        @Override
        public int getSlotLimit(int slot) {
            return slot == 2 ? 1 : 4096;
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            interactiveInsertions++;
            if (slot != 2) return super.insertItem(slot, stack, false);
            if (getStackInSlot(2).isEmpty()) {
                setStackInSlot(2, stack.copyWithCount(1));
                return ItemStack.EMPTY;
            }
            if (simulate) return ItemStack.EMPTY;
            setStackInSlot(2, stack.copyWithCount(1));
            if (stack.is(Items.BONE) && getStackInSlot(0).is(Items.DIAMOND)) {
                ritualStarted = true;
                return ItemStack.EMPTY;
            }
            setStackInSlot(2, ItemStack.EMPTY);
            return super.insertItem(0, stack, false);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return slot == 2 ? ItemStack.EMPTY : super.extractItem(slot, amount, simulate);
        }
    }

    private static final class Fixture {
        final SummoningRitualAltarBatchDelegate delegate = new SummoningRitualAltarBatchDelegate();
        final ServerLevel level = mock(ServerLevel.class);
        final ServerPlayer player = mock(ServerPlayer.class);
        final BlockEntity altar = mock(BlockEntity.class);
        final ItemStackHandler inventory;
        final LazyOptional<IItemHandler> itemCapability;
        final CraftStorageEndpoint endpoint = mock(CraftStorageEndpoint.class);
        final List<ItemStack> recycled = new ArrayList<>();

        Fixture() throws Exception {
            this(new ItemStackHandler(3));
        }

        Fixture(ItemStackHandler inventory) throws Exception {
            this.inventory = inventory;
            this.itemCapability = LazyOptional.of(() -> inventory);
            when(player.serverLevel()).thenReturn(level);
            when(level.isLoaded(BlockPos.ZERO)).thenReturn(true);
            when(level.hasChunkAt(BlockPos.ZERO)).thenReturn(true);
            when(level.getBlockEntity(BlockPos.ZERO)).thenReturn(altar);
            when(altar.getCapability(ForgeCapabilities.ITEM_HANDLER)).thenReturn(itemCapability);
            when(altar.getLevel()).thenReturn(level);
            when(altar.getBlockPos()).thenReturn(BlockPos.ZERO);
            when(altar.getBlockState()).thenReturn(Blocks.STONE.defaultBlockState());
            when(endpoint.insert(any(ServerPlayer.class), any(ItemStack.class), anyBoolean()))
                    .thenAnswer(call -> {
                        ItemStack stack = call.getArgument(1);
                        boolean simulate = call.getArgument(2);
                        if (!simulate) recycled.add(stack.copy());
                        return StorageOperationResult.inserted(
                                simulate ? StorageOperationMode.SIMULATE : StorageOperationMode.PERFORM,
                                stack, ItemStack.EMPTY);
                    });

            set(delegate, "altarPos", BlockPos.ZERO);
            configureRecipe("test_ritual", Ingredient.of(Items.STICK),
                    List.of(new IngredientSpec(Ingredient.of(Items.DIAMOND), 1)));
            List<ItemStack> snapshot = new ArrayList<>();
            for (int slot = 0; slot < inventory.getSlots(); slot++) {
                snapshot.add(inventory.getStackInSlot(slot).copy());
            }
            set(delegate, "preparedSnapshot", List.copyOf(snapshot));
            delegate.setStorageEndpoint(endpoint);
        }

        void configureRecipe(String recipePath, Ingredient catalyst, List<IngredientSpec> inputs)
                throws Exception {
            Recipe<?> recipe = mock(Recipe.class);
            when(recipe.getId()).thenReturn(new ResourceLocation("summoningrituals", recipePath));

            // 预设校验完成后的配方与库存快照，直接验证实际投料和完成观察入口。
            set(delegate, "recipe", recipe);
            set(delegate, "catalyst", catalyst);
            set(delegate, "recipeInputs", inputs);
        }

        boolean start() {
            return start(List.of(new ItemStack(Items.STICK), new ItemStack(Items.DIAMOND)));
        }

        boolean start(List<ItemStack> materials) {
            return delegate.tryStartWithMaterials(player, materials, new ExtractionLedger());
        }

        void hideItemCapability() {
            // 模拟原祭坛在仪式运行时不再暴露 ITEM_HANDLER，保留其真实库存。
            when(altar.getCapability(ForgeCapabilities.ITEM_HANDLER)).thenReturn(LazyOptional.empty());
        }
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
