package com.huanghuang.rsintegration.autoeat.client;

import com.huanghuang.rsintegration.autoeat.AutoEatMode;
import com.huanghuang.rsintegration.autoeat.network.UpdateBlacklistPacket;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@OnlyIn(Dist.CLIENT)
public final class AutoEatScreen extends Screen {

    private static final int COLS = 9;
    private static final int ROWS = 5;
    private static final int SLOT_SIZE = 18;
    private static final int EFFECT_COLS = 2;
    private static final int EFFECT_ROWS = 4;
    private static final int EFFECT_ENTRY_W = 110;
    private static final int EFFECT_ENTRY_H = 21;
    private static final int EFFECT_GAP = 4;
    private static final int PANEL_W = 252;
    private static final int PANEL_H = 210;

    private enum Tab { FOOD, EFFECT }

    private final AutoEatMode mode;
    private final Set<ResourceLocation> itemBlacklist;
    private final Set<ResourceLocation> originalItemBlacklist;
    private final Set<ResourceLocation> effectBlacklist;
    private final Set<ResourceLocation> originalEffectBlacklist;

    private Tab tab = Tab.FOOD;
    private List<ResourceLocation> filteredEntries = new ArrayList<>();
    private int page;
    private int totalPages = 1;
    private int leftPos;
    private int topPos;
    private int gridW;
    private int gridH;
    private int panelLeft;
    private int panelTop;

    private EditBox searchBox;
    private FlatButton foodTabButton;
    private FlatButton effectTabButton;
    private FlatButton previousButton;
    private FlatButton nextButton;
    private String lastFilterText = "";

    private boolean dragging;
    private boolean dragAdd;
    private final Set<Integer> toggledThisDrag = new HashSet<>();

    private static List<ResourceLocation> cachedFoods;
    private static List<ResourceLocation> cachedEffects;

    public AutoEatScreen(AutoEatMode mode) {
        super(Component.translatable("rsi.autoeat.btn.blacklist"));
        this.mode = mode;
        originalItemBlacklist = new HashSet<>(ClientState.blacklistedItems);
        itemBlacklist = new HashSet<>(originalItemBlacklist);
        originalEffectBlacklist = new HashSet<>(ClientState.blacklistedEffects);
        effectBlacklist = new HashSet<>(originalEffectBlacklist);
        applyFilter();
    }

    private static List<ResourceLocation> getAllFoods() {
        if (cachedFoods == null) {
            cachedFoods = new ArrayList<>();
            for (Item item : ForgeRegistries.ITEMS) {
                ResourceLocation key = ForgeRegistries.ITEMS.getKey(item);
                if (item.isEdible() && key != null) cachedFoods.add(key);
            }
        }
        return cachedFoods;
    }

    private static List<ResourceLocation> getAllEffects() {
        if (cachedEffects == null) {
            cachedEffects = new ArrayList<>();
            for (MobEffect effect : ForgeRegistries.MOB_EFFECTS) {
                ResourceLocation key = ForgeRegistries.MOB_EFFECTS.getKey(effect);
                if (key != null) cachedEffects.add(key);
            }
        }
        return cachedEffects;
    }

