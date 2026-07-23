package com.huanghuang.rsintegration.mixin.minecraft;

import com.huanghuang.rsintegration.villager.client.VillagerRestockClient;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MerchantScreen.class)
public abstract class MerchantScreenSelectionMixin {
    @Inject(method = "postButtonClick", at = @At("HEAD"))
    private void rsIntegration$markTradeSelected(CallbackInfo callbackInfo) {
        VillagerRestockClient.markTradeSelected((MerchantScreen) (Object) this);
    }
}
