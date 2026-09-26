package com.huanghuang.rsintegration.mixin.ftbquests;
import java.lang.reflect.Method;

import com.huanghuang.rsintegration.compat.ftbquests.client.ScanStorageTasksButton;
import com.huanghuang.rsintegration.config.ClientSyncedConfig;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import dev.ftb.mods.ftbquests.client.gui.quests.OtherButtonsPanelTop;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds the one-shot combined item-task scan to FTB Quests' upper-right sidebar. */
@Mixin(value = OtherButtonsPanelTop.class, remap = false)
public abstract class OtherButtonsPanelTopMixin {

    @Inject(method = "addWidgets", at = @At("TAIL"))
    private void rsi$addStorageTaskScanButton(CallbackInfo callback) {
        OtherButtonsPanelTop panel = (OtherButtonsPanelTop) (Object) this;
        boolean enabled = ClientSyncedConfig.isSynced()
                ? ClientSyncedConfig.ENABLE_FTB_QUEST_STORAGE_SCAN_BUTTON
                : RSIntegrationConfig.ENABLE_FTB_QUEST_STORAGE_SCAN_BUTTON.get();
        if (enabled) panel.add(new ScanStorageTasksButton(panel));
    }
}
