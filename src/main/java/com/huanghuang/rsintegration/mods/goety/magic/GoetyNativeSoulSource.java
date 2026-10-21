package com.huanghuang.rsintegration.mods.goety.magic;

/** 机器原生灵魂来源，例如诅咒笼或绑定的 Arca。 */
public interface GoetyNativeSoulSource {
    int available();

    /** 返回实际扣除量；实现必须在失败时不产生部分扣除。 */
    int consume(int requested);
}
