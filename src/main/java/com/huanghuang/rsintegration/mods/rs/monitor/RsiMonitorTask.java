package com.huanghuang.rsintegration.mods.rs.monitor;

import com.huanghuang.rsintegration.mods.ironsspellbooks.client.InkFluidRenderer;
import com.huanghuang.rsintegration.crafting.progress.CraftProgressSnapshot;
import com.huanghuang.rsintegration.crafting.progress.CraftMaterialView;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.refinedmods.refinedstorage.api.render.IElementDrawer;
import com.refinedmods.refinedstorage.apiimpl.API;
import com.refinedmods.refinedstorage.screen.CraftingMonitorScreen;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import java.util.List;
import java.util.UUID;

/** 兼容原生标签内部的 Task 强转；执行仍由 RSI 管理。 */
public final class RsiMonitorTask extends CraftingMonitorScreen.Task {
    private final ItemStack target;
    private final CraftProgressSnapshot snapshot;
    private final CraftMaterialView materials;

    public RsiMonitorTask(ItemStack target, CraftProgressSnapshot snapshot) {
        this(target, snapshot, CraftMaterialView.EMPTY);
    }

    public RsiMonitorTask(ItemStack target, CraftProgressSnapshot snapshot, CraftMaterialView materials) {
        // 原生 int 数量只作为图标占位，实际数量由下面的 long 提示和元素显示。
        super(snapshot.craftId(), API.instance().createCraftingRequestInfo(target, 1), 1, 0L, 0,
                RsiMonitorElement.elements(materials));
        this.target = target.copy();
        this.snapshot = snapshot;
        this.materials = materials;
    }

    public UUID craftId() { return snapshot.craftId(); }
    public CraftProgressSnapshot snapshot() { return snapshot; }
    public CraftMaterialView materials() { return materials; }

    public int materialIndex(ItemStack output) {
        if (output.isEmpty()) return -1;
        MaterialKey key = MaterialKey.of(output);
        for (int i = 0; i < materials.rows().size(); i++) {
            if (key.equals(materials.rows().get(i).material())) return i;
        }
        return -1;
    }

    @Override public void drawIcon(GuiGraphics graphics, int x, int y,
                                   IElementDrawer<ItemStack> items, IElementDrawer<FluidStack> fluids) {
        InkFluidRenderer.render(graphics, target, x, y);
    }

    @Override public void drawTooltip(Font font, GuiGraphics graphics, int x, int y) {
        Component amount = snapshot.outputProgress().known()
                ? Component.translatable("rsi.monitor.produced", snapshot.outputProgress().completed(), snapshot.outputProgress().total())
                : Component.translatable("rsi.monitor.nodes", snapshot.completedNodes(), snapshot.totalNodes());
        graphics.renderComponentTooltip(font, List.of(target.getHoverName(),
                Component.translatable("rsi.monitor.source"), amount), x, y);
    }
}
