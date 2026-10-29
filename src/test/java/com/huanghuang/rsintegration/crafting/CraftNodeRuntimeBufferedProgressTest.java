package com.huanghuang.rsintegration.crafting;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.OperationStartContext;
import com.huanghuang.rsintegration.crafting.graph.ConcurrentNodeExecutor;
import com.huanghuang.rsintegration.crafting.graph.CaptureLeaseRegistry;
import com.huanghuang.rsintegration.crafting.graph.MachineLeaseRegistry;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
import com.huanghuang.rsintegration.crafting.graph.OperationBudget;
import com.huanghuang.rsintegration.crafting.graph.OperationId;
import com.huanghuang.rsintegration.crafting.loadbalancer.ParallelCraftGroup;
import com.huanghuang.rsintegration.mixin.minecraft.ItemEntityAccessor;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry.BoundMachine;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class CraftNodeRuntimeBufferedProgressTest extends BootstrapTest {
    private final AtomicLong tick = new AtomicLong();
    private ServerLevel level;
    private ServerPlayer player;
    private ItemEntity outputEntity;

    @BeforeAll
    static void initializeModRuntime() {
        resetConfig();
        FMLJavaModLoadingContext context = mock(FMLJavaModLoadingContext.class);
        when(context.getModEventBus()).thenReturn(mock(IEventBus.class));
        try (MockedStatic<FMLJavaModLoadingContext> loading = mockStatic(FMLJavaModLoadingContext.class)) {
            loading.when(FMLJavaModLoadingContext::get).thenReturn(context);
            assertNotNull(RSIntegrationMod.LOGGER);
        }
    }

    @BeforeEach
    void createContextWithShortIdleTimeout() {
        resetConfig();
        RSIntegrationConfig.MULTIBLOCK_CRAFT_TIMEOUT_SECONDS.set(10);
        MinecraftServer server = mock(MinecraftServer.class);
        level = mock(ServerLevel.class);
        player = mock(ServerPlayer.class);
        when(player.getServer()).thenReturn(server);
        when(player.serverLevel()).thenReturn(level);
        when(server.getTickCount()).thenAnswer(call -> Math.toIntExact(tick.get()));
        when(level.getGameTime()).thenAnswer(call -> tick.get());
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        outputEntity = mock(ItemEntity.class, withSettings().extraInterfaces(ItemEntityAccessor.class));
        when(outputEntity.position()).thenReturn(Vec3.ZERO);
    }

    @AfterEach
    void restoreConfig() {
        CraftOutputInterceptor.clearAll();
        resetConfig();
    }

    private static void resetConfig() {
        CommentedConfig config = CommentedConfig.inMemory();
        RSIntegrationConfig.SERVER_SPEC.correct(config);
        RSIntegrationConfig.SERVER_SPEC.setConfig(config);
    }

    @ParameterizedTest
    @ValueSource(ints = {129, 1024})
    void incrementalNativeOutputsKeepLongBufferedOrderAlive(int operations) {
        ParallelCraftGroup group = startGroup(operations);
        CraftNodeRuntime runtime = runtime(group);

        for (int operation = 0; operation < operations; operation++) {
            produceOutput();
            tick.incrementAndGet();
            ConcurrentNodeExecutor.Observation result = runtime.observe();
            assertTrue(runtime.consumeProgressSignal());
            if (operation + 1 == operations) {
                assertEquals(ConcurrentNodeExecutor.Observation.SUCCEEDED, result);
            } else {
                assertEquals(ConcurrentNodeExecutor.Observation.WORKING, result);
                assertEquals(0, runtime.waitTicks());
                tick.incrementAndGet();
                assertEquals(ConcurrentNodeExecutor.Observation.WORKING, runtime.observe());
                assertFalse(runtime.consumeProgressSignal());
            }
            if (operation < 63) {
                assertEquals(0, group.getCompletedOperations(),
                        "子批次尚未完成时，也必须观察到每一轮实际产物");
            }
        }
        assertTrue(tick.get() > timeoutTicks());
        assertEquals(operations, runtime.completedOperations());
    }

    @Test
    void repeatedSnapshotWithoutNewOutputStillTimesOut() {
        ParallelCraftGroup group = startGroup(129);
        CraftNodeRuntime runtime = runtime(group);
        produceOutput();
        assertEquals(ConcurrentNodeExecutor.Observation.WORKING, runtime.observe());
        assertTrue(runtime.consumeProgressSignal());

        for (int idle = 0; idle < timeoutTicks(); idle++) {
            tick.incrementAndGet();
            assertEquals(ConcurrentNodeExecutor.Observation.WORKING, runtime.observe());
            assertFalse(runtime.consumeProgressSignal());
        }
        tick.incrementAndGet();
        assertEquals(ConcurrentNodeExecutor.Observation.FAILED, runtime.observe());
        assertEquals(CraftFailureCode.TIMEOUT, runtime.failureCode());
        runtime.cleanupFailure();
    }

    @Test
    void leasedMachineQueueOutlivesStartDeadlineAndStillTimesOutAfterARealStall() {
        ParallelCraftGroup group = createGroup(129);
        MachineLeaseRegistry leases = new MachineLeaseRegistry();
        var machine = new MachineLeaseRegistry.MachineKey(
                Level.OVERWORLD.location(), BlockPos.ZERO, "test_buffered_progress");
        var lease = leases.tryAcquire(machine, new OperationId(UUID.randomUUID(), new NodeId(1), 0));
        group.setMachineLeases(leases);
        group.setOperationKernel(new OperationExecutionKernel(new OperationResourceCoordinator(
                        leases, new CaptureLeaseRegistry(), new OperationBudget(1, 10_000))),
                UUID.randomUUID(), new NodeId(0), new OperationBudget(1, 10_000));
        startGroup(group);
        CraftNodeRuntime runtime = runtime(group);

        for (int elapsed = 0; elapsed <= 1200; elapsed++) {
            tick.incrementAndGet();
            assertEquals(ConcurrentNodeExecutor.Observation.WORKING, runtime.observe());
            assertTrue(group.isWaitingForLeasedMachine());
            assertFalse(runtime.consumeProgressSignal());
            assertEquals(0, group.getRunningOperations());
        }
        assertTrue(leases.release(lease));
        tick.addAndGet(20);
        assertEquals(ConcurrentNodeExecutor.Observation.WORKING, runtime.observe());
        assertFalse(group.isWaitingForLeasedMachine());
        assertTrue(group.getRunningOperations() > 0);
        produceOutput();
        tick.incrementAndGet();
        assertEquals(ConcurrentNodeExecutor.Observation.WORKING, runtime.observe());
        assertTrue(runtime.consumeProgressSignal());
        for (int idle = 0; idle < timeoutTicks(); idle++) {
            tick.incrementAndGet();
            assertEquals(ConcurrentNodeExecutor.Observation.WORKING, runtime.observe());
        }
        tick.incrementAndGet();
        assertEquals(ConcurrentNodeExecutor.Observation.FAILED, runtime.observe());
        assertEquals(CraftFailureCode.TIMEOUT, runtime.failureCode());
        runtime.cleanupFailure();
        assertEquals(0, leases.size());
    }

    @Test
    void queueTimeDoesNotConsumeLaterMaterialRetryWindow() throws Exception {
        AsyncCraftChain chain = new AsyncCraftChain(
                UUID.randomUUID(), player.getServer(), null, List.of());
        String machineBusy = "all bound machines are busy, unloaded, or unavailable";
        assertEquals(ConcurrentNodeExecutor.StartStatus.RETRY, retry(chain, machineBusy));
        tick.set(1000);
        assertEquals(ConcurrentNodeExecutor.StartStatus.RETRY, retry(chain, machineBusy));
        assertEquals(ConcurrentNodeExecutor.StartStatus.RETRY, retry(chain, "missing material"));
        tick.set(1600);
        assertEquals(ConcurrentNodeExecutor.StartStatus.RETRY, retry(chain, "missing material"));
        tick.set(1601);
        assertEquals(ConcurrentNodeExecutor.StartStatus.FAILED, retry(chain, "missing material"));
    }

    private static ConcurrentNodeExecutor.StartStatus retry(
            AsyncCraftChain chain, String detail) throws Exception {
        Method method = AsyncCraftChain.class.getDeclaredMethod(
                "graphRetry", NodeId.class, String.class, List.class);
        method.setAccessible(true);
        return ((ConcurrentNodeExecutor.StartResult) method.invoke(
                chain, new NodeId(0), detail, List.of())).status();
    }

    private static int timeoutTicks() {
        return RSIntegrationConfig.MULTIBLOCK_CRAFT_TIMEOUT_SECONDS.get() * 20;
    }

    private CraftNodeRuntime runtime(ParallelCraftGroup group) {
        CraftNodeRuntime runtime = new CraftNodeRuntime(
                new NodeId(0), "malum:spirit_crucible/large_order", group, null, null);
        runtime.setChainContext(new ArrayList<>(), player);
        return runtime;
    }

    private void produceOutput() {
        when(outputEntity.getItem()).thenReturn(new ItemStack(Items.DIAMOND, 8));
        EntityJoinLevelEvent event = new EntityJoinLevelEvent(outputEntity, level);
        CraftOutputInterceptor.onEntityJoin(event);
        assertTrue(event.isCanceled());
    }

    private ParallelCraftGroup startGroup(int operations) {
        ParallelCraftGroup group = createGroup(operations);
        startGroup(group);
        return group;
    }

    private ParallelCraftGroup createGroup(int operations) {
        ModType type = ModType.register("test_buffered_progress",
                new String[0], new String[0], new String[0], BufferedDelegate::new);
        BoundMachine machine = new BoundMachine(new ResourceLocation("minecraft", "overworld"),
                BlockPos.ZERO, type, "test");
        return new ParallelCraftGroup(
                List.of(machine), type, new ResourceLocation("test", "buffered"), player, operations);
    }

    private void startGroup(ParallelCraftGroup group) {
        int operations = group.getTotalOperations();
        group.setReservationTokens(Collections.nCopies(
                operations, mock(ExtractionLedger.ReservationToken.class)));
        group.setVirtualDebits(Collections.nCopies(operations, List.of()));
        ExtractionLedger ledger = mock(ExtractionLedger.class);
        List<ItemStack> materials = Collections.nCopies(operations, new ItemStack(Items.IRON_INGOT));
        assertTrue(group.startOperation(OperationStartContext.repeatedChainReserved(
                player, ledger, group.operationMaterialPlan(), operations, 1, materials)));
    }

    private static final class BufferedDelegate implements IBatchDelegate {
        private int batchSize = 1;

        @Override
        public boolean validateAndInit(ServerPlayer player, ResourceLocation recipeId,
                                       ResourceLocation dimension, BlockPos pos) {
            return true;
        }

        @Override
        public List<IngredientSpec> getRequiredMaterials() {
            return List.of(new IngredientSpec(Ingredient.of(Items.IRON_INGOT), 1));
        }

        @Override
        public int preferredParallelBatchSize(int operations, int workers) {
            return 64;
        }

        @Override
        public void prepareGraphBatch(int executions) {
            batchSize = executions;
        }

        @Override
        public boolean startOperation(OperationStartContext context) {
            return true;
        }

        @Override
        public boolean isCraftComplete(ServerLevel level) {
            return false;
        }

        @Override
        public ItemStack getExpectedOutput() {
            return new ItemStack(Items.DIAMOND, 8);
        }

        @Override
        public ExpectedProduction getExpectedProduction() {
            return new ExpectedProduction(new ItemStack(Items.DIAMOND), 8 * batchSize);
        }

        @Override
        public AABB getOutputCaptureRegion() {
            return new AABB(BlockPos.ZERO).inflate(3);
        }

        @Override
        public ItemStack collectResult(ServerPlayer player) {
            return ItemStack.EMPTY;
        }

        @Override
        public void onBatchFailed(ServerPlayer player, String reason) {}

        @Override
        public void onBatchFinished(ServerPlayer player) {}

        @Override
        public BlockPos getMachinePos() {
            return BlockPos.ZERO;
        }
    }
}
