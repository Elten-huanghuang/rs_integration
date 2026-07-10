package com.huanghuang.rsintegration.mixin.refinedstorage;

import com.huanghuang.rsintegration.machine.MachineHub;
import com.huanghuang.rsintegration.sidepanel.client.MachineTabHandler;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Mixin(value = com.refinedmods.refinedstorage.screen.grid.GridScreen.class, remap = false)
public abstract class GridScreenTooltipMixin {

    @Inject(method = "m_280003_", at = @At("HEAD"), cancellable = true, remap = false)
    private void rsi$suppressGridTooltip(GuiGraphics gfx, int mouseX, int mouseY, CallbackInfo ci) {
        if (MachineHub.isVisible()) {
            ci.cancel();
            return;
        }

        if (MachineTabHandler.isMachineCenterHovered()) {
            int machineCount = MachineTabHandler.getAllMachines().size();
            List<Component> lines = new ArrayList<>();
            lines.add(Component.translatable("rsi.machine_center.title"));
            if (machineCount > 0) {
                lines.add(Component.translatable("rsi.machine_center.machine_count", machineCount)
                        .withStyle(ChatFormatting.GRAY));
            } else {
                lines.add(Component.translatable("rsi.machine_center.no_machines")
                        .withStyle(ChatFormatting.GRAY));
            }
            lines.add(Component.translatable("rsi.machine_center.tooltip_click")
                    .withStyle(ChatFormatting.AQUA));
            gfx.renderTooltip(Minecraft.getInstance().font, lines, Optional.empty(), mouseX, mouseY);
            ci.cancel();
            return;
        }

        if (MachineTabHandler.isResonanceBackpackHovered()) {
            List<Component> lines = new ArrayList<>();
            lines.add(Component.translatable("rsi.resonance_backpack.title"));
            lines.add(Component.translatable("rsi.resonance_backpack.tooltip_click")
                    .withStyle(ChatFormatting.AQUA));
            gfx.renderTooltip(Minecraft.getInstance().font, lines, Optional.empty(), mouseX, mouseY);
            ci.cancel();
        }
    }
}
