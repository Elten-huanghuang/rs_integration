package com.huanghuang.rsintegration.feature.foodorder.client;

import com.huanghuang.rsintegration.feature.foodorder.FoodOrderClient;
import com.huanghuang.rsintegration.feature.foodorder.FoodOrderEntry;
import com.huanghuang.rsintegration.feature.foodorder.network.FoodOrderListPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.registries.ForgeRegistries;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 点餐终端界面：左侧食物列表，右侧详情与下单按钮。布局按旧附属 1.0.19 还原。 */
@OnlyIn(Dist.CLIENT)
public final class FoodOrderScreen extends Screen {

    private static final int ROW_H = 20;
    private static final int PANEL_W = 460;
    private static final int PANEL_H = 250;
    private static final int HEADER_H = 22;
    private static final int SLOT = 18;
    private static final long TIMEOUT_MS = 8_000L;

    private final List<FoodOrderEntry> entries = new ArrayList<>();
    private final List<FoodOrderEntry> shown = new ArrayList<>();

    private EditBox searchBox;
    private FoodOrderButton orderOneButton;
    private FoodOrderButton orderAllButton;
    private FoodOrderButton filterButton;

    private Component sources = Component.empty();
    private int totalUneaten;
    private int totalOrderable;
    private boolean indexing;
    private boolean partial;
    private long epoch;
    private long receivedAt = System.currentTimeMillis();
    private boolean loading = true;
    private boolean orderableOnly;
    private int scrollRows;
    private int selected = -1;
    private int lastClicked = -1;
    private long lastClickAt;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int listX;
    private int listY;
    private int listW;
    private int listH;
    private int detailX;
    private int detailY;
    private int detailW;
    private int buttonY;

    public FoodOrderScreen() {
        super(Component.translatable("rsi.food_order.title"));
    }

    @Override
    protected void init() {
        super.init();
        panelW = Math.min(PANEL_W, width - 16);
        panelH = Math.min(PANEL_H, height - 16);
        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;
        listX = panelX + 8;
        listY = panelY + 64;
        listW = Math.max(40, Math.min(190, panelW - 26));
        listH = Math.max(ROW_H * 3, panelH - 104);
        detailX = listX + listW + 10;
        detailY = panelY + 42;
        detailW = Math.max(40, panelW - listW - 26);
        buttonY = panelY + panelH - 22;

        searchBox = new EditBox(font, listX + 13, panelY + 46,
                Math.max(30, listW - 87), 14,
                Component.translatable("rsi.food_order.search"));
        searchBox.setBordered(false);
        searchBox.setTextColor(FoodOrderTheme.TEXT);
        searchBox.setMaxLength(60);
        searchBox.setHint(Component.translatable("rsi.food_order.search"));
        searchBox.setResponder(text -> rebuild());
        addRenderableWidget(searchBox);

        filterButton = addRenderableWidget(new FoodOrderButton(
                listX + listW - 68, panelY + 42, 68, 16,
                Component.translatable("rsi.food_order.filter.all"),
                button -> {
                    orderableOnly = !orderableOnly;
                    rebuild();
                }));
        addRenderableWidget(new FoodOrderButton(
                panelX + panelW - 54, panelY + 3, 46, 16,
                Component.translatable("rsi.food_order.refresh"),
                button -> {
                    loading = true;
                    receivedAt = System.currentTimeMillis();
                    FoodOrderClient.requestList();
                }));
        orderAllButton = addRenderableWidget(new FoodOrderButton(
                listX, buttonY, listW, 16,
                Component.translatable("rsi.food_order.order_all"),
                button -> FoodOrderClient.submit(null, true, epoch)));
        orderOneButton = addRenderableWidget(new FoodOrderButton(
                detailX, buttonY, Math.min(96, Math.max(40, detailW)), 16,
                Component.translatable("rsi.food_order.order_one"),
                button -> orderSelected()));

        FoodOrderClient.requestList();
        rebuild();
    }

