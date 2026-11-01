package com.huanghuang.rsintegration.crafting;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.graph.AllocationId;
import com.huanghuang.rsintegration.crafting.graph.ConcurrentNodeExecutor;
import com.huanghuang.rsintegration.crafting.graph.CraftNode;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.crafting.graph.DagScheduler;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.graph.InputDemand;
import com.huanghuang.rsintegration.crafting.graph.InputPortId;
import com.huanghuang.rsintegration.crafting.graph.MaterialAllocation;
import com.huanghuang.rsintegration.crafting.graph.MaterialBroker;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.graph.MaterialSource;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
import com.huanghuang.rsintegration.crafting.graph.OutputDeclaration;
import com.huanghuang.rsintegration.crafting.graph.OutputKind;
import com.huanghuang.rsintegration.crafting.graph.OutputPortId;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import com.huanghuang.rsintegration.crafting.planning.PureRecipePlanner;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.mockito.MockedStatic;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** 配方/库存规模与订单执行图分开测量；使用真实发布入口，兼容修改前的编译产物。 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Fork(value = 2, jvmArgsAppend = {"-Xms1g", "-Xmx3g"})
public class CraftingExecutionBenchmark extends BootstrapTest {
    private static final int RECIPE_COUNT = 100_000;
    private static final int WORKERS = 32;
    private static final int DEPTH = 64;
    private static boolean initialized;

    private static synchronized void initializeRuntime() throws Exception {
        if (initialized) return;
        Method bootstrap = BootstrapTest.class.getDeclaredMethod("bootstrapMinecraft");
        bootstrap.setAccessible(true);
        bootstrap.invoke(null);
        CommentedConfig config = CommentedConfig.inMemory();
        RSIntegrationConfig.SERVER_SPEC.correct(config);
        RSIntegrationConfig.SERVER_SPEC.setConfig(config);
        FMLJavaModLoadingContext context = mock(FMLJavaModLoadingContext.class);
        when(context.getModEventBus()).thenReturn(mock(IEventBus.class));
        try (MockedStatic<FMLJavaModLoadingContext> loading = mockStatic(FMLJavaModLoadingContext.class)) {
            loading.when(FMLJavaModLoadingContext::get).thenReturn(context);
            Class.forName("com.huanghuang.rsintegration.RSIntegrationMod");
        }
        initialized = true;
    }

    @State(Scope.Thread)
    public static class CatalogData {
        @Param({"50000", "100000"})
        public int stockTypes;
        ImmutableRecipeGraph catalog;
        Map<MaterialRef, Long> stock;
        List<IngredientRef> roots;

        @Setup(Level.Trial)
        public void createCatalog() throws Exception {
            initializeRuntime();
            stock = new LinkedHashMap<>();
            for (int i = 0; i < stockTypes; i++) {
                stock.put(material("stock_" + i), 2048L + i % 7 * 512L);
            }
            Map<MaterialRef, List<RecipeNode>> outputs = new LinkedHashMap<>();
            MaterialRef previous = material("stock_0");
            for (int i = 0; i < RECIPE_COUNT; i++) {
                MaterialRef output = material("output_" + i);
                MaterialRef input = i < DEPTH ? previous : material("stock_" + i % stockTypes);
                outputs.put(output, List.of(new RecipeNode(id("recipe_" + i), output, 1,
                        List.of(new IngredientRef(List.of(input), 1)))));
                previous = output;
            }
            catalog = new ImmutableRecipeGraph(outputs);
            roots = List.of(new IngredientRef(List.of(material("output_" + (DEPTH - 1))), 128));
            check(catalog.recipesById().size() == RECIPE_COUNT && stock.size() == stockTypes);
        }
    }

    @State(Scope.Thread)
    public static class ExecutionData extends CatalogData {
        @Param({"256", "2048"})
        public int activeNodes;
        AsyncCraftChain chain;
        MaterialBroker broker;
        DagScheduler scheduler;
        CraftNodeRuntime[] workers;
        MethodHandle publication;
        MaterialBroker.ReservationToken held;
        MaterialBroker.Request partialOutput;
        long initialAvailable;

