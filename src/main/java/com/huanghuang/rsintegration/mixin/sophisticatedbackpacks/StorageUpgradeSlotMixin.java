package com.huanghuang.rsintegration.mixin.sophisticatedbackpacks;

import com.huanghuang.rsintegration.RSIntegrationMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import net.p3pp3rf1y.sophisticatedcore.common.gui.StorageContainerMenuBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = StorageContainerMenuBase.StorageUpgradeSlot.class, remap = false)
public class StorageUpgradeSlotMixin {

    @Shadow
    private int slotIndex;

    @Inject(method = "m_5857_", at = @At("HEAD"), cancellable = true)
    private void onMayPlaceHead(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (stack.isEmpty()) return;
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (key != null && key.getNamespace().equals("rs_integration")) {
            RSIntegrationMod.LOGGER.debug("[RSI-SB] mayPlace HEAD slot={} item={} class={} count={} - FORCE true",
                    slotIndex, key, stack.getItem().getClass().getName(), stack.getCount());
            cir.setReturnValue(true);
            cir.cancel();
        }
    }

    @Inject(method = "m_5857_", at = @At("RETURN"), cancellable = false)
    private void onMayPlaceReturn(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (key != null && key.getNamespace().equals("rs_integration")) {
            RSIntegrationMod.LOGGER.debug("[RSI-SB] mayPlace RETURN slot={} item={} result={}",
                    slotIndex, key, cir.getReturnValue());
        }
    }
}
