package com.huanghuang.rsintegration.crafting;

/** Server-thread budget shared by all vanilla crafting chains during one tick. */
final class VanillaCraftingTickBudget {
    private final int limit;
    private int used;

    VanillaCraftingTickBudget(int limit) {
        if (limit <= 0) throw new IllegalArgumentException("limit must be positive");
        this.limit = limit;
    }

    ChainAllowance allowance(int perChainLimit) {
        return new ChainAllowance(this, Math.max(1, perChainLimit));
    }

    int used() {
        return used;
    }

    int limit() {
        return limit;
    }

    private int claimUpTo(int requested) {
        int granted = Math.min(Math.max(0, requested), limit - used);
        used += granted;
        return granted;
    }

    static final class ChainAllowance {
        private final VanillaCraftingTickBudget global;
        private final int limit;
        private int used;

        private ChainAllowance(VanillaCraftingTickBudget global, int limit) {
            this.global = global;
            this.limit = limit;
        }

        int remaining() {
            return Math.max(0, Math.min(limit - used, global.limit - global.used));
        }

        int claimUpTo(int requested) {
            int granted = global.claimUpTo(Math.min(Math.max(0, requested), limit - used));
            used += granted;
            return granted;
        }

        boolean tryClaimExact(int requested) {
            if (requested <= 0) throw new IllegalArgumentException("requested must be positive");
            if (remaining() < requested) return false;
            return claimUpTo(requested) == requested;
        }

        int used() {
            return used;
        }
    }
}
