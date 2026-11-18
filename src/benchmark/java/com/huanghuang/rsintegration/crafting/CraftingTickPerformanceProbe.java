package com.huanghuang.rsintegration.crafting;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities;
import com.huanghuang.rsintegration.crafting.batch.OperationStartContext;
import com.huanghuang.rsintegration.crafting.graph.ConcurrentNodeExecutor;
import com.huanghuang.rsintegration.crafting.graph.CraftNode;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.crafting.graph.DagScheduler;
import com.huanghuang.rsintegration.crafting.graph.MaterialBroker;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.graph.NodeAdmissionCoordinator;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
import com.huanghuang.rsintegration.crafting.graph.NodeOutputAccumulator;
import com.huanghuang.rsintegration.crafting.graph.OutputDeclaration;
import com.huanghuang.rsintegration.crafting.graph.OutputKind;
import com.huanghuang.rsintegration.crafting.graph.OutputPortId;
import com.huanghuang.rsintegration.crafting.graph.RootAllocation;
import com.huanghuang.rsintegration.crafting.graph.RootDemand;
import com.huanghuang.rsintegration.crafting.graph.MaterialSource;
import com.huanghuang.rsintegration.crafting.loadbalancer.ParallelCraftGroup;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry.BoundMachine;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.mockito.MockedStatic;

import java.io.PrintWriter;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * 测量真实执行器、节点运行时及发布/结算入口，不包含世界 tick、机器扫描和网络 I/O。
 * 机器用真实物品槽位替身，玩家/世界边界用 Mockito；不以延时或忙等伪造机器成本。
 * 非预算模式的多任务统计为总工作量；预算模式模拟共享窗口，均不能等同于游戏真实 tick。
 */
public final class CraftingTickPerformanceProbe extends BootstrapTest {
    private static final int WARMUP = 24;
    private static final int SAMPLES = 41;
    private static final long BUDGET_NANOS = 8_000_000L;
    private static final MethodHandle PUBLICATION = method("publishIncrementalGraphOutputs");
    private static final MethodHandle COMPLETION = method("completeGraphNode");
    private static final MethodHandle ALLOWANCE = allowanceSetter();

    private CraftingTickPerformanceProbe() {}

    public static void main(String[] args) throws Throwable {
        bootstrapMinecraft();
        CommentedConfig config = CommentedConfig.inMemory();
        RSIntegrationConfig.SERVER_SPEC.correct(config);
        RSIntegrationConfig.SERVER_SPEC.setConfig(config);
        FMLJavaModLoadingContext context = mock(FMLJavaModLoadingContext.class);
        when(context.getModEventBus()).thenReturn(mock(IEventBus.class));
        try (MockedStatic<FMLJavaModLoadingContext> loading = mockStatic(FMLJavaModLoadingContext.class)) {
            loading.when(FMLJavaModLoadingContext::get).thenReturn(context);
            Class.forName("com.huanghuang.rsintegration.RSIntegrationMod");
        }
        MinecraftServer server = mock(MinecraftServer.class, withSettings().stubOnly());
        when(server.getPlayerList()).thenReturn(mock(PlayerList.class, withSettings().stubOnly()));
        ServerLevel level = mock(ServerLevel.class, withSettings().stubOnly());
        ServerPlayer player = mock(ServerPlayer.class, withSettings().stubOnly());
        when(player.getServer()).thenReturn(server);
        when(player.serverLevel()).thenReturn(level);
        AtomicLong tick = new AtomicLong();
        when(level.getGameTime()).thenAnswer(call -> tick.get());

        Path output = Path.of(args.length == 0
                ? "build/benchmark/execution-tick/baseline.csv" : args[0]);
        Files.createDirectories(output.toAbsolutePath().getParent());
        try (PrintWriter csv = new PrintWriter(Files.newBufferedWriter(output, StandardCharsets.UTF_8))) {
            if (args.length > 1 && "group".equals(args[1])) {
                measureGroups(csv, player, level, tick);
                return;
            }
            String header = "tasks,nodes_per_task,tag_fields,scenario,samples,median_ms,p95_ms,max_ms,"
                    + "observe_median_ms,publish_median_ms,settle_median_ms,over_8ms,budgeted,"
                    + "median_slices,slice_p95_ms,slice_max_ms,workers_per_node,different_items";
            csv.println(header);
            System.out.println(header);
            if (args.length > 1 && "group-budget".equals(args[1])) {
                for (int count : new int[]{4, 32, 128, 512}) {
                    for (int tags : new int[]{0, 32}) {
                        measure(csv, player, 1, 1, tags, true, true, count, tick, false);
                    }
                }
                return;
            }
            boolean budgeted = args.length > 5 && Boolean.parseBoolean(args[5]);
            if (args.length >= 5) {
                measure(csv, player, Integer.parseInt(args[1]), Integer.parseInt(args[2]),
                        Integer.parseInt(args[3]), Boolean.parseBoolean(args[4]), budgeted, 1, tick,
                        args.length > 6 && Boolean.parseBoolean(args[6]));
                return;
            }
            for (int tasks : new int[]{1, 4}) {
                for (int nodes : new int[]{4, 32, 128, 512}) {
                    for (int tags : new int[]{0, 32}) {
                        for (boolean complete : new boolean[]{false, true}) {
                            measure(csv, player, tasks, nodes, tags, complete, budgeted);
                        }
                    }
                }
            }
        }
    }

