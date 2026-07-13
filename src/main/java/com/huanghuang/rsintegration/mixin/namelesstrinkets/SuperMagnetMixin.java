package com.huanghuang.rsintegration.mixin.namelesstrinkets;

import com.cozary.nameless_trinkets.items.trinkets.SuperMagnet;
import com.huanghuang.rsintegration.crafting.CraftOutputInterceptor;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Prevents the Nameless Trinkets SuperMagnet from teleporting ItemEntities
 * that are inside an active {@link CraftOutputInterceptor} capture zone,
 * closing a gap where the magnet could steal machine craft outputs before
 * the batch chain collects them.
 */
@Mixin(value = SuperMagnet.class, remap = false)
public abstract class SuperMagnetMixin {

    @Redirect(method = "curioTick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/item/ItemEntity;m_32061_()V"), require = 1)
    private void rsi$preserveCaptureZonePickupDelay(ItemEntity instance) {
        if (!CraftOutputInterceptor.isInActiveZone(instance.level(), instance.position())) {
            instance.setNoPickUpDelay();
        }
    }

    @Redirect(method = "curioTick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/item/ItemEntity;m_6034_(DDD)V"), require = 1)
    private void rsi$blockCaptureZoneTeleport(ItemEntity instance, double x, double y, double z) {
        if (!CraftOutputInterceptor.isInActiveZone(instance.level(), instance.position())) {
            instance.moveTo(x, y, z);
        }
    }
}
