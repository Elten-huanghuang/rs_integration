package com.huanghuang.rsintegration.mixin.sophisticatedbackpacks;

import net.p3pp3rf1y.sophisticatedcore.upgrades.UpgradeHandler;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = UpgradeHandler.class, remap = false)
public class UpgradeHandlerMixin {
    // Previously held a no-op @Inject on isItemValid — removed as dead code.
}