    private static void measureGroups(PrintWriter csv, ServerPlayer player, ServerLevel level,
                                      AtomicLong tick) {
        csv.println("workers,tag_fields,scenario,median_ms,p95_ms,max_ms");
        for (int count : new int[]{4, 32, 128, 512}) {
            for (int tags : new int[]{0, 32}) {
                for (boolean complete : new boolean[]{false, true}) {
                    long[] times = new long[SAMPLES];
                    ItemStack result = new ItemStack(Items.IRON_INGOT, 64);
                    for (int i = 0; i < tags; i++) {
                        result.getOrCreateTag().putString("field_" + i, "value_" + i);
                    }
                    for (int round = -WARMUP; round < SAMPLES; round++) {
                        List<BoundMachine> machines = new ArrayList<>();
                        List<SlotDelegate> delegates = new ArrayList<>();
                        for (int machine = 0; machine < count; machine++) {
                            machines.add(new BoundMachine(Level.OVERWORLD.location(),
                                    new BlockPos(machine, 64, 0), ModType.CUSTOM_GUI, "probe"));
                        }
                        ParallelCraftGroup group = new ParallelCraftGroup(machines, ModType.CUSTOM_GUI,
                                new ResourceLocation("tick_probe", "group"), player, count, false,
                                BatchConcurrencyCapabilities.machineSlot(), null, count, machine -> {
                                    SlotDelegate delegate = new SlotDelegate();
                                    delegates.add(delegate);
                                    return delegate;
                                });
                        check(group.startOperation(OperationStartContext.delegateExtracted(
                                player, ExtractionLedger.external())));
                        while (group.getRunningOperations() < count) {
                            tick.incrementAndGet();
                            group.observeCraft(level);
                        }
                        if (complete) {
                            for (SlotDelegate delegate : delegates) delegate.output.setItem(0, result.copy());
                        }
                        NodeId node = new NodeId(0);
                        OutputPortId port = new OutputPortId(node, 0);
                        MaterialKey key = MaterialKey.of(result);
                        MaterialBroker broker = new MaterialBroker();
                        CraftNodeRuntime runtime = new CraftNodeRuntime(node, "group_probe",
                                group, null, null);
                        runtime.setChainContext(new ArrayList<>(), player);
                        runtime.attachOutputs(new NodeOutputAccumulator(List.of(OutputDeclaration.item(
                                port, key, (long) count * 64, OutputKind.PRIMARY))));
                        tick.incrementAndGet();
                        long started = System.nanoTime();
                        ConcurrentNodeExecutor.Observation observation = runtime.observe();
                        for (NodeOutputAccumulator.Publication publication : runtime.drainIncrementalOutputs()) {
                            broker.publishActual(new MaterialSource.ProducerOutput(publication.port()),
                                    publication.material(), publication.stack());
                        }
                        long elapsed = System.nanoTime() - started;
                        check((observation == ConcurrentNodeExecutor.Observation.SUCCEEDED) == complete);
                        check(group.getCompletedOperations() == (complete ? count : 0));
                        check(broker.totalAvailable() == (complete ? (long) count * 64 : 0L));
                        if (round >= 0) times[round] = elapsed;
                    }
                    Arrays.sort(times);
                    String line = String.format(Locale.ROOT, "%d,%d,%s,%.6f,%.6f,%.6f",
                            count, tags, complete ? "complete" : "waiting", ms(times[SAMPLES / 2]),
                            ms(times[(int) (SAMPLES * 0.95)]), ms(times[SAMPLES - 1]));
                    csv.println(line);
                    csv.flush();
                    System.out.println(line);
                }
            }
        }
    }

