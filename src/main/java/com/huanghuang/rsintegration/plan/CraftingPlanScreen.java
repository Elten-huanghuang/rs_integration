package com.huanghuang.rsintegration.plan;

import com.huanghuang.rsintegration.batch.BatchCraftNetworkHandler;
import com.huanghuang.rsintegration.batch.GenericCraftPacket;
import com.huanghuang.rsintegration.batch.ModType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.*;

@OnlyIn(Dist.CLIENT)
public final class CraftingPlanScreen extends Screen {

    private static final int SLOT_SIZE = 18;
    private static final int CARD_PAD = 6;
    private static final int ARROW_W = 16;
    private static final int STEPS_TOP = 36;
    private static final int INDENT = 22;
    private static final int CONNECTOR_GAP = 16;

    // Colors
    private static final int C_GREEN = 0xFF33AA33;
    private static final int C_RED = 0xFFAA3333;
    private static final int C_ORANGE = 0xFFAA8833;
    private static final int C_BG = 0xC0101010;
    private static final int C_CARD = 0xC0202020;
    private static final int C_MULTIBLOCK_CARD = 0xC0181828;
    private static final int C_MODTAG_BG = 0xCC333366;
    private static final int C_MODTAG_TEXT = 0xFFAAAACC;
    private static final int C_TREE_LINE = 0xFF555555;
    private static final int C_OR_BADGE = 0xCC886622;
    private static final int C_OR_TEXT = 0xFFFFCC66;

    private final PlanResponse plan;
    private int scrollOffset;
    private int maxScroll;
    private boolean dragging;
    private int missingAreaTop;
    private int missingAreaHeight;
    private int materialAreaTop;
    private int materialAreaHeight;

    // OR-path selection state
    private record AltChoice(ResourceLocation recipeId, String modTypeId) {}

    private final Map<String, List<AltChoice>> altChoices = new LinkedHashMap<>();
    private final Map<String, Integer> altSelection = new LinkedHashMap<>();
    private final List<ORHitbox> orHitboxes = new ArrayList<>();

    private record ORHitbox(int x, int y, int w, int h, String itemKey, int altIndex) {}

    protected CraftingPlanScreen(PlanResponse plan) {
        super(Component.translatable("rsi.plan.title", plan.targetName()));
        this.plan = plan;
    }

    @Override
    protected void init() {
        super.init();
        Font font = minecraft.font;
        int contentW = width - 40;

        // Compute missing items area
        int missingCount = plan.missing() != null ? plan.missing().size() : 0;
        if (missingCount > 0) {
            missingAreaHeight = font.lineHeight + 6 + missingCount * (font.lineHeight + 4) + 4;
        } else {
            missingAreaHeight = 0;
        }

        // Compute material grid layout — measure widest count string first
        int maxCountW = font.width("0/0");
        for (var entry : plan.materials().entrySet()) {
            String s = entry.getValue().available() + "/" + entry.getValue().needed();
            int w = font.width(s);
            if (w > maxCountW) maxCountW = w;
        }
        int matCols = Math.max(1, contentW / (SLOT_SIZE + maxCountW + 12));
        int matRows = (int) Math.ceil((double) plan.materials().size() / matCols);
        int matGridH = matRows * (SLOT_SIZE + 6) + 4;
        // Header "Materials:" + grid + padding
        materialAreaHeight = (plan.materials().isEmpty() ? 0 : font.lineHeight + 6 + matGridH + 8);
        // Stack: buttons at bottom, then materials, then missing, then steps
        materialAreaTop = height - 28 - materialAreaHeight;
        missingAreaTop = materialAreaTop - missingAreaHeight;

        int btnW = 80;
        int btnY = height - 24;

        addRenderableWidget(Button.builder(
                        Component.translatable("rsi.plan.confirm"),
                        btn -> onConfirm())
                .pos(width / 2 - btnW - 10, btnY)
                .size(btnW, 20)
                .build());

        addRenderableWidget(Button.builder(
                        Component.translatable("rsi.plan.cancel"),
                        btn -> onClose())
                .pos(width / 2 + 10, btnY)
                .size(btnW, 20)
                .build());
    }