    @Override
    protected void init() {
        super.init();
        gridW = COLS * SLOT_SIZE;
        gridH = ROWS * SLOT_SIZE;
        panelLeft = (width - PANEL_W) / 2;
        panelTop = (height - PANEL_H) / 2;
        leftPos = panelLeft + (PANEL_W - gridW) / 2;
        topPos = panelTop + 75;

        if (mode != AutoEatMode.STACK) {
            int tabWidth = 104;
            foodTabButton = addRenderableWidget(new FlatButton(panelLeft + 12, panelTop + 28,
                    tabWidth, 18, Component.translatable("rsi.autoeat.tab.food"),
                    () -> switchTab(Tab.FOOD), false));
            effectTabButton = addRenderableWidget(new FlatButton(panelLeft + PANEL_W - 12 - tabWidth,
                    panelTop + 28, tabWidth, 18, Component.translatable("rsi.autoeat.tab.effects"),
                    () -> switchTab(Tab.EFFECT), false));
        }

        int searchW = PANEL_W - 24;
        searchBox = new EditBox(font, panelLeft + 12, panelTop + 52,
                searchW, 16, Component.empty());
        searchBox.setHint(Component.translatable("rsi.autoeat.search_hint"));
        searchBox.setBordered(false);
        searchBox.setMaxLength(80);
        searchBox.setValue(lastFilterText);
        searchBox.setResponder(this::onSearchChanged);
        addRenderableWidget(searchBox);

        int btnY = panelTop + PANEL_H - 27;
        if (mode != AutoEatMode.STACK) {
            addRenderableWidget(new FlatButton(panelLeft + 12, btnY, 48, 20,
                    Component.translatable("rsi.autoeat.btn.select_all"), this::selectAllVisible, false));
            addRenderableWidget(new FlatButton(panelLeft + 64, btnY, 48, 20,
                    Component.translatable("rsi.autoeat.btn.deselect"), this::clearVisible, false));
        }
        addRenderableWidget(new FlatButton(panelLeft + PANEL_W - 62, btnY, 50, 20,
                Component.translatable("gui.done"), this::onClose, true));

        previousButton = addRenderableWidget(new FlatButton(panelLeft + 118, btnY, 20, 20,
                Component.translatable("rsi.autoeat.btn.prev_page"), () -> {
                            if (page > 0) page--;
                            updateButtonStates();
                        }, false));
        nextButton = addRenderableWidget(new FlatButton(panelLeft + 142, btnY, 20, 20,
                Component.translatable("rsi.autoeat.btn.next_page"), () -> {
                            if (page < totalPages - 1) page++;
                            updateButtonStates();
                        }, false));
        updateButtonStates();
    }

    private void switchTab(Tab newTab) {
        if (mode == AutoEatMode.STACK || tab == newTab) return;
        tab = newTab;
        lastFilterText = "";
        searchBox.setValue("");
        applyFilter();
    }

    private void updateButtonStates() {
        if (foodTabButton != null) foodTabButton.selected = tab == Tab.FOOD;
        if (effectTabButton != null) effectTabButton.selected = tab == Tab.EFFECT;
        if (previousButton != null) previousButton.active = page > 0;
        if (nextButton != null) nextButton.active = page < totalPages - 1;
    }

    private void onSearchChanged(String text) {
        if (text.equals(lastFilterText)) return;
        lastFilterText = text;
        applyFilter();
    }

    private void applyFilter() {
        String query = lastFilterText.trim().toLowerCase(Locale.ROOT);
        filteredEntries = new ArrayList<>();
        List<ResourceLocation> source = tab == Tab.FOOD ? getAllFoods() : getAllEffects();
        for (ResourceLocation key : source) {
            if (query.isEmpty() || matchesQuery(key, query)) filteredEntries.add(key);
        }
        Set<ResourceLocation> activeBlacklist = activeBlacklist();
        filteredEntries.sort((left, right) -> {
            int blacklistOrder = Boolean.compare(activeBlacklist.contains(right), activeBlacklist.contains(left));
            if (blacklistOrder != 0) return blacklistOrder;
            return displayName(left).compareToIgnoreCase(displayName(right));
        });
        page = 0;
        totalPages = Math.max(1, (filteredEntries.size() + entriesPerPage() - 1) / entriesPerPage());
        updateButtonStates();
    }

    private boolean matchesQuery(ResourceLocation key, String query) {
        if (key.toString().toLowerCase(Locale.ROOT).contains(query)) return true;
        String displayName = displayName(key).toLowerCase(Locale.ROOT);
        return displayName.contains(query)
                || PinyinUtil.toPinyin(displayName).contains(query)
                || PinyinUtil.toPinyinInitials(displayName).contains(query);
    }

    private String displayName(ResourceLocation key) {
        if (tab == Tab.FOOD) {
            Item item = ForgeRegistries.ITEMS.getValue(key);
            return item == null ? key.toString() : item.getDescription().getString();
        }
        MobEffect effect = ForgeRegistries.MOB_EFFECTS.getValue(key);
        return effect == null ? key.toString() : effect.getDisplayName().getString();
    }

    private Set<ResourceLocation> activeBlacklist() {
        return tab == Tab.FOOD ? itemBlacklist : effectBlacklist;
    }

