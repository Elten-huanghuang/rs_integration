package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.crafting.consumption.FuelMath;
import com.huanghuang.rsintegration.crafting.graph.CraftNode;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.graph.GraphConcurrencyPolicy;
import com.huanghuang.rsintegration.mods.embers.KnownCodeSavedData;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import com.huanghuang.rsintegration.util.ModIds;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.ForgeHooks;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 计划阶段的并行分派估算。
 *
 * <p>服务端执行时 {@code ParallelCraftGroup} 会重新按真实机器、燃料、催化剂校验；
 * 这里只负责让递归树在合成前就能展示“会分给几台机器、每台多少、燃料/催化剂大概多少”。
 * 估算不修改世界，也不预留材料。</p>
 */
public final class ParallelPlanEstimator {
    private ParallelPlanEstimator() {}

    public static Map<Integer, ParallelPlan> estimate(ServerPlayer player, CraftPlanGraph graph,
                                                      Map<Item, Long> available) {
        return new Request(player, available).estimate(graph);
    }

    /**
     * 单步/旧版计划没有 {@code CraftPlanGraph} 时，按 {@link PlanStep} 本身估算，
     * 让只有一层配方的递归树也能显示并行机器、燃料与复用材料。
     */
    @Nullable
    public static ParallelPlan estimate(ServerPlayer player, PlanStep step,
                                        Map<Item, Long> available) {
        return new Request(player, available).estimateStep(step);
    }

    /** 只在同一份预览内复用无上下文探针结果及燃料库存；执行端仍独立重验。 */
    public static final class Request {
        private final ServerPlayer player;
        private final Map<Item, Long> available;
        private final Map<ProbeKey, ProbeDecision> probes = new HashMap<>();
        private final Map<BindingKey, List<AltarBindingRegistry.BoundMachine>> bindings = new HashMap<>();
        private int burnTicks = -1;

        public Request(ServerPlayer player, Map<Item, Long> available) {
            this.player = player;
            this.available = available;
        }

        public Map<Integer, ParallelPlan> estimate(CraftPlanGraph graph) {
            GraphEstimate work = start(graph);
            work.advance(Long.MAX_VALUE);
            return work.result();
        }

        public GraphEstimate start(@Nullable CraftPlanGraph graph) {
            return new GraphEstimate(graph == null ? List.of() : graph.nodes());
        }

        public final class GraphEstimate {
            private final Iterator<CraftNode> nodes;
            private final Map<Integer, ParallelPlan> plans = new HashMap<>();

            private GraphEstimate(List<CraftNode> nodes) {
                this.nodes = nodes.iterator();
            }

            public boolean advance(long budgetNanos) {
                long start = System.nanoTime();
                while (nodes.hasNext()) {
                    CraftNode node = nodes.next();
                    ParallelPlan plan = estimateNode(node);
                    if (plan != null) {
                        plans.put(node.id().value(), plan);
                    }
                    if (System.nanoTime() - start >= budgetNanos) break;
                }
                return !nodes.hasNext();
            }

            public Map<Integer, ParallelPlan> result() {
                if (nodes.hasNext()) throw new IllegalStateException("并行估算尚未完成");
                return Map.copyOf(plans);
            }

        }

        @Nullable
        public ParallelPlan estimateStep(PlanStep step) {
            if (step == null || step.modType() == null) return null;
            List<InputAmount> inputs = new ArrayList<>();
            List<ItemStack> stacks = step.inputs();
            List<DemandRole> roles = step.inputRoles();
            for (int i = 0; i < stacks.size(); i++) {
                DemandRole role = i < roles.size() ? roles.get(i) : DemandRole.CONSUMED;
                inputs.add(new InputAmount(stacks.get(i), role, Math.max(1L, stacks.get(i).getCount())));
            }
            long executions = Math.max(1L, step.batches());
            return estimate(step.recipeId(), step.modType().id(),
                    (int) Math.min(Integer.MAX_VALUE, executions), inputs, step.inferMode());
        }

