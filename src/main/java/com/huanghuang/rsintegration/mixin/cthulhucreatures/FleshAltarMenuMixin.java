package com.huanghuang.rsintegration.mixin.cthulhucreatures;

import com.huanghuang.rsintegration.network.gui.RemoteGuiAuth;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "cn.blockforge.generated.nirvanabossboss7206f.FleshAltarMenu", remap = false)
public abstract class FleshAltarMenuMixin {
    @Inject(method = {"m_6875_", "stillValid"}, at = @At("HEAD"), cancellable = true, remap = false)
    private void rsi$remoteStillValid(Player player, CallbackInfoReturnable<Boolean> callback) {
        // 只放行与当前远程菜单绑定的授权，目标被拆除或区块卸载后仍会关闭。
        if (player instanceof ServerPlayer serverPlayer
                && RemoteGuiAuth.isAuthorized(serverPlayer, (AbstractContainerMenu) (Object) this)) {
            callback.setReturnValue(true);
        }
    }
}
