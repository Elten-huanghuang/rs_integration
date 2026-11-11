package com.huanghuang.rsintegration.mods.rs.monitor;

import net.minecraft.core.GlobalPos;

/** RS 原生监控界面保留跳转来源终端的运行时接口。 */
public interface MonitorNavigationAccess {
    GlobalPos rsi$returnTerminal();
    void rsi$returnTerminal(GlobalPos terminal);
}
