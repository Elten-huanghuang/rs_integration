package com.huanghuang.rsintegration.magic;

import com.huanghuang.rsintegration.crafting.quantity.SaturatingLongMath;

/**
 * 单次 Goety 任务的统一灵魂账本。
 *
 * <p>启动前锁定"网络预留 + 原生当前余额"必须覆盖总预算。运行期固定先扣
 * 网络预留，网络不足时再由原生来源补差，避免同一份灵魂重复计算。</p>
 */
public final class GoetySoulSourceCoordinator implements AutoCloseable {
    private final GoetySoulNetworkPool network;
    private final GoetyNativeSoulSource nativeSource;
    private final long total;
    private final long availableAtStart;
    private long consumed;
    private boolean closed;

    private GoetySoulSourceCoordinator(GoetySoulNetworkPool network,
                                       GoetyNativeSoulSource nativeSource,
                                       long total, long availableAtStart) {
        this.network = network;
        this.nativeSource = nativeSource;
        this.total = total;
        this.availableAtStart = availableAtStart;
    }

    public static GoetySoulSourceCoordinator create(
            long total, GoetySoulNetworkPool network, GoetyNativeSoulSource nativeSource) {
        if (network == null || nativeSource == null || total <= 0) return null;
        long available = combineLong(network.remaining(), nativeSource.available());
        if (available < total) return null;
        return new GoetySoulSourceCoordinator(
                network, nativeSource, total, available);
    }

    public long total() {
        return total;
    }

    public long availableAtStart() {
        return availableAtStart;
    }

    public long available() {
        if (closed) return 0;
        long remainingBudget = Math.max(0, total - consumed);
        return Math.min(remainingBudget,
                combineLong(network.remaining(), nativeSource.available()));
    }

    public long networkRemaining() {
        return closed ? 0 : network.remaining();
    }

    /** 原子语义：无法足额扣除时不会留下本次网络扣费。 */
    public boolean consume(long amount) {
        if (closed || amount <= 0 || available() < amount) return false;

        long fromNetwork = Math.min(amount, network.remaining());
        long nativeNeeded = amount - fromNetwork;
        if (nativeNeeded > Integer.MAX_VALUE || nativeSource.available() < nativeNeeded) return false;

        long withdrawn = fromNetwork <= 0 ? 0 : network.withdraw(fromNetwork);
        if (withdrawn != fromNetwork) {
            if (withdrawn > 0) network.refund(withdrawn);
            return false;
        }
        long remaining = amount - withdrawn;
        if (remaining <= 0) {
            consumed += amount;
            return true;
        }

        int fromNative = nativeSource.consume((int) remaining);
        if (fromNative == remaining) {
            consumed += amount;
            return true;
        }

        if (withdrawn > 0) network.refund(withdrawn);
        return false;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        network.close();
    }

    static long combineLong(long network, int nativeSource) {
        return SaturatingLongMath.add(Math.max(0, network), Math.max(0, nativeSource));
    }

    /** Goety 原生接口仍以 int 暴露余额，跨过该边界时显式饱和。 */
    static int combine(int network, int nativeSource) {
        return SaturatingLongMath.clampToInt(combineLong(network, nativeSource));
    }
}
