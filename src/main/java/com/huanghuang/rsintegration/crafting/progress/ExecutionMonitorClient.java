package com.huanghuang.rsintegration.crafting.progress;

import com.huanghuang.rsintegration.crafting.plan.PlanBackgroundClient;
import com.huanghuang.rsintegration.mods.rs.monitor.RsMonitorNavigationClient;
import com.huanghuang.rsintegration.storage.StorageReference;
import com.huanghuang.rsintegration.util.ModIds;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.ModList;

import javax.annotation.Nullable;
import java.util.UUID;

/** 计划中心与快捷键共用监控入口，网络来源未知时交给服务端确认。 */
public final class ExecutionMonitorClient {
    private ExecutionMonitorClient() {}

    public record Target(@Nullable StorageReference network, @Nullable UUID craftId) {
        public boolean generic() {
            return network != null && !"refinedstorage".equals(network.backendId().value());
        }
    }

    public static Target target() {
        // 待确认计划也能查看所属网络，不能要求先提交订单才能进入 RS 监控。
        for (var plan : PlanBackgroundClient.completedPlans().values()) {
            if (plan.storageReference() != null) return new Target(plan.storageReference(), null);
        }
        UUID latest = null;
        for (var craft : CraftProgressTracker.taskSnapshots()) if (!craft.isTerminal()) latest = craft.craftId();
        return new Target(null, latest);
    }

    public static void open() {
        Target target = target();
        if (target.generic() || !ModList.get().isLoaded(ModIds.REFINED_STORAGE)) {
            Minecraft.getInstance().setScreen(new CraftProgressScreen());
        } else {
            RsMonitorNavigationClient.openExecutionMonitor(target.network(), target.craftId());
        }
    }
}
