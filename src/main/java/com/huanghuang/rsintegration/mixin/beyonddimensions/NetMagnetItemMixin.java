package com.huanghuang.rsintegration.mixin.beyonddimensions;

import com.huanghuang.rsintegration.compat.ftbquests.ExternalItemProgressBridge;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Reports items successfully picked up by BD's portable network magnet. */
@Pseudo
@Mixin(targets = "com.wintercogs.beyonddimensions.common.item.NetMagnetItem", remap = false)
public abstract class NetMagnetItemMixin {

    @WrapOperation(
            method = "workContent",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraftforge/event/ForgeEventFactory;firePlayerItemPickupEvent(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/item/ItemEntity;Lnet/minecraft/world/item/ItemStack;)V"),
            remap = false,
            require = 0)
    private void rsi$reportFtbPickup(Player player, ItemEntity entity, ItemStack picked,
                                      Operation<Void> original) {
        original.call(player, entity, picked);
        if (player instanceof ServerPlayer serverPlayer && picked != null && !picked.isEmpty()) {
            ExternalItemProgressBridge.enqueue(serverPlayer, picked.copy());
        }
    }
}
