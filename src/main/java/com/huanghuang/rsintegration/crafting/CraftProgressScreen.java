package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.batch.BatchCraftNetworkHandler;
import com.huanghuang.rsintegration.crafting.batch.CraftCancelPacket;
import com.huanghuang.rsintegration.util.UIRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Interactive client screen for inspecting and cancelling active crafts. */
public final class CraftProgressScreen extends Screen {
    private final List<CraftProgressSnapshot> crafts = new ArrayList<>();
    private UUID selectedCraft;

    public CraftProgressScreen() {
        super(Component.translatable("rsi.progress.screen.title"));
        CraftProgressSnapshot first = CraftProgressTracker.first();
        selectedCraft = first == null ? null : first.craftId();
    }

    @Override
    protected void init() {
        refreshSelection();
    }

    @Override
    public void tick() {
        refreshSelection();
        if (selectedCraft == null && crafts.isEmpty()) onClose();
    }

    private void refreshSelection() {
        List<CraftProgressSnapshot> current = new ArrayList<>(CraftProgressTracker.snapshots());
        crafts.clear();
        crafts.addAll(current);
        if (selectedCraft == null && !crafts.isEmpty()) selectedCraft = crafts.get(0).craftId();
        if (selectedCraft != null && crafts.stream().noneMatch(s -> s.craftId().equals(selectedCraft))) {
            selectedCraft = crafts.isEmpty() ? null : crafts.get(0).craftId();
        }
        rebuildButtons();
    }

    private CraftProgressSnapshot selectedSnapshot() {
        if (selectedCraft == null) return null;
        return crafts.stream().filter(s -> s.craftId().equals(selectedCraft)).findFirst().orElse(null);
    }

    private void rebuildButtons() {
        clearWidgets();
        CraftProgressSnapshot snapshot = selectedSnapshot();
        if (snapshot == null) {
            addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
                    .bounds(width / 2 - 45, height / 2 + 40, 90, 20).build());
            return;
        }
        if (crafts.size() > 1) {
            addRenderableWidget(Button.builder(Component.literal("<"), button -> select(-1))
                    .bounds(width / 2 - 112, height / 2 + 50, 24, 20).build());
            addRenderableWidget(Button.builder(Component.literal(">"), button -> select(1))
                    .bounds(width / 2 + 88, height / 2 + 50, 24, 20).build());
        }
        Button hudToggle = Button.builder(Component.translatable(
                        CraftProgressTracker.isVisible()
                                ? "rsi.progress.hud.disable"
                                : "rsi.progress.hud.enable"), button -> {
                    CraftProgressTracker.toggleVisible();
                    rebuildButtons();
                })
                .bounds(width / 2 - 95, height / 2 + 22, 190, 20).build();
        addRenderableWidget(hudToggle);

        Button cancel = Button.builder(Component.translatable("rsi.progress.cancel"), button -> {
                    BatchCraftNetworkHandler.CHANNEL.sendToServer(new CraftCancelPacket(snapshot.craftId()));
                    button.active = false;
                })
                .bounds(width / 2 - 74, height / 2 + 50, 148, 20).build();
        cancel.active = CraftProgressOverlay.cancellable(snapshot.result());
        addRenderableWidget(cancel);
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
                .bounds(width / 2 - 45, height / 2 + 78, 90, 20).build());
    }

    private void select(int delta) {
        if (crafts.isEmpty()) return;
        int current = 0;
        for (int i = 0; i < crafts.size(); i++) {
            if (crafts.get(i).craftId().equals(selectedCraft)) {
                current = i;
                break;
            }
        }
        selectedCraft = crafts.get(Math.floorMod(current + delta, crafts.size())).craftId();
        rebuildButtons();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        int cardWidth = Math.min(420, width - 32);
        int cardHeight = 190;
        int left = (width - cardWidth) / 2;
        int top = (height - cardHeight) / 2;
        UIRenderer.card(graphics, left, top, cardWidth, cardHeight, 8f, 0xFF5B9BD5);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, top + 14, 0xFFFFFF);
        CraftProgressSnapshot snapshot = selectedSnapshot();
        if (snapshot == null) {
            graphics.drawCenteredString(font, Component.translatable("rsi.progress.screen.empty"),
                    width / 2, top + 82, 0xAAB4BB);
            return;
        }
        ItemStack target = CraftProgressTracker.target(snapshot.craftId());
        int y = top + 40;
        if (!target.isEmpty()) {
            graphics.renderItem(target, left + 28, y - 6);
            String name = font.plainSubstrByWidth(target.getHoverName().getString(), cardWidth - 90);
            graphics.drawString(font, Component.literal(name), left + 54, y, 0xFFFFFF, false);
        } else {
            graphics.drawString(font, Component.translatable("rsi.progress.target_unknown"),
                    left + 28, y, 0xAAB4BB, false);
        }
        y += 28;
        graphics.drawString(font, CraftProgressOverlay.title(snapshot.result()),
                left + 28, y, CraftProgressOverlay.accent(snapshot.result()), true);
        String percent = CraftProgressOverlay.progressPercent(snapshot) + "%";
        graphics.drawString(font, percent, left + cardWidth - 28 - font.width(percent), y,
                CraftProgressOverlay.accent(snapshot.result()), true);
        y += 17;
        int barWidth = cardWidth - 56;
        UIRenderer.rounded(graphics, left + 28, y, barWidth, 8, 4f, 0xFF263640);
        int fill = Math.round(barWidth * CraftProgressOverlay.progressPercent(snapshot) / 100f);
        if (fill > 0) UIRenderer.rounded(graphics, left + 28, y, fill, 8, 4f,
                CraftProgressOverlay.accent(snapshot.result()));
        y += 20;
        graphics.drawString(font, Component.translatable("rsi.progress.summary",
                snapshot.completedNodes(), snapshot.totalNodes(), snapshot.runningNodes()),
                left + 28, y, 0xFFFFFF, false);
        graphics.drawString(font, CraftProgressOverlay.detail(snapshot),
                left + 28, y + 15, 0xAAB4BB, false);
        if (crafts.size() > 1) {
            int selected = crafts.indexOf(snapshot) + 1;
            graphics.drawCenteredString(font, Component.literal(selected + "/" + crafts.size()),
                    width / 2, top + cardHeight - 32, 0xAAB4BB);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
