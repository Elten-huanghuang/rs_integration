package com.huanghuang.rsintegration.mixin.majruszsdifficulty;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Converts every Progressive Difficulty treasure-bag grant into a world drop. */
@Mixin(targets = "com.majruszlibrary.item.ItemHelper", remap = false)
public abstract class MajruszItemHelperMixin {

    private static final String MAGNET_ONLY_TAG = "RSIMajruszTreasureBagMagnetOnly";

    @Inject(method = "giveToPlayer", at = @At("HEAD"), cancellable = true, remap = false)
    private static void rsi$dropTreasureBag(ItemStack stack, Player player, CallbackInfo ci) {
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (!itemId.getNamespace().equals("majruszsdifficulty")
                || !itemId.getPath().endsWith("_treasure_bag")) {
            return;
        }

        ItemEntity itemEntity = new ItemEntity(player.level(), player.getX(),
                player.getY() + 0.5D, player.getZ(), stack);
        itemEntity.getPersistentData().putBoolean(MAGNET_ONLY_TAG, true);
        itemEntity.setPickUpDelay(Integer.MAX_VALUE);
        itemEntity.setDeltaMovement(0.0D, 0.1D, 0.0D);
        player.level().addFreshEntity(itemEntity);
        ci.cancel();
    }
}
