package com.huanghuang.rsintegration.machine;

import com.huanghuang.rsintegration.machine.MachineSlotType;
import com.huanghuang.rsintegration.sidepanel.data.BindingInfo;
import com.huanghuang.rsintegration.sidepanel.data.MachineStatusCache;
import com.huanghuang.rsintegration.sidepanel.client.MachineTabHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

/**
 * Handles mouse and keyboard input for the Terminal Hub overlay.
 */
public final class MachineHubInputHandler {

    private MachineHubInputHandler() {}

    /**
     * Handle a mouse click within the hub area.
     *
     * @param mouseX  gui-scaled mouse X
     * @param mouseY  gui-scaled mouse Y
     * @param button  GLFW mouse button
     * @return true if the click was consumed by the hub
     */
    public static boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!MachineHub.isVisible()) return false;

        // Only consume clicks within the hub panel bounds
        if (!MachineHub.isWithinBounds((int) mouseX, (int) mouseY)) {
            // Click outside hub — hide on left-click, but DON'T consume the event
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                MachineHub.hide();
            }
            return false;
        }

        // Title bar drag
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && MachineHub.tryStartDrag(mouseX, mouseY)) {
            return true;
        }

        // Close button takes priority
        if (MachineHub.isCloseButtonHovered() && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            MachineHub.hide();
            return true;
        }

        int idx = MachineHub.getHoveredIndex();
        if (idx < 0 || idx >= MachineHub.getMachines().size()) {
            // Clicked inside hub but outside the machine grid — ignore
            return true;
        }

        BindingInfo info = MachineHub.getMachines().get(idx);

        // Right-click always opens GUI
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            MachineTabHandler.onClick(info);
            MachineHub.hide();
            return true;
        }

        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            MachineInteractType type = MachineInteractType.fromBlockKey(info.blockKey());

            // GUI type: always open GUI
            if (type == MachineInteractType.GUI) {
                MachineTabHandler.onClick(info);
                MachineHub.hide();
                return true;
            }

            // Quick type: differentiate by cursor + state
            var screen = Minecraft.getInstance().screen;
            boolean shift = screen != null && screen.hasShiftDown();
            Minecraft mc = Minecraft.getInstance();
            ItemStack carried = mc.player != null
                ? mc.player.containerMenu.getCarried()
                : ItemStack.EMPTY;
            MachineStatus status = MachineStatusCache.getInstance().get(info);

            if (carried.isEmpty() && status.state() == MachineState.HAS_OUTPUT) {
                MachineTabHandler.onCollect(info, shift);
            } else if (!carried.isEmpty()) {
                MachineSlotType slot = shift ? MachineSlotType.FUEL : MachineSlotType.INPUT;
                MachineTabHandler.onInsert(info, slot);
            } else {
                MachineTabHandler.onClick(info);
            }
            MachineHub.hide();
            return true;
        }

        return true; // Consume any click within hub area
    }

    /**
     * Handle a key press while the hub is visible.
     *
     * @param keyCode GLFW key code
     * @return true if the key was consumed by the hub
     */
    public static boolean keyPressed(int keyCode) {
        if (!MachineHub.isVisible()) return false;

        if (keyCode == GLFW.GLFW_KEY_ESCAPE || keyCode == GLFW.GLFW_KEY_E) {
            if (!MachineHub.getFilterText().isEmpty()) {
                // Escape first clears filter, second closes hub
                MachineHub.clearFilter();
                return true;
            }
            MachineHub.hide();
            return true;
        }

        // Backspace in filter
        if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
            MachineHub.backspaceFilter();
            return true;
        }

        // Number keys 1-9 for quick selection
        if (keyCode >= GLFW.GLFW_KEY_1 && keyCode <= GLFW.GLFW_KEY_9) {
            int idx = keyCode - GLFW.GLFW_KEY_1;
            var machines = MachineHub.getMachines();
            if (idx < machines.size()) {
                MachineTabHandler.onClick(machines.get(idx));
                MachineHub.hide();
            }
            return true;
        }

        return true; // Consume all keys while hub is visible
    }

    /**
     * Handle a typed character while the hub is visible — feeds the filter.
     */
    public static boolean charTyped(char codePoint, int modifiers) {
        if (!MachineHub.isVisible()) return false;

        if (codePoint >= 0x20 && codePoint != 0x7F) {
            MachineHub.appendFilterChar(codePoint);
        }
        return true;
    }

    /**
     * Handle mouse scroll within the hub area.
     */
    public static boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (!MachineHub.isVisible()) return false;
        if (!MachineHub.isWithinBounds((int) mouseX, (int) mouseY)) return false;
        int newOffset = MachineHub.getScrollOffset() - (int) delta * 16;
        if (newOffset < 0) newOffset = 0;
        MachineHub.setScrollOffset(newOffset);
        return true;
    }

    /** Handle mouse release to end drag. */
    public static boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (MachineHub.isDragging()) {
            MachineHub.endDrag();
            return true;
        }
        return false;
    }

    /** Hub consumes all mouse events while visible. */
    public static boolean isConsumingInput() {
        return MachineHub.isVisible();
    }
}
