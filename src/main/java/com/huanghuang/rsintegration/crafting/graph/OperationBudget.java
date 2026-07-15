package com.huanghuang.rsintegration.crafting.graph;

/** Server-thread permit account for machine-backed craft operations. */
public final class OperationBudget {

    private final int maxActive;
    private final int maxStarts;
    private int active;
    private int starts;

    public OperationBudget(int maxActive, int maxStarts) {
        if (maxActive <= 0 || maxStarts <= 0) {
            throw new IllegalArgumentException("operation budget limits must be positive");
        }
        this.maxActive = maxActive;
        this.maxStarts = maxStarts;
    }

    public Permit tryAcquire() {
        return tryAcquire(1);
    }

    public Permit tryAcquire(int cost) {
        if (cost <= 0) throw new IllegalArgumentException("operation cost must be positive");
        if (active > maxActive - cost || starts > maxStarts - cost) return null;
        active += cost;
        starts += cost;
        OperationBudget[] owners = new OperationBudget[cost];
        java.util.Arrays.fill(owners, this);
        return new Permit(owners);
    }

    public static Permit combine(Permit first, Permit second) {
        if (first == null || second == null) throw new NullPointerException("permits");
        OperationBudget[] owners = new OperationBudget[first.owners.length + second.owners.length];
        System.arraycopy(first.owners, 0, owners, 0, first.owners.length);
        System.arraycopy(second.owners, 0, owners, first.owners.length, second.owners.length);
        first.owners = null;
        second.owners = null;
        return new Permit(owners);
    }

    /** Record a new start that reuses an already-active worker permit. */
    public boolean tryRecordStart() {
        if (starts >= maxStarts) return false;
        starts++;
        return true;
    }

    /** Atomically record one reused-worker start against both budget scopes. */
    public static boolean tryRecordStart(OperationBudget first, OperationBudget second) {
        if (first == null || second == null) throw new NullPointerException("budgets");
        if (first.starts >= first.maxStarts || second.starts >= second.maxStarts) return false;
        first.starts++;
        second.starts++;
        return true;
    }

    /** Roll back lifetime starts recorded for an operation that never reached delegate start. */
    public static void rollbackRecordedStart(OperationBudget first, OperationBudget second) {
        if (first == null || second == null) throw new NullPointerException("budgets");
        if (first.starts <= 0 || second.starts <= 0) {
            throw new IllegalStateException("no recorded start to roll back");
        }
        first.starts--;
        second.starts--;
    }

    public int availableCapacity() {
        return Math.max(0, Math.min(maxActive - active, maxStarts - starts));
    }

    public int active() { return active; }
    public int starts() { return starts; }

    public static final class Permit implements AutoCloseable {
        private OperationBudget[] owners;

        private Permit(OperationBudget[] owners) {
            this.owners = owners;
        }

        /** Roll back a permit when no delegate start was attempted. */
        public void cancelBeforeStart() {
            OperationBudget[] current = owners;
            if (current == null) return;
            owners = null;
            for (OperationBudget owner : current) {
                owner.active--;
                owner.starts--;
            }
        }

        @Override
        public void close() {
            OperationBudget[] current = owners;
            if (current == null) return;
            owners = null;
            for (OperationBudget owner : current) owner.active--;
        }
    }
}