    private void onConfirm() {
        if (plan.recipeId() != null && !plan.recipeId().isEmpty()) {
            ResourceLocation id = ResourceLocation.tryParse(plan.recipeId());
            if (id != null) {
                // Pass execution routing for mod recipes
                ResourceLocation execDim = null;
                net.minecraft.core.BlockPos execPos = null;
                if (plan.executionDim() != null && !plan.executionDim().isEmpty()) {
                    execDim = ResourceLocation.tryParse(plan.executionDim());
                    execPos = new net.minecraft.core.BlockPos(
                            plan.executionPosX(), plan.executionPosY(), plan.executionPosZ());
                }
                BatchCraftNetworkHandler.CHANNEL.sendToServer(
                        new GenericCraftPacket(id, false, Collections.emptyMap(), execDim, execPos));
            }
        }
        onClose();
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        renderBackground(gfx);
        super.render(gfx, mouseX, mouseY, partialTick);

        Font font = minecraft.font;
        int contentW = width - 40;
        int left = 20;

        // Title
        gfx.drawCenteredString(font, title, width / 2, 10, 0xFFFFFF);

        // Subtitle: result + status
        String statusKey = plan.success() ? "rsi.plan.status_ok" : "rsi.plan.status_fail";
        int statusColor = plan.success() ? 0x55FF55 : 0xFF5555;
        gfx.drawCenteredString(font, Component.translatable(statusKey), width / 2, 22, statusColor);

        // Steps area (scrollable) — reserve space for missing + material area
        int areaTop = STEPS_TOP;
        int bottomReserved = missingAreaHeight > 0 ? missingAreaTop : materialAreaTop;
        int areaBottom = bottomReserved - 4;
        if (missingAreaHeight == 0 && materialAreaHeight == 0) areaBottom = height - 30;

        gfx.enableScissor(left, areaTop, left + contentW, areaBottom);
        try {
            orHitboxes.clear();

            int y = areaTop - scrollOffset;
            int stepIdx = 0;

            // Draw target recipe first
            y = drawStepCard(gfx, font, left + 4, y, contentW - 8,
                    plan.targetResult(), plan.targetResult().getHoverName().getString(),
                    null, -1, stepIdx++);
            int prevCardBottom = y;
            int prevStepX = left + 4;

            // Draw intermediate steps
            for (PlanStep step : plan.steps()) {
                int stepX = left + 4 + step.depth() * INDENT;
                int cardW = contentW - 8 - step.depth() * INDENT;

                y += CONNECTOR_GAP;
                drawTreeConnector(gfx, prevCardBottom, y, stepX, prevStepX);

                int cardTop = y;
                y = drawStepCard(gfx, font, stepX, cardTop, cardW,
                        step.output(), step.output().getHoverName().getString(),
                        step, step.batches(), stepIdx++);

                prevCardBottom = y;
                prevStepX = stepX;
            }

            maxScroll = Math.max(0, y - areaBottom + scrollOffset);
        } finally {
            gfx.disableScissor();
        }

        // Missing items (unresolvable ingredients / errors)
        if (missingAreaHeight > 0 && plan.missing() != null && !plan.missing().isEmpty()) {
            gfx.fill(left, missingAreaTop, left + contentW, missingAreaTop + missingAreaHeight, C_BG);
            int my = missingAreaTop + 4;
            gfx.drawString(font, Component.translatable("rsi.plan.missing_header"),
                    left + 6, my, 0xFF6666);
            my += font.lineHeight + 4;
            for (String msg : plan.missing()) {
                gfx.drawString(font, "  " + msg, left + 6, my, 0xFF8888);
                my += font.lineHeight + 4;
            }
        }

        // Material summary at bottom
        if (materialAreaHeight > 0) {
            renderMaterialArea(gfx, font, left, materialAreaTop, contentW);
        }
    }

