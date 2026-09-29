package com.huanghuang.rsintegration.craftingstation;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.ClientSyncedConfig;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.sidepanel.data.BindingCache;
import com.huanghuang.rsintegration.sidepanel.data.BindingInfo;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** 客户端根据服务端同步配置和绑定缓存判断内嵌工作站是否可用。 */
@OnlyIn(Dist.CLIENT)
public final class CraftingStationAvailability {
    private CraftingStationAvailability() {}

    public static boolean isAvailable(CraftingStationMode mode) {
        if (mode == CraftingStationMode.CRAFTING) return true;
        boolean requireBinding = ClientSyncedConfig.isSynced()
                ? ClientSyncedConfig.REQUIRE_BOUND_MACHINE_FOR_VIRTUAL_STATION
                : RSIntegrationConfig.REQUIRE_BOUND_MACHINE_FOR_VIRTUAL_STATION.get();
        if (!requireBinding) return true;

        String requiredType = mode.requiredBindingTypeId();
        if (requiredType == null) return true;
        for (BindingInfo info : BindingCache.getInstance().getAll()) {
            ModType type = ModType.fromBlockKey(info.blockKey());
            if (type != null && requiredType.equals(type.id())) return true;
        }
        return false;
    }
}
