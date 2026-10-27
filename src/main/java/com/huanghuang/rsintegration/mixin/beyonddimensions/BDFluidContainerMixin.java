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
 * 接管超越维度菜单里的流体容器点击（把网络存储中的流体装进/倒出光标上的容器）。
 *
 * <p>BDBaseMenu#customClickHandler 的第四个参数在版本间变过：0.7.x 是 boolean shift，
 * 0.8.x 起是 GLFW 修饰键掩码（低五位，SHIFT=1、CTRL=2、ALT=4，见 BdSlotAdapter#heldModifiers）。
 * 因此这里给两个描述符各写一个注入点，都用 {@code require = 0}：当前版本只会命中其中一个，
 * 另一个静默跳过。若像以前那样只按方法名匹配，另一个重载就会在描述符不符时抛
 * InvalidInjectionException 直接崩客户端。</p>
 *
 * <p>0.8.x 还多了带 amount 的六参重载（customClickHandler(int, KeyAmount, int, int, long)），
 * 那是“取指定数量”通道，语义与容器点击无关，故意不接。</p>
 */
@Pseudo
@Mixin(targets = "com.wintercogs.beyonddimensions.common.menu.BDBaseMenu", remap = false)
public abstract class BDFluidContainerMixin {
    @Inject(method = "customClickHandler(ILcom/wintercogs/beyonddimensions/api/storage/key/KeyAmount;IZ)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void rsi$fluidContainers(int slot, @Coerce Object selected, int button,
                                     boolean shift, CallbackInfo ci) {
        rsi$handleClick(slot, selected, button, shift, ci);
    }

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
