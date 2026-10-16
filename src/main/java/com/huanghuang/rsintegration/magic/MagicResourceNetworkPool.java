package com.huanghuang.rsintegration.magic;

/** 任务或节点可继续提取的魔法资源预留。 */
public interface MagicResourceNetworkPool extends AutoCloseable {
    int remaining();

    int withdraw(int requested);

    void refund(int amount);

    @Override
    void close();
}
