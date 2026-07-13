package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.crafting.tree.IngredientKey;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.util.UIRenderer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Map;

/**
 * Extracted rendering logic for {@code CraftingPlanScreen}.
 * Handles step cards, tree connectors, and the material area.
 *
 * <h3>Wiring status</h3>
 * <b>Partially wired</b> — {@code drawTreeConnector()} and
 * {@code renderMaterialArea()} are delegated from {@code CraftingPlanScreen}.
 * {@code drawStepCard()} remains inline in the screen because step cards
 * carry OR-hitbox state, grid layouts, and mod warnings that are tightly
 * coupled to screen-level fields ({@code altChoices, orHitboxes, orRendered}).
 * The engine's {@code drawStepCard()} serves as a reference for a simpler
 * card style that could be adopted after a screen refactor.
 *
 * <h3>Color theme</h3>
 * Emerald-green palette matching {@code CraftingPlanScreen}.
 */
@OnlyIn(Dist.CLIENT)
public final class PlanRenderEngine {
    // ── Color constants — emerald theme ──────────────────────────────
    public static final int CARD_BG          = 0xFF1A2A1A;
    public static final int CARD_BORDER      = 0xFF2D5A2D;
    public static final int CARD_HOVER_BG    = 0xFF1F3A1F;
    public static final int TREE_LINE        = 0xFF3A6A3A;
    public static final int STEP_NUMBER_BG   = 0xFF2D5A2D;
    public static final int STEP_NUMBER_TEXT = 0xFFE0FFE0;
    public static final int MATERIAL_SLOT_BG = 0xFF0D1A0D;
    public static final int TREE_LINE_TOP    = 0xB4334433;
    public static final int TREE_LINE_BOT    = 0x1E334433;

    private static final int CARD_HEIGHT = 28;
    private static final int SLOT_SIZE   = 18;

    // Material pill colors
    private static final int C_PILL_GREEN_BG  = 0xCC1B5E20;
    private static final int C_PILL_GREEN_FG  = 0xFFC8E6C9;
    private static final int C_PILL_RED_BG    = 0xCCB71C1C;
    private static final int C_PILL_RED_FG    = 0xFFFFCDD2;
    private static final int C_PILL_ORANGE_BG = 0xCCE65100;
    private static final int C_PILL_ORANGE_FG = 0xFFFFE0B2;
    private static final int C_GREEN  = 0xFF4AE04A;
    private static final int C_ORANGE = 0xFFFFAA33;
    private static final int C_RED    = 0xFFFF4444;

    private final Font font;
    private final PlanAnimationController animation = new PlanAnimationController();

    public PlanRenderEngine(Font font) {
        this.font = font;
    }

    public PlanAnimationController animation() {
        return animation;
    }

    // ── Step cards — reference implementation ────────────────────────

    /**
     * Draw a step card at the given position.
     * This is a simpler reference implementation. The production screen
     * uses an inline version with dynamic height, grid layouts, OR badges,
     * and mod warnings that depend on screen-level state.
     */
    public void drawStepCard(GuiGraphics gfx, int x, int y, int width,
                              PlanStep step, int stepIndex,
                              boolean hovered, boolean selected) {
        int bgColor = selected ? CARD_HOVER_BG : hovered ? CARD_BORDER : CARD_BG;
        int alpha = (int) (animation.getAlpha(stepIndex) * 255);
        int slideX = animation.getSlideOffset(stepIndex, 40);

        int cardX = x + slideX;
        int cardW = width;

        gfx.fill(cardX, y, cardX + cardW, y + CARD_HEIGHT, bgColor | (alpha << 24));
        gfx.fill(cardX, y, cardX + 3, y + CARD_HEIGHT, CARD_BORDER | (alpha << 24));

        int numX = cardX + 8;
        int numY = y + 5;
        gfx.fill(numX - 1, numY - 1, numX + 17, numY + 17, STEP_NUMBER_BG | (alpha << 24));
        String num = String.valueOf(stepIndex + 1);
        gfx.drawString(font, num,
                numX + 9 - font.width(num) / 2,
                numY + 9 - font.lineHeight / 2,
                STEP_NUMBER_TEXT | (alpha << 24));

        ItemStack output = step.output();
        if (output != null && !output.isEmpty()) {
            gfx.renderItem(output, cardX + 30, y + 6);
        }

        String displayName = formatRecipeName(step.recipeId());
        if (displayName != null) {
            int maxNameW = cardW - 80;
            String truncated = font.plainSubstrByWidth(displayName, maxNameW);
            gfx.drawString(font, truncated, cardX + 50, y + 4,
                    0xFFFFFFFF | (alpha << 24));
        }

        if (step.batches() > 1) {
            String batch = "×" + step.batches();
            int batchW = font.width(batch);
            int batchX = cardX + cardW - batchW - 8;
            gfx.drawString(font, batch, batchX, y + 4,
                    0xFFAAAAAA | (alpha << 24));
        }

        ModType modType = step.modType();
        if (modType != null) {
            String modLabel = formatModTypeLabel(modType.id());
            gfx.drawString(font, modLabel, cardX + 50, y + 18,
                    0xFF6A8A6A | (alpha << 24));
        }
    }

