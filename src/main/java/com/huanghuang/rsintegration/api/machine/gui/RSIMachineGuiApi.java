package com.huanghuang.rsintegration.api.machine.gui;

import com.huanghuang.rsintegration.api.RSIIntegrationApi;
import com.huanghuang.rsintegration.api.RSIIntegrationServices;
import com.huanghuang.rsintegration.api.RegistrationPhase;
import net.minecraft.world.level.block.entity.BlockEntityType;
import java.util.Objects;

/** 为原生交互会修改世界状态的方块注册菜单的公共 API。 */
public final class RSIMachineGuiApi {
    public static final int API_MAJOR = 1, API_MINOR = 0;
    private RSIMachineGuiApi() {}

    /**
     * 在通用设置阶段（enqueueWork）注册。处理器优先于 Block.use；
     * 注册后即使菜单打开失败，也不会再调用原生交互。
     * 存在重复处理器或当前不处于注册阶段时返回 false。
     * 打开菜单仍会经过 RSI 现有的绑定与权限校验。
     */
    public static boolean register(BlockEntityType<?> type, MachineGuiOpener opener) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(opener, "opener");
        if (RSIIntegrationApi.get().map(api -> api.phase() != RegistrationPhase.REGISTRATION).orElse(true)) {
            return false;
        }
        return RSIIntegrationServices.registerMachineGui(type, opener);
    }
}