    private static final int GRID_SLOT = 18;
    private static final int GRID_GAP = 1;

    private int drawStepCard(GuiGraphics gfx, Font font, int x, int y, int cardW,
                              ItemStack output, String outputName,
                              PlanStep step, int batches, int idx) {
        boolean isMultiblock = step != null
                && step.modType() != null
                && step.modType() != ModType.GENERIC;
        boolean isGrid = step != null && step.recipeWidth() > 0 && step.recipeHeight() > 0;

        int gridW = isGrid ? step.recipeWidth() * (GRID_SLOT + GRID_GAP) - GRID_GAP : 0;
        int gridH = isGrid ? step.recipeHeight() * (GRID_SLOT + GRID_GAP) - GRID_GAP : 0;

        int cardH = SLOT_SIZE + CARD_PAD * 2 + font.lineHeight + 4;
        if (step != null) {
            if (isGrid) {
                cardH = Math.max(cardH, CARD_PAD * 2 + gridH + font.lineHeight + 4);
                if (isMultiblock) cardH += font.lineHeight + 4;
            } else {
                int inputCols = Math.max(1, Math.min(step.inputs().size(), 9));
                int inputRows = (int) Math.ceil((double) step.inputs().size() / inputCols);
                cardH += (SLOT_SIZE + 2) * Math.max(1, inputRows) + 4;
                if (isMultiblock) cardH += font.lineHeight + 4;
            }
        }
        // Reserve space for OR alternative badges
        int orBadgeH = (step != null && step.hasOrSiblings()) ? font.lineHeight + 8 : 0;
        cardH += orBadgeH;

        // Card background
        gfx.fill(x, y, x + cardW, y + cardH, isMultiblock ? C_MULTIBLOCK_CARD : C_CARD);

        int cx = x + CARD_PAD;
        int cy = y + CARD_PAD;

        // Mod type tag for multi-block steps
        if (isMultiblock) {
            String modLabel = Component.translatable(
                    "rsi.batch.mod." + step.modType().id()).getString();
            int labelW = font.width(modLabel) + 8;
            gfx.fill(cx, cy, cx + labelW, cy + font.lineHeight + 4, C_MODTAG_BG);
            gfx.drawString(font, modLabel, cx + 4, cy + 2, C_MODTAG_TEXT);
            cy += font.lineHeight + 8;
        }

        // Batch label
        if (batches > 1) {
            gfx.drawString(font, batches + "x", cx, cy, 0xAAAAAA);
            cx += font.width(batches + "x") + 4;
        }

        if (step != null && !step.inputs().isEmpty()) {
            if (isGrid) {
                // ── 3×3 / 2×2 / 1×N crafting grid ─────────────────
                int gridLeft = cx;
                int gridTop = cy;
                // Draw grid slots in correct positions (row-major from recipe)
                for (int i = 0; i < step.inputs().size(); i++) {
                    int col = i % step.recipeWidth();
                    int row = i / step.recipeWidth();
                    int sx = gridLeft + col * (GRID_SLOT + GRID_GAP);
                    int sy = gridTop + row * (GRID_SLOT + GRID_GAP);
                    drawGridSlot(gfx, font, sx, sy, step.inputs().get(i), step.batches());
                }
                // Draw empty placeholder slots for the remaining grid cells
                int totalCells = step.recipeWidth() * step.recipeHeight();
                for (int i = step.inputs().size(); i < totalCells; i++) {
                    int col = i % step.recipeWidth();
                    int row = i / step.recipeWidth();
                    int sx = gridLeft + col * (GRID_SLOT + GRID_GAP);
                    int sy = gridTop + row * (GRID_SLOT + GRID_GAP);
                    drawGridPlaceholder(gfx, sx, sy);
                }
                cx = gridLeft + gridW + ARROW_W;
                cy = gridTop + gridH / 2 - SLOT_SIZE / 2;
            } else {
                // ── Linear layout (multi-block / non-shaped recipes) ─
                int cols = Math.min(step.inputs().size(), 9);
                for (int i = 0; i < step.inputs().size(); i++) {
                    ItemStack in = step.inputs().get(i);
                    int sx = cx + (i % cols) * (SLOT_SIZE + 2);
                    int sy = cy + (i / cols) * (SLOT_SIZE + 2);
                    drawSlot(gfx, font, sx, sy, in, step.batches(), true);
                }
                cx += cols * (SLOT_SIZE + 2) + ARROW_W;
            }
        }

        // Arrow
        gfx.drawCenteredString(font, "→", cx, cy + SLOT_SIZE / 2 - font.lineHeight / 2, 0xFFCC66);
        cx += ARROW_W;

        // Output slot
        int outAmount = batches > 0 ? output.getCount() * batches : output.getCount();
        drawSlot(gfx, font, cx, cy, output, outAmount, false);

        // Output name (truncate if too long)
        int nameMaxW = x + cardW - cx - SLOT_SIZE - 8;
        String displayName = outputName;
        if (font.width(outputName) > nameMaxW) {
            int w = 0;
            StringBuilder sb = new StringBuilder();
            for (char c : outputName.toCharArray()) {
                int cw = font.width(String.valueOf(c));
                if (w + cw + font.width("...") > nameMaxW) break;
                w += cw;
                sb.append(c);
            }
            displayName = sb.toString() + "...";
        }
        gfx.drawString(font, displayName, cx + SLOT_SIZE + 6, cy + 4, 0xCCCCCC);

        // ── OR alternative badges at card bottom (recipe-level: different machines / inputs) ──
        if (orBadgeH > 0) {
            String itemKey = BuiltInRegistries.ITEM.getKey(step.output().getItem()).toString();

            // Build the union of all alternatives for this output item,
            // merging across multiple PlanSteps that produce the same item.
            List<AltChoice> choices = altChoices.computeIfAbsent(itemKey, k -> new ArrayList<>());

            String curModType = step.modType() != null ? step.modType().id() : "minecraft:crafting";
            AltChoice curChoice = new AltChoice(step.recipeId(), curModType);
            if (!choices.contains(curChoice)) choices.add(curChoice);

            for (int i = 0; i < step.alternatives().size() && i < step.alternativeModTypes().size(); i++) {
                AltChoice ac = new AltChoice(step.alternatives().get(i), step.alternativeModTypes().get(i));
                if (!choices.contains(ac)) choices.add(ac);
            }

            for (PlanStep other : plan.steps()) {
                if (other == step) continue;
                if (!ItemStack.isSameItem(step.output(), other.output())) continue;
                String otherModType = other.modType() != null ? other.modType().id() : "minecraft:crafting";
                AltChoice ac = new AltChoice(other.recipeId(), otherModType);
                if (!choices.contains(ac)) choices.add(ac);
            }

            int curIdx = altSelection.getOrDefault(itemKey, 0);
            if (curIdx >= choices.size()) curIdx = 0;

            int badgeY = y + cardH - font.lineHeight - 6;
            int badgeX = x + CARD_PAD;

            for (int i = 0; i < choices.size(); i++) {
                AltChoice ac = choices.get(i);
                String modLabel = Component.translatable("rsi.batch.mod." + ac.modTypeId).getString();
                int bw = font.width(modLabel) + 8;
                if (badgeX + bw > x + cardW - CARD_PAD) break;

                boolean isSelected = (i == curIdx);
                int bg = isSelected ? C_OR_BADGE : 0xCC333333;
                int fg = isSelected ? C_OR_TEXT : 0xFF888888;

                gfx.fill(badgeX, badgeY, badgeX + bw, badgeY + font.lineHeight + 4, bg);
                gfx.drawString(font, modLabel, badgeX + 4, badgeY + 2, fg);

                // All badges get hitboxes — clicking selected one reverts to default
                orHitboxes.add(new ORHitbox(badgeX, badgeY, bw, font.lineHeight + 4,
                        itemKey, i));
                badgeX += bw + 3;
            }
        }

        return y + cardH + 6;
    }

