package com.huanghuang.rsintegration.mods.vanilla;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.TaskOutputReception;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate.CraftPhase;
import com.huanghuang.rsintegration.crafting.batch.MaterialPlan;
import com.huanghuang.rsintegration.crafting.batch.OperationStartContext;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoints;
import com.huanghuang.rsintegration.storage.rs.RefinedStorageOutputReception;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.util.Action;
import com.huanghuang.rsintegration.mixin.minecraft.AbstractFurnaceAccessor;
import com.huanghuang.rsintegration.storage.StorageBackendId;
import com.huanghuang.rsintegration.storage.StorageItemKey;
import com.huanghuang.rsintegration.storage.StorageOperationMode;
import com.huanghuang.rsintegration.storage.StorageOperationResult;
import com.huanghuang.rsintegration.storage.StorageSnapshot;
import com.huanghuang.rsintegration.storage.StorageSnapshotResult;
import com.huanghuang.rsintegration.storage.StoredItem;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CookingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 通过实际缓冲批次启动入口验证燃料数量，防止把快照展示栈当成库存。 */
class VanillaMachineFuelTest extends BootstrapTest {
    private static final StorageBackendId BACKEND = new StorageBackendId("test");
    private static final BlockPos POS = new BlockPos(-1, -60, 1);

    private VanillaMachineBatchDelegate delegate;
    private TestFurnace furnace;
    private ServerLevel level;
    private ServerPlayer player;
    private CraftStorageEndpoint endpoint;
    private MockedStatic<ForgeHooks> fuels;

    @BeforeAll
    static void configure() throws Exception {
        CommentedConfig config = CommentedConfig.inMemory();
        RSIntegrationConfig.SERVER_SPEC.correct(config);
        RSIntegrationConfig.SERVER_SPEC.setConfig(config);
        FMLJavaModLoadingContext context = mock(FMLJavaModLoadingContext.class);
        when(context.getModEventBus()).thenReturn(mock(IEventBus.class));
        try (MockedStatic<FMLJavaModLoadingContext> loading = mockStatic(FMLJavaModLoadingContext.class)) {
            loading.when(FMLJavaModLoadingContext::get).thenReturn(context);
            Class.forName(RSIntegrationMod.class.getName());
        }
    }

    @BeforeEach
    void createFurnace() throws Exception {
        // 纯单测不启动 Forge 的燃料表注册，固定煤炭的原版燃烧时间。
        fuels = mockStatic(ForgeHooks.class);
        fuels.when(() -> ForgeHooks.getBurnTime(any(ItemStack.class), any()))
                .thenAnswer(call -> call.getArgument(0, ItemStack.class).is(Items.COAL) ? 1600 : 0);
        delegate = new VanillaMachineBatchDelegate();
        furnace = new TestFurnace();
        player = mock(ServerPlayer.class);
        endpoint = mock(CraftStorageEndpoint.class);
        level = mock(ServerLevel.class);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(player.serverLevel()).thenReturn(level);
        when(level.hasChunkAt(POS)).thenReturn(true);
        when(level.getBlockEntity(POS)).thenReturn(furnace);
        when(level.getBlockState(POS)).thenReturn(Blocks.FURNACE.defaultBlockState());
        when(level.getRecipeManager()).thenReturn(new RecipeManager());
        furnace.setLevel(level);
        setField("myLevel", level);
        setField("myPos", POS);
        setField("furnaceBE", furnace);
        setField("recipe", new SmeltingRecipe(
                new ResourceLocation("minecraft", "iron_ingot_from_smelting_iron_ore"),
                "", CookingBookCategory.MISC, Ingredient.of(Items.IRON_ORE),
                new ItemStack(Items.IRON_INGOT), 0.7F, 200));
        Field kind = VanillaMachineBatchDelegate.class.getDeclaredField("kind");
        kind.setAccessible(true);
        kind.set(delegate, Arrays.stream(kind.getType().getEnumConstants())
                .filter(value -> ((Enum<?>) value).name().equals("FURNACE"))
                .findFirst().orElseThrow());
        delegate.setStorageEndpoint(endpoint);
        when(endpoint.insert(eq(player), any(ItemStack.class), eq(false)))
                .thenAnswer(call -> StorageOperationResult.inserted(
                        StorageOperationMode.PERFORM, call.getArgument(1, ItemStack.class), ItemStack.EMPTY));
        when(endpoint.extractExact(eq(player), any(ItemStack.class), anyLong(), eq(false)))
                .thenAnswer(call -> StorageOperationResult.extracted(
                        StorageOperationMode.PERFORM, call.getArgument(2, Long.class),
                        List.of(call.getArgument(1, ItemStack.class)
                                .copyWithCount(Math.toIntExact(call.getArgument(2, Long.class))))));
    }

