package com.huanghuang.rsintegration.mixin.traderefresh;
import java.lang.reflect.Method;

import com.huanghuang.rsintegration.villager.tradelock.VillagerTradeLockHooks;
import net.minecraftforge.network.NetworkEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Guards TradeRefresh's server-side refresh packet without linking the optional mod. */
@Pseudo
@Mixin(targets = "dev.xkmc.traderefresh.network.RefreshToServer", remap = false)
public abstract class TradeRefreshTradeLockMixin {
    @Inject(method = "handle", at = @At("HEAD"), cancellable = true, remap = false)
    private void rsIntegration$lockBookmarkedTrade(NetworkEvent.Context context,
                                                   CallbackInfo callback) {
        if (context != null && VillagerTradeLockHooks.blockRefresh(context.getSender())) {
            callback.cancel();
        }
    }
}
