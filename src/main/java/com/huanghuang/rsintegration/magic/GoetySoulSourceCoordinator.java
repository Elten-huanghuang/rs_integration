package com.huanghuang.rsintegration.magic;

/**
 * 单次 Goety 任务的统一灵魂账本。
 *
 * <p>启动前锁定"网络预留 + 原生当前余额"必须覆盖总预算。运行期固定先扣
 * 网络预留，网络不足时再由原生来源补差，避免同一份灵魂重复计算。</p>
 */
public final class GoetySoulSourceCoordinator implements AutoCloseable {
    private final GoetySoulNetworkPool network;
    private final GoetyNativeSoulSource nativeSource;
    private final int total;
    private final int availableAtStart;
    private boolean closed;

    private GoetySoulSourceCoordinator(GoetySoulNetworkPool network,
                                       GoetyNativeSoulSource nativeSource,
                                       int total, int availableAtStart) {
        this.network = network;
        this.nativeSource = nativeSource;
        this.total = total;
        this.availableAtStart = availableAtStart;
    }

    public static GoetySoulSourceCoordinator create(
            int total, GoetySoulNetworkPool network, GoetyNativeSoulSource nativeSource) {
        if (network == null || nativeSource == null || total <= 0) return null;
        int available = combine(network.remaining(), nativeSource.available());
        if (available < total) return null;
        return new GoetySoulSourceCoordinator(
                network, nativeSource, total, available);
    }

    public int total() {
        return total;
    }

    public int availableAtStart() {
        return availableAtStart;
    }

    public int available() {
        if (closed) return 0;
        return combine(network.remaining(), nativeSource.available());
    }

    public int networkRemaining() {
        return closed ? 0 : network.remaining();
    }

    /** 原子语义：无法足额扣除时不会留下本次网络扣费。 */
    public boolean consume(int amount) {
        if (closed || amount <= 0 || available() < amount) return false;

        int fromNetwork = Math.min(amount, network.remaining());
        int nativeNeeded = amount - fromNetwork;
        if (nativeSource.available() < nativeNeeded) return false;

        int withdrawn = fromNetwork <= 0 ? 0 : network.withdraw(fromNetwork);
        if (withdrawn != fromNetwork) {
            if (withdrawn > 0) network.refund(withdrawn);
            return false;
        }
        int remaining = amount - withdrawn;
        if (remaining <= 0) return true;

        int fromNative = nativeSource.consume(remaining);
        if (fromNative == remaining) return true;

        if (withdrawn > 0) network.refund(withdrawn);
        return false;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        network.close();
    }

    static int combine(int network, int nativeSource) {
        long result = (long) Math.max(0, network) + Math.max(0, nativeSource);
        return result > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) result;
    }
}
