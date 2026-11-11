package com.huanghuang.rsintegration.mixin.refinedstorage;

import com.huanghuang.rsintegration.mods.rs.monitor.MonitorMenuAccess;
import com.huanghuang.rsintegration.mods.rs.monitor.MonitorScopeState;
import com.huanghuang.rsintegration.mods.rs.monitor.RsMonitorServer;
import com.refinedmods.refinedstorage.container.CraftingMonitorContainerMenu;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = CraftingMonitorContainerMenu.class, remap = false)
public abstract class CraftingMonitorMenuMixin implements MonitorMenuAccess {
    @Unique private final MonitorScopeState rsi$scope = new MonitorScopeState();

    @Override public MonitorScopeState rsi$monitorScope() { return rsi$scope; }

    @Inject(method = {"broadcastChanges", "m_38946_"}, at = @At("TAIL"), require = 1)
    private void rsi$syncOwnedCrafts(CallbackInfo ci) {
        var menu = (CraftingMonitorContainerMenu) (Object) this;
        if (menu.getPlayer() instanceof ServerPlayer player) {
            RsMonitorServer.synchronize(player, menu, rsi$scope);
        }
    }
}
