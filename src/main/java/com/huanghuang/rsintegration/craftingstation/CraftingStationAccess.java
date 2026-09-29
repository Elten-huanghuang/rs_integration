package com.huanghuang.rsintegration.craftingstation;

import com.refinedmods.refinedstorage.container.GridContainerMenu;

/** 供界面、槽位代理和网络包访问终端内嵌工作站状态。 */
public interface CraftingStationAccess {
    CraftingStationMode rsi$getCraftingStationMode();
    void rsi$setCraftingStationMode(CraftingStationMode mode);
    CraftingStationState rsi$getCraftingStationState();

    static CraftingStationAccess access(GridContainerMenu menu) {
        return (CraftingStationAccess) (Object) menu;
    }
}
