package com.huanghuang.rsintegration.mixin.enigmaticaddons;

import auviotre.enigmatic.addon.contents.items.ArtificialFlower;
import auviotre.enigmatic.addon.handlers.SuperAddonHandler;
import com.huanghuang.rsintegration.resonance.bridge.RSInventoryBridge;
import com.huanghuang.rsintegration.resonance.passive.PassiveItemLookup;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

/** Lets Artificial Flower's native debuff-immunity lookup see resonance-disk flowers. */
@Mixin(value = ArtificialFlower.class, remap = false)
public abstract class ArtificialFlowerMixin {

    @Redirect(
            method = "onEffectApply",
            at = @At(
                    value = "INVOKE",
                    target = "Lauviotre/enigmatic/addon/handlers/SuperAddonHandler;getAllItem(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/Item;)Ljava/util/List;"
            ),
            require = 0,
            remap = false
    )
    private List<ItemStack> rsi$includeDiskFlowers(Player player, Item item) {
        List<ItemStack> original = SuperAddonHandler.getAllItem(player, item);
        if (!(player instanceof ServerPlayer serverPlayer)) return original;
        return PassiveItemLookup.appendMatching(
                original, RSInventoryBridge.getDiskItems(serverPlayer), item);
    }
}