    private void selectAllVisible() {
        int start = page * entriesPerPage();
        int end = Math.min(start + entriesPerPage(), filteredEntries.size());
        activeBlacklist().addAll(filteredEntries.subList(start, end));
    }

    private void clearVisible() {
        int start = page * entriesPerPage();
        int end = Math.min(start + entriesPerPage(), filteredEntries.size());
        activeBlacklist().removeAll(filteredEntries.subList(start, end));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        renderPanel(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);

        int start = page * entriesPerPage();
        int end = Math.min(start + entriesPerPage(), filteredEntries.size());
        for (int i = start; i < end; i++) {
            int local = i - start;
            int sx = entryX(local);
            int sy = entryY(local);
            ResourceLocation key = filteredEntries.get(i);
            boolean blacklisted = activeBlacklist().contains(key);
            boolean selected = mode == AutoEatMode.STACK && key.equals(ClientState.selectedItem);
            boolean hovered = isMouseOverSlot(mouseX, mouseY, sx, sy);

            int background = selected
                    ? (hovered ? 0xA03388FF : 0x803366FF)
                    : blacklisted
                    ? (hovered ? 0xA0FF3333 : 0x80CC2222)
                    : (hovered ? 0x80FFFFFF : 0x40FFFFFF);
            int entryWidth = tab == Tab.FOOD ? SLOT_SIZE : EFFECT_ENTRY_W;
            int entryHeight = tab == Tab.FOOD ? SLOT_SIZE : EFFECT_ENTRY_H;
            graphics.fill(sx, sy, sx + entryWidth, sy + entryHeight, background);
            drawBorder(graphics, sx, sy, entryWidth, entryHeight,
                    hovered ? 0xFF9AA7B5 : 0xFF4B5560);
            renderEntryIcon(graphics, key, sx, sy);

            if (blacklisted && mode != AutoEatMode.STACK) {
                graphics.fill(sx + 1, sy + 1, sx + entryWidth - 1, sy + entryHeight - 1, 0x48D83A3A);
            }
            if (tab == Tab.EFFECT) {
                String name = font.plainSubstrByWidth(displayName(key), EFFECT_ENTRY_W - 31);
                graphics.drawString(font, name, sx + 23, sy + 7,
                        blacklisted ? 0xFFFFB3B3 : 0xFFE7EDF3, false);
                if (blacklisted) {
                    graphics.drawString(font, "x", sx + EFFECT_ENTRY_W - 9, sy + 7, 0xFFFF6868, true);
                }
            }
            if (hovered) {
                graphics.renderTooltip(font,
                        Component.literal(displayName(key) + " (" + key + ")"), mouseX, mouseY);
            }
        }

        String title = titleText();
        String pageText = (page + 1) + " / " + totalPages;
        int pageWidth = font.width(pageText);
        int maxTitleWidth = PANEL_W - pageWidth - 32;
        if (maxTitleWidth > 0 && font.width(title) > maxTitleWidth) {
            title = font.plainSubstrByWidth(title, maxTitleWidth);
        }
        int titleY = panelTop + 10;
        graphics.drawString(font, title, panelLeft + 12, titleY, 0xFFF1F5F9, true);
        graphics.drawString(font, pageText, panelLeft + PANEL_W - 12 - pageWidth,
                titleY, 0xFFB8C2CC, false);

        if (filteredEntries.isEmpty()) {
            Component empty = Component.translatable("rsi.autoeat.empty_search");
            graphics.drawCenteredString(font, empty, panelLeft + PANEL_W / 2,
                    topPos + 38, 0xFF9AA4AF);
        }
    }

    private void renderEntryIcon(GuiGraphics graphics, ResourceLocation key, int sx, int sy) {
        if (tab == Tab.FOOD) {
            Item item = ForgeRegistries.ITEMS.getValue(key);
            if (item != null) graphics.renderItem(new ItemStack(item), sx + 1, sy + 1);
            return;
        }
        MobEffect effect = ForgeRegistries.MOB_EFFECTS.getValue(key);
        if (effect != null) {
            TextureAtlasSprite sprite = minecraft.getMobEffectTextures().get(effect);
            graphics.blit(sx + 2, sy + 2, 0, 18, 18, sprite);
        }
    }

