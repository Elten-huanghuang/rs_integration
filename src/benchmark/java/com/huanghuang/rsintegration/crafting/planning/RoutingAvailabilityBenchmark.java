package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.planning.AsyncPurePlanningService.RouteInputs;
import com.huanghuang.rsintegration.crafting.planning.AsyncPurePlanningService.RoutedPlan;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** 比较真实路由入口；包含首次投影、需求树检查及搜索，不包含采集库存和构建配方目录。 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgsAppend = {"-Xms1g", "-Xmx3g"})
@State(Scope.Thread)
public class RoutingAvailabilityBenchmark extends BootstrapTest {
    @Param({"50000", "100000"})
    public int stockTypes;
    @Param({"ready", "missing"})
    public String scenario;
    private PlanningSnapshot snapshot;

    @Setup
    public void create() throws ReflectiveOperationException {
        // 沿用单测的 Forge 引导兼容处理，初始化不计入基准。
        var bootstrap = BootstrapTest.class.getDeclaredMethod("bootstrapMinecraft");
        bootstrap.setAccessible(true);
        bootstrap.invoke(null);
        List<Item> itemTypes = BuiltInRegistries.ITEM.stream().filter(item ->
                item != Items.AIR && item != Items.IRON_INGOT && item != Items.GOLD_INGOT).toList();
        Map<StackKey, Long> items = new HashMap<>();
        for (int index = 0; index < stockTypes - 1; index++) {
            items.put(new StackKey(itemTypes.get(index % itemTypes.size()),
                    "{BenchVariant:" + index + "}"), 2000L + index % 1000);
        }
        items.put(new StackKey(scenario.equals("ready") ? Items.IRON_INGOT : Items.GOLD_INGOT, null), 2000L);

        Map<MaterialRef, List<RecipeNode>> catalog = new HashMap<>();
        MaterialRef previous = new MaterialRef(new ResourceLocation("minecraft:iron_ingot"), "");
        ResourceLocation target = null;
        for (int index = 0; index < 8; index++) {
            MaterialRef output = material("chain_" + index);
            target = id("chain_" + index);
            catalog.put(output, List.of(new RecipeNode(target, output, 1,
                    List.of(new IngredientRef(List.of(previous), 1)))));
            previous = output;
        }
        MaterialRef unrelatedInput = material("unavailable");
        for (int index = 8; index < 100000; index++) {
            MaterialRef output = material("unrelated_" + index);
            catalog.put(output, List.of(new RecipeNode(id("unrelated_" + index), output, 1,
                    List.of(new IngredientRef(List.of(unrelatedInput), 1)))));
        }
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(catalog);
        ImmutableRecipeGraphProjector.publishCompiled(null, 1L, graph, 0L);
        snapshot = new PlanningSnapshot(UUID.randomUUID(), 1L, 1L, target, items,
                Map.of(), graph, "benchmark", "benchmark", false);
        RoutedPlan legacy = legacyRoutedRequest();
        RoutedPlan reused = reusedRoutedRequest();
        if (!legacy.equals(reused) || reused.plan() == null
                || reused.plan().feasible() != scenario.equals("ready")) {
            throw new AssertionError("库存复用改变了路由、计划或缺料结果");
        }
    }

    @Benchmark
    public RoutedPlan legacyRoutedRequest() {
        RouteInputs routing = new RouteInputs(
                ImmutableRecipeGraphProjector.projectAvailability(snapshot.availableItems()),
                512, Set.of(), Set.of(), Set.of());
        return compute(routing);
    }

    @Benchmark
    public RoutedPlan reusedRoutedRequest() {
        return compute(RouteInputs.fromItems(
                snapshot.availableItems(), 512, Set.of(), Set.of(), Set.of()));
    }

    private RoutedPlan compute(RouteInputs routing) {
        return AsyncPurePlanningService.computeRouted(snapshot, routing, 1,
                100, 65536, 8192, 60000);
    }

    private static MaterialRef material(String path) {
        return new MaterialRef(id(path), "");
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("routing_benchmark", path);
    }
}