    // ── Tree connector ────────────────────────────────────────────────

    /**
     * Draw a tree connector line between a parent card and its child.
     * Uses gradient vertical lines and a step-number circle at the branch point,
     * matching the production {@code CraftingPlanScreen} style.
     *
     * @param prevBottom Y of the previous card's bottom edge
     * @param currTop    Y of the current card area's top (after gap)
     * @param childX     X of the child card's left edge
     * @param parentX    X of the parent card's left edge
     * @param indent     the indentation step (e.g. {@code INDENT = 28})
     * @param stepIdx    zero-based index of the child step
     */
    public void drawTreeConnector(GuiGraphics gfx,
                                   int prevBottom, int currTop,
                                   int childX, int parentX,
                                   int indent, int stepIdx) {
        int stemX = childX - indent / 2;
        if (stemX < 4) stemX = 4;

        UIRenderer.vLineGradient(gfx, stemX, prevBottom, currTop, 2f,
                TREE_LINE_TOP, TREE_LINE_BOT);

        if (childX > stemX + 6) {
            int hY = currTop - 4;
            int branchSteps = Math.max(1, (childX - stemX) / 4);
            for (int i = 0; i < branchSteps; i++) {
                float t = (float) i / branchSteps;
                int segX = (int) (stemX + t * (childX - stemX));
                int nextX = (int) (stemX + (t + 1f / branchSteps) * (childX - stemX));
                int c = UIRenderer.mix(TREE_LINE_TOP, TREE_LINE_BOT, t * 0.5f);
                gfx.fill(segX, hY, nextX + 1, hY + 2, c);
            }
        }

        int circleX = stemX + 1;
        int circleY = currTop - 6;
        int circleR = 6;
        UIRenderer.rounded(gfx, circleX - circleR, circleY - circleR,
                circleR * 2f, circleR * 2f, circleR, 0xFF1A2A1A);
        UIRenderer.rounded(gfx, circleX - circleR + 1, circleY - circleR + 1,
                circleR * 2f - 2, circleR * 2f - 2, circleR - 1, 0xFF2A4A2A);

        String num = String.valueOf(stepIdx + 1);
        gfx.drawString(font, num,
                circleX - font.width(num) / 2,
                circleY - font.lineHeight / 2,
                0xFF88BB88);
    }

    // ── Material area ─────────────────────────────────────────────────

