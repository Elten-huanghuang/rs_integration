package com.huanghuang.rsintegration.mods.jei;

import com.huanghuang.rsintegration.mods.tetra.client.TetraMaterialSortMode;
import com.huanghuang.rsintegration.mods.tetra.client.TetraWorkbenchMaterialState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;

import java.util.List;

/** Supplies the dynamic JEI exclusion area for the Tetra sort menu. */
public final class TetraJeiSortExclusion {
    private static final int PANEL_WIDTH = 132;

    private static volatile boolean menuOpen;
    private static volatile int sortX;
    private static volatile int sortY;

    private TetraJeiSortExclusion() {}

    public static void updateSortArea(int x, int y) {
        sortX = x;
        sortY = y;
    }

    public static void setMenuOpen(boolean open) {
        menuOpen = open;
    }

    public static void clear() {
        menuOpen = false;
    }

    public static List<Rect2i> getGuiExtraAreas(Screen screen) {
        if (!menuOpen || !TetraWorkbenchMaterialState.isActive()
                || screen == null
                || !screen.getClass().getName().equals(
                        "se.mickelus.tetra.blocks.workbench.gui.WorkbenchScreen")) {
            return List.of();
        }
        int panelHeight = TetraMaterialSortMode.values().length * 15 + 4;
        int screenWidth = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int panelX = Math.max(0, Math.min(sortX, screenWidth - PANEL_WIDTH));
        int panelY = Math.max(0, sortY - panelHeight);
        return List.of(new Rect2i(panelX, panelY, PANEL_WIDTH, panelHeight));
    }
}