        @Nullable
        private ParallelPlan estimateNode(CraftNode node) {
            List<InputAmount> inputs = new ArrayList<>();
            for (var input : node.inputs()) {
                inputs.add(new InputAmount(input.displayHint(), input.role(), Math.max(1L, input.quantity())));
            }
            return estimate(node.recipeId(), node.modTypeId(),
                    Math.max(1, node.executions()), inputs, node.inferMode());
        }

        @Nullable
        private ParallelPlan estimate(ResourceLocation recipeId, String modTypeId, int executions,
                                      List<InputAmount> inputs, boolean inferMode) {
            ModType type = ModType.findById(modTypeId);
            if (type == null) return null;
            List<AltarBindingRegistry.BoundMachine> machines = bindings.computeIfAbsent(
                    new BindingKey(type, recipeId), key -> deduplicateLoaded(
                            AltarBindingRegistry.getBoundMachinesForRecipe(player, type, recipeId), player));
            if (machines.isEmpty()) return null;

            int reusableLimit = reusableLimit(inputs, available);
            int machineLimit = Math.min(executions, machines.size());
            int workerCount = Math.max(1, Math.min(machineLimit, reusableLimit));
            boolean inferencePending = inferMode
                    && ModIds.ID_EMBERS_ALCHEMY.equals(type.id())
                    && !embersCodeKnown(player, recipeId);
            ProbeDecision decision = probes.computeIfAbsent(new ProbeKey(type, inferencePending),
                    ParallelPlanEstimator::probe);
            if (decision.limit() != null) workerCount = 1;

            Recipe<?> recipe = player.serverLevel().getRecipeManager().byKey(recipeId).orElse(null);
            boolean cooking = recipe instanceof AbstractCookingRecipe;
            int cookTicks = cooking ? Math.max(1, ((AbstractCookingRecipe) recipe).getCookingTime()) : 1;
            if (cooking && burnTicks < 0) burnTicks = bestBurnTicks(available);
            long catalystPerWorker = perWorkerAmount(inputs, DemandRole.CATALYST);
            long durabilityPerWorker = perWorkerAmount(inputs, DemandRole.DURABILITY);
            String limitedBy = decision.limit() != null
                    ? decision.limit()
                    : limitedBy(executions, machines.size(), reusableLimit, cooking, burnTicks,
                            catalystPerWorker > 0L, durabilityPerWorker > 0L);

            int base = executions / workerCount;
            int remainder = executions % workerCount;
            List<ParallelPlan.WorkerShare> workers = new ArrayList<>(workerCount);
            long totalFuel = 0L;
            for (int i = 0; i < workerCount; i++) {
                int operations = base + (i < remainder ? 1 : 0);
                long fuel = cooking && burnTicks > 0
                        ? FuelMath.requiredItems((long) operations * cookTicks, burnTicks) : 0L;
                totalFuel += fuel;
                AltarBindingRegistry.BoundMachine machine = machines.get(i);
                workers.add(new ParallelPlan.WorkerShare(machine.pos(), machine.type().id(),
                        operations, fuel, catalystPerWorker, durabilityPerWorker));
            }
            long totalCatalyst = catalystPerWorker * workerCount;
            long totalDurability = durabilityPerWorker * workerCount;
            return new ParallelPlan(workers, executions, totalFuel, totalCatalyst, totalDurability, limitedBy);
        }
    }


    private record ProbeKey(ModType type, boolean inference) {}

    private record BindingKey(ModType type, ResourceLocation recipeId) {}

    private record ProbeDecision(@Nullable String limit) {}

