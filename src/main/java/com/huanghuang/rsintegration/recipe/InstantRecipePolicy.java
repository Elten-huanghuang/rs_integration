package com.huanghuang.rsintegration.recipe;
import com.huanghuang.rsintegration.recipe.InstantRecipePolicy;

/**
 * 即时配方的批量执行契约。
 *
 * <p>只有处理器明确声明策略后，配方才能绕过逐次调度。策略只描述
 * “如何安全放大一次逻辑执行”，不替代材料角色、输出捕获或并发能力。</p>
 */
public record InstantRecipePolicy(Mode mode, int maxExecutions) {

    public enum Mode {
        /** 保持逐次执行。 */
        NONE,
        /** 成本、主产物和副产物都可直接按逻辑次数做账本结算。 */
        LEDGER_TRANSACTION,
        /** 每次仍需要真实装配，只允许在有限内存批中重复执行。 */
        SIMULATED_ASSEMBLY
    }

    public InstantRecipePolicy {
        mode = mode == null ? Mode.NONE : mode;
        maxExecutions = Math.max(0, maxExecutions);
        if (mode == Mode.NONE) maxExecutions = 0;
    }

    public static InstantRecipePolicy none() {
        return new InstantRecipePolicy(Mode.NONE, 0);
    }

    public static InstantRecipePolicy ledgerTransactionPolicy() {
        return new InstantRecipePolicy(Mode.LEDGER_TRANSACTION, 0);
    }

    public static InstantRecipePolicy simulatedAssembly(int maxExecutions) {
        if (maxExecutions <= 0) {
            throw new IllegalArgumentException("simulated assembly requires a positive limit");
        }
        return new InstantRecipePolicy(Mode.SIMULATED_ASSEMBLY, maxExecutions);
    }

    public boolean enabled() {
        return mode != Mode.NONE;
    }

    public boolean ledgerTransaction() {
        return mode == Mode.LEDGER_TRANSACTION;
    }

    public boolean simulatedAssembly() {
        return mode == Mode.SIMULATED_ASSEMBLY;
    }

    /** 0 表示只受调用方剩余次数和安全上限约束。 */
    public int limit(int requestedExecutions) {
        int requested = Math.max(1, requestedExecutions);
        if (!enabled()) return 1;
        return maxExecutions == 0 ? requested : Math.min(requested, maxExecutions);
    }
}