    public void setData(FoodOrderListPacket packet) {
        entries.clear();
        entries.addAll(packet.entries());
        sources = packet.sources();
        totalUneaten = packet.totalUneaten();
        totalOrderable = packet.totalOrderable();
        indexing = packet.indexing();
        partial = packet.partial();
        epoch = packet.epoch();
        receivedAt = System.currentTimeMillis();
        loading = false;
        selected = -1;
        rebuild();
    }

    private void rebuild() {
        String query = searchBox == null ? "" : searchBox.getValue().trim().toLowerCase(Locale.ROOT);
        shown.clear();
        for (FoodOrderEntry entry : entries) {
            if (orderableOnly && !entry.orderable()) {
                continue;
            }
            if (!query.isEmpty() && !matches(entry, query)) {
                continue;
            }
            shown.add(entry);
        }
        if (selected >= shown.size()) {
            selected = shown.isEmpty() ? -1 : 0;
        }
        scrollRows = 0;
        if (filterButton != null) {
            filterButton.setMessage(Component.translatable(orderableOnly
                    ? "rsi.food_order.filter.orderable"
                    : "rsi.food_order.filter.all"));
        }
        updateButtons();
    }

    private boolean matches(FoodOrderEntry entry, String query) {
        if (entry.stack().getHoverName().getString().toLowerCase(Locale.ROOT).contains(query)) {
            return true;
        }
        var key = ForgeRegistries.ITEMS.getKey(entry.stack().getItem());
        return key != null && key.toString().toLowerCase(Locale.ROOT).contains(query);
    }

