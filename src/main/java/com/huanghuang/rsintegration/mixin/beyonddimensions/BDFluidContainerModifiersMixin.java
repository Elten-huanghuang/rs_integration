package com.huanghuang.rsintegration.mixin.beyonddimensions;

import com.huanghuang.rsintegration.mods.goety.magic.BeyondDimensionsSoulInteraction;
import com.huanghuang.rsintegration.storage.bd.BeyondDimensionsFluidInteraction;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 0.8 起接管超越维度菜单里的流体容器点击。
 *
 * <p>0.8 的 {@code BDBaseMenu#customClickHandler} 第四个参数由 boolean shift 换成 GLFW
 * 修饰键掩码：bit1 对应第一参数（原 shift 位），bit2 是 Ctrl、bit4 是 Alt，组合方式见
 * {@code BdSlotAdapter#heldModifiers(boolean, boolean)}。这里只关心第一位，即
 * {@code (modifiers & 0x1) != 0}。带 amount 的六参重载是“取指定数量”通道，语义与容器
 * 点击无关，故意不接。</p>
 */
@Pseudo
@Mixin(targets = "com.wintercogs.beyonddimensions.common.menu.BDBaseMenu", remap = false)
public abstract class BDFluidContainerModifiersMixin {
    @Inject(method = "customClickHandler(ILcom/wintercogs/beyonddimensions/api/storage/key/KeyAmount;II)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void rsi$fluidContainersModifiers(int slot, @Coerce Object selected, int button,
                                              int modifiers, CallbackInfo ci) {
        rsi$handleClick(slot, selected, button, (modifiers & 0x1) != 0, ci);
    }

    @Unique
    private void rsi$handleClick(int slot, Object selected, int button, boolean shift,
                                 CallbackInfo ci) {
        if (BeyondDimensionsSoulInteraction.handle(
                (AbstractContainerMenu) (Object) this, button)
                || BeyondDimensionsFluidInteraction.handle((AbstractContainerMenu) (Object) this,
                slot, selected, button, shift)) ci.cancel();
    }
}
