package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.crafting.quantity.SaturatingLongMath;
import com.huanghuang.rsintegration.crafting.tree.IngredientKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 固定路线的实时库存投影。只统计初始供料，生产者输出不冒充现货。 */
public final class PreviewInventoryModel {
    public enum Status { READY, MISSING, REPLAN, UNAVAILABLE }

    private final List<IngredientKey> keys;
    private final Map<IngredientKey, Integer> indices = new HashMap<>();
    private final Map<IngredientKey, Long> required = new HashMap<>();
    private final Map<IngredientKey, Long> current = new HashMap<>();
    private final Set<IngredientKey> dirty = new LinkedHashSet<>();
    private final Set<Item> types = new LinkedHashSet<>();
    private final Set<Item> readOnlyTypes = Collections.unmodifiableSet(types);
    private final boolean complete;
    private int shortages;
    private boolean demandOverflow;

    public PreviewInventoryModel(PlanResponse plan) {
        if (plan.graph() == null) throw new IllegalArgumentException("缺少固定材料分配图");
        keys = List.copyOf(plan.materials().keySet());
        for (int i = 0; i < keys.size(); i++) {
            indices.put(keys.get(i), i);
            types.add(keys.get(i).item());
        }
        PlanGraphView graph = plan.graph();
        for (var edge : graph.edges()) {
            if (edge.source().initial()) require(edge.material(), edge.quantity());
        }
        for (var root : graph.roots()) {
            for (var edge : root.allocations()) {
                if (edge.source().initial()) require(edge.material(), edge.quantity());
            }
            require(root.display(), root.unresolvedQuantity());
        }
        for (var unresolved : graph.unresolved()) require(unresolved.display(), unresolved.quantity());
        // 缺料图的展示模板不携带完整 Ingredient 谓词；补入展示物品也必须重新规划，
        // 不能据此宣布标签、动态 NBT 或未展开分支已经可以执行。
        complete = plan.success() && graph.unresolved().isEmpty()
                && graph.roots().stream().allMatch(root -> root.unresolvedQuantity() == 0);
        shortages = required.size();
        dirty.addAll(keys);
    }

    private void require(ItemStack stack, long amount) {
        if (stack.isEmpty() || amount <= 0) return;
        IngredientKey key = IngredientKey.of(stack);
        if (!indices.containsKey(key)) throw new IllegalArgumentException("材料表缺少图中的初始供料");
        long previous = required.getOrDefault(key, 0L);
        if (amount > Long.MAX_VALUE - previous) demandOverflow = true;
        required.put(key, SaturatingLongMath.add(previous, amount));
    }

    public Set<Item> itemTypes() { return readOnlyTypes; }
    public List<IngredientKey> keys() { return keys; }
    public boolean tracks(IngredientKey key) { return indices.containsKey(key); }
    public long requiredAmount(IngredientKey key) { return required.getOrDefault(key, 0L); }

    /** amount 是所有可用来源合并后的绝对数量，不是增减量。 */
    public void update(IngredientKey key, long amount) {
        if (!tracks(key)) return;
        amount = Math.max(0L, amount);
        long before = current.getOrDefault(key, 0L);
        if (before == amount) return;
        long need = required.getOrDefault(key, 0L);
        if (before < need && amount >= need) shortages--;
        else if (before >= need && amount < need) shortages++;
        current.put(key, amount);
        dirty.add(key);
    }

    public Status status() {
        return shortages > 0 ? Status.MISSING : complete && !demandOverflow ? Status.READY : Status.REPLAN;
    }

    public List<PlanInventoryUpdatePacket.Entry> drain(boolean full) {
        Iterable<IngredientKey> changed = full ? keys : dirty;
        List<PlanInventoryUpdatePacket.Entry> result = new ArrayList<>();
        for (IngredientKey key : changed) {
            long have = current.getOrDefault(key, 0L);
            result.add(new PlanInventoryUpdatePacket.Entry(indices.get(key), have,
                    Math.max(0L, required.getOrDefault(key, 0L) - have)));
        }
        dirty.clear();
        return List.copyOf(result);
    }
}
