package com.huanghuang.rsintegration.crafting;

/** 离线回放为单个服务端请求启用的细分计时，子阶段与评分总时间有重叠。 */
final class CompatibilityProfile {
    enum Phase { SCORE, GROUP, COUNT, COLLECT, SORT, TAKE }

    private final long[] total = new long[Phase.values().length];
    int groupHits;
    int skippedSorts;

    void finish(Phase phase, long start) {
        total[phase.ordinal()] += System.nanoTime() - start;
    }

    long total(Phase phase) {
        return total[phase.ordinal()];
    }
}
