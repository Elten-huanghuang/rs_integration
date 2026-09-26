package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.RSIntegrationMod;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import java.nio.file.Files;
import java.util.stream.Stream;
import net.minecraft.Util;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Read-only snapshot: file export is always an explicit player action. */
public final class CraftFailureScreen extends Screen {
    private final Screen parent;
    private final CraftProgressSnapshot snapshot;
    private final ItemStack target;
    private final CraftFailureContext context;
    private boolean detailed;
    private List<Component> report;
    private List<FormattedCharSequence> wrapped = List.of();
    private int scroll;
    private int left;
    private int top;
    private int bodyWidth;
    private int bodyHeight;
    private Path exportDirectory;
    private Component exportStatus;

    CraftFailureScreen(Screen parent, CraftFailureHistory.Entry entry) {
        super(Component.translatable("rsi.diagnostic.title").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
        this.parent = parent;
        this.snapshot = entry.snapshot();
        this.target = entry.target();
        this.context = entry.context();
        report = CraftFailureReport.lines(snapshot, target, context, detailed);
    }

    @Override
    protected void init() {
        bodyWidth = Math.max(80, Math.min(700, width - 24));
        left = (width - bodyWidth) / 2;
        top = 34;
        int columns = bodyWidth < 480 ? 2 : 4;
        int footerHeight = columns == 2 ? 88 : 64;
        bodyHeight = Math.max(12, height - top - footerHeight);
        exportDirectory = CraftFailureReportExporter.directory(minecraft.gameDirectory.toPath());
        refreshLines();
        int buttonWidth = (bodyWidth - (columns - 1) * 4) / columns;
        addRenderableWidget(new Checkbox(left, height - footerHeight + 8, bodyWidth, 20,
                Component.translatable("rsi.diagnostic.detailed"), detailed) {
            @Override
            public void onPress() {
                super.onPress();
                detailed = selected();
                report = CraftFailureReport.lines(snapshot, target, context, detailed);
                scroll = 0;
                refreshLines();
            }
        });
        int buttonY = height - footerHeight + 36;
        addRenderableWidget(Button.builder(Component.translatable("rsi.diagnostic.export"), button -> exportReport())
                .bounds(left, buttonY, buttonWidth, 20).build());
        Button openDirectory = addRenderableWidget(Button.builder(Component.translatable("rsi.diagnostic.open_directory"),
                button -> Util.getPlatform().openFile(exportDirectory.toFile()))
                .bounds(left + buttonWidth + 4, buttonY, buttonWidth, 20).build());
        openDirectory.active = Files.isDirectory(exportDirectory);
        addRenderableWidget(Button.builder(Component.translatable("rsi.diagnostic.dismiss"), button -> {
            CraftProgressTracker.dismissFailure(snapshot.craftId());
            onClose();
        }).bounds(left + (2 % columns) * (buttonWidth + 4), buttonY + (2 / columns) * 24, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), button -> onClose())
                .bounds(left + (3 % columns) * (buttonWidth + 4), buttonY + (3 / columns) * 24, buttonWidth, 20).build());
        clampScroll();
    }

    private void refreshLines() {
        List<Component> lines = new ArrayList<>();
        if (exportStatus != null) lines.add(exportStatus);
        lines.add(Component.translatable("rsi.diagnostic.export_directory", exportDirectory.toString())
                .withStyle(ChatFormatting.GRAY));
        lines.add(Component.empty());
        lines.addAll(report.subList(1, report.size()));
        wrapped = lines.stream().flatMap(line -> line.getString().isEmpty()
                ? Stream.of(FormattedCharSequence.EMPTY)
                : font.split(line, bodyWidth - 16).stream()).toList();
    }

    private void exportReport() {
        try {
            Path file = CraftFailureReportExporter.export(minecraft.gameDirectory.toPath(), snapshot.craftId(), report);
            exportStatus = Component.translatable("rsi.diagnostic.export_success", file.toString())
                    .withStyle(ChatFormatting.GREEN);
        } catch (IOException | RuntimeException failure) {
            RSIntegrationMod.LOGGER.warn("[RSI] Could not export craft failure report to {}", exportDirectory, failure);
            exportStatus = Component.translatable("rsi.diagnostic.export_failure",
                    CraftFailureReport.safeText(failure.getMessage() == null
                            ? failure.getClass().getSimpleName() : failure.getMessage())).withStyle(ChatFormatting.RED);
        }
        scroll = 0;
        rebuildWidgets();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, 13, 0xFFF1F4F6);
        graphics.fill(left, top - 3, left + bodyWidth, top - 2, 0xFF57656B);
        graphics.enableScissor(left, top, left + bodyWidth, top + bodyHeight);
        int visible = bodyHeight / 12;
        for (int i = scroll; i < Math.min(wrapped.size(), scroll + visible); i++) {
            graphics.drawString(font, wrapped.get(i), left + 4, top + (i - scroll) * 12, 0xFFE1E5E7, false);
        }
        graphics.disableScissor();
        if (wrapped.size() > visible) {
            int thumb = Math.max(8, bodyHeight * visible / wrapped.size());
            int thumbY = top + (bodyHeight - thumb) * scroll / Math.max(1, wrapped.size() - visible);
            graphics.fill(left + bodyWidth - 3, top, left + bodyWidth - 1, top + bodyHeight, 0xFF333D43);
            graphics.fill(left + bodyWidth - 3, thumbY, left + bodyWidth - 1, thumbY + thumb, 0xFFA9B8BE);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void clampScroll() {
        scroll = Math.max(0, Math.min(scroll, Math.max(0, wrapped.size() - bodyHeight / 12)));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        scroll -= (int) Math.signum(delta) * 3;
        clampScroll();
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        int previous = scroll;
        switch (keyCode) {
            case org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN -> scroll++;
            case org.lwjgl.glfw.GLFW.GLFW_KEY_UP -> scroll--;
            case org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_DOWN -> scroll += Math.max(1, bodyHeight / 12);
            case org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_UP -> scroll -= Math.max(1, bodyHeight / 12);
            case org.lwjgl.glfw.GLFW.GLFW_KEY_HOME -> scroll = 0;
            case org.lwjgl.glfw.GLFW.GLFW_KEY_END -> scroll = wrapped.size();
            default -> { return super.keyPressed(keyCode, scanCode, modifiers); }
        }
        clampScroll();
        return previous != scroll;
    }

    @Override
    public void onClose() { minecraft.setScreen(parent); }

    @Override
    public boolean isPauseScreen() { return false; }
}
