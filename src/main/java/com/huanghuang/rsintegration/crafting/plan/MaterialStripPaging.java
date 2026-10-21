package com.huanghuang.rsintegration.crafting.plan;

/** 材料条分页的纯计算规则，避免每个渲染帧重复推导边界。 */
final class MaterialStripPaging {
    private MaterialStripPaging() {}

    static int pageCount(int totalEntries, int pageSize) {
        if (totalEntries <= 0 || pageSize <= 0) return 1;
        return (totalEntries + pageSize - 1) / pageSize;
    }

    static int clampPage(int page, int totalEntries, int pageSize) {
        return Math.max(0, Math.min(page, pageCount(totalEntries, pageSize) - 1));
    }

    static int pageStart(int page, int totalEntries, int pageSize) {
        return clampPage(page, totalEntries, pageSize) * Math.max(1, pageSize);
    }

    static int pageEnd(int page, int totalEntries, int pageSize) {
        return Math.min(totalEntries,
                pageStart(page, totalEntries, pageSize) + Math.max(1, pageSize));
    }

    static int movePage(int page, int delta, int totalEntries, int pageSize) {
        return clampPage(page + Integer.signum(delta), totalEntries, pageSize);
    }
}