    private void renderPanel(GuiGraphics graphics) {
        graphics.fill(panelLeft - 1, panelTop - 1,
                panelLeft + PANEL_W + 1, panelTop + PANEL_H + 1, 0xFF11151A);
        graphics.fill(panelLeft, panelTop,
                panelLeft + PANEL_W, panelTop + PANEL_H, 0xF020252B);
        graphics.fill(panelLeft, panelTop,
                panelLeft + PANEL_W, panelTop + 3, 0xFF4E9BD6);
        graphics.fill(panelLeft + 8, panelTop + 72,
                panelLeft + PANEL_W - 8, panelTop + 170, 0x7012171C);
        int searchBorder = searchBox != null && searchBox.isFocused() ? 0xFF62B5E5 : 0xFF46535F;
        graphics.fill(panelLeft + 11, panelTop + 51,
                panelLeft + PANEL_W - 11, panelTop + 69, searchBorder);
        graphics.fill(panelLeft + 12, panelTop + 52,
                panelLeft + PANEL_W - 12, panelTop + 68, 0xFF171C21);
        if (mode != AutoEatMode.STACK) {
            int underlineX = tab == Tab.FOOD ? panelLeft + 12 : panelLeft + PANEL_W - 116;
            graphics.fill(underlineX, panelTop + 45, underlineX + 104, panelTop + 47, 0xFF62B5E5);
        }
    }

