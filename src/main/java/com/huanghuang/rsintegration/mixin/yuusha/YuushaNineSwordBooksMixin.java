package com.huanghuang.rsintegration.mixin.yuusha;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.resonance.bridge.RSInventoryBridge;
import com.huanghuang.rsintegration.resonance.disk.ResonanceDiskWrapper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = org.heike233.chapterofyuusha3.comm.compat.curios.item.moonstone.NineSwordBooks.class, remap = false)
public abstract class YuushaNineSwordBooksMixin {

    @Unique
    private static int rsi$diagCount;

    @Inject(method = "countValidSwords", at = @At("RETURN"), cancellable = true)
    private void rsi$addDiskSwords(Player player, CallbackInfoReturnable<Integer> cir) {
        int extra = rsi$countDiskSwords(player);
        if (extra > 0) {
            int hotbar = cir.getReturnValue();
            int total = Math.min(hotbar + extra,
                    RSIntegrationConfig.NINE_SWORD_MAX_COUNT.get());
            cir.setReturnValue(total);
            if (rsi$diagCount < 5) {
                rsi$diagCount++;
                RSIntegrationMod.LOGGER.info("[RSI-YuushaNineSwords] countValidSwords: hotbar={}, disk={}, final={}",
                        hotbar, extra, total);
            }
        }
    }

    @Inject(method = "calculateTotalSwordDamage", at = @At("RETURN"), cancellable = true)
    private static void rsi$addDiskDamage(Player player, CallbackInfoReturnable<Float> cir) {
        float extra = rsi$sumDiskSwordDamage(player);
        if (extra > 0f) {
            cir.setReturnValue(cir.getReturnValue() + extra);
        }
    }

    @Unique
    private static int rsi$countDiskSwords(Player player) {
        if (!(player instanceof ServerPlayer sp)) return 0;
        ResonanceDiskWrapper disk = RSInventoryBridge.getResonanceDisk(sp);
        if (disk == null) return 0;
        int count = 0;
        for (ItemStack s : disk.getStacks()) {
            if (!s.isEmpty() && s.getItem() instanceof mods.flammpfeil.slashblade.item.ItemSlashBlade) {
                count++;
            }
        }
        return count;
    }

    @Unique
    private static float rsi$sumDiskSwordDamage(Player player) {
        if (!(player instanceof ServerPlayer sp)) return 0f;
        ResonanceDiskWrapper disk = RSInventoryBridge.getResonanceDisk(sp);
        if (disk == null) return 0f;
        float total = 0f;
        for (ItemStack s : disk.getStacks()) {
            if (!s.isEmpty() && s.getItem() instanceof mods.flammpfeil.slashblade.item.ItemSlashBlade blade) {
                var mods = blade.getAttributeModifiers(EquipmentSlot.MAINHAND, s);
                for (var mod : mods.get(Attributes.ATTACK_DAMAGE)) {
                    if (mod.getOperation() == AttributeModifier.Operation.ADDITION) {
                        total += (float) mod.getAmount();
                    }
                }
            }
        }
        return total;
    }
}
