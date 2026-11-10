package com.huanghuang.rsintegration.config;

/** 一次求解固定使用同一份辅助预算，配置重载不会改变进行中的搜索。 */
public record PurePlannerLimits(int auxiliaryMaxDepth, int seedProbeMaxNodes,
                                int diagnosticMaxOperations) {
    public static final int DEFAULT_AUXILIARY_MAX_DEPTH = 512;
    public static final int DEFAULT_SEED_PROBE_MAX_NODES = 64;
    public static final int DEFAULT_DIAGNOSTIC_MAX_OPERATIONS = 32_768;
    // 辅助估算仍有递归，因此只允许降低其保护上限；主求解栈没有此限制。
    public static final int MAX_RECURSIVE_AUXILIARY_DEPTH = DEFAULT_AUXILIARY_MAX_DEPTH;

    public PurePlannerLimits {
        if (auxiliaryMaxDepth < 1 || auxiliaryMaxDepth > MAX_RECURSIVE_AUXILIARY_DEPTH
                || seedProbeMaxNodes < 1 || seedProbeMaxNodes > MAX_RECURSIVE_AUXILIARY_DEPTH
                || diagnosticMaxOperations < 1) {
            throw new IllegalArgumentException("invalid pure planner auxiliary budgets");
        }
    }

    public static PurePlannerLimits load() {
        return new PurePlannerLimits(
                RSIntegrationConfig.serverInt(RSIntegrationConfig.CRAFTING_PURE_AUXILIARY_MAX_DEPTH),
                RSIntegrationConfig.serverInt(RSIntegrationConfig.CRAFTING_PURE_SEED_PROBE_MAX_NODES),
                RSIntegrationConfig.serverInt(RSIntegrationConfig.CRAFTING_PURE_DIAGNOSTIC_MAX_OPERATIONS));
    }
}