    private String titleText() {
        if (mode == AutoEatMode.STACK && ClientState.selectedItem != null) {
            Item selected = ForgeRegistries.ITEMS.getValue(ClientState.selectedItem);
            String name = selected == null ? ClientState.selectedItem.toString()
                    : selected.getDescription().getString();
            return Component.translatable("rsi.autoeat.title.select", name).getString();
        }
        if (mode == AutoEatMode.STACK) {
            return Component.translatable("rsi.autoeat.btn.select").getString();
        }
        return Component.translatable(tab == Tab.FOOD
                ? "rsi.autoeat.title.food_blacklist"
                : "rsi.autoeat.title.effect_blacklist").getString();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (searchBox.mouseClicked(mouseX, mouseY, button)) {
            setFocused(searchBox);
            return true;
        }
        if (searchBox.isFocused()) searchBox.setFocused(false);
        if (button == 0) {
            int index = getSlotAt(mouseX, mouseY);
            if (index >= 0) {
                ResourceLocation key = filteredEntries.get(index);
                if (mode == AutoEatMode.STACK) {
                    ClientState.selectedItem = key;
                } else {
                    Set<ResourceLocation> blacklist = activeBlacklist();
                    boolean wasBlacklisted = blacklist.contains(key);
                    if (wasBlacklisted) blacklist.remove(key);
                    else blacklist.add(key);
                    dragging = true;
                    dragAdd = !wasBlacklisted;
                    toggledThisDrag.clear();
                    toggledThisDrag.add(index);
                }
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (dragging && mode != AutoEatMode.STACK) {
            int index = getSlotAt(mouseX, mouseY);
            if (index >= 0 && toggledThisDrag.add(index)) {
                if (dragAdd) activeBlacklist().add(filteredEntries.get(index));
                else activeBlacklist().remove(filteredEntries.get(index));
            }
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (dragging) {
            dragging = false;
            toggledThisDrag.clear();
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public void onClose() {
        if (mode != AutoEatMode.STACK) {
            Set<ResourceLocation> addedItems = difference(itemBlacklist, originalItemBlacklist);
            Set<ResourceLocation> removedItems = difference(originalItemBlacklist, itemBlacklist);
            Set<ResourceLocation> addedEffects = difference(effectBlacklist, originalEffectBlacklist);
            Set<ResourceLocation> removedEffects = difference(originalEffectBlacklist, effectBlacklist);

            ClientState.blacklistedItems.clear();
            ClientState.blacklistedItems.addAll(itemBlacklist);
            ClientState.blacklistedEffects.clear();
            ClientState.blacklistedEffects.addAll(effectBlacklist);

            if (!addedItems.isEmpty() || !removedItems.isEmpty()
                    || !addedEffects.isEmpty() || !removedEffects.isEmpty()) {
                NetworkHandler.CHANNEL.sendToServer(new UpdateBlacklistPacket(
                        addedItems, removedItems, addedEffects, removedEffects));
            }
        }
        super.onClose();
    }

    private static Set<ResourceLocation> difference(Set<ResourceLocation> left,
                                                    Set<ResourceLocation> right) {
        Set<ResourceLocation> result = new HashSet<>(left);
        result.removeAll(right);
        return result;
    }

    private int getSlotAt(double mouseX, double mouseY) {
        int count = Math.min(entriesPerPage(), filteredEntries.size() - page * entriesPerPage());
        for (int local = 0; local < count; local++) {
            int x = entryX(local);
            int y = entryY(local);
            int entryWidth = tab == Tab.FOOD ? SLOT_SIZE : EFFECT_ENTRY_W;
            int entryHeight = tab == Tab.FOOD ? SLOT_SIZE : EFFECT_ENTRY_H;
            if (mouseX >= x && mouseX < x + entryWidth && mouseY >= y && mouseY < y + entryHeight) {
                return page * entriesPerPage() + local;
            }
        }
        return -1;
    }

    private static void drawBorder(GuiGraphics graphics, int x, int y,
                                   int width, int height, int color) {
        int right = x + width;
        int bottom = y + height;
        graphics.fill(x, y, right, y + 1, color);
        graphics.fill(x, bottom - 1, right, bottom, color);
        graphics.fill(x, y, x + 1, bottom, color);
        graphics.fill(right - 1, y, right, bottom, color);
    }

    private int entriesPerPage() {
        return tab == Tab.FOOD ? COLS * ROWS : EFFECT_COLS * EFFECT_ROWS;
    }

    private int entryX(int local) {
        if (tab == Tab.FOOD) return leftPos + (local % COLS) * SLOT_SIZE;
        int listWidth = EFFECT_COLS * EFFECT_ENTRY_W + (EFFECT_COLS - 1) * EFFECT_GAP;
        return panelLeft + (PANEL_W - listWidth) / 2
                + (local % EFFECT_COLS) * (EFFECT_ENTRY_W + EFFECT_GAP);
    }

    private int entryY(int local) {
        if (tab == Tab.FOOD) return topPos + (local / COLS) * SLOT_SIZE;
        return topPos + (local / EFFECT_COLS) * (EFFECT_ENTRY_H + EFFECT_GAP);
    }

    private boolean isMouseOverSlot(double mouseX, double mouseY, int x, int y) {
        int entryWidth = tab == Tab.FOOD ? SLOT_SIZE : EFFECT_ENTRY_W;
        int entryHeight = tab == Tab.FOOD ? SLOT_SIZE : EFFECT_ENTRY_H;
        return mouseX >= x && mouseX < x + entryWidth && mouseY >= y && mouseY < y + entryHeight;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static final class FlatButton extends AbstractButton {
        private final Runnable action;
        private final boolean primary;
        private boolean selected;

        private FlatButton(int x, int y, int width, int height, Component message,
                           Runnable action, boolean primary) {
            super(x, y, width, height, message);
            this.action = action;
            this.primary = primary;
        }

        @Override
        public void onPress() {
            action.run();
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            boolean highlighted = isHoveredOrFocused();
            int border = !active ? 0xFF343B42
                    : highlighted ? 0xFF70C4F0
                    : selected ? 0xFF62B5E5 : 0xFF46535F;
            int fill = !active ? 0xFF1C2227
                    : primary ? (highlighted ? 0xFF347EAA : 0xFF28698F)
                    : selected ? 0xFF244C66
                    : highlighted ? 0xFF333D46 : 0xFF252D34;
            graphics.fill(getX(), getY(), getX() + width, getY() + height, border);
            graphics.fill(getX() + 1, getY() + 1,
                    getX() + width - 1, getY() + height - 1, fill);
            int color = active ? 0xFFF2F6FA : 0xFF68727B;
            graphics.drawCenteredString(Minecraft.getInstance().font, getMessage(),
                    getX() + width / 2, getY() + (height - 8) / 2, color);
        }

        @Override
        protected void updateWidgetNarration(
                net.minecraft.client.gui.narration.NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }
}
