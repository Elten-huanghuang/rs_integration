package com.huanghuang.rsintegration.craftingstation;

/** 暴露终端切石机配方列表的当前滚动起点给 BaseScreen Tooltip 注入。 */
public interface StonecutterScreenAccess {
    int rsi$getStonecutterStartIndex();
}
