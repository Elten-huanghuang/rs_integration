package com.huanghuang.rsintegration.mixin.refinedstorage;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.refinedmods.refinedstorage.item.NetworkItem;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = NetworkItem.class, remap = false)
public class NetworkItemUseMixin {

    @Inject(method = "m_7203_", at = @At("HEAD"), cancellable = true)
    private void rsi$cancelUseWhenSneaking(Level level, Player player, InteractionHand hand,
                                           CallbackInfoReturnable<InteractionResultHolder<ItemStack>> cir) {
        if (RSIntegrationConfig.ENABLE_BINDING.get() && player.isShiftKeyDown()) {
            cir.setReturnValue(InteractionResultHolder.pass(player.getItemInHand(hand)));
            cir.cancel();
        }
    }
}
