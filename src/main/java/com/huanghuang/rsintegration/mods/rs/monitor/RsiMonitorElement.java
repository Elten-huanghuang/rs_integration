package com.huanghuang.rsintegration.mods.rs.monitor;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.progress.CraftMaterialView;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.refinedmods.refinedstorage.api.autocrafting.craftingmonitor.ICraftingMonitorElement;
import com.refinedmods.refinedstorage.api.render.IElementDrawers;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import java.util.ArrayList;
import java.util.List;

/** 一种具体材料占一格；数量来自任务账本，不用加工次数代替材料数量。 */
public final class RsiMonitorElement implements ICraftingMonitorElement {
    private static final ResourceLocation ID = new ResourceLocation(RSIntegrationMod.MOD_ID, "monitor_material");
    private final CraftMaterialView.Row row;
    private final ItemStack icon;
    private final List<Component> lines;
    private final List<Component> tooltip;

    private RsiMonitorElement(CraftMaterialView.Row row, boolean complete) {
        this.row = row;
        icon = row.material().toStack(1);
        var labels = new ArrayList<Component>();
        if (row.available() > 0) labels.add(MonitorGridRenderer.amount("available", row.available()));
        if (row.crafting() > 0) labels.add(MonitorGridRenderer.amount("crafting", row.crafting()));
        if (row.scheduled() > 0) labels.add(MonitorGridRenderer.amount("scheduled", row.scheduled()));
        if (row.toExtract() > 0) labels.add(MonitorGridRenderer.amount("to_extract", row.toExtract()));
        if (row.processing() > 0) labels.add(MonitorGridRenderer.amount("processing", row.processing()));
        if (row.uncertain()) labels.add(Component.translatable("rsi.monitor.material.uncertain"));
        if (labels.isEmpty()) labels.add(Component.translatable("rsi.monitor.material.settled"));
        lines = List.copyOf(labels);
        String unit = InkFluidSupport.isToken(icon) ? " mB" : "";
        tooltip = new ArrayList<>();
        tooltip.add(icon.getHoverName());
        tooltip.add(Component.translatable("rsi.monitor.material.scope"));
        tooltip.add(exact("available", row.available(), unit));
        tooltip.add(exact("processing", row.processing(), unit));
        tooltip.add(exact("crafting", row.crafting(), unit));
        tooltip.add(exact("scheduled", row.scheduled(), unit));
        tooltip.add(exact("to_extract", row.toExtract(), unit));
        tooltip.add(exact("produced", row.produced(), unit));
        if (row.uncertain()) tooltip.add(Component.translatable("rsi.monitor.material.uncertain_hint"));
        if (!complete) tooltip.add(Component.translatable("rsi.monitor.material.partial"));
    }

    private static Component exact(String key, long amount, String unit) {
        return Component.translatable("rsi.monitor.material." + key, amount + unit);
    }

    public MonitorGridStyle style() {
        if (row.processing() > 0 || row.crafting() > 0) return MonitorGridStyle.RUNNING;
        if (row.scheduled() > 0 || row.toExtract() > 0 || row.uncertain()) return MonitorGridStyle.SCHEDULED;
        return MonitorGridStyle.STORED;
    }

    public static List<ICraftingMonitorElement> elements(CraftMaterialView view) {
        return view.rows().stream().<ICraftingMonitorElement>map(row -> new RsiMonitorElement(row, view.complete())).toList();
    }

    @Override public void draw(GuiGraphics graphics, int x, int y, IElementDrawers drawers) {
        drawSized(graphics, x, y, drawers, 73, 29);
    }
    public void drawSized(GuiGraphics graphics, int x, int y, IElementDrawers drawers, int width, int height) {
        MonitorGridRenderer.draw(graphics, x, y, icon, FluidStack.EMPTY, lines, style(), drawers, width, height);
    }
    @Override public ResourceLocation getBaseId() { return ID; }
    @Override public ResourceLocation getId() { return ID; }
    @Override public List<Component> getTooltip() { return List.copyOf(tooltip); }
    @Override public boolean merge(ICraftingMonitorElement other) { return false; }
    @Override public int baseElementHashCode() { return row.material().hashCode(); }
    @Override public int elementHashCode() { return row.hashCode(); }
    @Override public void write(FriendlyByteBuf buf) {
        throw new UnsupportedOperationException("RSI monitor elements use material packets");
    }
}