    private static void measure(PrintWriter csv, ServerPlayer player, int tasks,
                                int nodes, int tags, boolean complete, boolean budgeted) throws Throwable {
        measure(csv, player, tasks, nodes, tags, complete, budgeted, 1, null, false);
    }

    private static void measure(PrintWriter csv, ServerPlayer player, int tasks,
                                int nodes, int tags, boolean complete, boolean budgeted,
                                int groupSize, AtomicLong tick, boolean differentItems) throws Throwable {
        List<ItemStack> results = new ArrayList<>();
        List<CraftPlanGraph> graphs = new ArrayList<>();
        Item[] items = {Items.IRON_INGOT, Items.GOLD_INGOT, Items.COPPER_INGOT, Items.DIAMOND};
        for (int task = 0; task < tasks; task++) {
            ItemStack result = new ItemStack(items[differentItems ? task % items.length : 0], 64);
            for (int field = 0; field < tags; field++) {
                result.getOrCreateTag().putString("field_" + field, "value_" + field);
            }
            results.add(result);
            graphs.add(graph(nodes, result, (long) groupSize * 64));
        }
        long[][] timings = new long[4][SAMPLES];
        int[] sliceCounts = new int[SAMPLES];
        List<Long> slices = new ArrayList<>();
        int overBudget = 0;
        for (int round = -WARMUP; round < SAMPLES; round++) {
            List<Fixture> fixtures = new ArrayList<>();
            for (int task = 0; task < tasks; task++) {
                fixtures.add(new Fixture(graphs.get(task), player, results.get(task),
                        complete, groupSize, tick, budgeted));
            }
            long started = System.nanoTime();
            int sliceCount = 0;
            int cursor = 0;
            do {
                long sliceStarted = System.nanoTime();
                long deadline = sliceStarted + BUDGET_NANOS;
                int processed = 0;
                for (int offset = 0; offset < fixtures.size(); offset++) {
                    if (budgeted && System.nanoTime() >= deadline) break;
                    Fixture fixture = fixtures.get((cursor + offset) % fixtures.size());
                    processed++;
                    if (fixture.roundFinished(nodes)) continue;
                    fixture.tick(() -> !budgeted || System.nanoTime() < deadline);
                }
                cursor = (cursor + Math.max(1, processed)) % fixtures.size();
                long sliceElapsed = System.nanoTime() - sliceStarted;
                if (round >= 0) slices.add(sliceElapsed);
                check(++sliceCount < 10_000);
            } while (fixtures.stream().anyMatch(fixture -> !fixture.roundFinished(nodes)));
            long elapsed = System.nanoTime() - started;
            long observe = 0L;
            long publish = 0L;
            long settle = 0L;
            for (Fixture fixture : fixtures) {
                fixture.verify(complete, nodes, groupSize);
                observe += fixture.observeNanos;
                publish += fixture.publishNanos;
                settle += fixture.settleNanos;
            }
            if (round < 0) continue;
            timings[0][round] = elapsed;
            timings[1][round] = observe;
            timings[2][round] = publish;
            timings[3][round] = settle;
            sliceCounts[round] = sliceCount;
            if (elapsed > BUDGET_NANOS) overBudget++;
        }
        for (long[] timing : timings) Arrays.sort(timing);
        Arrays.sort(sliceCounts);
        slices.sort(Long::compare);
        String line = String.format(Locale.ROOT,
                "%d,%d,%d,%s,%d,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%d,%s,%d,%.6f,%.6f,%d,%s",
                tasks, nodes, tags, complete ? "complete" : "waiting", SAMPLES,
                ms(timings[0][SAMPLES / 2]), ms(timings[0][(int) (SAMPLES * 0.95)]),
                ms(timings[0][SAMPLES - 1]), ms(timings[1][SAMPLES / 2]),
                ms(timings[2][SAMPLES / 2]), ms(timings[3][SAMPLES / 2]), overBudget, budgeted,
                sliceCounts[SAMPLES / 2], ms(slices.get((int) (slices.size() * 0.95))),
                ms(slices.get(slices.size() - 1)), groupSize, differentItems);
        csv.println(line);
        csv.flush();
        System.out.println(line);
    }

