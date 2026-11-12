package com.huanghuang.rsintegration.mods.jei;

import com.huanghuang.rsintegration.crafting.plan.PlanScreenHost;
import com.huanghuang.rsintegration.client.gui.CraftingPanelBounds;
import com.huanghuang.rsintegration.mods.rs.monitor.MonitorTaskList;
import com.refinedmods.refinedstorage.screen.CraftingMonitorScreen;
import com.refinedmods.refinedstorage.screen.widget.sidebutton.SideButton;
import mezz.jei.api.gui.handlers.IGuiProperties;
import mezz.jei.api.gui.handlers.IScreenHandler;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.renderer.Rect2i;

import java.util.ArrayList;
import java.util.List;

/** 用整个监控器作为 JEI 的主区域，收藏栏从任务栏左边开始，不再向下裁切。 */
public final class MonitorJeiGuiHandler implements IScreenHandler<CraftingMonitorScreen> {
    @Override
    public IGuiProperties apply(CraftingMonitorScreen screen) {
        if (screen instanceof PlanScreenHost host && host.rsi$planPage() != null) {
            // 计划列表仅占用自己的面板；没有固定面板边界的树页仍使用全屏。
            Rect2i area = host.rsi$planPage() instanceof CraftingPanelBounds panel ? panel.panelBounds()
                    : new Rect2i(0, 0, screen.width, screen.height);
            return CraftingGuiProperties.of(screen, area);
        }
        List<Rect2i> areas = new ArrayList<>();
        areas.add(new Rect2i(screen.getGuiLeft(), screen.getGuiTop(), screen.getXSize(), screen.getYSize()));
        for (var child : screen.children()) {
            if (child instanceof MonitorTaskList tasks) addVisible(areas, tasks);
        }
        for (SideButton button : screen.getSideButtons()) addVisible(areas, button);
        int left = areas.stream().mapToInt(Rect2i::getX).min().orElseThrow();
        int top = areas.stream().mapToInt(Rect2i::getY).min().orElseThrow();
        int right = areas.stream().mapToInt(area -> area.getX() + area.getWidth()).max().orElseThrow();
        int bottom = areas.stream().mapToInt(area -> area.getY() + area.getHeight()).max().orElseThrow();
        return CraftingGuiProperties.of(screen, new Rect2i(left, top, right - left, bottom - top));
    }

    private static void addVisible(List<Rect2i> areas, AbstractWidget widget) {
        if (widget.visible && widget.getWidth() > 0 && widget.getHeight() > 0) {
            areas.add(new Rect2i(widget.getX(), widget.getY(), widget.getWidth(), widget.getHeight()));
        }
    }
}
