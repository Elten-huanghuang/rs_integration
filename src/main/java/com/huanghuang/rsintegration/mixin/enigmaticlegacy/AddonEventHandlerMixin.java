package com.huanghuang.rsintegration.mixin.enigmaticlegacy;

import auviotre.enigmatic.addon.handlers.AddonEventHandler;
import com.aizistral.enigmaticlegacy.handlers.SuperpositionHandler;
import com.huanghuang.rsintegration.resonance.bridge.RSInventoryBridge;
import com.huanghuang.rsintegration.resonance.disk.ResonanceDiskWrapper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 拦截 Enigmatic Addons 原生事件处理器中的 hasItem 判定。
 * 完美保留原模组的所有伤害计算与前置条件逻辑。
 */
@Mixin(value = AddonEventHandler.class, remap = false)
public abstract class AddonEventHandlerMixin {

    // 拦截 AddonEventHandler 中所有的 hasItem 调用
    @Redirect(
            method = {
                    "onTick",
                    "onEntityHurt",
                    "onFirstHurt",
                    "onEntityAttack"
            },
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/aizistral/enigmaticlegacy/handlers/SuperpositionHandler;hasItem(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/Item;)Z"
            ),
            remap = false
    )
    private boolean rsi$redirectHasItem(Player player, Item item) {
        // 1. 走原版判定，如果原版背包有，直接返回 true，完美放行
        if (SuperpositionHandler.hasItem(player, item)) {
            return true;
        }

        // 2. 如果原版背包找不到，我们补查 RS 盘
        if (player instanceof ServerPlayer sp) {
            ResonanceDiskWrapper disk = RSInventoryBridge.getResonanceDisk(sp);
            if (disk != null) {
                for (ItemStack stack : disk.getStacks()) {
                    // 如果 RS 盘内的物品和模组正在查询的物品一致，返回 true
                    if (!stack.isEmpty() && stack.getItem() == item) {
                        return true;
                    }
                }
            }
        }

        return false;
    }
}