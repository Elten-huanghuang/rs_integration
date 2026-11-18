package com.huanghuang.rsintegration.mods.embers;

import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.MaterialPlan;
import com.huanghuang.rsintegration.crafting.batch.MaterialPlan.Allocation;
import com.huanghuang.rsintegration.crafting.batch.MaterialPlan.Entry;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.ArrayList;
import java.util.List;

public final class EreAlchemyMaterials {
    private EreAlchemyMaterials() {}

    public static MaterialPlan create(Ingredient tablet, List<Ingredient> aspects, List<Ingredient> inputs) {
        return declare(tablet, aspects, inputs, Allocation.GRAPH);
    }

    /** 推理首轮猜测的象征不属于已确定的图材料。 */
    public static MaterialPlan createInference(Ingredient tablet, List<Ingredient> aspects, List<Ingredient> inputs) {
        return declare(tablet, aspects, inputs, Allocation.SUPPLEMENTAL);
    }

    private static MaterialPlan declare(Ingredient tablet, List<Ingredient> aspects, List<Ingredient> inputs,
                                        Allocation aspectAllocation) {
        if (aspects.size() != inputs.size()) throw new IllegalArgumentException("aspect/input count mismatch");
        List<Entry> entries = new ArrayList<>();
        if (tablet != null) entries.add(new Entry("tablet", new IngredientSpec(tablet, 1), Allocation.GRAPH, 0));
        for (int i = 0; i < inputs.size(); i++) {
            entries.add(new Entry("aspect:" + i, new IngredientSpec(aspects.get(i), 1, DemandRole.CATALYST),
                    aspectAllocation, entries.size()));
            entries.add(new Entry("input:" + i, new IngredientSpec(inputs.get(i), 1), Allocation.GRAPH, entries.size()));
        }
        return new MaterialPlan(entries);
    }
}