    /** Slot with availability border for items inside the crafting grid. */
    private void drawGridSlot(GuiGraphics gfx, Font font, int x, int y,
                               ItemStack stack, int needed) {
        if (stack.isEmpty()) {
            // Empty grid cell (shaped recipe hole) — render as distinct placeholder
            gfx.fill(x - 1, y - 1, x + GRID_SLOT + 1, y + GRID_SLOT + 1, 0xFF444444);
            gfx.fill(x, y, x + GRID_SLOT, y + GRID_SLOT, 0xFF333333);
            return;
        }

        int avail = 0;
        PlanResponse.Availability a = plan.materials().get(stack.getItem());
        if (a != null) avail = a.available();

        int border;
        if (avail >= needed) {
            border = C_GREEN;
        } else if (avail > 0) {
            border = C_ORANGE;
        } else {
            border = C_RED;
        }

        gfx.fill(x - 1, y - 1, x + GRID_SLOT + 1, y + GRID_SLOT + 1, border);
        gfx.fill(x, y, x + GRID_SLOT, y + GRID_SLOT, 0xFF8B8B8B);
        gfx.renderItem(stack, x + 1, y + 1);

        if (needed > 1) {
            gfx.pose().pushPose();
            gfx.pose().translate(0, 0, 200);
            String txt = String.valueOf(needed);
            gfx.drawString(font, txt, x + GRID_SLOT - font.width(txt),
                    y + GRID_SLOT - font.lineHeight + 2, 0xFFFFFF);
            gfx.pose().popPose();
        } else {
            gfx.renderItemDecorations(font, stack, x + 1, y + 1);
        }
    }

