package com.huanghuang.rsintegration.network.craftingstation;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.feature.craftingstation.CraftingStationMode;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** 服务端权威校验内嵌工作站是否允许启用。 */
final class CraftingStationServerPolicy {
    private CraftingStationServerPolicy() {}

    static boolean ensureAvailable(
            ServerPlayer player, CraftingStationMode mode) {
        if (!RSIntegrationConfig.REQUIRE_BOUND_MACHINE_FOR_VIRTUAL_STATION.get()
                || mode.requiredBindingTypeId() == null) {
            return true;
        }
        ModType type = ModType.byId(mode.requiredBindingTypeId());
        if (type != null
                && !AltarBindingRegistry.getBoundMachinesForType(player, type).isEmpty()) {
            return true;
        }
        player.displayClientMessage(Component.translatable(
                "rsi.generic.error.no_bound_machine",
                Component.translatable(mode.displayNameKey())), true);
        return false;
    }
}
