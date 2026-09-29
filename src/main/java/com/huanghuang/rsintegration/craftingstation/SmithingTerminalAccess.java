package com.huanghuang.rsintegration.craftingstation;

import com.refinedmods.refinedstorage.container.GridContainerMenu;

/** 供客户端绘制、网络包和槽位代理访问 GridContainerMenu Mixin 状态。 */
public interface SmithingTerminalAccess extends CraftingStationAccess {
    boolean rsi$isSmithingMode();
    void rsi$setSmithingMode(boolean enabled);
    SmithingTerminalState rsi$getSmithingState();

    static SmithingTerminalAccess access(GridContainerMenu menu) {
        return (SmithingTerminalAccess) (Object) menu;
    }
}