    @AfterEach
    void closeFuelHook() {
        if (fuels != null) fuels.close();
    }

    @ParameterizedTest
    @CsvSource({"11,64,2", "64,64,8", "64,9223372036854775807,8"})
    void bufferedBatchUsesActualSnapshotAmount(int operations, long stock, int expectedFuel) {
        StorageSnapshot snapshot = stock(stock);
        assertEquals(1, snapshot.items().get(0).stack().getCount());

        assertTrue(start(operations));

        assertEquals(operations, furnace.getItem(0).getCount());
        assertTrue(furnace.getItem(1).is(Items.COAL));
        assertEquals(expectedFuel, furnace.getItem(1).getCount());
        assertEquals(operations, delegate.getExpectedProduction().count());
        assertNull(delegate.lastStartFailureDetail());
        verify(endpoint).extractExact(eq(player), argThat(stack -> stack.is(Items.COAL)),
                eq((long) expectedFuel), eq(false));
        assertEquals(stock, snapshot.items().get(0).amount());
        assertEquals(1, snapshot.items().get(0).stack().getCount());
    }

    @Test
    void genuinelyInsufficientFuelRejectsBatchAndRemovesInput() {
        stock(1);

        assertFalse(start(11));

        assertTrue(furnace.getItem(0).isEmpty());
        assertTrue(furnace.getItem(1).isEmpty());
        assertNotNull(delegate.lastStartFailureDetail());
        assertTrue(delegate.lastStartFailureDetail().contains("燃料"));
        assertTrue(delegate.lastStartFailureDetail().contains("操作数=11"));
        assertTrue(delegate.lastStartFailureDetail().contains("minecraft:iron_ingot_from_smelting_iron_ore"));
        verify(endpoint, never()).extractExact(any(ServerPlayer.class), any(ItemStack.class),
                anyLong(), anyBoolean());
    }

    @Test
    void emptyStorageRejectsBatch() {
        stock(0);

        assertFalse(start(11));

        assertTrue(furnace.getItem(0).isEmpty());
        assertTrue(furnace.getItem(1).isEmpty());
    }

    @Test
    void existingFuelIsToppedUpOnlyByMissingAmount() {
        stock(64);
        furnace.setItem(1, new ItemStack(Items.COAL));

        assertTrue(start(11));

        assertEquals(2, furnace.getItem(1).getCount());
        verify(endpoint).extractExact(eq(player), argThat(stack -> stack.is(Items.COAL)),
                eq(1L), eq(false));
        verify(endpoint, never()).snapshot(any(ServerPlayer.class));
    }

    @Test
    void successfulRetryClearsPreviousFuelFailure() {
        stock(1);
        assertFalse(start(11));
        assertNotNull(delegate.lastStartFailureDetail());

        stock(64);
        assertTrue(start(11));

        assertNull(delegate.lastStartFailureDetail());
        assertEquals(11, furnace.getItem(0).getCount());
        assertEquals(2, furnace.getItem(1).getCount());
    }

    @Test
    void collectedOutputNeedsConsumedInputAndIdleTimerOnDifferentTicks() throws Exception {
        stock(64);
        try (var session = TaskOutputReception.open()) {
            delegate.setOutputReception(session);
            assertTrue(delegate.supportsOutputReception());
            assertTrue(start(11));
            assertEquals(11, session.current().remaining("vanilla:primary"));
            furnace.setItem(2, new ItemStack(Items.IRON_INGOT, 11));
            assertEquals(CraftPhase.WORKING, observe(1));
            assertEquals(11, session.current().received("vanilla:primary"));
            assertTrue(furnace.getItem(2).isEmpty());

            furnace.setItem(0, ItemStack.EMPTY);
            furnace.progress = 10;
            assertEquals(CraftPhase.WORKING, observe(2));
            furnace.progress = 0;
            // 余火不是加工状态；不能等到燃料完全熄灭才完成。
            Field litTime = FurnaceBlockEntity.class.getSuperclass().getDeclaredField("litTime");
            litTime.setAccessible(true);
            litTime.setInt(furnace, 1200);
            assertEquals(CraftPhase.WORKING, observe(3));
            assertEquals(CraftPhase.WORKING, observe(3));
            assertEquals(CraftPhase.DONE, observe(4));
            assertTrue(delegate.collectStructuredResults(player).isEmpty());
            assertEquals(11, delegate.drainReceivedOutputs().get(0).getCount());
            assertTrue(delegate.drainReceivedOutputs().isEmpty());
        }
    }

