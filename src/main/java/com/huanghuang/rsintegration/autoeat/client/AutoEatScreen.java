package com.huanghuang.rsintegration.autoeat.client;

import com.huanghuang.rsintegration.autoeat.AutoEatMode;
import com.huanghuang.rsintegration.autoeat.network.UpdateBlacklistPacket;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;

@OnlyIn(Dist.CLIENT)
public final class AutoEatScreen extends Screen {

    private static final int COLS = 9;
    private static final int ROWS = 5;
    private static final int SLOT_SIZE = 18;
    private static final int ITEMS_PER_PAGE = COLS * ROWS;

    private final AutoEatMode mode;
    private final List<Item> allItems;
    private final Set<ResourceLocation> blacklist;
    private final Set<ResourceLocation> originalBlacklist;

    private List<Item> filteredItems;
    private int page;
    private int totalPages;
    private int leftPos, topPos, gridW, gridH;

    // search
    private EditBox searchBox;
    private String lastFilterText = "";

    // drag multi-select
    private boolean dragging;
    private boolean dragAdd;
    private final Set<Integer> toggledThisDrag = new HashSet<>();

    private static List<Item> cachedAllItems;

    private static List<Item> getAllEdibleItems() {
        if (cachedAllItems == null) {
            List<Item> items = new ArrayList<>();
            for (Item item : ForgeRegistries.ITEMS) {
                if (item.isEdible()) items.add(item);
            }
            cachedAllItems = items;
        }
        return cachedAllItems;
    }

    public AutoEatScreen(AutoEatMode mode) {
        super(Component.translatable("rsi.autoeat.btn.blacklist"));
        this.mode = mode;
        allItems = getAllEdibleItems();
        originalBlacklist = new HashSet<>(ClientState.blacklistedItems);
        blacklist = new HashSet<>(originalBlacklist);
        filteredItems = new ArrayList<>(allItems);
        totalPages = Math.max(1, (filteredItems.size() + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE);
        page = 0;
    }

    @Override
    protected void init() {
        super.init();
        gridW = COLS * SLOT_SIZE;
        gridH = ROWS * SLOT_SIZE;
        leftPos = (width - gridW) / 2;
        topPos = (height - gridH) / 2 - 30;

        // Search box
        int searchW = Math.min(160, gridW);
        int searchX = leftPos + (gridW - searchW) / 2;
        searchBox = new EditBox(font, searchX, topPos - 22, searchW, 16, Component.empty());
        searchBox.setHint(Component.translatable("rsi.autoeat.search_hint"));
        searchBox.setMaxLength(50);
        searchBox.setResponder(this::onSearchChanged);
        addRenderableWidget(searchBox);

        // Bottom row: batch buttons (left) + Done (right)
        int btnY = topPos + gridH + 4;
        if (mode != AutoEatMode.STACK) {
            addRenderableWidget(Button.builder(
                            Component.translatable("rsi.autoeat.btn.select_all"), btn -> selectAllVisible())
                    .pos(leftPos, btnY)
                    .size(50, 20)
                    .build());
            addRenderableWidget(Button.builder(
                            Component.translatable("rsi.autoeat.btn.deselect"), btn -> clearVisible())
                    .pos(leftPos + 54, btnY)
                    .size(50, 20)
                    .build());
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), btn -> onClose())
                .pos(leftPos + gridW - 50, btnY)
                .size(50, 20)
                .build());

