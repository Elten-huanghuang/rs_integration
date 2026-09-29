package com.huanghuang.rsintegration.mixin.minecraft;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** 让虚拟终端沿用原版铁砧菜单的经验扣除和输入消耗流程。 */
@Mixin(AnvilMenu.class)
public interface AnvilMenuAccessor {
    @Invoker("onTake")
    void rsi$onTake(Player player, ItemStack result);
}
