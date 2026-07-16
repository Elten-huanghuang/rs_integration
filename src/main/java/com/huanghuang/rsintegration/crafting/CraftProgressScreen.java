package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.batch.BatchCraftNetworkHandler;
import com.huanghuang.rsintegration.crafting.batch.CraftCancelPacket;
import com.huanghuang.rsintegration.util.UIRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Full-screen task center for inspecting and cancelling active crafts. */
public final class CraftProgressScreen extends Screen {
    private static final int TEXT = 0xFFF1F4F6;
    private static final int MUTED = 0xFF9DA9B1;
    private static final int DIM = 0xFF71808A;
    private static final int SURFACE = 0xB8172026;
    private static final int SURFACE_HOVER = 0xD0253139;
    private static final int DIVIDER = 0x443F505A;
    private static final int BAR_BG = 0xFF253139;

    private final List<CraftProgressSnapshot> crafts = new ArrayList<>();
    private UUID selectedCraft;
    private Button hudButton;
    private Button cancelButton;
    private int nodeScroll;
    private ItemStack hoveredNodeStack = ItemStack.EMPTY;

    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int sidebarWidth;
    private int contentX;
    private int contentY;
    private int contentWidth;
    private int contentHeight;

    public CraftProgressScreen() {
        super(Component.translatable("rsi.progress.screen.title"));
        CraftProgressSnapshot first = CraftProgressTracker.first();
        selectedCraft = first == null ? null : first.craftId();
    }

    @Override
    protected void init() {
        updateLayout();
        refreshCrafts();
        buildButtons();
    }

    @Override
    public void tick() {
        UUID previous = selectedCraft;
        refreshCrafts();
        if (selectedCraft == null && crafts.isEmpty()) {
            onClose();
            return;
        }
        if (!java.util.Objects.equals(previous, selectedCraft)) nodeScroll = 0;
        updateButtonState();
    }

    private void updateLayout() {
        panelWidth = Math.min(780, Math.max(296, width - 24));
        panelHeight = Math.min(460, Math.max(216, height - 24));
        panelX = (width - panelWidth) / 2;
        panelY = (height - panelHeight) / 2;
        sidebarWidth = Math.max(116, Math.min(196, panelWidth / 4));
        contentX = panelX + sidebarWidth + 1;
        contentY = panelY + 43;
        contentWidth = panelWidth - sidebarWidth - 1;
        contentHeight = panelHeight - 43 - 39;
    }

    private void refreshCrafts() {
        crafts.clear();
        crafts.addAll(CraftProgressTracker.snapshots());
        if (selectedCraft == null && !crafts.isEmpty()) selectedCraft = crafts.get(0).craftId();
        if (selectedCraft != null && crafts.stream().noneMatch(s -> s.craftId().equals(selectedCraft))) {
            selectedCraft = crafts.isEmpty() ? null : crafts.get(0).craftId();
        }
    }

    private CraftProgressSnapshot selectedSnapshot() {
        if (selectedCraft == null) return null;
        return crafts.stream().filter(s -> s.craftId().equals(selectedCraft)).findFirst().orElse(null);
    }

