package com.huanghuang.rsintegration.mixin.malum;

import com.sammy.malum.visual_effects.SpiritAltarParticleEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.ItemStackHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 兼容 Malum 祭坛库存槽位数与底层物品列表长度不一致的情况。
 * <p>
 * Malum 的粒子循环按 {@code slotCount} 遍历，而 Lodestone 库存虽然继承了
 * Forge 的 {@code ItemStackHandler}，其目标方法参数仍要求子类类型。
 * 两者不一致时会在客户端 tick 中抛出越界异常并导致崩溃，因此这里通过
 * {@link Coerce} 接收子类实参，并对越界访问返回空物品。
 */
@Mixin(value = SpiritAltarParticleEffects.class, remap = false)
public abstract class SpiritAltarParticleEffectsMixin {

    @Redirect(
            method = "passiveSpiritAltarParticles",
            at = @At(
                    value = "INVOKE",
                    target = "Lteam/lodestar/lodestone/systems/blockentity/"
                            + "LodestoneBlockEntityInventory;getStackInSlot(I)"
                            + "Lnet/minecraft/world/item/ItemStack;"),
            remap = false,
            require = 0)
    private static ItemStack rsi$getSpiritStackSafely(
            @Coerce ItemStackHandler inventory, int slot) {
        if (slot < 0 || slot >= inventory.getSlots()) {
            return ItemStack.EMPTY;
        }
        try {
            return inventory.getStackInSlot(slot);
        } catch (RuntimeException exception) {
            return ItemStack.EMPTY;
        }
    }
}