        @Setup(Level.Trial)
        public void createExecution() throws Exception {
            List<CraftNode> nodes = new ArrayList<>();
            List<MaterialAllocation> allocations = new ArrayList<>();
            MaterialKey iron = MaterialKey.of(new ItemStack(Items.IRON_INGOT));
            for (int i = 0; i < activeNodes; i++) {
                NodeId node = new NodeId(i);
                List<InputDemand> inputs = new ArrayList<>();
                List<OutputDeclaration> outputs = new ArrayList<>();
                if (i < WORKERS) {
                    for (int j = 0; j < 2; j++) {
                        ItemStack inputStack = new ItemStack(Items.STONE);
                        inputStack.getOrCreateTag().putInt("variant", i * 2 + j);
                        MaterialKey key = MaterialKey.of(inputStack);
                        InputPortId port = new InputPortId(node, j);
                        inputs.add(new InputDemand(port, Ingredient.of(Items.STONE), 4096,
                                DemandRole.CONSUMED, inputStack));
                        allocations.add(new MaterialAllocation(new AllocationId(allocations.size()),
                                port, new MaterialSource.InitialPool(key), key, 4096));
                    }
                    outputs.add(OutputDeclaration.item(new OutputPortId(node, 0), iron,
                            2L * activeNodes, OutputKind.PRIMARY));
                } else {
                    InputPortId port = new InputPortId(node, 0);
                    inputs.add(new InputDemand(port, Ingredient.of(Items.IRON_INGOT), 2,
                            DemandRole.CONSUMED, new ItemStack(Items.IRON_INGOT)));
                    allocations.add(new MaterialAllocation(new AllocationId(allocations.size()), port,
                            new MaterialSource.ProducerOutput(new OutputPortId(
                                    new NodeId((i - WORKERS) % WORKERS), 0)), iron, 2));
                }
                nodes.add(new CraftNode(node, id("recipe_" + i), ModType.CUSTOM_GUI.id(), null,
                        1, List.of(), List.of(), false, null, null, inputs, outputs));
            }
            CraftPlanGraph graph = new CraftPlanGraph(1, nodes, allocations, List.of(), List.of(),
                    nodes.stream().map(CraftNode::id).toList());
            chain = new AsyncCraftChain(UUID.randomUUID(), mock(MinecraftServer.class), null, graph);
            broker = (MaterialBroker) field(chain, "graphMaterials");
            scheduler = (DagScheduler) field(chain, "graphScheduler");
            workers = new CraftNodeRuntime[WORKERS];
            for (int i = 0; i < WORKERS; i++) {
                scheduler.claim(new NodeId(i));
                // 只测观察后的发布回调；不伪造机器加工、世界 tick 或实际库存 I/O。
                workers[i] = new CraftNodeRuntime(new NodeId(i), "benchmark", null, null, null);
            }
            Method method = AsyncCraftChain.class.getDeclaredMethod("publishIncrementalGraphOutputs",
                    NodeId.class, ConcurrentNodeExecutor.Worker.class);
            method.setAccessible(true);
            publication = MethodHandles.lookup().unreflect(method).bindTo(chain);
            MaterialSource source = new MaterialSource.ProducerOutput(new OutputPortId(new NodeId(0), 0));
            broker.publish(source, iron, 1);
            partialOutput = new MaterialBroker.Request(source, iron, 1);
            held = broker.reserve(new NodeId(0), List.of(partialOutput));
            initialAvailable = broker.totalAvailable();
        }

        void callbacks() throws Throwable {
            for (CraftNodeRuntime worker : workers) {
                publication.invokeExact(worker.nodeId(), (ConcurrentNodeExecutor.Worker) worker);
            }
        }

        void returnUnit() {
            broker.release(held);
        }

        void holdUnit() {
            held = broker.reserve(new NodeId(0), List.of(partialOutput));
            check(held != null);
        }

        @TearDown(Level.Trial)
        public void verifyConservation() {
            check(broker.totalAvailable() == initialAvailable);
            check(scheduler.readyCount() == 0 && !scheduler.isStopping());
            check(scheduler.state(new NodeId(activeNodes - 1)) == DagScheduler.NodeState.BLOCKED);
        }
    }

    @Benchmark
    public PureRecipePlanner.Result recursivePlan(CatalogData data) {
        PureRecipePlanner.Result result = PureRecipePlanner.resolve(data.catalog, data.stock, data.roots, 256);
        check(result.feasible() && result.steps().size() == DEPTH);
        return result;
    }

    @Benchmark
    public void waitingCallbacks(ExecutionData data) throws Throwable {
        data.callbacks();
    }

    @Benchmark
    public void oneReturnPerRound(ExecutionData data) throws Throwable {
        data.returnUnit();
        data.callbacks();
        data.holdUnit();
    }

    @Benchmark
    public void everyCallbackReturns(ExecutionData data) throws Throwable {
        for (CraftNodeRuntime worker : data.workers) {
            data.returnUnit();
            data.publication.invokeExact(worker.nodeId(), (ConcurrentNodeExecutor.Worker) worker);
            data.holdUnit();
        }
    }

    /** 在非计时运行中统计真实 readiness 调用；不把计数器放进 JMH 热路径。 */
    public static void main(String[] args) throws Throwable {
        ExecutionData data = new ExecutionData();
        data.stockTypes = 100_000;
        data.activeNodes = 2048;
        data.createCatalog();
        data.createExecution();
        CountingRequests counted = new CountingRequests();
        @SuppressWarnings("unchecked")
        Map<NodeId, List<MaterialBroker.Request>> requests =
                (Map<NodeId, List<MaterialBroker.Request>>) field(data.chain, "graphRequests");
        counted.putAll(requests);
        Field requestField = AsyncCraftChain.class.getDeclaredField("graphRequests");
        requestField.setAccessible(true);
        requestField.set(data.chain, counted);
        data.callbacks();
        CraftingExecutionBenchmark benchmark = new CraftingExecutionBenchmark();
        System.out.println("scenario,rounds,callbacks,readiness_checks,full_scans");
        for (String scenario : List.of("waiting", "one_return", "every_return")) {
            counted.checks = 0L;
            for (int i = 0; i < 100; i++) {
                switch (scenario) {
                    case "waiting" -> benchmark.waitingCallbacks(data);
                    case "one_return" -> benchmark.oneReturnPerRound(data);
                    case "every_return" -> benchmark.everyCallbackReturns(data);
                    default -> throw new AssertionError(scenario);
                }
            }
            System.out.println(scenario + ",100,3200," + counted.checks + ","
                    + counted.checks / (data.activeNodes - WORKERS));
            data.verifyConservation();
        }
    }

    private static final class CountingRequests extends HashMap<NodeId, List<MaterialBroker.Request>> {
        long checks;

        @Override
        public List<MaterialBroker.Request> getOrDefault(Object key, List<MaterialBroker.Request> fallback) {
            checks++;
            return super.getOrDefault(key, fallback);
        }
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("execution_benchmark", path);
    }

    private static MaterialRef material(String path) {
        return new MaterialRef(id(path), "");
    }

    private static void check(boolean condition) {
        if (!condition) throw new AssertionError("benchmark invariant failed");
    }
}
