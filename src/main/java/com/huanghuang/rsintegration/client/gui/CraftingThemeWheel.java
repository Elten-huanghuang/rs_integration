package com.huanghuang.rsintegration.client.gui;

import com.huanghuang.rsintegration.config.CraftingUiTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/** 主题卡围成一圈；中央显示名称与翻页，不用大色块遮住整个界面。 */
public final class CraftingThemeWheel {
    public static final int PAGE_SIZE = 8;
    public static final int PAGE_COUNT = (CraftingUiTheme.values().length + PAGE_SIZE - 1) / PAGE_SIZE;
    public record Card(int x, int y, int width, int height) {
        public boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
        }
    }

    public record Layout(int x, int y, int radiusX, int radiusY, int cardWidth, int cardHeight) {
        public static Layout forViewport(int width, int height) {
            boolean compact = width < 400 || height < 220;
            int cw = compact ? 78 : 96, ch = compact ? 28 : 36;
            return new Layout(width / 2, height / 2, Math.min(170, Math.max(20, width / 2 - cw / 2 - 10)),
                    Math.min(106, Math.max(20, height / 2 - ch / 2 - 32)), cw, ch);
        }
        private double angle(int index) {
            int count = Math.min(PAGE_SIZE, CraftingUiTheme.values().length - index / PAGE_SIZE * PAGE_SIZE);
            return index % PAGE_SIZE * Math.PI * 2 / count - Math.PI / 2;
        }
        public int pointX(int index) { return x + (int) Math.round(Math.cos(angle(index)) * radiusX); }
        public int pointY(int index) { return y + (int) Math.round(Math.sin(angle(index)) * radiusY); }
        public Card card(int index) { return new Card(pointX(index) - cardWidth / 2, pointY(index) - cardHeight / 2, cardWidth, cardHeight); }
        public int themeAt(double mouseX, double mouseY, int page) {
            for (int i = page * PAGE_SIZE; i < Math.min(CraftingUiTheme.values().length, (page + 1) * PAGE_SIZE); i++) {
                if (card(i).contains(mouseX, mouseY)) return i;
            }
            return -1;
        }
        public Card previousPage() { return new Card(x - 36, y + 10, 14, 13); }
        public Card nextPage() { return new Card(x + 22, y + 10, 14, 13); }
        public Card pageIndicator(int page) { return new Card(x - PAGE_COUNT * 8 + page * 16 + 2, y + 10, 12, 13); }
        public int pageAt(double mouseX, double mouseY) {
            for (int page = 0; page < PAGE_COUNT; page++) {
                if (pageIndicator(page).contains(mouseX, mouseY)) return page;
            }
            return -1;
        }
        public int pageStepAt(double mouseX, double mouseY) {
            return previousPage().contains(mouseX, mouseY) ? -1 : nextPage().contains(mouseX, mouseY) ? 1 : 0;
        }
    }

    private Layout layout = Layout.forViewport(320, 180);
    public Layout layout() { return layout; }
    public void resize(int width, int height) { layout = Layout.forViewport(width, height); }

    public void render(GuiGraphics graphics, Font font, int width, int height, CraftingUiTheme.Scope scope,
                       int highlighted, CraftingUiTheme current, boolean saveFailed, int mouseX, int mouseY) {
        resize(width, height);
        CraftingUiTheme[] themes = CraftingUiTheme.values();
        int page = highlighted / PAGE_SIZE;
        int first = page * PAGE_SIZE, end = Math.min(themes.length, first + PAGE_SIZE);
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 1000);
        graphics.drawManaged(() -> {
            graphics.fill(0, 0, width, height, 0xB00C111A);
            for (int i = 0; i < 300; i++) {
                double angle = i * Math.PI / 150;
                int px = layout.x + (int) Math.round(Math.cos(angle) * layout.radiusX);
                int py = layout.y + (int) Math.round(Math.sin(angle) * layout.radiusY);
                graphics.fill(px, py, px + 1, py + 1, 0x806B8195);
            }
            for (int i = first; i < end; i++) {
                Card card = layout.card(i);
                boolean focus = i == highlighted;
                box(graphics, card, focus ? 0xFF344252 : 0xF0202B39,
                        focus ? 0xFFF0D499 : themes[i] == current ? 0xFF9BBECA : 0xFF4C5D70);
                int sampleWidth = card.width / 2, sampleX = card.x + (card.width - sampleWidth) / 2;
                int sampleY = card.y + 5, sampleHeight = card.height < 36 ? 6 : 9;
                graphics.fill(sampleX, sampleY, sampleX + sampleWidth, sampleY + sampleHeight, themes[i].swatch());
                graphics.fill(sampleX, sampleY, sampleX + 3, sampleY + sampleHeight, themes[i].swatchAccent());
                graphics.fill(sampleX + 7, sampleY + 2, sampleX + sampleWidth - 5, sampleY + 3, themes[i].swatchAccent());
                if (themes[i] == current) {
                    graphics.fill(card.x + card.width - 7, card.y + 4, card.x + card.width - 4, card.y + 7, 0xFFF0D499);
                }
            }
            int ruleY = layout.y + 6;
            graphics.fill(layout.x - 21, ruleY, layout.x + 21, ruleY + 1, 0x505D738B);
            chevron(graphics, layout.x - 29, layout.y + 16, -1,
                    layout.previousPage().contains(mouseX, mouseY) ? 0xFFF0D499 : 0xFF9AAFC5);
            chevron(graphics, layout.x + 29, layout.y + 16, 1,
                    layout.nextPage().contains(mouseX, mouseY) ? 0xFFF0D499 : 0xFF9AAFC5);
            for (int p = 0; p < PAGE_COUNT; p++) {
                Card indicator = layout.pageIndicator(p);
                int cx = indicator.x + indicator.width / 2;
                int color = p == page ? 0xFFF0D499 : indicator.contains(mouseX, mouseY) ? 0xFF9AAFC5 : 0xFF536981;
                int halfWidth = p == page ? 4 : 1;
                graphics.fill(cx - halfWidth, layout.y + 15, cx + halfWidth + 1, layout.y + 18, color);
            }
        });
        for (int i = first; i < end; i++) {
            Card card = layout.card(i);
            String name = CraftingUiStyle.fit(font, CraftingThemeButton.themeName(themes[i]).getString(), card.width - 10);
            centered(graphics, font, name, card.x + card.width / 2, card.y + card.height - 12,
                    i == highlighted ? 0xFFFFE6B1 : 0xFFDFE9F3);
        }
        var lines = font.split(CraftingThemeButton.themeName(themes[highlighted]), 74);
        int lineCount = Math.min(2, lines.size());
        int textY = layout.y - (lineCount > 1 ? 17 : 8);
        for (int i = 0; i < lineCount; i++) {
            var line = lines.get(i);
            graphics.drawString(font, line, layout.x - font.width(line) / 2, textY + i * (font.lineHeight + 1), 0xFFF7E4BD, false);
        }
        centered(graphics, font, Component.translatable(scope == CraftingUiTheme.Scope.TREE
                        ? "rsi.theme.tree" : "rsi.theme.monitor").getString(), width / 2, Math.max(4,
                layout.y - layout.radiusY - layout.cardHeight / 2 - 20), 0xFFF4F0E8);
        String hint = Component.translatable(saveFailed ? "rsi.theme.save_failed_short" : "rsi.theme.wheel_hint").getString();
        centered(graphics, font, CraftingUiStyle.fit(font, hint, width - 12), width / 2,
                Math.min(height - font.lineHeight - 4, layout.y + layout.radiusY + layout.cardHeight / 2 + 17),
                saveFailed ? 0xFFFFAEB8 : 0xFFB5C6D9);
        graphics.pose().popPose();
    }

    private static void box(GuiGraphics g, Card c, int fill, int border) {
        g.fill(c.x + 2, c.y, c.x + c.width - 2, c.y + 1, border);
        g.fill(c.x + 2, c.y + c.height - 1, c.x + c.width - 2, c.y + c.height, border);
        g.fill(c.x, c.y + 2, c.x + 1, c.y + c.height - 2, border);
        g.fill(c.x + c.width - 1, c.y + 2, c.x + c.width, c.y + c.height - 2, border);
        g.fill(c.x + 1, c.y + 1, c.x + c.width - 1, c.y + c.height - 1, fill);
    }

    private static void chevron(GuiGraphics g, int x, int y, int direction, int color) {
        for (int row = -3; row <= 3; row++) {
            int px = x + direction * (3 - Math.abs(row));
            g.fill(px, y + row, px + 2, y + row + 1, color);
        }
    }

    private static void centered(GuiGraphics g, Font font, String value, int x, int y, int color) {
        g.drawString(font, value, x - font.width(value) / 2, y, color, false);
    }
}
