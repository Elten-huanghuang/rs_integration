package com.huanghuang.rsintegration.mixin.distantworlds;
import java.lang.reflect.Method;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.ItemHandlerHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts Distant Worlds wither totem drops to convert direct inventory insertion
 * into entity drops that can be picked up by RS magnet upgrades.
 */
@Mixin(value = ItemHandlerHelper.class, remap = false)
public class DistantWorldsItemHelperMixin {
    private static final String MAGNET_ONLY_TAG = "rs_integration:magnet_only";

    @Inject(method = "giveItemToPlayer(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/ItemStack;)V",
            at = @At("HEAD"), cancellable = true, remap = false)
    private static void rsi$dropWitherTotem(Player player, ItemStack stack, CallbackInfo ci) {
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (!itemId.getNamespace().equals("distant_worlds")
                || (!itemId.getPath().equals("wither_totem")
                    && !itemId.getPath().equals("charged_wither_totem"))) {
            return;
        }

        ItemEntity itemEntity = new ItemEntity(player.level(), player.getX(),
                player.getY() + 0.5D, player.getZ(), stack);
        itemEntity.getPersistentData().putBoolean(MAGNET_ONLY_TAG, true);
        // Set a short delay - allows pickup after 20 ticks (1 second)
        itemEntity.setPickUpDelay(20);
        itemEntity.setDeltaMovement(0.0D, 0.1D, 0.0D);
        player.level().addFreshEntity(itemEntity);
        ci.cancel();
    }
}
