package com.huanghuang.rsintegration.crafting.plan;

final class PlanQuantityControls {
    private PlanQuantityControls() {}

    static long applyOffset(int current, int offset) {
        if (offset == 0) return 1L;
        if (offset == 64 && current == 1) return 64L;
        return current + (long) offset;
    }
}
