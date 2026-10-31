package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.DirectMaterialAllocator;
import com.huanghuang.rsintegration.crafting.ScaledIngredientSpec;
import com.huanghuang.rsintegration.crafting.graph.AllocationId;
import com.huanghuang.rsintegration.crafting.graph.CraftNode;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanValidator;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.graph.InputDemand;
import com.huanghuang.rsintegration.crafting.graph.InputPortId;
import com.huanghuang.rsintegration.crafting.graph.MaterialAllocation;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.graph.MaterialSource;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
import com.huanghuang.rsintegration.crafting.graph.OutputDeclaration;
import com.huanghuang.rsintegration.crafting.graph.OutputKind;
import com.huanghuang.rsintegration.crafting.graph.OutputPortId;
import com.huanghuang.rsintegration.crafting.graph.OutputProbability;
import com.huanghuang.rsintegration.crafting.graph.RootAllocation;
import com.huanghuang.rsintegration.crafting.graph.RootDemand;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.common.crafting.CompoundIngredient;
import net.minecraftforge.common.crafting.StrictNBTIngredient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 将成功搜索的材料事件转成执行图，不重新搜索配方或重选 NBT 变体。 */
public final class PurePlanGraphAdapter {
    private PurePlanGraphAdapter() {}

    public static CraftPlanGraph toInputGraph(
            PureRecipePlanner.Result result, Map<StackKey, ? extends Number> available,
            List<ScaledIngredientSpec> terminalInputs) {
        return replay(result, available).finish(terminalInputs);
    }

    /** 自增和反向换算已经按最终产物规划，不得再追加一次原终端配方。 */
    public static CraftPlanGraph toOutputGraph(
            PureRecipePlanner.Result result, Map<StackKey, ? extends Number> available) {
        return replay(result, available).finishOutput(
                result.materialFlow().roots(), result.materialFlow().additionalOutput());
    }

    public static boolean targetsOutput(PureRecipePlanner.Result result,
                                        RecipeNode target) {
        if (result == null || result.materialFlow() == null || target == null
                || !result.materialFlow().outputTarget()) return false;
        List<IngredientRef> roots = result.materialFlow().roots();
        return roots.size() == 1
                && roots.get(0).alternatives().equals(List.of(target.output()));
    }

    private static Builder replay(
            PureRecipePlanner.Result result, Map<StackKey, ? extends Number> available) {
        if (!result.feasible() || result.materialFlow() == null) {
            throw new IllegalArgumentException("Pure plan has no successful material flow");
        }
        Builder builder = new Builder();
        available.forEach((key, count) -> {
            if (count != null && count.longValue() > 0L) {
                MaterialRef material = ImmutableRecipeGraphProjector.material(key.toStack(), true);
                MaterialKey identity = new MaterialKey(key.item(), key.tag());
                builder.publish(material, identity, new MaterialSource.InitialPool(identity),
                        count.longValue());
            }
        });
        for (PureRecipePlanner.FlowEvent event : result.materialFlow().events()) {
            if (event instanceof PureRecipePlanner.MaterialUse use) builder.consume(use);
            else builder.produce((PureRecipePlanner.Production) event);
        }
        builder.consumeRootCatalysts();
        return builder;
    }

