package com.huanghuang.rsintegration.mods.apotheosis.client;

import com.huanghuang.rsintegration.mods.apotheosis.ApothSpawnerModels.Entry;
import com.huanghuang.rsintegration.mods.apotheosis.network.ApothSpawnerExecutePacket;
import com.huanghuang.rsintegration.mods.apotheosis.network.ApothSpawnerRefreshPacket;
import com.huanghuang.rsintegration.mods.apotheosis.network.ApothSpawnerStatePacket;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ApothSpawnerUpgradeScreen extends Screen {
    private static final int PANEL_W = 360;
    private static final int PANEL_H = 244;
    private static final int ROW_H = 25;
    private enum View { UPGRADES, MATERIALS }

    private final ResourceLocation dimension;
    private final net.minecraft.core.BlockPos pos;
    private List<Entry> entries;
    private final Map<ResourceLocation, Integer> selected = new HashMap<>();
    private View view = View.UPGRADES;
    private int scroll;
    private String message;
    private ResourceLocation draggingSlider;

    private ApothSpawnerUpgradeScreen(ApothSpawnerStatePacket packet) {
        super(Component.translatable("rsi.apotheosis.spawner.title"));
        this.dimension = packet.dimension();
        this.pos = packet.pos();
        this.entries = packet.entries();
        this.message = packet.message();
        // Opt-in by default: displaying the maximum reachable value must not
        // implicitly schedule every modifier for execution.
    }

    public static void accept(ApothSpawnerStatePacket packet) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof ApothSpawnerUpgradeScreen screen
                && screen.dimension.equals(packet.dimension()) && screen.pos.equals(packet.pos())) {
            screen.entries = packet.entries();
            screen.message = packet.message();
            screen.selected.keySet().removeIf(id -> screen.entries.stream().noneMatch(e -> e.recipeId().equals(id) && selectable(e)));
            screen.selected.replaceAll((id, amount) -> Math.min(amount, screen.entries.stream()
                    .filter(e -> e.recipeId().equals(id)).mapToInt(Entry::applications).findFirst().orElse(0)));
            return;
        }
        mc.setScreen(new ApothSpawnerUpgradeScreen(packet));
    }

    @Override
    protected void init() {
        int left = (width - PANEL_W) / 2;
        int top = (height - PANEL_H) / 2;
        addRenderableWidget(new FlatButton(left + 10, top + 27, 82, 18,
                Component.translatable("rsi.apotheosis.spawner.upgrades"), () -> switchView(View.UPGRADES)));
        addRenderableWidget(new FlatButton(left + 96, top + 27, 82, 18,
                Component.translatable("rsi.apotheosis.spawner.materials"), () -> switchView(View.MATERIALS)));
        addRenderableWidget(new FlatButton(left + 182, top + 27, 82, 18,
                Component.translatable("rsi.apotheosis.spawner.select_all"), this::selectAll));
        addRenderableWidget(new FlatButton(left + PANEL_W - 82, top + 27, 72, 18,
                Component.translatable("rsi.apotheosis.spawner.refresh"), () ->
                NetworkHandler.CHANNEL.sendToServer(new ApothSpawnerRefreshPacket(dimension, pos))));
        addRenderableWidget(new FlatButton(left + PANEL_W - 70, top + PANEL_H - 27, 60, 20,
                Component.translatable("gui.done"), this::execute));
    }

    private void switchView(View next) { view = next; scroll = 0; }
    private void selectAll() {
        selected.clear();
        for (Entry entry : entries) if (selectable(entry)) selected.put(entry.recipeId(), entry.applications());
    }
    private void execute() {
        selected.entrySet().removeIf(e -> e.getValue() <= 0);
        if (!selected.isEmpty()) NetworkHandler.CHANNEL.sendToServer(
                new ApothSpawnerExecutePacket(dimension, pos, Map.copyOf(selected), true));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        int left = (width - PANEL_W) / 2;
        int top = (height - PANEL_H) / 2;
        graphics.fill(left, top, left + PANEL_W, top + PANEL_H, 0xEE111619);
        border(graphics, left, top, PANEL_W, PANEL_H, 0xFF53616A);
        graphics.drawString(font, title, left + 10, top + 9, 0xFFE8F0F2, false);
        graphics.drawString(font, pos.toShortString(), left + PANEL_W - 90, top + 9, 0xFF8FA2AA, false);
        super.render(graphics, mouseX, mouseY, partialTick);

        int first = scroll / ROW_H;
        int y = top + 51;
        int visible = 6;
        for (int i = first; i < Math.min(entries.size(), first + visible); i++) {
            Entry entry = entries.get(i);
            renderRow(graphics, entry, left + 10, y, mouseX, mouseY);
            y += ROW_H;
        }
        if (message != null && !message.isEmpty()) {
            String key = message.contains(":") ? message.substring(0, message.indexOf(':')) : message;
            graphics.drawString(font, Component.translatable(key), left + 122, top + PANEL_H - 21, 0xFFFFC766, false);
        }
    }

    private void renderRow(GuiGraphics graphics, Entry entry, int x, int y, int mouseX, int mouseY) {
        int chosen = selected.getOrDefault(entry.recipeId(), 0);
        boolean active = chosen > 0;
        boolean hovered = mouseX >= x && mouseX < x + PANEL_W - 20 && mouseY >= y && mouseY < y + 22;
        int bg = entry.complete() ? 0x50334438 : !entry.supported() ? 0x50443333 : active ? 0x70406A50 : 0x50313A3F;
        if (hovered) bg += 0x10000000;
        graphics.fill(x, y, x + PANEL_W - 20, y + 22, bg);
        border(graphics, x, y, PANEL_W - 20, 22, active ? 0xFF5EAD77 : 0xFF46535A);
        graphics.renderItem(entry.material(), x + 3, y + 3);
        String name = Component.translatable("rsi.apotheosis.spawner.stat." + entry.statId()).getString();
        graphics.drawString(font, name, x + 24, y + 3, 0xFFE3EAED, false);
        if (view == View.UPGRADES) {
            int previewTarget = entry.applications() == 0 ? entry.currentValue()
                    : entry.currentValue() + (int) Math.round((entry.targetValue() - entry.currentValue())
                    * (chosen / (double) entry.applications()));
            String value = entry.complete() ? Component.translatable("rsi.apotheosis.spawner.complete").getString()
                    : entry.currentValue() + " -> " + previewTarget;
            graphics.drawString(font, value, x + 24, y + 13, entry.complete() ? 0xFF75CE8D : 0xFF9FB0B7, false);
            if (view == View.UPGRADES && selectable(entry)) {
                int sx = x + 190, sy = y + 16, sw = 100;
                graphics.fill(sx, sy, sx + sw, sy + 2, 0xFF33444A);
                graphics.fill(sx, sy, sx + (int) (sw * chosen / (double) entry.applications()), sy + 2, 0xFF65C58A);
                graphics.fill(sx + (int) (sw * chosen / (double) entry.applications()) - 2, sy - 3,
                        sx + (int) (sw * chosen / (double) entry.applications()) + 2, sy + 6, 0xFFDDEFE4);
                graphics.drawString(font, chosen + "/" + entry.applications(), x + 298, y + 7, 0xFFE6C36A, false);
            }
        } else {
            int required = selected.getOrDefault(entry.recipeId(), 0);
            if (required == 0) {
                graphics.drawString(font, Component.translatable("rsi.apotheosis.spawner.not_selected"),
                        x + 155, y + 7, 0xFF7F8D93, false);
            } else {
                int missing = Math.max(0, required - entry.available());
                graphics.drawString(font, Component.translatable("rsi.apotheosis.spawner.stock", entry.available(), required),
                        x + 155, y + 7, missing == 0 ? 0xFF75CE8D : 0xFFFF7B72, false);
            }
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Let actual widgets (especially the bottom Done button) consume the
        // click before row/slider hit testing can interpret it as a hidden row.
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        if (view != View.UPGRADES) return false;
        int left = (width - PANEL_W) / 2;
        int top = (height - PANEL_H) / 2;
        int first = scroll / ROW_H;
        int index = first + (int) ((mouseY - (top + 51)) / ROW_H);
        if (mouseX >= left + 10 && mouseX < left + PANEL_W - 10 && mouseY >= top + 51 && index >= first && index < entries.size()) {
            Entry entry = entries.get(index);
            if (selectable(entry)) {
                if (entry.applications() == 1) {
                    if (selected.getOrDefault(entry.recipeId(), 0) > 0) selected.remove(entry.recipeId());
                    else selected.put(entry.recipeId(), 1);
                    return true;
                }
                if (mouseX >= left + 196 && mouseX <= left + 304) {
                    draggingSlider = entry.recipeId();
                    setSliderAmount(entry, mouseX, left + 200);
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingSlider != null) {
            Entry entry = entries.stream().filter(e -> e.recipeId().equals(draggingSlider)).findFirst().orElse(null);
            if (entry != null) setSliderAmount(entry, mouseX, (width - PANEL_W) / 2 + 200);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        draggingSlider = null;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int max = Math.max(0, (entries.size() - 6) * ROW_H);
        scroll = Math.max(0, Math.min(max, scroll - (int) Math.signum(delta) * ROW_H));
        return true;
    }

    private static boolean selectable(Entry entry) { return entry.supported() && !entry.complete() && entry.applications() > 0; }
    private static int sliderAmount(Entry entry, double mouseX, int sliderLeft) {
        if (entry.applications() <= 0) return 0;
        int value = (int) Math.round((mouseX - sliderLeft) / 100.0 * entry.applications());
        return Math.max(0, Math.min(entry.applications(), value));
    }
    private void setSliderAmount(Entry entry, double mouseX, int sliderLeft) {
        int amount = sliderAmount(entry, mouseX, sliderLeft);
        if (amount <= 0) selected.remove(entry.recipeId());
        else selected.put(entry.recipeId(), amount);
    }

    private final class FlatButton extends AbstractButton {
        private final Runnable action;
        private FlatButton(int x, int y, int w, int h, Component label, Runnable action) {
            super(x, y, w, h, label); this.action = action;
        }
        @Override public void onPress() { action.run(); }
        @Override public void renderWidget(GuiGraphics g, int mx, int my, float partial) {
            int color = isHoveredOrFocused() ? 0xFF3D6652 : 0xFF294238;
            g.fill(getX(), getY(), getX() + width, getY() + height, color);
            border(g, getX(), getY(), width, height, isHoveredOrFocused() ? 0xFF8ED8AE : 0xFF527B66);
            g.drawCenteredString(font, getMessage(), getX() + width / 2, getY() + (height - 8) / 2, 0xFFE5F3EA);
        }
        @Override protected void updateWidgetNarration(
                net.minecraft.client.gui.narration.NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }
    private static void border(GuiGraphics g, int x, int y, int w, int h, int c) {
        g.fill(x, y, x + w, y + 1, c); g.fill(x, y + h - 1, x + w, y + h, c);
        g.fill(x, y, x + 1, y + h, c); g.fill(x + w - 1, y, x + w, y + h, c);
    }

    @Override public boolean isPauseScreen() { return false; }
}
