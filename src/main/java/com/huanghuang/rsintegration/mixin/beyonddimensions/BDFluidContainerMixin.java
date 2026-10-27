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
 * <p>本类只服务 0.7.x 的旧版逻辑：{@code BDBaseMenu#customClickHandler} 的第四个参数
 * 在这里是 boolean shift。0.8 起该参数换成 GLFW 修饰键掩码，对应逻辑在
 * {@link BDFluidContainerModifiersMixin}；两者由 {@code RSIntegrationMixinPlugin}
 * 按版本（0.8 新增的 api.menu.interaction 包是否存在）与完整方法描述符分别启用，
 * 同一次运行只会装上其中一个。</p>
 *
 * <p>描述符写全是为了防止再次出现“只按方法名匹配、换版本后描述符不符直接崩客户端”
 * 的事故（0.8.7 上的 InvalidInjectionException 就是这么来的）。</p>
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

    @Unique
    private void rsi$handleClick(int slot, Object selected, int button, boolean shift,
                                 CallbackInfo ci) {
        if (BeyondDimensionsSoulInteraction.handle(
                (AbstractContainerMenu) (Object) this, button)
                || BeyondDimensionsFluidInteraction.handle((AbstractContainerMenu) (Object) this,
                slot, selected, button, shift)) ci.cancel();
    }
}
