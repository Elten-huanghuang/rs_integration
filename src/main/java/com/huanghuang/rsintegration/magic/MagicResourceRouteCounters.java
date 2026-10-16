package com.huanghuang.rsintegration.magic;

import com.huanghuang.rsintegration.magic.route.BackendResolution;
import com.huanghuang.rsintegration.magic.route.Channel;

import javax.annotation.Nullable;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 路由层的运行期计数器，供 {@code /rsi_debug perf} 展示。
 *
 * <p>只记「路由判定」本身，不记库存量：这样排查「预览说够、执行拿不到」时，
 * 能先看是不是走了 UNSUPPORTED / UNREACHABLE 分支，而不是去猜库存。</p>
 */
public final class MagicResourceRouteCounters {
    private static final AtomicLong ROUTE_TOTAL = new AtomicLong();
    private static final AtomicLong UNSUPPORTED_TOTAL = new AtomicLong();
    private static final AtomicLong UNREACHABLE_TOTAL = new AtomicLong();
    private static final AtomicLong AMBIGUOUS_TOTAL = new AtomicLong();
    private static final AtomicLong NO_CHANNEL_TOTAL = new AtomicLong();
    /** 最近一次路由，供 perf 段显示「上一次判定是怎么落的」。 */
    private static volatile String lastRoute = "";

    private MagicResourceRouteCounters() {}

    /** 记一次 {@code resolve()} 的结果；四种状态各有独立计数。 */
    public static void record(MagicResourceKind kind, String linkName,
                              BackendResolution resolution) {
        ROUTE_TOTAL.incrementAndGet();
        lastRoute = kind == null ? linkName : kind.id() + "@" + linkName
                + " → " + resolution.kind();
        switch (resolution.kind()) {
            case UNSUPPORTED -> UNSUPPORTED_TOTAL.incrementAndGet();
            case UNREACHABLE -> UNREACHABLE_TOTAL.incrementAndGet();
            case AMBIGUOUS -> AMBIGUOUS_TOTAL.incrementAndGet();
            case AVAILABLE -> {
                if (resolution.channel() == Channel.NONE) NO_CHANNEL_TOTAL.incrementAndGet();
            }
        }
    }

    public static long routeTotal() { return ROUTE_TOTAL.get(); }

    public static long unsupportedTotal() { return UNSUPPORTED_TOTAL.get(); }

    public static long unreachableTotal() { return UNREACHABLE_TOTAL.get(); }

    public static long ambiguousTotal() { return AMBIGUOUS_TOTAL.get(); }

    public static long noChannelTotal() { return NO_CHANNEL_TOTAL.get(); }

    /** 最近一次路由的「资源@链路 → 状态」；没有记录时为空串。 */
    public static String lastRoute() { return lastRoute; }

    /** 诊断用：清零；只在命令里调用。 */
    public static void reset() {
        ROUTE_TOTAL.set(0);
        UNSUPPORTED_TOTAL.set(0);
        UNREACHABLE_TOTAL.set(0);
        AMBIGUOUS_TOTAL.set(0);
        NO_CHANNEL_TOTAL.set(0);
        lastRoute = "";
    }
}
