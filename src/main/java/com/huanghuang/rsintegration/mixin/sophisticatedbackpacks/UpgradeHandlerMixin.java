package com.huanghuang.rsintegration.mixin.sophisticatedbackpacks;

import com.huanghuang.rsintegration.RSIntegrationMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import net.p3pp3rf1y.sophisticatedcore.upgrades.IUpgradeItem;
import net.p3pp3rf1y.sophisticatedcore.upgrades.UpgradeHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = UpgradeHandler.class, remap = false)
public class UpgradeHandlerMixin {

    @Inject(method = "isItemValid", at = @At("RETURN"), cancellable = false)
    private void onIsItemValid(int slot, ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (!stack.isEmpty()) {
            Item item = stack.getItem();
            ResourceLocation key = ForgeRegistries.ITEMS.getKey(item);
            if (key != null && key.getNamespace().equals("rs_integration")) {
                boolean result = cir.getReturnValue();
                boolean isIUpgrade = item instanceof IUpgradeItem;
                RSIntegrationMod.LOGGER.debug("[RSI-SB] isItemValid(slot={}, item={}): result={}, instanceof IUpgradeItem={}",
                        slot, key, result, isIUpgrade);
            }
        }
    }
}