    private static ItemStack stack(MaterialRef material) {
        if (material.isFluid()) {
            ItemStack token = InkFluidSupport.tokenFor(new IntegrationResourceKey(
                    IntegrationResourceKey.ResourceKind.FLUID,
                    material.resourceId(), parseTag(material.nbt())), 1);
            if (token.isEmpty()) throw new IllegalArgumentException("Unavailable fluid token");
            return token;
        }
        ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(material.itemId()));
        if (stack.isEmpty()) throw new IllegalArgumentException("Unknown planned item " + material);
        if (!material.nbt().isEmpty()) stack.setTag(parseTag(material.nbt()));
        return stack;
    }

    private static CompoundTag parseTag(String nbt) {
        try {
            return TagParser.parseTag(nbt.isEmpty() ? "{}" : nbt);
        } catch (CommandSyntaxException exception) {
            throw new IllegalArgumentException("Invalid planned material NBT", exception);
        }
    }

    private static Ingredient exactIngredient(List<RootAllocation> supplies) {
        List<Ingredient> choices = supplies.stream().map(RootAllocation::material).distinct()
                .map(material -> (Ingredient) StrictNBTIngredient.of(material.toStack(1)))
                .toList();
        return choices.size() == 1 ? choices.get(0)
                : CompoundIngredient.of(choices.toArray(Ingredient[]::new));
    }

    private static final class Lot {
        private final MaterialKey material;
        private final MaterialSource source;
        private long remaining;

        private Lot(MaterialKey material, MaterialSource source, long remaining) {
            this.material = material;
            this.source = source;
            this.remaining = remaining;
        }
    }

    private static final class Builder {
        private final Map<MaterialRef, List<Lot>> stock = new HashMap<>();
        private final Map<Integer, Map<Integer, PendingInput>> inputs = new HashMap<>();
        private final List<PureRecipePlanner.MaterialUse> rootCatalysts = new ArrayList<>();
        private final List<CraftNode> nodes = new ArrayList<>();
        private final List<MaterialAllocation> allocations = new ArrayList<>();
        private final Map<Integer, List<RootAllocation>> rootSupply = new LinkedHashMap<>();
        private long nextAllocation;
        private int nextNode;

        private void publish(MaterialRef ref, MaterialKey material, MaterialSource source, long amount) {
            stock.computeIfAbsent(ref, ignored -> new ArrayList<>())
                    .add(new Lot(material, source, amount));
        }

        private void consume(PureRecipePlanner.MaterialUse use) {
            if (use.demand().role() == DemandRole.CATALYST) {
                // 催化剂要在依赖完成后借出，内外层配方才能依次使用同一件工具。
                if (use.consumer() < 0) rootCatalysts.add(use);
                else addInput(use, List.of());
                return;
            }
            List<RootAllocation> supply = takeRecorded(use.materials());
            if (use.consumer() < 0) {
                rootSupply.computeIfAbsent(use.port(), ignored -> new ArrayList<>()).addAll(supply);
            } else {
                addInput(use, supply);
            }
        }

        private void addInput(PureRecipePlanner.MaterialUse use, List<RootAllocation> supply) {
            PendingInput previous = inputs.computeIfAbsent(use.consumer(), ignored -> new LinkedHashMap<>())
                    .put(use.port(), new PendingInput(use, lots(supply)));
            if (previous != null) throw new IllegalArgumentException("Duplicate recorded input");
        }

        private List<RootAllocation> takeRecorded(Map<MaterialRef, Long> materials) {
            List<RootAllocation> supply = new ArrayList<>();
            materials.forEach((material, amount) -> {
                supply.addAll(take(stock.getOrDefault(material, List.of()), amount));
            });
            return supply;
        }

        private void produce(PureRecipePlanner.Production production) {
            Map<Integer, PendingInput> recorded = inputs.remove(production.node());
            List<Map.Entry<Integer, PendingInput>> ordered = recorded == null ? List.of()
                    : recorded.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList();
            long remaining = production.batches();
            while (remaining > 0L) {
                int executions = (int) Math.min(Integer.MAX_VALUE, remaining);
                produceChunk(production, ordered, executions);
                remaining -= executions;
            }
        }

        private void produceChunk(PureRecipePlanner.Production production,
                                  List<Map.Entry<Integer, PendingInput>> recorded,
                                  int executions) {
            NodeId node = new NodeId(nextNode++);
            List<InputDemand> demands = new ArrayList<>();
            Map<InputPortId, List<RootAllocation>> borrowed = new LinkedHashMap<>();
            for (Map.Entry<Integer, PendingInput> entry : recorded) {
                PendingInput pending = entry.getValue();
                IngredientRef demand = pending.use().demand();
                boolean catalyst = demand.role() == DemandRole.CATALYST;
                if (!catalyst && demand.count() % production.batches() != 0L) {
                    throw new IllegalArgumentException("Input cannot be split across execution batches");
                }
                long quantity = catalyst ? demand.count()
                        : Math.multiplyExact(demand.count() / production.batches(), executions);
                List<RootAllocation> supply = catalyst
                        ? takeRecorded(pending.use().materials()) : take(pending.supply(), quantity);
                InputPortId port = new InputPortId(node, entry.getKey());
                demands.add(new InputDemand(port, exactIngredient(supply), quantity,
                        demand.role(), supply.get(0).material().toStack(1)));
                for (RootAllocation allocation : supply) {
                    allocations.add(new MaterialAllocation(new AllocationId(nextAllocation++),
                            port, allocation.source(), allocation.material(), allocation.quantity()));
                }
                if (catalyst) borrowed.put(port, supply);
            }
            ItemStack display = stack(production.output());
            MaterialKey material = MaterialKey.of(display);
            long count = Math.multiplyExact(production.recipe().outputCount(), executions);
            IntegrationResourceKey resource = InkFluidSupport.resource(display);
            if (resource == null) resource = IntegrationResourceKey.item(display);
            List<OutputDeclaration> outputs = new ArrayList<>();
            OutputPortId output = new OutputPortId(node, 0);
            outputs.add(new OutputDeclaration(output, material, count, OutputKind.PRIMARY,
                    count, count, OutputProbability.guaranteed(),
                    production.output().runtimeNbt() || production.output().constrainedNbt(),
                    null, resource));
            publish(production.output(), material, new MaterialSource.ProducerOutput(output), count);
            borrowed.forEach((port, catalysts) -> {
                for (RootAllocation catalyst : catalysts) {
                    OutputPortId returned = new OutputPortId(node, outputs.size());
                    outputs.add(OutputDeclaration.item(returned, catalyst.material(), catalyst.quantity(),
                            OutputKind.CATALYST_RETURN, catalyst.quantity(), catalyst.quantity(),
                            OutputProbability.guaranteed(), false, port));
                    MaterialRef ref = ImmutableRecipeGraphProjector.material(catalyst.material().toStack(1), true);
                    publish(ref, catalyst.material(), new MaterialSource.ProducerOutput(returned),
                            catalyst.quantity());
                }
            });
            nodes.add(new CraftNode(node, production.recipe().recipeId(),
                    production.recipe().modTypeId(), production.recipe().recipeTypeId(),
                    executions, List.of(), List.of(), false, null,
                    production.demandedOutput() == null ? null : stack(production.demandedOutput()),
                    demands, outputs, production.recipe().magicCosts()));
        }

        private void consumeRootCatalysts() {
            for (PureRecipePlanner.MaterialUse use : rootCatalysts) {
                rootSupply.computeIfAbsent(use.port(), ignored -> new ArrayList<>())
                        .addAll(takeRecorded(use.materials()));
            }
        }

        private CraftPlanGraph finish(List<ScaledIngredientSpec> terminalInputs) {
            // 只在已选定的供料中恢复原槽位；最大流避免宽标签抢走后续精确槽的材料。
            List<Lot> remaining = lots(rootSupply.values().stream().flatMap(List::stream).toList());
            Map<StackKey, Long> supplied = new LinkedHashMap<>();
            for (Lot lot : remaining) {
                supplied.merge(new StackKey(lot.material.item(), lot.material.tag()),
                        lot.remaining, Math::addExact);
            }
            DirectMaterialAllocator.Result assigned =
                    DirectMaterialAllocator.allocateScaled(terminalInputs, supplied);
            if (!assigned.feasible()) throw new IllegalArgumentException("Terminal inputs differ from pure plan");
            Map<Integer, List<RootAllocation>> bySlot = new HashMap<>();
            for (DirectMaterialAllocator.Allocation allocation : assigned.allocations()) {
                MaterialKey material = new MaterialKey(allocation.material().item(), allocation.material().tag());
                List<Lot> matches = remaining.stream().filter(lot -> lot.material.equals(material)).toList();
                bySlot.computeIfAbsent(allocation.ingredientIndex(), ignored -> new ArrayList<>())
                        .addAll(take(matches, allocation.count()));
            }
            List<RootDemand> roots = new ArrayList<>();
            for (int index = 0; index < terminalInputs.size(); index++) {
                ScaledIngredientSpec spec = terminalInputs.get(index);
                if (spec.isEmpty()) continue;
                List<RootAllocation> supply = bySlot.get(index);
                roots.add(new RootDemand(exactIngredient(supply), spec.count(), 0,
                        supply.get(0).material().toStack(1), supply, spec.role()));
            }
            if (remaining.stream().anyMatch(lot -> lot.remaining > 0L)) {
                throw new IllegalArgumentException("Unused recorded terminal supply");
            }
            return graph(roots);
        }

        private CraftPlanGraph finishOutput(List<IngredientRef> demands, boolean additionalOutput) {
            List<RootDemand> roots = new ArrayList<>();
            for (int index = 0; index < demands.size(); index++) {
                IngredientRef demand = demands.get(index);
                List<RootAllocation> supply = rootSupply.getOrDefault(index, List.of());
                long quantity = demand.count();
                if (additionalOutput) {
                    // 旧库存只参与净增量计算，未投入本次复制的物品不能成为交付需求。
                    supply = supply.stream().filter(allocation ->
                            allocation.source() instanceof MaterialSource.ProducerOutput).toList();
                    quantity = supply.stream().mapToLong(RootAllocation::quantity).reduce(0L, Math::addExact);
                    if (quantity == 0L) throw new IllegalArgumentException("Additional output plan has no production");
                }
                roots.add(new RootDemand(exactIngredient(supply), quantity, 0L,
                        supply.get(0).material().toStack(1), supply, demand.role()));
            }
            return graph(roots);
        }

        private CraftPlanGraph graph(List<RootDemand> roots) {
            if (!inputs.isEmpty()) throw new IllegalArgumentException("Recorded consumer did not complete");
            CraftPlanGraph graph = new CraftPlanGraph(CraftPlanGraph.CURRENT_VERSION,
                    nodes, allocations, roots, List.of(), nodes.stream().map(CraftNode::id).toList());
            CraftPlanValidator.validate(graph);
            return graph;
        }

        private static List<Lot> lots(List<RootAllocation> supply) {
            return supply.stream().map(allocation ->
                    new Lot(allocation.material(), allocation.source(), allocation.quantity())).toList();
        }

        private static List<RootAllocation> take(List<Lot> lots, long quantity) {
            List<RootAllocation> supply = new ArrayList<>();
            long remaining = quantity;
            for (Lot lot : lots) {
                long taken = Math.min(remaining, lot.remaining);
                if (taken <= 0L) continue;
                supply.add(new RootAllocation(lot.source, lot.material, taken));
                lot.remaining -= taken;
                remaining -= taken;
            }
            if (remaining != 0L) throw new IllegalArgumentException("Missing recorded material supply");
            return supply;
        }

        private record PendingInput(PureRecipePlanner.MaterialUse use, List<Lot> supply) {}
    }
}