    private void buildButtons() {
        clearWidgets();
        int footerY = panelY + panelHeight - 30;
        hudButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
                    CraftProgressTracker.toggleVisible();
                    updateButtonState();
                })
                .bounds(contentX + 12, footerY, Math.min(164, contentWidth / 2), 20).build());
        cancelButton = addRenderableWidget(Button.builder(
                        Component.translatable("rsi.progress.cancel"), button -> confirmCancel())
                .bounds(contentX + contentWidth - 182, footerY, 104, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
                .bounds(contentX + contentWidth - 70, footerY, 58, 20).build());
        updateButtonState();
    }

    private void updateButtonState() {
        if (hudButton == null || cancelButton == null) return;
        hudButton.setMessage(Component.translatable(CraftProgressTracker.isVisible()
                ? "rsi.progress.hud.hide" : "rsi.progress.hud.show"));
        CraftProgressSnapshot snapshot = selectedSnapshot();
        cancelButton.active = snapshot != null && CraftProgressOverlay.cancellable(snapshot.result());
    }

    private void confirmCancel() {
        CraftProgressSnapshot snapshot = selectedSnapshot();
        if (snapshot == null || !CraftProgressOverlay.cancellable(snapshot.result())) return;
        ItemStack target = CraftProgressTracker.target(snapshot.craftId());
        Component message = Component.translatable("rsi.progress.cancel.confirm.detail",
                target.isEmpty() ? Component.translatable("rsi.progress.target_unknown") : target.getHoverName());
        minecraft.setScreen(new ConfirmScreen(confirmed -> {
            minecraft.setScreen(this);
            if (confirmed) {
                BatchCraftNetworkHandler.CHANNEL.sendToServer(new CraftCancelPacket(snapshot.craftId()));
                cancelButton.active = false;
            }
        }, Component.translatable("rsi.progress.cancel.confirm.title"), message));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        UIRenderer.roundedGradient(graphics, panelX, panelY, panelWidth, panelHeight, 9f,
                0xF21B252B, 0xF2121A1F);
        graphics.fill(panelX + sidebarWidth, panelY + 43,
                panelX + sidebarWidth + 1, panelY + panelHeight, DIVIDER);
        graphics.fill(panelX, panelY + 42, panelX + panelWidth, panelY + 43, DIVIDER);
        graphics.fill(contentX, panelY + panelHeight - 38,
                panelX + panelWidth, panelY + panelHeight - 37, DIVIDER);

        renderHeader(graphics);
        renderSidebar(graphics, mouseX, mouseY);
        hoveredNodeStack = ItemStack.EMPTY;
        renderDetail(graphics, mouseX, mouseY);
        super.render(graphics, mouseX, mouseY, partialTick);
        if (!hoveredNodeStack.isEmpty()) {
            graphics.renderTooltip(font, hoveredNodeStack, mouseX, mouseY);
        }
    }

    private void renderHeader(GuiGraphics graphics) {
        graphics.drawString(font, title, panelX + 15, panelY + 12, TEXT, true);
        String count = Component.translatable("rsi.progress.screen.task_count", crafts.size()).getString();
        int badgeWidth = font.width(count) + 14;
        UIRenderer.pillBadge(graphics, font, panelX + panelWidth - badgeWidth - 13, panelY + 9,
                badgeWidth, 17, 0x663C5664, 0xFFD9E4EA, count);
    }

    private void renderSidebar(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = panelX + 7;
        int y = panelY + 51;
        int width = sidebarWidth - 14;
        graphics.drawString(font, Component.translatable("rsi.progress.screen.tasks"),
                x + 5, y, MUTED, true);
        y += 16;
        int rowHeight = 45;
        int maxRows = Math.max(1, (panelHeight - 43 - 24) / rowHeight);
        int selectedIndex = selectedIndex();
        int start = Math.max(0, Math.min(selectedIndex - maxRows / 2, crafts.size() - maxRows));
        for (int i = start; i < Math.min(crafts.size(), start + maxRows); i++) {
            CraftProgressSnapshot snapshot = crafts.get(i);
            boolean selected = snapshot.craftId().equals(selectedCraft);
            boolean hovered = inside(mouseX, mouseY, x, y, width, rowHeight - 4);
            int accent = CraftProgressOverlay.accent(snapshot.result());
            UIRenderer.rounded(graphics, x, y, width, rowHeight - 4, 5f,
                    selected ? 0xE02A3941 : hovered ? SURFACE_HOVER : SURFACE);
            if (selected) graphics.fill(x, y + 5, x + 3, y + rowHeight - 9, accent);

            ItemStack target = CraftProgressTracker.target(snapshot.craftId());
            if (!target.isEmpty()) graphics.renderItem(target, x + 8, y + 6);
            int textX = x + (target.isEmpty() ? 8 : 29);
            int available = x + width - 7 - textX;
            String name = target.isEmpty()
                    ? Component.translatable("rsi.progress.target_unknown").getString()
                    : target.getHoverName().getString();
            graphics.drawString(font, font.plainSubstrByWidth(name, available), textX, y + 6, TEXT, false);
            String percent = CraftProgressOverlay.progressPercent(snapshot) + "%";
            graphics.drawString(font, percent, textX, y + 19, accent, false);
            int barX = textX + font.width(percent) + 5;
            int barWidth = Math.max(4, x + width - 7 - barX);
            UIRenderer.rounded(graphics, barX, y + 22, barWidth, 3, 1.5f, BAR_BG);
            int fill = Math.round(barWidth * CraftProgressOverlay.progressPercent(snapshot) / 100f);
            if (fill > 0) UIRenderer.rounded(graphics, barX, y + 22, fill, 3, 1.5f, accent);
            y += rowHeight;
        }
    }

    private void renderDetail(GuiGraphics graphics, int mouseX, int mouseY) {
        CraftProgressSnapshot snapshot = selectedSnapshot();
        if (snapshot == null) {
            graphics.drawCenteredString(font, Component.translatable("rsi.progress.screen.empty"),
                    contentX + contentWidth / 2, contentY + contentHeight / 2, MUTED);
            return;
        }

        int x = contentX + 14;
        int right = contentX + contentWidth - 14;
        int y = contentY + 12;
        int accent = CraftProgressOverlay.accent(snapshot.result());
        ItemStack target = CraftProgressTracker.target(snapshot.craftId());
        if (!target.isEmpty()) {
            UIRenderer.slotBg(graphics, x, y, 20, 0xFF42535D);
            graphics.renderItem(target, x + 2, y + 2);
        }
        int titleX = x + (target.isEmpty() ? 0 : 29);
        String targetName = target.isEmpty()
                ? Component.translatable("rsi.progress.target_unknown").getString()
                : target.getHoverName().getString();
        graphics.drawString(font, font.plainSubstrByWidth(targetName, Math.max(30, right - titleX - 100)),
                titleX, y, TEXT, true);
        Component status = CraftProgressOverlay.title(snapshot.result());
        int statusWidth = font.width(status) + 14;
        UIRenderer.pillBadge(graphics, font, right - statusWidth, y - 2, statusWidth, 17,
                UIRenderer.alpha(accent, 0.22f), accent, status.getString());
        graphics.drawString(font, CraftProgressOverlay.detail(snapshot), titleX, y + 13, MUTED, false);
        y += 35;

        int percent = CraftProgressOverlay.progressPercent(snapshot);
        UIRenderer.rounded(graphics, x, y, right - x, 8, 4f, BAR_BG);
        int fill = Math.round((right - x) * percent / 100f);
        if (fill > 0) UIRenderer.rounded(graphics, x, y, fill, 8, 4f, accent);
        String percentText = percent + "%";
        graphics.drawString(font, percentText, right - font.width(percentText), y + 12, accent, true);
        graphics.drawString(font, Component.translatable("rsi.progress.summary",
                snapshot.completedNodes(), snapshot.totalNodes(), snapshot.runningNodes()),
                x, y + 12, MUTED, false);
        y += 30;

        int gap = 6;
        int statWidth = Math.max(42, (right - x - gap * 2) / 3);
        renderStat(graphics, x, y, statWidth, Component.translatable("rsi.progress.stat.completed"),
                snapshot.completedNodes(), 0xFF67BE7B);
        renderStat(graphics, x + statWidth + gap, y, statWidth,
                Component.translatable("rsi.progress.stat.running"), snapshot.runningNodes(), 0xFF68A9E8);
        int waiting = Math.max(0, snapshot.totalNodes() - snapshot.completedNodes() - snapshot.runningNodes());
        renderStat(graphics, x + (statWidth + gap) * 2, y, right - x - (statWidth + gap) * 2,
                Component.translatable("rsi.progress.stat.waiting"), waiting, 0xFFE0B35A);
        y += 37;

        graphics.drawString(font, Component.translatable("rsi.progress.screen.steps"), x, y, TEXT, true);
        List<CraftProgressSnapshot.NodeProgress> nodes = orderedNodes(snapshot);
        String nodeCount = Component.translatable("rsi.progress.screen.step_count", nodes.size()).getString();
        graphics.drawString(font, nodeCount, right - font.width(nodeCount), y, DIM, false);
        y += 15;

        int listBottom = contentY + contentHeight - 4;
        int rowHeight = 39;
        int visibleRows = Math.max(1, (listBottom - y) / rowHeight);
        nodeScroll = Math.max(0, Math.min(nodeScroll, Math.max(0, nodes.size() - visibleRows)));
        graphics.enableScissor(x, y, right, listBottom);
        for (int i = nodeScroll; i < Math.min(nodes.size(), nodeScroll + visibleRows + 1); i++) {
            renderNodeRow(graphics, nodes.get(i), x, y + (i - nodeScroll) * rowHeight,
                    right - x, rowHeight - 4, mouseX, mouseY);
        }
        graphics.disableScissor();
        if (nodes.size() > visibleRows) renderScrollbar(graphics, right - 2, y, listBottom - y,
                nodes.size(), visibleRows);
    }

    private void renderStat(GuiGraphics graphics, int x, int y, int width,
                            Component label, int value, int color) {
        UIRenderer.rounded(graphics, x, y, width, 29, 5f, SURFACE);
        graphics.drawString(font, Integer.toString(value), x + 8, y + 5, color, true);
        graphics.drawString(font, font.plainSubstrByWidth(label.getString(), width - 16),
                x + 8, y + 17, DIM, false);
    }

    private void renderNodeRow(GuiGraphics graphics, CraftProgressSnapshot.NodeProgress node,
                               int x, int y, int width, int height, int mouseX, int mouseY) {
        boolean hovered = inside(mouseX, mouseY, x, y, width, height);
        int color = nodeColor(node);
        UIRenderer.rounded(graphics, x, y, width, height, 5f, hovered ? SURFACE_HOVER : SURFACE);
        graphics.fill(x, y + 5, x + 3, y + height - 5, color);
        ItemStack output = node.displayOutput();
        if (!output.isEmpty()) {
            UIRenderer.slotBg(graphics, x + 9, y + 7, 18, 0xFF3B4A53);
            graphics.renderItem(output, x + 10, y + 8);
        }
        int textX = x + (output.isEmpty() ? 10 : 34);
        int badgeWidth = Math.min(94, font.width(CraftProgressPresentation.state(node)) + 12);
        int available = Math.max(30, width - (textX - x) - badgeWidth - 18);
        graphics.drawString(font, font.plainSubstrByWidth(
                CraftProgressPresentation.outputName(node).getString(), available), textX, y + 7, TEXT, false);
        String state = CraftProgressPresentation.state(node).getString();
        UIRenderer.pillBadge(graphics, font, x + width - badgeWidth - 7, y + 5,
                badgeWidth, 15, UIRenderer.alpha(color, 0.2f), color,
                font.plainSubstrByWidth(state, badgeWidth - 8));
        Component machine = CraftProgressPresentation.machine(node);
        Component detail = node.reason() == CraftProgressSnapshot.Reason.NONE
                ? machine : machine.copy().append(Component.literal(" · "))
                .append(Component.translatable(node.reason().translationKey()));
        graphics.drawString(font, font.plainSubstrByWidth(detail.getString(), width - (textX - x) - 10),
                textX, y + 20, MUTED, false);
        if (!output.isEmpty() && inside(mouseX, mouseY, x + 8, y + 6, 21, 21)) {
            hoveredNodeStack = output;
        }
    }

    private void renderScrollbar(GuiGraphics graphics, int x, int y, int height,
                                 int totalRows, int visibleRows) {
        graphics.fill(x, y, x + 2, y + height, 0x55384750);
        int thumbHeight = Math.max(12, height * visibleRows / totalRows);
        int maxScroll = totalRows - visibleRows;
        int thumbY = y + (height - thumbHeight) * nodeScroll / Math.max(1, maxScroll);
        graphics.fill(x, thumbY, x + 2, thumbY + thumbHeight, 0xFF718893);
    }

    private List<CraftProgressSnapshot.NodeProgress> orderedNodes(CraftProgressSnapshot snapshot) {
        List<CraftProgressSnapshot.NodeProgress> nodes = new ArrayList<>(snapshot.nodes());
        nodes.sort(Comparator.comparingInt(this::nodeRank)
                .thenComparingInt(CraftProgressSnapshot.NodeProgress::nodeId));
        return nodes;
    }

    private int nodeRank(CraftProgressSnapshot.NodeProgress node) {
        return switch (node.state()) {
            case RUNNING -> 0;
            case FAILED -> 1;
            case READY -> 2;
            case BLOCKED -> 3;
            case SUCCEEDED -> 4;
            case CANCELLED -> 5;
            case UNKNOWN -> 6;
        };
    }

    private int nodeColor(CraftProgressSnapshot.NodeProgress node) {
        if (node.draining()) return 0xFFB89AD7;
        return switch (node.state()) {
            case RUNNING -> 0xFF68A9E8;
            case FAILED -> 0xFFE06C75;
            case READY -> 0xFFE0B35A;
            case BLOCKED -> 0xFF929DA5;
            case SUCCEEDED -> 0xFF67BE7B;
            case CANCELLED, UNKNOWN -> 0xFF71808A;
        };
    }

    private int selectedIndex() {
        for (int i = 0; i < crafts.size(); i++) {
            if (crafts.get(i).craftId().equals(selectedCraft)) return i;
        }
        return 0;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int x = panelX + 7;
            int y = panelY + 67;
            int width = sidebarWidth - 14;
            int rowHeight = 45;
            int maxRows = Math.max(1, (panelHeight - 43 - 24) / rowHeight);
            int start = Math.max(0, Math.min(selectedIndex() - maxRows / 2, crafts.size() - maxRows));
            for (int i = start; i < Math.min(crafts.size(), start + maxRows); i++) {
                if (inside(mouseX, mouseY, x, y, width, rowHeight - 4)) {
                    selectedCraft = crafts.get(i).craftId();
                    nodeScroll = 0;
                    updateButtonState();
                    return true;
                }
                y += rowHeight;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (inside(mouseX, mouseY, contentX, contentY, contentWidth, contentHeight)) {
            CraftProgressSnapshot snapshot = selectedSnapshot();
            if (snapshot != null && !snapshot.nodes().isEmpty()) {
                nodeScroll -= (int) Math.signum(delta);
                nodeScroll = Math.max(0, Math.min(nodeScroll, snapshot.nodes().size() - 1));
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    private static boolean inside(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
