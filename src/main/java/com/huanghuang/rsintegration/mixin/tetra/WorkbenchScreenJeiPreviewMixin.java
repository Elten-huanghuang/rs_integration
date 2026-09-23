package com.huanghuang.rsintegration.mixin.tetra;

import com.huanghuang.rsintegration.mods.tetra.client.TetraWorkbenchMaterialState;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "se.mickelus.tetra.blocks.workbench.gui.WorkbenchScreen", remap = false)
public abstract class WorkbenchScreenJeiPreviewMixin {
    @Inject(method = "m_88315_", at = @At("RETURN"), remap = false)
    private void rsi$applyJeiMaterialPreview(GuiGraphics graphics, int mouseX,
                                             int mouseY, float partialTick,
                                             CallbackInfo ci) {
        TetraWorkbenchMaterialState.afterWorkbenchRender(this);
    }
}
