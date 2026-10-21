package com.huanghuang.rsintegration.machine.client;

import com.huanghuang.rsintegration.network.binding.BindingEventHandler;
import com.huanghuang.rsintegration.network.binding.BindingInfo;

/** 侧栏机器入口是否可直接打开或操作。 */
public final class MachineEntryPolicy {
    private MachineEntryPolicy() {}

    public static boolean isSelectable(BindingInfo info) {
        return info != null
                && BindingEventHandler.supportsGuiByInfo(info)
                && !MachinePreferenceClient.isBlocked(info);
    }
}
