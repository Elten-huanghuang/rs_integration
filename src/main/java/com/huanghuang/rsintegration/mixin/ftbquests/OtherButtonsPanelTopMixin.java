package com.huanghuang.rsintegration.mixin.ftbquests;

import com.huanghuang.rsintegration.compat.ftbquests.client.ScanInventoryTasksButton;
import com.huanghuang.rsintegration.compat.ftbquests.client.ScanStorageTasksButton;
import dev.ftb.mods.ftbquests.client.gui.quests.OtherButtonsPanelTop;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds the one-shot storage scan to FTB Quests' upper-right sidebar. */
@Mixin(value = OtherButtonsPanelTop.class, remap = false)
public abstract class OtherButtonsPanelTopMixin {

    @Inject(method = "addWidgets", at = @At("TAIL"))
    private void rsi$addStorageTaskScanButton(CallbackInfo callback) {
        OtherButtonsPanelTop panel = (OtherButtonsPanelTop) (Object) this;
        panel.add(new ScanStorageTasksButton(panel));
        panel.add(new ScanInventoryTasksButton(panel));
    }
}
