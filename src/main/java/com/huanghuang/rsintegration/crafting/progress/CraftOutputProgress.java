package com.huanghuang.rsintegration.crafting.progress;

import com.huanghuang.rsintegration.crafting.MaterialMatcher;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.crafting.graph.MaterialSource;
import com.huanghuang.rsintegration.crafting.graph.OutputPortId;
import com.huanghuang.rsintegration.crafting.quantity.SaturatingLongMath;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;

/** 最终目标的产出数量；与配方执行次数、依赖节点数量分开计数。 */
public record CraftOutputProgress(long completed, long total) {
    public static final CraftOutputProgress UNKNOWN = new CraftOutputProgress(0L, 0L);

    public CraftOutputProgress {
        total = Math.max(0L, total);
        completed = Math.min(total, Math.max(0L, completed));
    }

    public boolean known() {
        return total > 0L;
    }

    /** 只累计目标的根输出端口，排除中间材料和已有库存。 */
    public static CraftOutputProgress fromGraph(CraftPlanGraph graph, ItemStack target,
                                                Map<OutputPortId, Long> published) {
        if (target.isEmpty()) return UNKNOWN;
        Map<OutputPortId, Long> required = new LinkedHashMap<>();
        for (var root : graph.rootDemands()) {
            for (var allocation : root.allocations()) {
                if (allocation.source() instanceof MaterialSource.ProducerOutput source
                        && MaterialMatcher.matchesOutputDeclaration(allocation.material(), target)) {
                    required.merge(source.outputPort(), allocation.quantity(), SaturatingLongMath::add);
                }
            }
        }
        long completed = 0L;
        long total = 0L;
        for (var entry : required.entrySet()) {
            total = SaturatingLongMath.add(total, entry.getValue());
            completed = SaturatingLongMath.add(completed,
                    Math.min(entry.getValue(), published.getOrDefault(entry.getKey(), 0L)));
        }
        return new CraftOutputProgress(completed, total);
    }
}
