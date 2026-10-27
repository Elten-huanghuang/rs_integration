package com.huanghuang.rsintegration.mixin.beyonddimensions;

import com.huanghuang.rsintegration.machine.client.MachineHubPage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 0.8 的侧边栏页签点击最终都会走到 {@code BdPageSelection#open(String)}。
 *
 * <p>RSI 注册的三个页签（机器中心、共振背包、自动进食）只是“按钮”：点下去不应该切页，
 * 否则存储内容会被空页面顶掉、窗口还会因为页面尺寸变化而缩放。这里在切页之前拦下这三个 id，
 * 交给 {@link MachineHubPage#handleRailClick(String)} 处理，其余 id 保持 BD 原本行为。</p>
 */
@Pseudo
@Mixin(targets = "com.wintercogs.beyonddimensions.client.ui.base.BdPageSelection", remap = false)
public abstract class BdPageSelectionMixin {

    @Inject(method = "open", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void rsi$interceptRailClick(String pageId, CallbackInfo ci) {
        if (MachineHubPage.handleRailClick(pageId)) {
            ci.cancel();
        }
    }
}
