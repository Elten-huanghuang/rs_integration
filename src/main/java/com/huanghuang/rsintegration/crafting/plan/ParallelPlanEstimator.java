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
        Map<Integer, ParallelPlan> plans = new HashMap<>();
        if (graph == null) return plans;
        for (CraftNode node : graph.nodes()) {
            ParallelPlan plan = estimateNode(player, node, available);
            if (plan != null) plans.put(node.id().value(), plan);
        }
        return plans;
    }

    /**
     * 单步/旧版计划没有 {@code CraftPlanGraph} 时，按 {@link PlanStep} 本身估算，
     * 让只有一层配方的递归树也能显示并行机器、燃料与复用材料。
     */
    @Nullable
    public static ParallelPlan estimate(ServerPlayer player, PlanStep step,
                                        Map<Item, Long> available) {
        if (step == null || step.modType() == null) return null;
        List<InputAmount> inputs = new ArrayList<>();
        List<ItemStack> stacks = step.inputs();
        List<DemandRole> roles = step.inputRoles();
        for (int i = 0; i < stacks.size(); i++) {
            DemandRole role = i < roles.size() ? roles.get(i) : DemandRole.CONSUMED;
            inputs.add(new InputAmount(stacks.get(i), role,
                    Math.max(1L, stacks.get(i).getCount())));
        }
        long executions = Math.max(1L, step.batches());
        return estimate(player, step.recipeId(), step.modType().id(),
                (int) Math.min(Integer.MAX_VALUE, executions), inputs, available,
                false);
    }

    @Nullable
    private static ParallelPlan estimateNode(ServerPlayer player, CraftNode node,
                                             Map<Item, Long> available) {
        List<InputAmount> inputs = new ArrayList<>();
        for (var input : node.inputs()) {
            inputs.add(new InputAmount(input.displayHint(), input.role(),
                    Math.max(1L, input.quantity())));
        }
        return estimate(player, node.recipeId(), node.modTypeId(),
                Math.max(1, node.executions()), inputs, available, node.inferMode());
    }

    @Nullable
    private static ParallelPlan estimate(ServerPlayer player, ResourceLocation recipeId, String modTypeId,
                                         int executions, List<InputAmount> inputs,
                                         Map<Item, Long> available, boolean inferMode) {
        ModType type = ModType.byId(modTypeId);
        if (type == null) return null;
        List<AltarBindingRegistry.BoundMachine> machines = deduplicateLoaded(
                AltarBindingRegistry.getBoundMachinesForRecipe(player, type, recipeId), player);
        if (machines.isEmpty()) return null;

        int reusableLimit = reusableLimit(inputs, available);
        int machineLimit = Math.min(executions, machines.size());
        int workerCount = Math.max(1, Math.min(machineLimit, reusableLimit));
        String contractLimit = null;
        boolean inferencePending = inferMode
                && ModIds.ID_EMBERS_ALCHEMY.equals(type.id())
                && !embersCodeKnown(player, recipeId);
        try {
            IBatchDelegate probe = inferencePending
                    ? type.createInferDelegate()
                    : type.createDelegate();
            if (probe == null
                    || GraphConcurrencyPolicy.decide(type.id(), probe).exclusive()) {
                workerCount = 1;
                contractLimit = inferencePending
                        ? "配方代码未知，需先推断"
                        : "机器契约不支持并行";
            }
        } catch (RuntimeException exception) {
            // 计划展示不能因为某个模组 delegate 初始化失败而整体失败。
            workerCount = 1;
            contractLimit = inferencePending ? "配方代码未知，需先推断" : "机器契约不可用";
        }

        Recipe<?> recipe = player.serverLevel().getRecipeManager()
                .byKey(recipeId).orElse(null);
        boolean cooking = recipe instanceof AbstractCookingRecipe;
        int cookTicks = cooking ? Math.max(1, ((AbstractCookingRecipe) recipe).getCookingTime()) : 1;
        int burnTicks = bestBurnTicks(available);
        long catalystPerWorker = perWorkerAmount(inputs, DemandRole.CATALYST);
        long durabilityPerWorker = perWorkerAmount(inputs, DemandRole.DURABILITY);
        String limitedBy = contractLimit != null
                ? contractLimit
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
        return new ParallelPlan(workers, executions, totalFuel, totalCatalyst, totalDurability,
                limitedBy);
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
            ServerLevel level = player.server.getLevel(dimension);
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