        // Page nav
        if (totalPages > 1) {
            addRenderableWidget(Button.builder(Component.translatable("rsi.autoeat.btn.prev_page"), btn -> {
                        if (page > 0) page--;
                    })
                    .pos(leftPos - 24, topPos + gridH / 2 - 10)
                    .size(20, 20)
                    .build());

            addRenderableWidget(Button.builder(Component.translatable("rsi.autoeat.btn.next_page"), btn -> {
                        if (page < totalPages - 1) page++;
                    })
                    .pos(leftPos + gridW + 4, topPos + gridH / 2 - 10)
                    .size(20, 20)
                    .build());
        }
    }

    // ── search ────────────────────────────────────────────────────

    private void onSearchChanged(String text) {
        if (text.equals(lastFilterText)) return;
        lastFilterText = text;
        applyFilter();
    }

    private void applyFilter() {
        String query = lastFilterText.trim().toLowerCase();
        if (query.isEmpty()) {
            filteredItems = new ArrayList<>(allItems);
        } else {
            List<Item> result = new ArrayList<>();
            for (Item item : allItems) {
                if (matchesQuery(item, query)) {
                    result.add(item);
                }
            }
            filteredItems = result;
        }
        page = 0;
        totalPages = Math.max(1, (filteredItems.size() + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE);
    }

    private boolean matchesQuery(Item item, String query) {
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(item);
        if (key != null && key.toString().toLowerCase().contains(query)) return true;

        String displayName = item.getDescription().getString().toLowerCase();
        if (displayName.contains(query)) return true;

        if (PinyinUtil.toPinyin(displayName).contains(query)) return true;

        if (PinyinUtil.toPinyinInitials(displayName).contains(query)) return true;

        return false;
    }

    // ── batch operations ──────────────────────────────────────────

    private void selectAllVisible() {
        int startIdx = page * ITEMS_PER_PAGE;
        int endIdx = Math.min(startIdx + ITEMS_PER_PAGE, filteredItems.size());
        for (int i = startIdx; i < endIdx; i++) {
            ResourceLocation key = ForgeRegistries.ITEMS.getKey(filteredItems.get(i));
            if (key != null) blacklist.add(key);
        }
    }

    private void clearVisible() {
        int startIdx = page * ITEMS_PER_PAGE;
        int endIdx = Math.min(startIdx + ITEMS_PER_PAGE, filteredItems.size());
        for (int i = startIdx; i < endIdx; i++) {
            ResourceLocation key = ForgeRegistries.ITEMS.getKey(filteredItems.get(i));
            if (key != null) blacklist.remove(key);
        }
    }

    // ── render ────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        renderBackground(gfx);
        super.render(gfx, mouseX, mouseY, partialTick);

        int startIdx = page * ITEMS_PER_PAGE;
        int endIdx = Math.min(startIdx + ITEMS_PER_PAGE, filteredItems.size());

        for (int i = startIdx; i < endIdx; i++) {
            int localIdx = i - startIdx;
            int col = localIdx % COLS;
            int row = localIdx / COLS;
            int sx = leftPos + col * SLOT_SIZE;
            int sy = topPos + row * SLOT_SIZE;

            Item item = filteredItems.get(i);
            ResourceLocation key = ForgeRegistries.ITEMS.getKey(item);
            boolean blacklisted = key != null && blacklist.contains(key);
            boolean selected = mode == AutoEatMode.STACK && key != null && key.equals(ClientState.selectedItem);
            boolean hovered = isMouseOverSlot(mouseX, mouseY, sx, sy);

            int bg;
            if (selected) {
                bg = hovered ? 0xA03388FF : 0x803366FF;
            } else if (blacklisted) {
                bg = hovered ? 0xA0FF3333 : 0x80CC2222;
            } else {
                bg = hovered ? 0x80FFFFFF : 0x40FFFFFF;
            }
            gfx.fill(sx, sy, sx + SLOT_SIZE, sy + SLOT_SIZE, bg);
            drawSlotBorder(gfx, sx, sy, 0xFF555555);

            gfx.renderItem(new ItemStack(item), sx + 1, sy + 1);

            if (blacklisted && mode != AutoEatMode.STACK) {
                gfx.fill(sx + 1, sy + 1, sx + SLOT_SIZE - 1, sy + SLOT_SIZE - 1, 0x60FF0000);
            }

            // Render tooltip on hover
            if (hovered) {
                gfx.renderTooltip(font, item.getDescription(), mouseX, mouseY);
            }
        }

        // Title (left) + page indicator (right) — same line, both truncated so they never overlap
        String title;
        if (mode == AutoEatMode.STACK && ClientState.selectedItem != null) {
            Item sel = ForgeRegistries.ITEMS.getValue(ClientState.selectedItem);
            String name = sel != null ? sel.getDescription().getString()
                    : ClientState.selectedItem.toString();
            title = Component.translatable("rsi.autoeat.title.select", name).getString();
        } else if (mode == AutoEatMode.STACK) {
            title = Component.translatable("rsi.autoeat.btn.select").getString();
        } else {
            title = Component.translatable("rsi.autoeat.btn.blacklist").getString();
        }
        String pageText = (page + 1) + " / " + totalPages;
        int pageW = font.width(pageText);
        int maxTitleW = gridW - pageW - 8;
        if (maxTitleW > 0 && font.width(title) > maxTitleW) {
            title = font.plainSubstrByWidth(title, maxTitleW);
        }
        gfx.drawString(font, title, leftPos, topPos - 34, 0xAAAAAA, true);
        gfx.drawString(font, pageText,
                leftPos + gridW - pageW,
                topPos - 34, 0xFFFFFF, true);
    }

    // ── mouse: click / drag / release ─────────────────────────────

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (searchBox.mouseClicked(mx, my, button)) {
            setFocused(searchBox);
            return true;
        }
        // Clicking elsewhere — release search box focus
        if (searchBox.isFocused()) {
            searchBox.setFocused(false);
        }
        if (button == 0) {
            int globalIdx = getSlotAt(mx, my);
            if (globalIdx >= 0) {
                Item item = filteredItems.get(globalIdx);
                ResourceLocation key = ForgeRegistries.ITEMS.getKey(item);
                if (key == null) return true;

                if (mode == AutoEatMode.STACK) {
                    ClientState.selectedItem = key;
                } else {
                    boolean wasBlacklisted = blacklist.contains(key);
                    if (wasBlacklisted) blacklist.remove(key);
                    else blacklist.add(key);
                    dragging = true;
                    dragAdd = !wasBlacklisted;
                    toggledThisDrag.clear();
                    toggledThisDrag.add(globalIdx);
                }
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragging && mode != AutoEatMode.STACK) {
            int globalIdx = getSlotAt(mx, my);
            if (globalIdx >= 0 && toggledThisDrag.add(globalIdx)) {
                Item item = filteredItems.get(globalIdx);
                ResourceLocation key = ForgeRegistries.ITEMS.getKey(item);
                if (key != null) {
                    if (dragAdd) blacklist.add(key);
                    else blacklist.remove(key);
                }
            }
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (dragging) {
            dragging = false;
            toggledThisDrag.clear();
            return true;
        }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (searchBox.isFocused()) return searchBox.charTyped(codePoint, modifiers);
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (searchBox.isFocused()) {
            if (searchBox.keyPressed(keyCode, scanCode, modifiers)) return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ── close ─────────────────────────────────────────────────────

    @Override
    public void onClose() {
        if (mode != AutoEatMode.STACK) {
            Set<ResourceLocation> added = new HashSet<>(blacklist);
            added.removeAll(originalBlacklist);
            Set<ResourceLocation> removed = new HashSet<>(originalBlacklist);
            removed.removeAll(blacklist);

            ClientState.blacklistedItems.clear();
            ClientState.blacklistedItems.addAll(blacklist);

            if (!added.isEmpty() || !removed.isEmpty()) {
                NetworkHandler.CHANNEL.sendToServer(new UpdateBlacklistPacket(added, removed));
            }
        }
        super.onClose();
    }

    // ── helpers ───────────────────────────────────────────────────

    private int getSlotAt(double mx, double my) {
        if (mx < leftPos || my < topPos) return -1;
        int col = (int) ((mx - leftPos) / SLOT_SIZE);
        int row = (int) ((my - topPos) / SLOT_SIZE);
        if (col < 0 || col >= COLS || row < 0 || row >= ROWS) return -1;
        int idx = page * ITEMS_PER_PAGE + row * COLS + col;
        return (idx >= 0 && idx < filteredItems.size()) ? idx : -1;
    }

    private static void drawSlotBorder(GuiGraphics gfx, int x, int y, int color) {
        int r = x + SLOT_SIZE;
        int b = y + SLOT_SIZE;
        gfx.fill(x, y, r, y + 1, color);
        gfx.fill(x, b - 1, r, b, color);
        gfx.fill(x, y, x + 1, b, color);
        gfx.fill(r - 1, y, r, b, color);
    }

    private boolean isMouseOverSlot(double mx, double my, int sx, int sy) {
        return mx >= sx && mx < sx + SLOT_SIZE && my >= sy && my < sy + SLOT_SIZE;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
