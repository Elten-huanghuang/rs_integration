package com.huanghuang.rsintegration.mixin.terraequipment;

import com.huanghuang.rsintegration.resonance.bridge.RSInventoryBridge;
import com.huanghuang.rsintegration.resonance.disk.ResonanceDiskWrapper;
import com.inolia_zaicek.terra_equipment.config.TEConfig;
import com.inolia_zaicek.terra_equipment.item.EffectPotionItem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Extends Terra Equipment's infinite-potion scan to the resonance disk. */
@Mixin(value = com.inolia_zaicek.terra_equipment.util.AutoPotionTicker.class, remap = false)
public abstract class AutoPotionTickerMixin {

    @Inject(method = "onPlayerTick", at = @At("RETURN"), require = 1)
    private static void rsi$applyDiskPotionEffects(TickEvent.PlayerTickEvent event, CallbackInfo ci) {
        if (event.phase != TickEvent.Phase.END || event.player.level().isClientSide()) return;
        if (event.player.tickCount % 40 != 0) return;
        if (!(event.player instanceof ServerPlayer player)) return;

        ResonanceDiskWrapper disk = RSInventoryBridge.getResonanceDisk(player);
        if (disk == null) return;

        int requiredCount = (int) TEConfig.infinite_potion_number.get().doubleValue();
        for (ItemStack stack : disk.getStacks()) {
            if (stack.getCount() < requiredCount) continue;
            if (stack.getItem() instanceof EffectPotionItem potion) {
                player.addEffect(new MobEffectInstance(
                        potion.getEffect(), potion.getDuration(), potion.getAmplifier()));
            }
        }
    }
}