    private void updateButtons() {
        if (orderOneButton != null) {
            orderOneButton.active = selected >= 0 && selected < shown.size()
                    && shown.get(selected).orderable();
        }
        if (orderAllButton != null) {
            orderAllButton.active = totalOrderable > 0 && !loading;
            orderAllButton.setMessage(Component.translatable("rsi.food_order.order_all")
                    .append(" (" + totalOrderable + ")"));
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        FoodOrderTheme.panel(graphics, panelX, panelY, panelW, panelH);
        FoodOrderTheme.header(graphics, font, title, panelX, panelY, panelW, HEADER_H);
        renderSources(graphics);
        renderSearchRow(graphics);
        renderList(graphics, mouseX, mouseY);
        renderDetail(graphics);
        if (loading) {
            graphics.drawString(font, Component.translatable("rsi.food_order.loading"),
                    listX + 6, listY + 6, FoodOrderTheme.TEXT_DIM, false);
            if (System.currentTimeMillis() - receivedAt > TIMEOUT_MS) {
                graphics.drawString(font, Component.translatable("rsi.food_order.timeout"),
                        listX + 6, listY + 18, FoodOrderTheme.BAD, false);
            }
        }
        super.render(graphics, mouseX, mouseY, partialTick);
        int hovered = indexAt(mouseX, mouseY);
        if (hovered >= 0) {
            graphics.renderTooltip(font, shown.get(hovered).stack(), mouseX, mouseY);
        }
    }

    private void renderSources(GuiGraphics graphics) {
        String text = sources.getString();
        if (text.isEmpty()) {
            text = "-";
        }
        text = font.plainSubstrByWidth(text, Math.max(0, panelW - 80));
        graphics.drawString(font, text, panelX + panelW - 60 - font.width(text),
                panelY + 27, FoodOrderTheme.TEXT_DIM, false);
    }

    private void renderSearchRow(GuiGraphics graphics) {
        int x = listX;
        int y = panelY + 42;
        int width = Math.max(30, listW - 70);
        int height = 16;
        graphics.fill(x - 1, y - 1, x + width + 1, y + height + 1, FoodOrderTheme.CARD_EDGE);
        graphics.fill(x, y, x + width, y + height, FoodOrderTheme.SLOT_BG);
        graphics.fill(x, y, x + width, y + 1, FoodOrderTheme.CARD_EDGE);
        int iconX = x + 4;
        int iconY = y + 5;
        graphics.fill(iconX, iconY, iconX + 5, iconY + 1, FoodOrderTheme.TEXT_DIM);
        graphics.fill(iconX, iconY + 4, iconX + 5, iconY + 5, FoodOrderTheme.TEXT_DIM);
        graphics.fill(iconX, iconY + 1, iconX + 1, iconY + 4, FoodOrderTheme.TEXT_DIM);
        graphics.fill(iconX + 4, iconY + 1, iconX + 5, iconY + 4, FoodOrderTheme.TEXT_DIM);
        graphics.fill(iconX + 4, iconY + 4, iconX + 6, iconY + 6, FoodOrderTheme.TEXT_DIM);
    }

    private void renderList(GuiGraphics graphics, int mouseX, int mouseY) {
        FoodOrderTheme.listFrame(graphics, listX, listY, listW, listH);
        int rows = visibleRows();
        int maxScroll = maxScroll();
        scrollRows = Math.max(0, Math.min(scrollRows, maxScroll));
        graphics.enableScissor(listX, listY, listX + listW, listY + listH);
        for (int row = 0; row < rows; row++) {
            int index = scrollRows + row;
            if (index >= shown.size()) {
                break;
            }
            FoodOrderEntry entry = shown.get(index);
            int rowY = listY + row * ROW_H;
            boolean hovered = mouseX >= listX && mouseX < listX + listW
                    && mouseY >= rowY && mouseY < rowY + ROW_H;
            if (index == selected) {
                graphics.fill(listX, rowY, listX + listW, rowY + ROW_H, FoodOrderTheme.ROW_SEL);
                graphics.fill(listX, rowY, listX + 2, rowY + ROW_H, FoodOrderTheme.ACCENT);
            } else if (hovered) {
                graphics.fill(listX, rowY, listX + listW, rowY + ROW_H, FoodOrderTheme.ROW_HOVER);
            }
            if (row > 0) {
                graphics.fill(listX + 4, rowY, listX + listW - 4, rowY + 1, FoodOrderTheme.LINE);
            }
            graphics.renderItem(entry.stack(), listX + 5, rowY + 2);
            String status = Component.translatable(entry.kind().statusKey()).getString();
            int statusWidth = font.width(status);
            int statusX = listX + listW - 5 - statusWidth;
            graphics.fill(statusX - 3, rowY + 4, listX + listW - 2, rowY + 15,
                    FoodOrderTheme.PANEL_TOP);
            graphics.drawString(font, status, statusX, rowY + 6,
                    FoodOrderTheme.statusColor(entry.kind()), false);
            String name = font.plainSubstrByWidth(entry.stack().getHoverName().getString(),
                    Math.max(0, listW - 40 - statusWidth));
            graphics.drawString(font, name, listX + 26, rowY + 6, FoodOrderTheme.TEXT, false);
        }

        if (shown.isEmpty() && !loading) {
            FoodOrderTheme.emptyArt(graphics, listX + listW / 2, listY + listH / 2 - 10);
            MutableComponent empty = Component.translatable("rsi.food_order.empty");
            graphics.drawString(font, empty,
                    listX + (listW - font.width(empty)) / 2, listY + listH / 2 + 8,
                    FoodOrderTheme.TEXT_DIM, false);
        }
        graphics.disableScissor();
        FoodOrderTheme.scrollbar(graphics, listX + listW - 4, listY + 1, listH - 2,
                scrollRows, shown.size(), rows);
    }

    private void renderDetail(GuiGraphics graphics) {
        int cardHeight = Math.max(30, buttonY - 12 - detailY);
        FoodOrderTheme.card(graphics, detailX, detailY, detailW, cardHeight);
        if (selected >= 0 && selected < shown.size()) {
            FoodOrderEntry entry = shown.get(selected);
            String name = font.plainSubstrByWidth(entry.stack().getHoverName().getString(),
                    detailW - 12);
            graphics.drawString(font, name, detailX + 1, detailY + 2,
                    FoodOrderTheme.TITLE, false);
            FoodOrderTheme.divider(graphics, detailX, detailY + 13, detailW);

            int slotY = detailY + 17;
            FoodOrderTheme.slot(graphics, detailX, slotY, SLOT, false);
            graphics.renderItem(entry.stack(), detailX + 1, slotY + 1);
            graphics.drawString(font, Component.translatable(entry.kind().statusKey()),
                    detailX + SLOT + 6, slotY + 5,
                    FoodOrderTheme.statusColor(entry.kind()), false);

            int dividerY = slotY + SLOT + 8;
            FoodOrderTheme.divider(graphics, detailX, dividerY - 4, detailW);
            int textY = dividerY;
            for (FormattedCharSequence line : font.split(entry.detail(), Math.max(20, detailW - 8))) {
                graphics.drawString(font, line, detailX, textY,
                        FoodOrderTheme.TEXT_DIM, false);
                textY += 10;
                if (textY > buttonY - 14) {
                    break;
                }
            }
            if (entry.recipeId() != null && textY + 10 <= buttonY - 14) {
                graphics.drawString(font, Component.literal(entry.recipeId().toString()),
                        detailX, textY, FoodOrderTheme.TEXT_DIM, false);
            }
            if (partial) {
                graphics.drawString(font, Component.translatable("rsi.food_order.partial"),
                        detailX + 1, detailY + cardHeight - 12,
                        FoodOrderTheme.TEXT_DIM, false);
            }
        } else {
            if (!loading && !shown.isEmpty()) {
                FoodOrderTheme.sectionLabel(graphics, font,
                        Component.translatable("rsi.food_order.detail_hint"),
                        detailX + 1, detailY + 6, FoodOrderTheme.TEXT_DIM);
            }
            if (indexing) {
                FoodOrderTheme.sectionLabel(graphics, font,
                        Component.translatable("rsi.food_order.indexing"),
                        detailX + 1, detailY + 20, FoodOrderTheme.TEXT_DIM);
            }
        }
        renderFooter(graphics);
    }

    private void renderFooter(GuiGraphics graphics) {
        graphics.drawString(font,
                Component.translatable("rsi.food_order.summary", totalUneaten, totalOrderable),
                panelX + 9, buttonY - 11, FoodOrderTheme.TEXT_DIM, false);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        int index = indexAt(mouseX, mouseY);
        if (button == 0 && index >= 0) {
            long now = System.currentTimeMillis();
            if (index == lastClicked && now - lastClickAt < 250L) {
                selected = index;
                orderSelected();
            } else {
                selected = index;
            }
            lastClicked = index;
            lastClickAt = now;
            updateButtons();
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (isInList(mouseX, mouseY)) {
            scrollRows = Math.max(0, Math.min(maxScroll(),
                    scrollRows - (int) Math.signum(delta) * 2));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (searchBox != null && searchBox.isFocused()) {
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            orderSelected();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_R && Screen.hasControlDown()) {
            loading = true;
            receivedAt = System.currentTimeMillis();
            FoodOrderClient.requestList();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void orderSelected() {
        if (selected < 0 || selected >= shown.size()) {
            return;
        }
        FoodOrderEntry entry = shown.get(selected);
        if (!entry.orderable()) {
            return;
        }
        FoodOrderClient.submit(entry, false, epoch);
    }

    private boolean isInList(double mouseX, double mouseY) {
        return mouseX >= listX && mouseX < listX + listW
                && mouseY >= listY && mouseY < listY + listH;
    }

    private int visibleRows() {
        return Math.max(1, listH / ROW_H);
    }

    private int maxScroll() {
        return Math.max(0, shown.size() - visibleRows());
    }

    private int indexAt(double mouseX, double mouseY) {
        if (!isInList(mouseX, mouseY)) {
            return -1;
        }
        int row = (int) ((mouseY - listY) / ROW_H);
        int index = scrollRows + row;
        return index >= 0 && index < shown.size() && row < visibleRows() ? index : -1;
    }
}
