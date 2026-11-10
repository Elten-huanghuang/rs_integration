package com.huanghuang.rsintegration.feature.foodorder.client;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.feature.foodorder.FoodOrderClient;
import com.huanghuang.rsintegration.feature.foodorder.FoodOrderEntry;
import com.huanghuang.rsintegration.feature.foodorder.FoodOrderSnapshot;
import com.huanghuang.rsintegration.feature.foodorder.network.FoodOrderListPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.Item;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

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
    private final Map<Item, FoodOrderSearchIndex> searchIndex = new IdentityHashMap<>();
    private final FoodOrderListAccumulator listAccumulator = new FoodOrderListAccumulator();
    private final FoodOrderFavorites favorites = new FoodOrderFavorites();

    private EditBox searchBox;
    private FoodOrderButton orderOneButton;
    private FoodOrderButton orderAllButton;
    private FoodOrderDropdown filterDropdown;
    private FoodOrderBookmarkButton bookmarkButton;
    private Path favoritesPath;

    private Component sources = Component.empty();
    private int totalUneaten;
    private int totalOrderable;
    private boolean indexing;
    private boolean partial;
    private long epoch;
    private long receivedAt = System.currentTimeMillis();
    private boolean loading = true;
    private FoodOrderViewMode viewMode = FoodOrderViewMode.ALL;
    private int scrollRows;
    private int selected = -1;
    private int lastClicked = -1;
    private long lastClickAt;
    private FoodOrderEntry detailEntry;
    private List<ClientTooltipComponent> detailLines = List.of();
    private int detailScroll;
    private int detailLineWidth;
    private int detailModifiers;
    private long detailUpdatedAt;

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
        listW = Math.max(40, Math.min(190, (panelW - 26) * 45 / 100));
        listH = Math.max(ROW_H * 3, panelH - 104);
        detailX = listX + listW + 10;
        detailY = panelY + 42;
        detailW = Math.max(40, panelW - listW - 26);
        buttonY = panelY + panelH - 22;
        if (favoritesPath == null) {
            favoritesPath = FMLPaths.CONFIGDIR.get().resolve("rs_integration")
                    .resolve("food-order-favorites.json");
            if (!favorites.load(favoritesPath)) {
                RSIntegrationMod.LOGGER.warn("[RSI-FoodOrder] 无法读取食物收藏：{}", favoritesPath);
            }
        }

        String query = searchBox == null ? "" : searchBox.getValue();
        searchBox = new EditBox(font, listX + 13, panelY + 46,
                Math.max(30, listW - 87), 14,
                Component.translatable("rsi.food_order.search"));
        searchBox.setBordered(false);
        searchBox.setTextColor(FoodOrderTheme.TEXT);
        searchBox.setMaxLength(60);
        searchBox.setHint(Component.translatable("rsi.food_order.search"));
        searchBox.setTooltip(Tooltip.create(Component.translatable("rsi.food_order.search_hint")));
        searchBox.setValue(query);
        searchBox.setResponder(text -> rebuild());
        addRenderableWidget(searchBox);

        filterDropdown = addRenderableWidget(new FoodOrderDropdown(
                font, listX + listW - 68, panelY + 42, 68, 16, viewMode, this::selectViewMode));
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
        int bookmarkWidth = Math.min(56, Math.max(24, detailW - 12));
        bookmarkButton = addRenderableWidget(new FoodOrderBookmarkButton(
                detailX + detailW - bookmarkWidth + 4, detailY + detailCardHeight() - 34,
                bookmarkWidth, 16, button -> toggleFavorite()));

        FoodOrderClient.requestList();
        rebuild();
    }

    public void setData(FoodOrderListPacket packet) {
        FoodOrderSnapshot snapshot = listAccumulator.accept(packet);
        if (snapshot == null) {
            return;
        }
        entries.clear();
        entries.addAll(snapshot.entries());
        for (FoodOrderEntry entry : entries) {
            Item item = entry.stack().getItem();
            // 状态更新只替换条目，复用名称和拼音索引，避免大目录每次更新都重新转写。
            searchIndex.computeIfAbsent(item, ignored -> {
                var key = ForgeRegistries.ITEMS.getKey(item);
                return new FoodOrderSearchIndex(entry.stack().getHoverName().getString(),
                        key == null ? "" : key.toString());
            });
        }
        sources = snapshot.sources();
        totalUneaten = snapshot.totalUneaten();
        totalOrderable = snapshot.totalOrderable();
        indexing = snapshot.indexing();
        partial = snapshot.partial();
        epoch = snapshot.epoch();
        receivedAt = System.currentTimeMillis();
        loading = false;
        rebuild(true);
    }

    private void rebuild() {
        rebuild(false);
    }

    private void rebuild(boolean preservePosition) {
        List<String> terms = FoodOrderSearchIndex.queryTerms(searchBox == null ? "" : searchBox.getValue());
        FoodOrderEntry previousSelection = selected >= 0 && selected < shown.size()
                ? shown.get(selected) : null;
        shown.clear();
        for (FoodOrderEntry entry : entries) {
            if (viewMode == FoodOrderViewMode.ORDERABLE && !entry.orderable()) {
                continue;
            }
            if (viewMode == FoodOrderViewMode.FAVORITES && !isFavorite(entry)) {
                continue;
            }
            if (!terms.isEmpty() && !searchIndex.get(entry.stack().getItem()).matches(terms)) {
                continue;
            }
            shown.add(entry);
        }
        Comparator<FoodOrderEntry> favoritesFirst = Comparator.comparing(entry -> !isFavorite(entry));
        if (viewMode == FoodOrderViewMode.NUTRITION) {
            // 饱食度是主排序键，收藏只在饱食度相同时置顶。
            Map<FoodOrderEntry, Integer> nutrition = new IdentityHashMap<>();
            for (FoodOrderEntry entry : shown) {
                FoodOrderFoodInfo info = FoodOrderFoodInfo.from(entry.stack(),
                        minecraft == null ? null : minecraft.player);
                nutrition.put(entry, info == null ? -1 : info.nutrition());
            }
            shown.sort(Comparator.comparingInt((FoodOrderEntry entry) -> nutrition.get(entry))
                    .reversed().thenComparing(favoritesFirst));
        } else {
            // 其余模式继续收藏置顶，保持服务端原有的状态顺序。
            shown.sort(favoritesFirst);
        }
        // 筛选后按条目保留选择，不能让原行号指向另一道菜。
        selected = -1;
        if (previousSelection != null) {
            for (int index = 0; index < shown.size(); index++) {
                if (shown.get(index).stack().getItem() == previousSelection.stack().getItem()) {
                    selected = index;
                    break;
                }
            }
        }
        scrollRows = preservePosition ? Math.min(scrollRows, maxScroll()) : 0;
        lastClicked = -1;
        lastClickAt = 0L;
        detailEntry = null;
        detailScroll = 0;
        if (filterDropdown != null) {
            filterDropdown.setMode(viewMode);
        }
        updateButtons();
    }

    private void selectViewMode(FoodOrderViewMode mode) {
        viewMode = mode;
        rebuild();
    }

    private boolean isFavorite(FoodOrderEntry entry) {
        return favorites.contains(ForgeRegistries.ITEMS.getKey(entry.stack().getItem()));
    }

    private void toggleFavorite() {
        FoodOrderEntry entry = selectedEntry();
        if (entry == null) {
            return;
        }
        var key = ForgeRegistries.ITEMS.getKey(entry.stack().getItem());
        if (!favorites.toggle(key)) {
            return;
        }
        try {
            if (favoritesPath != null) {
                favorites.save(favoritesPath);
            }
        } catch (IOException exception) {
            favorites.toggle(key);
            RSIntegrationMod.LOGGER.warn("[RSI-FoodOrder] 保存食物收藏失败", exception);
            if (minecraft != null && minecraft.player != null) {
                minecraft.player.displayClientMessage(
                        Component.translatable("rsi.food_order.favorite.save_failed"), true);
            }
        }
        rebuild();
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
        if (bookmarkButton != null) {
            FoodOrderEntry entry = selectedEntry();
            bookmarkButton.visible = entry != null;
            bookmarkButton.active = entry != null;
            bookmarkButton.setFavorite(entry != null && isFavorite(entry));
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
        if (filterDropdown != null && filterDropdown.isExpanded()) {
            filterDropdown.renderPopup(graphics, mouseX, mouseY);
            return;
        }
        int hovered = indexAt(mouseX, mouseY);
        if (hovered >= 0) {
            graphics.renderTooltip(font, shown.get(hovered).stack(), mouseX, mouseY);
        } else {
            renderDetailTooltip(graphics, mouseX, mouseY);
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
            if (isFavorite(entry)) {
                graphics.fill(listX + 22, rowY + 3, listX + 25, rowY + 9, FoodOrderTheme.ACCENT);
                graphics.fill(listX + 22, rowY + 9, listX + 23, rowY + 11, FoodOrderTheme.ACCENT);
                graphics.fill(listX + 24, rowY + 9, listX + 25, rowY + 11, FoodOrderTheme.ACCENT);
            }
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
        int cardHeight = detailCardHeight();
        FoodOrderTheme.card(graphics, detailX, detailY, detailW, cardHeight);
        FoodOrderEntry entry = selectedEntry();
        if (entry != null) {
            String name = font.plainSubstrByWidth(entry.stack().getHoverName().getString(),
                    detailW - 12);
            graphics.drawString(font, name, detailX + 5, detailY + 5,
                    FoodOrderTheme.TITLE, false);
            FoodOrderTheme.divider(graphics, detailX + 5, detailY + 18, detailW - 10);

            int slotY = detailY + 23;
            FoodOrderTheme.slot(graphics, detailX + 5, slotY, SLOT, false);
            graphics.renderItem(entry.stack(), detailX + 6, slotY + 1);
            String status = font.plainSubstrByWidth(
                    Component.translatable(entry.kind().statusKey()).getString(), detailW - SLOT - 18);
            graphics.drawString(font, status, detailX + SLOT + 11, slotY + 5,
                    FoodOrderTheme.statusColor(entry.kind()), false);

            int contentWidth = Math.max(20, detailW - 18);
            int modifiers = (Screen.hasShiftDown() ? 1 : 0) | (Screen.hasControlDown() ? 2 : 0)
                    | (Screen.hasAltDown() ? 4 : 0);
            long now = System.currentTimeMillis();
            if (entry != detailEntry || contentWidth != detailLineWidth
                    || modifiers != detailModifiers || now - detailUpdatedAt >= 500L) {
                boolean changedEntry = entry != detailEntry;
                List<ClientTooltipComponent> lines = new ArrayList<>(
                        FoodOrderTooltip.gather(minecraft, font, entry.stack(), contentWidth, height));
                addDetailLines(lines, Component.translatable("rsi.food_order.materials.status"),
                        FoodOrderTheme.TITLE, contentWidth);
                addDetailLines(lines, entry.detail(), FoodOrderTheme.TEXT_DIM, contentWidth);
                detailLines = List.copyOf(lines);
                detailEntry = entry;
                detailLineWidth = contentWidth;
                detailModifiers = modifiers;
                detailUpdatedAt = now;
                if (changedEntry) {
                    detailScroll = 0;
                }
            }
            detailScroll = Math.max(0, Math.min(detailScroll, maxDetailScroll()));
            FoodOrderTooltip.render(graphics, font, detailLines, detailX + 5, detailContentY(),
                    contentWidth, detailContentBottom() - detailContentY(), detailScroll);
            FoodOrderTheme.scrollbar(graphics, detailX + detailW - 5, detailContentY(),
                    detailContentBottom() - detailContentY(),
                    detailScroll, FoodOrderTooltip.height(detailLines, font, contentWidth),
                    detailContentBottom() - detailContentY());
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
        if (partial) {
            String warning = font.plainSubstrByWidth(
                    Component.translatable("rsi.food_order.partial").getString(), detailW - 10);
            graphics.drawString(font, warning, detailX + 5, detailY + cardHeight - 12,
                    FoodOrderTheme.TEXT_DIM, false);
        }
        renderFooter(graphics);
    }

    private void addDetailLines(List<ClientTooltipComponent> lines, Component text, int color, int width) {
        for (FormattedCharSequence line : font.split(
                text.copy().withStyle(style -> style.withColor(color & 0xFFFFFF)), width)) {
            lines.add(ClientTooltipComponent.create(line));
        }
    }

    private void renderDetailTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        FoodOrderEntry entry = selectedEntry();
        if (entry == null) {
            return;
        }
        if (mouseX >= detailX + 5 && mouseX < detailX + 5 + SLOT
                && mouseY >= detailY + 23 && mouseY < detailY + 23 + SLOT) {
            graphics.renderTooltip(font, entry.stack(), mouseX, mouseY);
        } else if (entry.recipeId() != null && mouseX >= detailX && mouseX < detailX + detailW
                && mouseY >= detailY && mouseY < detailY + 18) {
            graphics.renderTooltip(font, Component.translatable("rsi.food_order.recipe_id",
                    entry.recipeId().toString()), mouseX, mouseY);
        }
    }

    private FoodOrderEntry selectedEntry() {
        return selected >= 0 && selected < shown.size() ? shown.get(selected) : null;
    }

    private int detailCardHeight() {
        return Math.max(30, buttonY - 12 - detailY);
    }

    private int detailContentY() {
        return detailY + 48;
    }

    private int detailContentBottom() {
        return Math.max(detailContentY() + 10, detailY + detailCardHeight() - 38);
    }

    private int maxDetailScroll() {
        return Math.max(0, FoodOrderTooltip.height(detailLines, font, Math.max(20, detailW - 18))
                - (detailContentBottom() - detailContentY()));
    }

    private void renderFooter(GuiGraphics graphics) {
        graphics.drawString(font,
                Component.translatable("rsi.food_order.summary", totalUneaten, totalOrderable),
                panelX + 9, buttonY - 11, FoodOrderTheme.TEXT_DIM, false);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 列表和纸面不是原生控件，点击后也要同时释放输入框与容器的焦点。
        if (searchBox != null && !searchBox.isMouseOver(mouseX, mouseY)) {
            searchBox.setFocused(false);
            if (getFocused() == searchBox) {
                setFocused(null);
            }
        }
        if (filterDropdown != null && filterDropdown.isExpanded()) {
            if (filterDropdown.mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
            if (getFocused() == filterDropdown) {
                setFocused(null);
            }
        }
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        int index = indexAt(mouseX, mouseY);
        if (button == 0 && index >= 0) {
            if (selected != index) {
                detailEntry = null;
                detailScroll = 0;
            }
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
        if (filterDropdown != null && filterDropdown.isExpanded()) {
            return filterDropdown.mouseScrolled(mouseX, mouseY, delta);
        }
        if (isInList(mouseX, mouseY)) {
            scrollRows = Math.max(0, Math.min(maxScroll(),
                    scrollRows - (int) Math.signum(delta) * 2));
            return true;
        }
        if (selectedEntry() != null && mouseX >= detailX && mouseX < detailX + detailW
                && mouseY >= detailContentY() && mouseY < detailContentBottom()) {
            detailScroll = Math.max(0, Math.min(maxDetailScroll(),
                    detailScroll - (int) Math.signum(delta) * 20));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (filterDropdown != null && (filterDropdown.isExpanded() || filterDropdown.isFocused())
                && filterDropdown.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
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
