package com.huanghuang.rsintegration.mixin.sophisticatedbackpacks;

import com.huanghuang.rsintegration.util.RsOperationPlayerContext;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.p3pp3rf1y.sophisticatedbackpacks.util.PlayerInventoryProvider;
import net.p3pp3rf1y.sophisticatedbackpacks.util.PlayerInventoryProvider.BackpackInventorySlotConsumer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Keeps the owning player available while Sophisticated Backpacks runs upgrades. */
@Mixin(value = PlayerInventoryProvider.class, remap = false)
public abstract class PlayerInventoryProviderMixin {
    @WrapOperation(
            method = "runOnBackpacks(Lnet/minecraft/world/entity/player/Player;Lnet/p3pp3rf1y/sophisticatedbackpacks/util/PlayerInventoryProvider$BackpackInventorySlotConsumer;Z)V",
            at = @At(value = "INVOKE", target = "Lnet/p3pp3rf1y/sophisticatedbackpacks/util/PlayerInventoryProvider$BackpackInventorySlotConsumer;accept(Lnet/minecraft/world/item/ItemStack;Ljava/lang/String;Ljava/lang/String;I)Z"),
            remap = false)
    private boolean rsi$withPlayerContext(BackpackInventorySlotConsumer consumer,
                                           ItemStack backpack, String handlerName,
                                           String inventoryName, int slot,
                                           Operation<Boolean> original, Player player) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return original.call(consumer, backpack, handlerName, inventoryName, slot);
        }
        try (RsOperationPlayerContext.Scope ignored = RsOperationPlayerContext.push(serverPlayer)) {
            return original.call(consumer, backpack, handlerName, inventoryName, slot);
        }
    }
}