    @Test
    void partialCollectionUsesTheActualPhysicalBatchOnly() {
        stock(64);
        delegate.prepareGraphBatch(64);
        try (var session = TaskOutputReception.open()) {
            delegate.setOutputReception(session);
            assertTrue(start(11));
            assertEquals(11, session.current().remaining("vanilla:primary"));
            furnace.setItem(2, new ItemStack(Items.IRON_INGOT, 5));
            assertEquals(CraftPhase.WORKING, observe(1));
            furnace.setItem(2, new ItemStack(Items.IRON_INGOT, 6));
            assertEquals(CraftPhase.WORKING, observe(2));
            assertTrue(furnace.getItem(2).isEmpty());
            assertEquals(11, session.current().received("vanilla:primary"));
            assertEquals(0, session.current().remaining("vanilla:primary"));
            assertEquals(11, delegate.drainReceivedOutputs().get(0).getCount());
            furnace.setItem(0, ItemStack.EMPTY);
            assertEquals(CraftPhase.WORKING, observe(3));
            assertEquals(CraftPhase.DONE, observe(4));
            assertTrue(delegate.collectAllResults(player).isEmpty());
            assertTrue(delegate.drainReceivedOutputs().isEmpty());
        }
    }

    @Test
    void consumedInputWithoutDeliveryStillWaitsAndLateOutputIsCollected() {
        stock(64);
        try (var session = TaskOutputReception.open()) {
            delegate.setOutputReception(session);
            assertTrue(start(2));
            furnace.setItem(0, ItemStack.EMPTY);
            assertEquals(CraftPhase.WORKING, observe(1));
            assertEquals(CraftPhase.WORKING, observe(2));
            furnace.setItem(2, new ItemStack(Items.IRON_INGOT, 2));
            assertEquals(CraftPhase.DONE, observe(3));
            assertEquals(2, delegate.drainReceivedOutputs().get(0).getCount());
        }
    }

    @Test
    void actualOutputBeyondTheDeclarationIsNotDiscarded() {
        stock(64);
        try (var session = TaskOutputReception.open()) {
            delegate.setOutputReception(session);
            assertTrue(start(2));
            furnace.setItem(0, ItemStack.EMPTY);
            furnace.setItem(2, new ItemStack(Items.IRON_INGOT, 4));
            assertEquals(CraftPhase.WORKING, observe(1));
            assertEquals(CraftPhase.DONE, observe(2));
            assertEquals(4, delegate.drainReceivedOutputs().get(0).getCount());
        }
    }

    @Test
    void preparedReceptionSurvivesSynchronousDeliveryAndFailedFuelAdmission() {
        stock(0);
        try (var session = TaskOutputReception.open()) {
            delegate.setOutputReception(session);
            furnace.onInputInserted = () -> {
                assertNotNull(session.current());
                assertFalse(session.current().complete());
                // 模拟受控收取在投料回调中已经移交实物，后续启动失败仍须保留。
                assertEquals(2, session.current().collect(TaskOutputReception.resource(
                        new ItemStack(Items.IRON_INGOT)), 2));
            };
            var plan = delegate.inputBufferPlan(2);
            var context = OperationStartContext.chainReserved(player, mock(ExtractionLedger.class),
                    MaterialPlan.fromLegacy(delegate.getRequiredMaterials()),
                    List.of(new ItemStack(Items.IRON_ORE, 2)), plan);
            assertFalse(delegate.startOperation(context));
            assertTrue(session.closed());
            assertTrue(furnace.getItem(0).isEmpty());
            assertEquals(2, delegate.drainReceivedOutputs().get(0).getCount());
            assertTrue(delegate.drainReceivedOutputs().isEmpty());
        }
    }

