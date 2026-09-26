package com.huanghuang.rsintegration.mixin.apotheosis;
import java.lang.reflect.Method;

import com.huanghuang.rsintegration.reforging.client.ReforgingRestockClient;
import net.minecraft.world.Container;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps a client-only sigil count sync from regenerating fake choices. */
@Mixin(targets = "dev.shadowsoffire.apotheosis.adventure.affix.reforging.ReforgingMenu", remap = false)
public abstract class ReforgingMenuRestockMixin {
    @Inject(method = "m_6199_(Lnet/minecraft/world/Container;)V", at = @At("HEAD"), cancellable = true,
            remap = false)
    private void rsIntegration$suppressRestockRefresh(Container container, CallbackInfo ci) {
        if (ReforgingRestockClient.consumeRefreshSuppression()) ci.cancel();
    }
}
