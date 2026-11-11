package com.huanghuang.rsintegration.mods.rs.monitor;

import com.huanghuang.rsintegration.crafting.progress.CraftProgressSnapshot;
import com.huanghuang.rsintegration.client.gui.MaterialCellStyle;
import com.huanghuang.rsintegration.mixin.refinedstorage.MonitorAmountsAccessor;
import com.huanghuang.rsintegration.mixin.refinedstorage.MonitorErrorAccessor;
import com.refinedmods.refinedstorage.api.autocrafting.craftingmonitor.ICraftingMonitorElement;

/** AE 式状态底色；颜色覆盖整格，文字与数量仍保留各自含义。 */
public enum MonitorGridStyle {
    STORED(MaterialCellStyle.STORED), RUNNING(MaterialCellStyle.RUNNING), SCHEDULED(MaterialCellStyle.SCHEDULED),
    BLOCKED(MaterialCellStyle.BLOCKED), STOPPING(MaterialCellStyle.STOPPING), COMPLETED(MaterialCellStyle.COMPLETED);

    private final int color;
    MonitorGridStyle(MaterialCellStyle style) { this.color = style.color(); }
    public int color() { return color; }

    public static MonitorGridStyle node(CraftProgressSnapshot.NodeProgress node) {
        if (node.draining()) return STOPPING;
        if (node.state() == CraftProgressSnapshot.NodeState.FAILED) return BLOCKED;
        if (MonitorDiagnostics.priority(node) == 0) return BLOCKED;
        if (node.runningOperations() > 0 || node.state() == CraftProgressSnapshot.NodeState.RUNNING) return RUNNING;
        if (node.state() == CraftProgressSnapshot.NodeState.SUCCEEDED) return COMPLETED;
        if (node.state() == CraftProgressSnapshot.NodeState.CANCELLED) return STORED;
        return SCHEDULED;
    }

    public static MonitorGridStyle nativeElement(ICraftingMonitorElement element) {
        if (element instanceof RsiMonitorElement material) return material.style();
        if (element instanceof MonitorErrorAccessor) return BLOCKED;
        if (element instanceof MonitorAmountsAccessor amount) {
            if (amount.rsi$missing() > 0) return BLOCKED;
            if (amount.rsi$processing() > 0 || amount.rsi$crafting() > 0) return RUNNING;
            if (amount.rsi$scheduled() > 0) return SCHEDULED;
        }
        return STORED;
    }
}
