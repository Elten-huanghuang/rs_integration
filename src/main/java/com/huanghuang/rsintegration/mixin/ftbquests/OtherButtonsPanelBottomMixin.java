package com.huanghuang.rsintegration.mixin.ftbquests;
import java.lang.reflect.Method;

import com.huanghuang.rsintegration.compat.ftbquests.client.ConfirmCheckmarksButton;
import com.huanghuang.rsintegration.config.ClientSyncedConfig;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
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
        boolean enabled = ClientSyncedConfig.isSynced()
                ? ClientSyncedConfig.ENABLE_FTB_QUEST_CHECKMARK_BUTTON
                : RSIntegrationConfig.ENABLE_FTB_QUEST_CHECKMARK_BUTTON.get();
        if (enabled) panel.add(new ConfirmCheckmarksButton(panel));
    }
}
