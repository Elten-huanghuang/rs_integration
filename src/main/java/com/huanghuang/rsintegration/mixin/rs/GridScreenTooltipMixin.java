package com.huanghuang.rsintegration.mixin.rs;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.machine.MachineHub;
import com.huanghuang.rsintegration.machine.MachineInteractType;
import com.huanghuang.rsintegration.machine.MachineState;
import com.huanghuang.rsintegration.machine.MachineStatus;
import com.huanghuang.rsintegration.network.BindingEventHandler;
import com.huanghuang.rsintegration.sidepanel.client.MachineTabHandler;
import com.huanghuang.rsintegration.sidepanel.data.BindingInfo;
import com.huanghuang.rsintegration.sidepanel.data.MachineStatusCache;
import com.huanghuang.rsintegration.util.TextBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Suppresses the default RS grid item tooltip and draws a machine-name
 * tooltip instead when the player is hovering a machine shortcut tab or Hub button.
 * Target: com.refinedmods.refinedstorage.screen.grid.GridScreen
 */
@Mixin(value = com.refinedmods.refinedstorage.screen.grid.GridScreen.class, remap = false)
public abstract class GridScreenTooltipMixin {

    @Inject(method = "m_280003_", at = @At("HEAD"), cancellable = true, remap = false)
    private void rsi$suppressGridTooltip(GuiGraphics gfx, int mouseX, int mouseY, CallbackInfo ci) {
        if (MachineHub.isVisible()) {
            ci.cancel();
            return;
        }

        if (MachineTabHandler.getHoveredTabIndex() >= 0) {
            var machines = MachineTabHandler.getVisibleTabs();
            int idx = MachineTabHandler.getHoveredTabIndex();
            if (!machines.isEmpty() && idx < machines.size()) {
                drawMachineTooltip(gfx, mouseX, mouseY, machines.get(idx));
            } else if (machines.isEmpty()) {
                // Hub button tooltip
                int total = MachineTabHandler.getAllMachines().size();
                if (total > 0 && MachineHub.shouldUseHub(total)) {
                    List<Component> hubTip = new ArrayList<>();
                    hubTip.add(Component.translatable("rsi.hub.title", total));
                    hubTip.add(Component.translatable("rsi.hub.tooltip_click")
                            .withStyle(ChatFormatting.GRAY));
                    gfx.renderTooltip(Minecraft.getInstance().font, hubTip,
                            Optional.empty(), mouseX, mouseY);
                }
            }
            ci.cancel();
        }
    }

    private static void drawMachineTooltip(GuiGraphics gfx, int mouseX, int mouseY, BindingInfo info) {
        MachineInteractType type = MachineInteractType.fromBlockKey(info.blockKey());
        MachineStatus status = MachineStatusCache.getInstance().get(info);

        List<Component> lines = new ArrayList<>();
        // Title line: block name with flow animation (matching backpack RS upgrade style)
        // Use client-side block-name resolution so gun-pack workbench names work.
        Component blockDisplay;
        ItemStack ds = info.displayStack();
        if (ds != null && !ds.isEmpty()) {
            blockDisplay = BindingEventHandler
                    .resolveBlockName(info.blockKey(), info.blockRegKey(), ds);
        } else {
            blockDisplay = Component.literal(
                    net.minecraft.client.resources.language.I18n.get(info.displayName()));
        }
        lines.add(TextBuilder.of(blockDisplay).colorFlow(1500L, 0.0F, RSIntegrationMod.RS_FLOW_COLORS).build());

        // Dimension + coordinates line in cornflower blue (matching backpack RS upgrade)
        String dimDisplay = dimDisplayName(info.dim());
        lines.add(TextBuilder.of("  " + dimDisplay + " " + info.pos().toShortString())
                .cornflowerBlue().build());

        lines.add(Component.empty());

        if (type == MachineInteractType.QUICK) {
            // State line with color
            Component stateLine = switch (status.state()) {
                case HAS_OUTPUT -> Component.translatable("rsi.machine.state.has_output")
                    .withStyle(ChatFormatting.BLUE);
                case WORKING -> {
                    String pct = (int)(status.progressFraction() * 100) + "%";
                    yield Component.translatable("rsi.machine.state.working", pct)
                        .withStyle(ChatFormatting.GOLD);
                }
                case IDLE -> Component.translatable("rsi.machine.state.idle")
                    .withStyle(ChatFormatting.GREEN);
                case UNKNOWN -> Component.translatable("rsi.machine.state.unknown")
                    .withStyle(ChatFormatting.GRAY);
            };
            lines.add(stateLine);

            // Items in slots
            ItemStack input = status.inputItem();
            ItemStack output = status.outputItem();
            ItemStack fuel = status.fuelItem();
            if (!input.isEmpty()) {
                lines.add(Component.translatable("rsi.machine.slot.input",
                    input.getCount(), input.getHoverName()).withStyle(ChatFormatting.GRAY));
            }
            if (!output.isEmpty()) {
                lines.add(Component.translatable("rsi.machine.slot.output",
                    output.getCount(), output.getHoverName()).withStyle(ChatFormatting.GRAY));
            }
            if (!fuel.isEmpty()) {
                lines.add(Component.translatable("rsi.machine.slot.fuel",
                    fuel.getCount(), fuel.getHoverName()).withStyle(ChatFormatting.GRAY));
            }

            // Action hints
            if (status.state() == MachineState.HAS_OUTPUT) {
                lines.add(Component.translatable("rsi.machine.hint.collect")
                    .withStyle(ChatFormatting.AQUA));
                lines.add(Component.translatable("rsi.machine.hint.collect_rs")
                    .withStyle(ChatFormatting.DARK_AQUA));
            } else {
                lines.add(Component.translatable("rsi.machine.hint.insert")
                    .withStyle(ChatFormatting.GRAY));
            }
        } else {
            // GUI type
            lines.add(Component.translatable("rsi.machine.type.gui")
                .withStyle(ChatFormatting.LIGHT_PURPLE));
            lines.add(Component.translatable("rsi.machine.hint.open_gui")
                .withStyle(ChatFormatting.GRAY));
        }

        gfx.renderTooltip(Minecraft.getInstance().font, lines, Optional.empty(), mouseX, mouseY);
    }

    private static String dimDisplayName(ResourceLocation dim) {
        if (dim == null) return "?";
        String path = dim.getPath();
        String[] parts = path.split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (!part.isEmpty()) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(Character.toUpperCase(part.charAt(0)));
                if (part.length() > 1) sb.append(part.substring(1));
            }
        }
        return sb.toString();
    }
}