    private static final class Fixture {
        final ConcurrentNodeExecutor executor;
        final AsyncCraftChain chain;
        final MaterialBroker materials;
        final DagScheduler scheduler;
        final boolean expectComplete;
        long observeNanos;
        long publishNanos;
        long settleNanos;
        int publications;
        int completions;
        boolean ready;

        Fixture(CraftPlanGraph graph, ServerPlayer player, ItemStack result,
                boolean complete, int groupSize, AtomicLong tick, boolean budgeted) throws Exception {
            expectComplete = complete;
            // 订单玩家不在线，仅省略外部完成提示；节点仍走真实玩家/世界解析入口。
            chain = new AsyncCraftChain(
                    UUID.randomUUID(), player.getServer(), null, graph);
            materials = (MaterialBroker) field(chain, "graphMaterials");
            scheduler = (DagScheduler) field(chain, "graphScheduler");
            NodeAdmissionCoordinator admissions =
                    (NodeAdmissionCoordinator) field(chain, "graphAdmissions");
            executor = new ConcurrentNodeExecutor(scheduler,
                    (ConcurrentNodeExecutor.AdmissionWorkerFactory) node -> {
                        NodeAdmissionCoordinator.Admission admission = admissions.tryAdmitClaimed(
                                new NodeAdmissionCoordinator.Candidate(node, List.of()));
                        check(admission != null);
                        admissions.commit(admission);
                        IBatchDelegate delegate;
                        if (groupSize == 1) {
                            delegate = newSlotDelegate();
                        } else {
                            List<BoundMachine> machines = new ArrayList<>();
                            for (int machine = 0; machine < groupSize; machine++) {
                                machines.add(new BoundMachine(Level.OVERWORLD.location(),
                                        new BlockPos(machine, 64, 0), ModType.CUSTOM_GUI, "probe"));
                            }
                            ParallelCraftGroup group = new ParallelCraftGroup(machines, ModType.CUSTOM_GUI,
                                    new ResourceLocation("tick_probe", "group"), player, groupSize, false,
                                    BatchConcurrencyCapabilities.machineSlot(), null, groupSize,
                                    machine -> newSlotDelegate());
                            check(group.startOperation(OperationStartContext.delegateExtracted(
                                    player, ExtractionLedger.external())));
                            while (group.getRunningOperations() < groupSize) {
                                tick.incrementAndGet();
                                group.observeCraft(player.serverLevel());
                            }
                            delegate = group;
                        }
                        CraftNodeRuntime runtime = new CraftNodeRuntime(node, "probe", delegate,
                                null, admission, null);
                        runtime.setChainContext(new ArrayList<>(), player);
                        runtime.attachOutputs(new NodeOutputAccumulator(
                                graph.nodes().get(node.value()).outputs()));
                        return ConcurrentNodeExecutor.StartResult.started(new ProbeWorker(runtime));
                    }, graph.nodes().size(), node -> false,
                    (node, worker) -> {
                        if (!ready) return;
                        long started = System.nanoTime();
                        CraftNodeRuntime runtime = ((ProbeWorker) worker).runtime;
                        invoke(PUBLICATION, chain, node, runtime);
                        publishNanos += System.nanoTime() - started;
                        if ((!budgeted || !runtime.hasPendingPublications())
                                && (!expectComplete
                                || runtime.completedOperations() >= runtime.totalOperations())) publications++;
                    },
                    (node, worker) -> {
                        long started = System.nanoTime();
                        Object status = invoke(COMPLETION, chain, node, ((ProbeWorker) worker).runtime);
                        settleNanos += System.nanoTime() - started;
                        completions++;
                        return (ConcurrentNodeExecutor.CompletionStatus) status;
                    }, Integer.MAX_VALUE, Integer.MAX_VALUE);
            while (executor.runningCount() < graph.nodes().size()) executor.tick();
            for (SlotDelegate delegate : delegates) {
                if (complete) delegate.output.setItem(0, result.copy());
            }
            ready = true;
            observeNanos = 0L;
        }

        private final List<SlotDelegate> delegates = new ArrayList<>();

        private SlotDelegate newSlotDelegate() {
            SlotDelegate delegate = new SlotDelegate();
            delegates.add(delegate);
            return delegate;
        }

