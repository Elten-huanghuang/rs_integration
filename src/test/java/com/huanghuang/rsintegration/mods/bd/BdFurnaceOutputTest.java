package com.huanghuang.rsintegration.mods.bd;

import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.TaskOutputReception;
import com.huanghuang.rsintegration.crafting.batch.InputBufferPlan;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate.PreparationState;
import com.huanghuang.rsintegration.crafting.batch.MachineOperation.CraftPhase;
import com.huanghuang.rsintegration.crafting.batch.OperationStartContext;
import com.huanghuang.rsintegration.crafting.batch.OperationStartContext.ReservedMaterial;
import com.huanghuang.rsintegration.mods.vanilla.CookingMachineBatchDelegate;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.wintercogs.beyonddimensions.api.storage.handler.impl.StackHandler;
import com.wintercogs.beyonddimensions.api.storage.key.KeyAmount;
import com.wintercogs.beyonddimensions.api.storage.key.impl.ItemStackKey;
import com.wintercogs.beyonddimensions.common.block.entity.BaseNetFurnaceBlockEntity;
import com.wintercogs.beyonddimensions.common.machine.ReceiveMode;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CookingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class BdFurnaceOutputTest extends BootstrapTest {
    private static final int LANES = 9;
    private static final BlockPos POS = new BlockPos(4, 64, 8);
    private static final ResourceLocation RECIPE = new ResourceLocation("minecraft", "iron_ingot_from_smelting_raw_iron");
    private final StackHandler inputs = new StackHandler(LANES);
    private final StackHandler outputs = new StackHandler(LANES);
    private final ItemStack result = new ItemStack(Items.IRON_INGOT);
    private BaseNetFurnaceBlockEntity<?> furnace;
    private ServerLevel level;
    private ServerPlayer player;
    private BdFurnaceBatchDelegate delegate;
    private BdFurnaceOutputHook<SmeltingRecipe> hook;
    private MockedConstruction<ItemStackKey> itemKeys;
    private BiConsumer<Integer, KeyAmount> consumed;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void prepare() {
        // BD 的键构造器依赖 Forge 的访问转换，单测只替换构造边界。
        itemKeys = mockConstruction(ItemStackKey.class, (key, context) -> {
            ItemStack stack = ((ItemStack) context.arguments().get(0)).copyWithCount(1);
            when(key.getReadOnlyStack()).thenReturn(stack);
            when(key.copyStackWithCount(anyLong())).thenAnswer(call ->
                    stack.copyWithCount(Math.toIntExact(call.getArgument(0, Long.class))));
            when(key.isEmpty()).thenReturn(stack.isEmpty());
            when(key.getVanillaMaxStackSize()).thenReturn((long) stack.getMaxStackSize());
            when(key.getTypeId()).thenReturn(ItemStackKey.ID);
        });
        furnace = mock(BaseNetFurnaceBlockEntity.class, withSettings().extraInterfaces(BdFurnaceOutputHook.class));
        furnace.receiveMode = ReceiveMode.STOP;
        hook = (BdFurnaceOutputHook<SmeltingRecipe>) furnace;
        level = mock(ServerLevel.class);
        player = mock(ServerPlayer.class);
        var manager = mock(RecipeManager.class);
        var recipe = new SmeltingRecipe(RECIPE, "", CookingBookCategory.MISC,
                Ingredient.of(Items.RAW_IRON), result, 0, 200);
        var check = (RecipeManager.CachedCheck<Container, SmeltingRecipe>) mock(RecipeManager.CachedCheck.class);
        when(check.getRecipeFor(any(Container.class), eq(level))).thenReturn(Optional.of(recipe));
        when(hook.rsi$recipeChecks()).thenReturn(Collections.nCopies(LANES, check));
        when(hook.rsi$recipeType()).thenReturn(RecipeType.SMELTING);
        when(furnace.getCapacity()).thenReturn(LANES);
        when(furnace.getInputStorageSlots()).thenReturn(inputs);
        when(furnace.getInputFilterSlots()).thenReturn(new StackHandler(1));
        when(furnace.getOutputStorageSlots()).thenReturn(outputs);
        when(furnace.getFuelStorageSlots()).thenReturn(new StackHandler(1));
        when(furnace.getLitTime()).thenReturn(Collections.nCopies(LANES, 300));
        when(furnace.getCookTime()).thenReturn(new ArrayList<>(Collections.nCopies(LANES, 0)));
        when(player.level()).thenReturn(level);
        when(player.serverLevel()).thenReturn(level);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(level.hasChunkAt(POS)).thenReturn(true);
        when(level.getBlockEntity(POS)).thenReturn(furnace);
        when(level.getRecipeManager()).thenReturn(manager);
        when(manager.byKey(RECIPE)).thenAnswer(call -> Optional.of(recipe));
        delegate = new BdFurnaceBatchDelegate();
        assertEquals(PreparationState.READY, delegate.prepare(player, RECIPE, null, POS).state());
    }

    @AfterEach
    void release() {
        if (itemKeys != null) itemKeys.close();
    }

    @Test
    void enablingNativeReturnDuringCraftTakesOnlyTheActiveOrderOutput() {
        try (var session = TaskOutputReception.open()) {
            Runnable receive = start(session);
            finishNativeCycle(2);
            receive.run();
            assertEquals(2, outputs.getStackBySlot(0).amount());
            furnace.receiveMode = ReceiveMode.OPEN;
            receive.run();
            receive.run();
            delegate.outputSource().transferAvailable(level);

            assertEquals(1, outputs.getStackBySlot(0).amount());
            assertEquals(1, session.received(TaskOutputReception.resource(result)));
            assertEquals(CraftPhase.DONE, delegate.observe(level).phase());
            delegate.cleanup(player);
            verify(hook).rsi$setOutputReceiver(null);
            verify(hook).rsi$setInputConsumed(null);
        }
    }

    @Test
    void disabledReturnUsesNormalSlotCollection() {
        try (var session = TaskOutputReception.open()) {
            Runnable receive = start(session);
            finishNativeCycle(1);
            receive.run();
            assertEquals(0, session.received(TaskOutputReception.resource(result)));

            delegate.outputSource().transferAvailable(level);
            assertTrue(outputs.isEmpty());
            assertEquals(1, session.received(TaskOutputReception.resource(result)));
            assertEquals(CraftPhase.DONE, delegate.observe(level).phase());
        }
    }

    @Test
    void cancellationUnregistersReceptionAndRecoversUnprocessedInput() {
        try (var session = TaskOutputReception.open()) {
            Runnable receive = start(session);
            delegate.recover(player, "test cancellation");
            verify(hook).rsi$setOutputReceiver(null);
            assertTrue(inputs.isEmpty());
            assertEquals(1, delegate.failureRecoveredInputs().size());
            assertTrue(delegate.failureRecoveredInputs().get(0).is(Items.RAW_IRON));

            furnace.receiveMode = ReceiveMode.OPEN;
            outputs.insert(0, new ItemStackKey(result), 1, false);
            receive.run();
            assertEquals(0, session.received(TaskOutputReception.resource(result)));
            assertEquals(1, outputs.getStackBySlot(0).amount());
        }
    }

    @Test
    void cookingWrapperPassesMergedBatchToNineLanesAndCollectsIncrementally() {
        var wrapper = new CookingMachineBatchDelegate();
        assertEquals(PreparationState.READY, wrapper.prepare(player, RECIPE, null, POS).state());
        assertTrue(wrapper.supportsInputBuffer());
        assertTrue(wrapper.expandsFlatBatchOperationLimit());
        assertEquals(576, wrapper.flatBatchOperationLimit(1));
        assertEquals(10, wrapper.preferredParallelBatchSize(20, 2));
        assertEquals(10, wrapper.prepareFlatBatch(10));
        var plan = wrapper.inputBufferPlan(10);
        assertEquals(9, plan.inputs().size());
        var material = new ItemStack(Items.RAW_IRON, 10);
        material.getOrCreateTag().putString("variant", "reserved");

        try (var session = TaskOutputReception.open()) {
            wrapper.bindProduction(session.writer());
            assertTrue(wrapper.machineOperation().start(OperationStartContext.chainReserved(
                    player, mock(ExtractionLedger.class), wrapper.materialPlan(),
                    List.of(new ReservedMaterial("slot:0", material)), plan)));
            Runnable receive = captureCallbacks();
            assertEquals(2, inputs.getStackBySlot(0).amount());
            assertEquals(1, inputs.getStackBySlot(8).amount());
            assertTrue(ItemStack.isSameItemSameTags(material,
                    ((ItemStackKey) inputs.getStackBySlot(0).key()).getReadOnlyStack()));

            furnace.receiveMode = ReceiveMode.OPEN;
            finishNativeCycle(0, 1);
            receive.run();
            assertEquals(1, inputs.getStackBySlot(0).amount());
            assertEquals(1, session.received(TaskOutputReception.resource(result)));
            assertEquals(CraftPhase.WORKING, wrapper.observe(level).phase());
            for (int lane = 0; lane < LANES; lane++) finishNativeCycle(lane, 1);
            receive.run();

            assertEquals(10, session.received(TaskOutputReception.resource(result)));
            assertEquals(CraftPhase.DONE, wrapper.observe(level).phase());
            assertTrue(outputs.isEmpty());
        }
    }

    @Test
    void nativeRedistributionKeepsTheOrderAndCancellationReturnsOnlyUnprocessedInput() {
        try (var session = TaskOutputReception.open()) {
            delegate.bindProduction(session.writer());
            assertTrue(delegate.startPreparedOperation(player, List.of(new ItemStack(Items.RAW_IRON, 2)),
                    mock(ExtractionLedger.class)));
            captureCallbacks();
            var moved = inputs.extract(0, 1, false);
            inputs.insert(8, moved.key(), moved.amount(), false);
            finishNativeCycle(8, 1);
            delegate.outputSource().transferAvailable(level);
            assertEquals(1, session.received(TaskOutputReception.resource(result)));
            assertEquals(CraftPhase.WORKING, delegate.observe(level).phase());

            delegate.recover(player, "test cancellation after partial production");
            assertEquals(1, delegate.failureRecoveredInputs().stream().mapToInt(ItemStack::getCount).sum());
            assertTrue(inputs.isEmpty());
        }
    }

    @Test
    void exactUnstackableInputCannotOverflowPhysicalLanes() {
        try (var session = TaskOutputReception.open()) {
            delegate.bindProduction(session.writer());
            var replacement = new ItemStack(Items.IRON_SWORD, 10);
            InputBufferPlan bound = delegate.inputBufferPlan(10).withResolvedMaterials(
                    List.of(new ReservedMaterial("slot:0", replacement)));
            assertFalse(delegate.startBufferedOperation(player, bound, mock(ExtractionLedger.class)));
            assertTrue(inputs.isEmpty());
            delegate.recover(player, "test input capacity");
            assertEquals(10, delegate.failureRecoveredInputs().stream().mapToInt(ItemStack::getCount).sum());
        }
    }

    private Runnable start(TaskOutputReception.Session session) {
        delegate.bindProduction(session.writer());
        assertTrue(delegate.startPreparedOperation(player, List.of(new ItemStack(Items.RAW_IRON)),
                mock(ExtractionLedger.class)));
        return captureCallbacks();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Runnable captureCallbacks() {
        var listener = ArgumentCaptor.forClass(BiConsumer.class);
        verify(hook).rsi$setInputConsumed(listener.capture());
        consumed = listener.getValue();
        var callback = ArgumentCaptor.forClass(Runnable.class);
        verify(hook).rsi$setOutputReceiver(callback.capture());
        return callback.getValue();
    }

    private void finishNativeCycle(int amount) {
        finishNativeCycle(0, amount);
    }

    private void finishNativeCycle(int lane, int amount) {
        outputs.insert(lane, new ItemStackKey(result), amount, false);
        consumed.accept(lane, inputs.extract(lane, 1, false));
    }
}
