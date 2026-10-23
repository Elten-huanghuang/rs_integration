package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/** 大图输出索引：三个数组保存哈希桶，避免为每个物品创建集合对象。 */
final class CompactOutputIndex {
    private final MaterialRef[] outputs;
    private final int[] next;
    private final int[] heads;

    CompactOutputIndex(Set<MaterialRef> source) {
        outputs = source.toArray(MaterialRef[]::new);
        next = new int[outputs.length];
        heads = new int[bucketCount(outputs.length)];
        Arrays.fill(heads, -1);
        // 倒序挂链，使查询顺序与原始输出映射的迭代顺序一致。
        for (int index = outputs.length - 1; index >= 0; index--) {
            if ((index & 1023) == 0) PlanningThreadContext.throwIfCancelled();
            int bucket = bucket(outputs[index].itemId());
            next[index] = heads[bucket];
            heads[bucket] = index;
        }
    }

    List<MaterialRef> find(ResourceLocation itemId) {
        List<MaterialRef> matches = null;
        int inspected = 0;
        for (int index = heads[bucket(itemId)]; index >= 0; index = next[index]) {
            if ((inspected++ & 1023) == 0) PlanningThreadContext.throwIfCancelled();
            MaterialRef output = outputs[index];
            if (!output.itemId().equals(itemId)) continue;
            if (matches == null) matches = new ArrayList<>();
            matches.add(output);
        }
        return matches == null ? List.of() : List.copyOf(matches);
    }

    /** 按未压缩的 8 字节引用保守计费，并为数组头及索引对象预留空间。 */
    static long estimatedBytes(int size) {
        return 128L + 12L * size + 4L * bucketCount(size);
    }

    private static int bucketCount(int size) {
        int desired = (int) Math.max(1L, ((long) size + 3L) / 4L);
        return desired == 1 ? 1 : Integer.highestOneBit(desired - 1) << 1;
    }

    private int bucket(ResourceLocation itemId) {
        int hash = itemId.hashCode();
        return (hash ^ (hash >>> 16)) & (heads.length - 1);
    }
}