    @Test
    void cancellationPreservesAlreadyReceivedResourcesAndRecoversOwnedInput() {
        stock(64);
        try (var session = TaskOutputReception.open()) {
            delegate.setOutputReception(session);
            assertTrue(start(3));
            furnace.setItem(0, new ItemStack(Items.IRON_ORE, 2));
            furnace.setItem(2, new ItemStack(Items.IRON_INGOT));
            assertEquals(CraftPhase.WORKING, observe(1));
            delegate.onBatchFailed(player, "cancelled");
            assertTrue(session.closed());
            assertTrue(furnace.getItem(0).isEmpty());
            assertEquals(2, delegate.failureRecoveredInputs().get(0).getCount());
            assertEquals(1, delegate.drainReceivedOutputs().get(0).getCount());
            assertTrue(delegate.drainReceivedOutputs().isEmpty());
        }
    }

    @Test
    void differentNbtAndAReplacedMachineCannotCompleteTheBatch() {
        stock(64);
        try (var session = TaskOutputReception.open()) {
            delegate.setOutputReception(session);
            assertTrue(start(1));
            ItemStack tagged = new ItemStack(Items.IRON_INGOT);
            tagged.getOrCreateTag().putString("owner", "other");
            furnace.setItem(2, tagged);
            assertEquals(CraftPhase.FAILED, observe(1));
            assertEquals(1, furnace.getItem(2).getCount());
            assertTrue(delegate.drainReceivedOutputs().isEmpty());
            assertEquals(CraftPhase.FAILED,
                    delegate.observeMachineCraft(level, new TestFurnace()).phase());
        }
    }

    @Test
    void oldEntryWithoutReceptionStillReturnsTheActualSlotOutput() {
        stock(64);
        assertTrue(start(2));
        furnace.setItem(0, ItemStack.EMPTY);
        furnace.setItem(2, new ItemStack(Items.IRON_INGOT, 2));
        assertEquals(CraftPhase.DONE, observe(1));
        assertEquals(2, delegate.collectResult(player).getCount());
        assertTrue(furnace.getItem(2).isEmpty());
        assertTrue(delegate.drainReceivedOutputs().isEmpty());
    }

    @Test
    void virtualMachinesAndMachinesWithoutTimerAreNotOptedIn() throws Exception {
        assertTrue(delegate.supportsOutputReception());
        setField("furnaceBE", new FurnaceBlockEntity(POS, Blocks.FURNACE.defaultBlockState()));
        assertFalse(delegate.supportsOutputReception());
        setField("furnaceBE", furnace);
        Field kind = VanillaMachineBatchDelegate.class.getDeclaredField("kind");
        kind.setAccessible(true);
        for (Object value : kind.getType().getEnumConstants()) {
            if (((Enum<?>) value).name().equals("FURNACE")) continue;
            kind.set(delegate, value);
            assertFalse(delegate.supportsOutputReception());
        }
    }

    private CraftPhase observe(long tick) {
        when(level.getGameTime()).thenReturn(tick);
        delegate.transferOutputs(level);
        return delegate.observeMachineCraft(level, furnace).phase();
    }

    @Test
    void observationDoesNotExtractAndNetworkDeliveryDoesNotFinishRunningMachine() {
        stock(64);
        Object network = new Object();
        try (var session = TaskOutputReception.open()) {
            session.bindNetwork(network, () -> true);
            delegate.setOutputReception(session);
            assertTrue(start(4));
            furnace.setItem(2, new ItemStack(Items.IRON_INGOT, 2));
            assertEquals(CraftPhase.WORKING, delegate.observeMachineCraft(level, furnace).phase());
            assertEquals(2, furnace.getItem(2).getCount());
            assertEquals(0, session.current().received("vanilla:primary"));
            delegate.transferOutputs(level);
            assertTrue(furnace.getItem(2).isEmpty());
            assertEquals(2, TaskOutputReception.receive(network, TaskOutputReception.resource(
                    new ItemStack(Items.IRON_INGOT)), 3, false));
            assertEquals(CraftPhase.WORKING, observe(1));
            assertEquals(CraftPhase.WORKING, observe(2));
            assertEquals(4, session.drainStacks().get(0).getCount());
            furnace.setItem(0, ItemStack.EMPTY);
            assertEquals(CraftPhase.WORKING, observe(3));
            assertEquals(CraftPhase.DONE, observe(4));
            assertTrue(delegate.collectAllResults(player).isEmpty());
            assertTrue(delegate.drainReceivedOutputs().isEmpty());
        }
    }