    /** Empty grid cell placeholder. */
    private void drawGridPlaceholder(GuiGraphics gfx, int x, int y) {
        gfx.fill(x, y, x + GRID_SLOT, y + GRID_SLOT, 0xFF555555);
    }

    private void drawTreeConnector(GuiGraphics gfx, int prevBottom, int currTop, int childX, int parentX) {
        int stemX = childX - INDENT / 2;
        if (stemX < 4) stemX = 4;
        // Vertical line
        gfx.fill(stemX, prevBottom, stemX + 1, currTop, C_TREE_LINE);
        // Horizontal branch to child card
        if (childX > stemX + 4) {
            int hY = currTop - 3;
            gfx.fill(stemX, hY, childX, hY + 1, C_TREE_LINE);
        }
    }

    private void selectAlternative(String itemKey, int index) {
        List<AltChoice> choices = altChoices.get(itemKey);
        if (choices == null || index < 0 || index >= choices.size()) return;
        int oldIdx = altSelection.getOrDefault(itemKey, 0);
        if (oldIdx == index) {
            // Clicking the already-selected alternative reverts to default
            if (index != 0) {
                altSelection.put(itemKey, 0);
            } else {
                return; // already on default, no-op
            }
        } else {
            altSelection.put(itemKey, index);
        }

        Map<String, String> forced = new HashMap<>();
        for (var e : altSelection.entrySet()) {
            if (e.getValue() == 0) continue;
            List<AltChoice> cl = altChoices.get(e.getKey());
            if (cl != null && e.getValue() < cl.size()) {
                forced.put(e.getKey(), cl.get(e.getValue()).recipeId().toString());
            }
        }
        // Always send — empty forced map means "revert to server default"
        ResourceLocation rid = ResourceLocation.tryParse(plan.recipeId());
        if (rid != null) {
            BatchCraftNetworkHandler.CHANNEL.sendToServer(
                    new GenericCraftPacket(rid, true, forced));
        }
    }

