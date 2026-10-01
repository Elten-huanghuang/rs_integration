package com.huanghuang.rsintegration.disk.core;

/** 节点只保存 int 饱和值，饱和后减少也能沿路径恢复真实可见总量。 */
public final class SaturatedTree {
    private final int base;
    private final int[] nodes;

    public SaturatedTree(int capacity) {
        if (capacity <= 0 || capacity > 1048576) throw new IllegalArgumentException("统计树容量越界");
        int size = 1;
        while (size < capacity) size <<= 1;
        base = size;
        nodes = new int[base * 2];
    }

    public static int add(int a, int b) {
        return b > Integer.MAX_VALUE - a ? Integer.MAX_VALUE : a + b;
    }

    public void set(int index, int value) {
        if (index < 0 || index >= base || value < 0) throw new IllegalArgumentException("统计叶节点越界");
        int node = base + index;
        nodes[node] = value;
        while ((node >>= 1) != 0) {
            int updated = add(nodes[node * 2], nodes[node * 2 + 1]);
            // 子树的可见汇总没有变化，更高节点也无需更新；叶值仍保留，减量可恢复。
            if (nodes[node] == updated) break;
            nodes[node] = updated;
        }
    }

    public int total() { return nodes[1]; }
}