    /**
     * Render the material requirements area with availability-colored pill badges.
     * The grid is capped to the panel height and scrolls vertically; returns the maximum
     * scroll offset (px) so the caller can clamp its stored {@code scrollOffset}.
     */
    public int renderMaterialArea(GuiGraphics gfx, int left, int top,
                                   int contentW, int areaHeight,
                                   Map<IngredientKey, PlanResponse.Availability> materials,
                                   int mouseX, int mouseY, int scrollOffset,
                                   MaterialTooltipSink tooltipSink) {
        UIRenderer.roundedGradient(gfx, left, top, contentW, areaHeight, 6f,
                0xE6141E18, 0xE6101814);
        gfx.fill(left + 1, top + 2, left + 4, top + areaHeight - 2, 0xFF44AA66);

        int y = top + 4;
        String hdr = Component.translatable("rsi.plan.materials").getString();
        UIRenderer.textBackdrop(gfx, font, left + 10, y, hdr, 0xAA0A0A0A);
        gfx.drawString(font, hdr, left + 10, y, 0xFFCCCCCC);
        y += font.lineHeight + 4;

        int maxPillW = 0;
        String[][] countPairs = new String[materials.size()][];
        int pi = 0;
        for (var entry : materials.entrySet()) {
            String availStr = formatCompact(entry.getValue().available());
            String needStr = formatCompact(entry.getValue().needed());
            countPairs[pi++] = new String[]{availStr, needStr};
            int w = font.width(availStr) + font.width("/") + font.width(needStr) + 14;
            if (w > maxPillW) maxPillW = w;
        }
        int cellW = SLOT_SIZE + maxPillW + 10;
        int cols = Math.max(1, (contentW - 16) / cellW);
        int rowStride = SLOT_SIZE + 8;

        int gridTop = y;
        int gridBottom = top + areaHeight;
        int visibleGridH = gridBottom - gridTop;
        int rows = (int) Math.ceil((double) materials.size() / cols);
        int contentGridH = rows * rowStride;
        int maxScroll = Math.max(0, contentGridH - visibleGridH);
        scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset));

        int cx = left + 8;
        int cy = gridTop - scrollOffset;
        int col = 0;

        gfx.enableScissor(left, gridTop, left + contentW, gridBottom);
        try {
            pi = 0;
            for (var entry : materials.entrySet()) {
                // Skip rows fully scrolled out of the visible band.
                if (cy + SLOT_SIZE < gridTop || cy > gridBottom) {
                    pi++;
                    col++;
                    cx += cellW;
                    if (col >= cols) { col = 0; cx = left + 8; cy += rowStride; }
                    continue;
                }

                int needed = entry.getValue().needed();
                int have = entry.getValue().available();
                int border = have >= needed ? C_GREEN : (have > 0 ? C_ORANGE : C_RED);

                ItemStack stack = entry.getKey().stack(1);
                UIRenderer.slotBg(gfx, cx, cy, SLOT_SIZE, border);
                gfx.renderItem(stack, cx + 1, cy + 1);
                gfx.renderItemDecorations(font, stack, cx + 1, cy + 1);

                if (tooltipSink != null
                        && mouseY >= gridTop && mouseY <= gridBottom
                        && mouseX >= cx - 1 && mouseX <= cx + SLOT_SIZE + 1
                        && mouseY >= cy - 1 && mouseY <= cy + SLOT_SIZE + 1) {
                    tooltipSink.setHoveredItem(stack, mouseX, mouseY, have, needed);
                }

                String[] pair = countPairs[pi++];
                String pillText = pair[0] + "/" + pair[1];
                int pillW = font.width(pillText) + 14;
                int pillH = font.lineHeight + 4;
                int pillX = cx + SLOT_SIZE + 4;
                int pillY = cy + SLOT_SIZE / 2 - pillH / 2;

                int pillBg, pillFg;
                if (have >= needed) {
                    pillBg = C_PILL_GREEN_BG; pillFg = C_PILL_GREEN_FG;
                } else if (have > 0) {
                    pillBg = C_PILL_ORANGE_BG; pillFg = C_PILL_ORANGE_FG;
                } else {
                    pillBg = C_PILL_RED_BG; pillFg = C_PILL_RED_FG;
                }
                UIRenderer.pillBadge(gfx, font, pillX, pillY, pillW, pillH, pillBg, pillFg, pillText);

                col++;
                cx += cellW;
                if (col >= cols) {
                    col = 0;
                    cx = left + 8;
                    cy += rowStride;
                }
            }
        } finally {
            gfx.disableScissor();
        }

        return maxScroll;
    }

    // ── Material tooltip callback ─────────────────────────────────────

    @FunctionalInterface
    public interface MaterialTooltipSink {
        void setHoveredItem(ItemStack stack, int mouseX, int mouseY, int available, int needed);
    }

    // ── Formatting helpers ────────────────────────────────────────────

    public static String formatRecipeName(ResourceLocation recipeId) {
        if (recipeId == null) return "Unknown";
        String path = recipeId.getPath();
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        StringBuilder sb = new StringBuilder();
        boolean capitalize = true;
        for (char c : name.toCharArray()) {
            if (c == '_') {
                sb.append(' ');
                capitalize = true;
            } else if (capitalize) {
                sb.append(Character.toUpperCase(c));
                capitalize = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Resolve a human-readable label for a mod type.
     * Uses the game's existing localisation when available —
     * either from {@code rsi.batch.mod.<id>} translation keys
     * or the mod's own display name from {@code mods.toml}.
     */
    private static final java.util.Map<String, String> MOD_LABEL_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    public static String formatModTypeLabel(String modTypeId) {
        String cached = MOD_LABEL_CACHE.get(modTypeId);
        if (cached != null) return cached;

        // 1. Explicit translation key (optional override)
        String key = "rsi.batch.mod." + modTypeId;
        if (net.minecraft.client.resources.language.I18n.exists(key)) {
            String label = net.minecraft.client.resources.language.I18n.get(key);
            MOD_LABEL_CACHE.put(modTypeId, label);
            return label;
        }
        // 2. Forge mod display name
        String forgeName = net.minecraftforge.fml.ModList.get()
                .getModContainerById(modTypeId)
                .map(c -> c.getModInfo().getDisplayName())
                .orElse("");
        if (!forgeName.isEmpty()) {
            MOD_LABEL_CACHE.put(modTypeId, forgeName);
            return forgeName;
        }
        // 3. Fallback
        String fallback = capitalizeWords(modTypeId.replace('_', ' '));
        MOD_LABEL_CACHE.put(modTypeId, fallback);
        return fallback;
    }

    private static String capitalizeWords(String input) {
        StringBuilder sb = new StringBuilder();
        boolean cap = true;
        for (char c : input.toCharArray()) {
            sb.append(cap ? Character.toUpperCase(c) : c);
            cap = (c == ' ');
        }
        return sb.toString();
    }

    public static String formatCompact(int n) {
        if (n < 1000) return String.valueOf(n);
        if (n < 10000) return String.format("%.1fK", n / 1000.0);
        if (n < 1000000) return (n / 1000) + "K";
        return String.format("%.1fM", n / 1000000.0);
    }
}
