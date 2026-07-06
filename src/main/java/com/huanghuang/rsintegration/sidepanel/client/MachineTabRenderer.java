package com.huanghuang.rsintegration.sidepanel.client;

import com.huanghuang.rsintegration.machine.MachineInteractType;
import com.huanghuang.rsintegration.machine.MachineState;
import com.huanghuang.rsintegration.machine.MachineStatus;
import com.huanghuang.rsintegration.sidepanel.data.BindingInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;

import java.util.List;

public final class MachineTabRenderer {

    private static final int TAB_WIDTH = 24;
    private static final int TAB_HEIGHT = 24;

    private MachineTabRenderer() {}

    public static int getTabWidth() { return TAB_WIDTH; }
    public static int getTabHeight() { return TAB_HEIGHT; }

    /** Draw a single machine tab at (x, y) with type-differentiated rendering. */
    public static void drawTab(GuiGraphics gfx, int x, int y, BindingInfo info,
                               MachineInteractType interactType, MachineStatus status,
                               boolean hovered) {
        int bgColor, borderColor;

        if (interactType == MachineInteractType.QUICK) {
            // State-colored for Quick machines (furnaces)
            switch (status.state()) {
                case HAS_OUTPUT:
                    borderColor = hovered ? 0xFF88CCFF : 0xFF4488FF;
                    bgColor = 0xFF223355;
                    break;
                case WORKING:
                    borderColor = hovered ? 0xFFFFCC44 : 0xFFFFAA00;
                    bgColor = 0xFF332211;
                    break;
                case IDLE:
                    borderColor = hovered ? 0xFF66DD66 : 0xFF22AA22;
                    bgColor = 0xFF113311;
                    break;
                default: // UNKNOWN
                    borderColor = hovered ? 0xFFAAAAAA : 0xFF666666;
                    bgColor = 0xFF222222;
                    break;
            }
        } else {
            // GUI type: purple-toned
            borderColor = hovered ? 0xFFCC99FF : 0xFF8855CC;
            bgColor = 0xFF221133;
        }

        // Background
        gfx.fill(x, y, x + TAB_WIDTH, y + TAB_HEIGHT, bgColor);
        // Border
        gfx.renderOutline(x, y, TAB_WIDTH, TAB_HEIGHT, borderColor);

        // Block item icon
        ItemStack icon = resolveIcon(info);
        if (!icon.isEmpty()) {
            gfx.renderItem(icon, x + 4, y + 4);
        }

        // Progress bar for Quick WORKING state
        if (interactType == MachineInteractType.QUICK && status.state() == MachineState.WORKING
                && status.maxProgress() > 0) {
            int barW = Math.max(1, (int) ((TAB_WIDTH - 4) * status.progressFraction()));
            gfx.fill(x + 2, y + TAB_HEIGHT - 5, x + 2 + barW, y + TAB_HEIGHT - 2, 0xFFFFCC00);
        }

        // Output count badge for Quick HAS_OUTPUT
        if (interactType == MachineInteractType.QUICK && status.state() == MachineState.HAS_OUTPUT
                && !status.outputItem().isEmpty()) {
            int count = status.outputItem().getCount();
            String cnt = count > 99 ? "…" : String.valueOf(count);
            Font font = Minecraft.getInstance().font;
            int textW = font.width(cnt);
            gfx.fill(x + TAB_WIDTH - textW - 3, y + TAB_HEIGHT - 10,
                     x + TAB_WIDTH - 1, y + TAB_HEIGHT - 1, 0xCC3366CC);
            gfx.drawString(font, cnt, x + TAB_WIDTH - textW - 2, y + TAB_HEIGHT - 10, 0xFFFFFF);
        }

        // Gear dots for GUI type
        if (interactType == MachineInteractType.GUI) {
            int dotX = x + TAB_WIDTH - 7;
            int dotY = y + 3;
            for (int r = 0; r < 2; r++) {
                for (int c = 0; c < 2; c++) {
                    gfx.fill(dotX + c * 3, dotY + r * 3,
                             dotX + c * 3 + 2, dotY + r * 3 + 2, 0xFFBB88EE);
                }
            }
        }
    }

    /** Hit-test: returns the index of the hovered machine, or -1. */
    public static int getHoveredTab(int mouseX, int mouseY, int tabStartX, int tabY,
                                     List<BindingInfo> machines, int maxX) {
        int x = tabStartX;
        for (int i = 0; i < machines.size(); i++) {
            if (x + TAB_WIDTH > maxX) break;
            if (mouseX >= x && mouseX < x + TAB_WIDTH &&
                mouseY >= tabY && mouseY < tabY + TAB_HEIGHT) {
                return i;
            }
            x += TAB_WIDTH + 2;
        }
        return -1;
    }

    /** Calculate total width needed for N tabs. */
    public static int getTotalWidth(int count) {
        return count * TAB_WIDTH + Math.max(0, count - 1) * 2;
    }

    /**
     * Resolves a block item icon from the binding's blockKey.
     * blockKey is a description ID (e.g. "block.goety.dark_altar") or
     * "{prefix}||block.modid.name".  Convert it to a registry key to
     * look up the block, falling back to the crafting table icon.
     */
    private static ItemStack resolveIcon(BindingInfo info) {
        return com.huanghuang.rsintegration.network.binding.BindingEventHandler.resolveBlockIcon(
                info.blockRegKey(), info.blockKey(), info.displayStack());
    }
}
