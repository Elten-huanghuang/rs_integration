package com.huanghuang.rsintegration.anvilmemory;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

/** Client layout description. A mod integration can register one without linking its classes. */
public interface AnvilMemoryClientAdapter {
    String id();
    boolean supports(AbstractContainerScreen<?> screen);
    Bounds swapButton(AbstractContainerScreen<?> screen);
    Bounds memoryPanel(AbstractContainerScreen<?> screen);

    default Bounds resultSlot(AbstractContainerScreen<?> screen) {
        return new Bounds(0, 0, 0, 0);
    }

    record Bounds(int x, int y, int width, int height) {
        public boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
        }
    }
}