    private StorageSnapshot stock(long amount) {
        StorageSnapshot snapshot = new StorageSnapshot(BACKEND, amount == 0 ? List.of()
                : List.of(new StoredItem(StorageItemKey.fromItemStack(
                        BACKEND, new ItemStack(Items.COAL)), amount)));
        when(endpoint.snapshot(player)).thenReturn(StorageSnapshotResult.success(snapshot));
        return snapshot;
    }

    @Test
    void operationAutomaticallyBindsRsAndSettlesMixedPhysicalDeliveryOnlyOnce() {
        stock(64);
        INetwork network = mock(INetwork.class, RETURNS_DEEP_STUBS);
        when(network.canRun()).thenReturn(true);
        when(network.getLevel()).thenReturn(level);
        when(network.getPosition()).thenReturn(POS);
        var rsEndpoint = CraftStorageEndpoints.fromResolvedNetwork(network);
        when(endpoint.session()).thenReturn(rsEndpoint.session());
        // 使用正式 RS 会话，磁盘拒收余量；不手工绑定任务。
        when(network.insertItem(any(), anyInt(), any())).thenAnswer(call ->
                RefinedStorageOutputReception.insertItem(network, call.getArgument(0), call.getArgument(1),
                        call.getArgument(2), (stack, count) -> stack.copyWithCount(count)));
        var plan = delegate.inputBufferPlan(4);
        assertTrue(delegate.startOperation(OperationStartContext.chainReserved(player, mock(ExtractionLedger.class),
                MaterialPlan.fromLegacy(delegate.getRequiredMaterials()),
                List.of(new ItemStack(Items.IRON_ORE, 4)), plan)));
        try (var session = delegate.outputReception()) {
            ItemStack iron = new ItemStack(Items.IRON_INGOT);
            assertEquals(1, network.insertItem(iron, 5, Action.SIMULATE).getCount());
            assertEquals(0, session.received(TaskOutputReception.resource(iron)));
            furnace.setItem(2, iron.copyWithCount(2));
            assertEquals(CraftPhase.WORKING, delegate.observeCraft(level).phase());
            assertEquals(2, furnace.getItem(2).getCount());
            delegate.transferOutputs(level);
            delegate.transferOutputs(level);
            assertEquals(2, session.received(TaskOutputReception.resource(iron)));
            assertEquals(1, network.insertItem(iron, 3, Action.PERFORM).getCount());
            assertEquals(CraftPhase.WORKING, observe(1));
            furnace.setItem(0, ItemStack.EMPTY);
            assertEquals(CraftPhase.WORKING, observe(2));
            assertEquals(CraftPhase.DONE, observe(3));
            assertTrue(delegate.collectAllResults(player).isEmpty());
            assertEquals(4, delegate.drainReceivedOutputs().get(0).getCount());
            // 已关闭批次不能在重复结算时移走后来放入机器的物品。
            furnace.setItem(2, iron.copy());
            assertTrue(delegate.collectAllResults(player).isEmpty());
            delegate.transferOutputs(level);
            assertEquals(1, furnace.getItem(2).getCount());
            assertTrue(delegate.drainReceivedOutputs().isEmpty());
            assertEquals(2, network.insertItem(iron, 2, Action.PERFORM).getCount());
            delegate.onBatchFinished(player);
            assertTrue(session.closed());
        }
    }

    private boolean start(int operations) {
        return delegate.startBufferedOperation(player, delegate.inputBufferPlan(operations),
                mock(ExtractionLedger.class));
    }

    private void setField(String name, Object value) throws Exception {
        Field field = VanillaMachineBatchDelegate.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(delegate, value);
    }

    private static final class TestFurnace extends FurnaceBlockEntity implements AbstractFurnaceAccessor {
        private int progress;
        private Runnable onInputInserted;

        private TestFurnace() {
            super(POS, Blocks.FURNACE.defaultBlockState());
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
            super.setItem(slot, stack);
            if (slot == 0 && !stack.isEmpty() && onInputInserted != null) onInputInserted.run();
        }

        @Override public int rsi$getCookingProgress() { return progress; }
        @Override public int rsi$getCookingTotalTime() { return 200; }
        @Override public int rsi$callGetBurnDuration(ItemStack stack) { return stack.is(Items.COAL) ? 1600 : 0; }
    }
}
