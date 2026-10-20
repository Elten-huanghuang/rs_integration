package com.huanghuang.rsintegration.magic;

/** 任务或节点可继续提取的魔法资源预留。 */
public interface MagicResourceNetworkPool extends AutoCloseable {
    long remaining();

    long withdraw(long requested);

    void refund(long amount);

    @Override
    void close();
}