        void tick(BooleanSupplier allowed) throws Throwable {
            ALLOWANCE.invoke(chain, allowed);
            executor.tick(allowed);
        }

        boolean roundFinished(int nodes) {
            return expectComplete ? executor.isTerminal()
                    : publications == nodes && !executor.hasPendingRound();
        }

        private final class ProbeWorker implements ConcurrentNodeExecutor.Worker {
            final CraftNodeRuntime runtime;

            ProbeWorker(CraftNodeRuntime runtime) {
                this.runtime = runtime;
            }

            @Override
            public ConcurrentNodeExecutor.Observation observe() {
                if (!ready) return ConcurrentNodeExecutor.Observation.WORKING;
                long started = System.nanoTime();
                ConcurrentNodeExecutor.Observation observation = runtime.observe();
                observeNanos += System.nanoTime() - started;
                return observation;
            }

            @Override
            public boolean hasPendingPublications() { return runtime.hasPendingPublications(); }

            @Override
            public void setWorkAllowed(BooleanSupplier workAllowed) { runtime.setWorkAllowed(workAllowed); }
        }

        void verify(boolean complete, int nodes, int groupSize) {
            check(publications == nodes);
            check(completions == (complete ? nodes : 0));
            check(executor.runningCount() == (complete ? 0 : nodes));
            check(materials.totalAvailable() == (complete ? (long) nodes * groupSize * 64 : 0L));
            check(scheduler.allSucceeded() == complete);
        }
    }

    private static final class SlotDelegate implements IBatchDelegate {
        final SimpleContainer output = new SimpleContainer(1);

        @Override
        public boolean validateAndInit(ServerPlayer player, ResourceLocation recipe,
                                       ResourceLocation dimension, BlockPos position) { return true; }

        @Override
        public boolean startOperation(OperationStartContext context) { return true; }

        @Override
        public boolean isCraftComplete(ServerLevel level) { return !output.isEmpty(); }

        @Override
        public ItemStack collectResult(ServerPlayer player) { return output.removeItemNoUpdate(0); }

        @Override
        public void onBatchFailed(ServerPlayer player, String reason) {}

        @Override
        public void onBatchFinished(ServerPlayer player) {}

        @Override
        public BlockPos getMachinePos() { return BlockPos.ZERO; }
    }

    private static CraftPlanGraph graph(int count, ItemStack result, long outputQuantity) {
        List<CraftNode> nodes = new ArrayList<>();
        List<RootDemand> roots = new ArrayList<>();
        MaterialKey key = MaterialKey.of(result);
        for (int index = 0; index < count; index++) {
            NodeId node = new NodeId(index);
            OutputPortId port = new OutputPortId(node, 0);
            nodes.add(new CraftNode(node, new ResourceLocation("tick_probe", "node_" + index),
                    ModType.CUSTOM_GUI.id(), null, 1, List.of(), List.of(), false, null, null,
                    List.of(), List.of(OutputDeclaration.item(port, key, outputQuantity, OutputKind.PRIMARY))));
            roots.add(new RootDemand(Ingredient.of(result), outputQuantity, 0, result,
                    List.of(new RootAllocation(new MaterialSource.ProducerOutput(port), key, outputQuantity))));
        }
        return new CraftPlanGraph(1, nodes, List.of(), roots, List.of(),
                nodes.stream().map(CraftNode::id).toList());
    }

    private static MethodHandle method(String name) {
        try {
            Method method = AsyncCraftChain.class.getDeclaredMethod(
                    name, NodeId.class, ConcurrentNodeExecutor.Worker.class);
            method.setAccessible(true);
            return MethodHandles.lookup().unreflect(method);
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static MethodHandle allowanceSetter() {
        try {
            Field field = AsyncCraftChain.class.getDeclaredField("dispatchAllowed");
            field.setAccessible(true);
            return MethodHandles.lookup().unreflectSetter(field);
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static Object invoke(MethodHandle method, Object chain, NodeId node,
                                 CraftNodeRuntime runtime) {
        try {
            return method.invoke(chain, node, runtime);
        } catch (Throwable failure) {
            throw new IllegalStateException("执行基准回调失败", failure);
        }
    }

    private static Object field(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static double ms(long nanos) { return nanos / 1_000_000.0; }

    private static void check(boolean condition) {
        if (!condition) throw new AssertionError("执行基准的产物或生命周期不守恒");
    }
}
