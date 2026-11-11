package com.huanghuang.rsintegration.mods.rs.monitor;

/** 状态随原生菜单创建和释放，不保留全局订阅表。 */
public interface MonitorMenuAccess {
    MonitorScopeState rsi$monitorScope();
}
