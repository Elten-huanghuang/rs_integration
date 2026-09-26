package com.huanghuang.rsintegration.mixin.ftbquests;
import java.lang.reflect.Method;

import com.huanghuang.rsintegration.compat.ftbquests.client.ConfirmCheckmarksButton;
import dev.ftb.mods.ftbquests.client.gui.quests.OtherButtonsPanelBottom;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds the action to FTB Quests' native bottom-right sidebar. */
@Mixin(value = OtherButtonsPanelBottom.class, remap = false)
public abstract class OtherButtonsPanelBottomMixin {

    @Inject(method = "addWidgets", at = @At("TAIL"))
    private void rsi$addConfirmCheckmarksButton(CallbackInfo callback) {
        OtherButtonsPanelBottom panel = (OtherButtonsPanelBottom) (Object) this;
        panel.add(new ConfirmCheckmarksButton(panel));
    }
}
