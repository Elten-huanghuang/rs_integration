package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.LongFunction;
import java.util.function.Supplier;

/** 与后端无关的入网分流作用域；不抽取机器、不判断机器进度，也不依赖 RS 或 AE 的返回值约定。 */
public final class NetworkOutputReception {
    private static final ThreadLocal<Set<Object>> INSERTING = new ThreadLocal<>();
    private static final ThreadLocal<Integer> INTERNAL = new ThreadLocal<>();

    private NetworkOutputReception() {}

    /** 无等待项、内部写回或同网嵌套时，后端无需创建资源键和复制 NBT。 */
    public static boolean canReceive(Object network) {
        if (INTERNAL.get() != null) return false;
        Set<Object> inserting = INSERTING.get();
        return (inserting == null || !inserting.contains(network))
                && TaskOutputReception.hasWaitingResources(network);
    }

    /** RSI 已持有资源的退款、恢复和最终写回；嵌套调用、异常返回都恢复原来的隔离层数。 */
    public static <T> T internal(Supplier<T> writeback) {
        Integer previous = INTERNAL.get();
        INTERNAL.set(previous == null ? 1 : previous + 1);
        try {
            return writeback.get();
        } finally {
            if (previous == null) INTERNAL.remove();
            else INTERNAL.set(previous);
        }
    }

    /**
     * 回调获得未被任务认领的数量，并返回后端原有结果。作用域覆盖整个后续存储调用。
     * 同网络递归不再认领，跨网络仍独立处理；不使用栈对象或来源标记猜测原始机器。
     */
    public static <T> T route(Object network, IntegrationResourceKey resource, long amount, boolean simulate,
                              LongFunction<T> remainder) {
        if (amount <= 0 || !canReceive(network)) return remainder.apply(amount);
        Set<Object> inserting = INSERTING.get();
        if (inserting == null) {
            inserting = Collections.newSetFromMap(new IdentityHashMap<>());
            INSERTING.set(inserting);
        }
        inserting.add(network);
        try {
            // 同一网络 tracked -> 普通 insert、经验转换及存储回调均只分流一次。
            long accepted = TaskOutputReception.receive(network, resource, amount, simulate);
            return remainder.apply(amount - accepted);
        } finally {
            // 移除全部网络引用后保留空容器，后续独立入库可复用递归保护集合。
            inserting.remove(network);
        }
    }
}