    private void drawSlot(GuiGraphics gfx, Font font, int x, int y,
                           ItemStack stack, int needed, boolean isInput) {
        // Border color based on availability
        int avail = 0;
        PlanResponse.Availability a = plan.materials().get(stack.getItem());
        if (a != null) avail = a.available();
        // For output slots, always green-ish
        int border;
        if (!isInput) {
            border = C_GREEN;
        } else if (avail >= needed) {
            border = C_GREEN;
        } else if (avail > 0) {
            border = C_ORANGE;
        } else {
            border = C_RED;
        }

        gfx.fill(x - 1, y - 1, x + SLOT_SIZE + 1, y + SLOT_SIZE + 1, border);
        gfx.fill(x, y, x + SLOT_SIZE, y + SLOT_SIZE, 0xFF8B8B8B);

        gfx.renderItem(stack, x + 1, y + 1);

        if (needed > 1) {
            gfx.pose().pushPose();
            gfx.pose().translate(0, 0, 200);
            String txt = String.valueOf(needed);
            gfx.drawString(font, txt, x + SLOT_SIZE - font.width(txt), y + SLOT_SIZE - font.lineHeight + 2, 0xFFFFFF);
            gfx.pose().popPose();
        } else {
            gfx.renderItemDecorations(font, stack, x + 1, y + 1);
        }
    }

    private void renderMaterialArea(GuiGraphics gfx, Font font, int left, int top, int contentW) {
        // Background
        gfx.fill(left, top, left + contentW, top + materialAreaHeight, C_BG);

        // Header
        int y = top + 4;
        gfx.drawString(font, Component.translatable("rsi.plan.materials"), left + 6, y, 0xCCCCCC);
        y += font.lineHeight + 4;

        // Compute grid: measure widest count string so cells don't overlap
        int maxCountW = font.width("0/0");
        for (var entry : plan.materials().entrySet()) {
            String s = entry.getValue().available() + "/" + entry.getValue().needed();
            int w = font.width(s);
            if (w > maxCountW) maxCountW = w;
        }
        int cellW = SLOT_SIZE + maxCountW + 12;
        int cols = Math.max(1, contentW / cellW);
        int cx = left + 6;
        int cy = y;
        int col = 0;

        gfx.enableScissor(left, top, left + contentW, top + materialAreaHeight);
        try {
            for (var entry : plan.materials().entrySet()) {
                int needed = entry.getValue().needed();
                int have = entry.getValue().available();
                int border = have >= needed ? C_GREEN : (have > 0 ? C_ORANGE : C_RED);

                // Slot background
                gfx.fill(cx - 1, cy - 1, cx + SLOT_SIZE + 1, cy + SLOT_SIZE + 1, border);
                gfx.fill(cx, cy, cx + SLOT_SIZE, cy + SLOT_SIZE, 0xFF8B8B8B);
                gfx.renderItem(new ItemStack(entry.getKey()), cx + 1, cy + 1);

                // Count: have/needed
                String countStr = have + "/" + needed;
                int textColor = have >= needed ? 0x55FF55 : (have > 0 ? 0xFFAA33 : 0xFF5555);
                gfx.drawString(font, countStr, cx + SLOT_SIZE + 4, cy + SLOT_SIZE / 2 - font.lineHeight / 2, textColor);

                col++;
                cx += cellW;
                if (col >= cols) {
                    col = 0;
                    cx = left + 6;
                    cy += SLOT_SIZE + 6;
                }
            }
        } finally {
            gfx.disableScissor();
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset - (int) delta * 10));
        return true;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragging) {
            scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset - (int) dy));
        }
        return true;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            // Check OR badge clicks — hitboxes are in screen coordinates
            if (my >= STEPS_TOP) {
                for (ORHitbox hb : orHitboxes) {
                    if (mx >= hb.x && mx <= hb.x + hb.w
                            && my >= hb.y && my <= hb.y + hb.h) {
                        selectAlternative(hb.itemKey, hb.altIndex);
                        return true;
                    }
                }
            }
            dragging = true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        dragging = false;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