    private static ProbeDecision probe(ProbeKey key) {
        try {
            IBatchDelegate delegate = key.inference()
                    ? key.type().createInferDelegate() : key.type().createDelegate();
            if (delegate != null && !GraphConcurrencyPolicy.decide(key.type().id(), delegate).exclusive()) {
                return new ProbeDecision(null);
            }
            return new ProbeDecision(key.inference() ? "配方代码未知，需先推断" : "机器契约不支持并行");
        } catch (RuntimeException | LinkageError failure) {
            // 可选模组的探针失败只影响并行提示，不影响完整材料计划。
            return new ProbeDecision(key.inference() ? "配方代码未知，需先推断" : "机器契约不可用");
        }
    }

    /**
     * 与执行端一致：Embers 代码已缓存/已保存时视为已知，不再按“推断中”独占。
     */
    private static boolean embersCodeKnown(ServerPlayer player, ResourceLocation recipeId) {
        ServerLevel level = player.serverLevel();
        KnownCodeSavedData savedData = KnownCodeSavedData.get(level);
        savedData.setWorldSeed(level.getSeed());
        return savedData.hasCode(recipeId.toString());
    }

    /**
     * 可复用材料（催化剂、耐久工具）按“每台 worker 预留一份”计算，
     * 和 {@code ParallelCraftGroup} 的 reusable 预留、执行期 worker 上限一致。
     */
    private static int reusableLimit(List<InputAmount> inputs, Map<Item, Long> available) {
        int limit = Integer.MAX_VALUE;
        for (InputAmount input : inputs) {
            if (input.role() != DemandRole.CATALYST
                    && input.role() != DemandRole.DURABILITY) continue;
            Item item = input.stack().getItem();
            long perWorker = Math.max(1L, input.count());
            long stock = available.getOrDefault(item, 0L);
            limit = Math.min(limit, (int) Math.min(Integer.MAX_VALUE, stock / perWorker));
        }
        return limit;
    }

    private static long perWorkerAmount(List<InputAmount> inputs, DemandRole role) {
        long total = 0L;
        for (InputAmount input : inputs) {
            if (input.role() == role) total += Math.max(1L, input.count());
        }
        return total;
    }

    /**
     * 规划只展示当前已加载、且同一方块只算一次的机器，避免把未加载绑定
     * 或重复绑定显示成多台并行机器。
     */
    private static List<AltarBindingRegistry.BoundMachine> deduplicateLoaded(
            List<AltarBindingRegistry.BoundMachine> machines, ServerPlayer player) {
        Map<String, AltarBindingRegistry.BoundMachine> unique = new LinkedHashMap<>();
        for (AltarBindingRegistry.BoundMachine machine : machines) {
            if (machine == null || machine.dim() == null || machine.pos() == null) continue;
            ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, machine.dim());
            ServerLevel level = player.getServer().getLevel(dimension);
            if (level == null || !level.isLoaded(machine.pos())) continue;
            unique.putIfAbsent(machine.dim() + "@" + machine.pos().asLong()
                    + "/" + machine.type().id(), machine);
        }
        return List.copyOf(unique.values());
    }

    private static int bestBurnTicks(Map<Item, Long> available) {
        int best = 0;
        for (var entry : available.entrySet()) {
            if (entry.getValue() == null || entry.getValue() <= 0L) continue;
            int ticks = ForgeHooks.getBurnTime(new ItemStack(entry.getKey()), RecipeType.SMELTING);
            if (ticks > best) best = ticks;
        }
        return best;
    }

    @Nullable
    private static String limitedBy(int executions, int machineCount, int reusableLimit,
                                    boolean cooking, int burnTicks,
                                    boolean hasCatalyst, boolean hasDurability) {
        if (reusableLimit < Math.min(executions, machineCount)) {
            if (hasDurability) return hasCatalyst ? "催化剂/工具不足" : "工具不足";
            return "催化剂不足";
        }
        if (machineCount < executions) return "绑定机器不足";
        if (cooking && burnTicks <= 0) return "缺少燃料";
        return null;
    }

    private record InputAmount(ItemStack stack, DemandRole role, long count) {}
}
