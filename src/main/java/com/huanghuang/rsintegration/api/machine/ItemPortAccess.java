package com.huanghuang.rsintegration.api.machine;

import net.minecraft.world.Container;

public enum ItemPortAccess {
    /**
     * 通过方向 capability 访问槽位。
     */
    ITEM_HANDLER,
    /**
     * 通过方块实体内部完整库存访问槽位。
     *
     * <p>用于容器槽、结果槽等未通过方向 capability 暴露的固定槽位。
     * 集成必须通过 {@link MachineIntegration#internalItemHandler}
     * 提供库存。</p>
     */
    MACHINE_INVENTORY,
    /**
     * 通过标准 {@link Container} 访问槽位。
     */
    CONTAINER,
    CUSTOM
}
