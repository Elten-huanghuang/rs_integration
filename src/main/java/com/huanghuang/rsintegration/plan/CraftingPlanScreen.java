package com.huanghuang.rsintegration.plan;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.batch.BatchCraftNetworkHandler;
import com.huanghuang.rsintegration.batch.GenericCraftPacket;
import com.huanghuang.rsintegration.batch.ModType;
import com.huanghuang.rsintegration.util.UIRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.*;

@OnlyIn(Dist.CLIENT)
public final class CraftingPlanScreen extends Screen {

    private static final int SLOT_SIZE = 18;
    private static final int CARD_PAD = 8;
    private static final int ARROW_W = 18;
    private static final int STEPS_TOP = 40;
    private static final int INDENT = 28;
    private static final int CONNECTOR_GAP = 18;

    // ── New color palette ────────────────────────────────────────
    private static final int C_GREEN       = 0xFF4AE04A;
    private static final int C_RED         = 0xFFFF4444;
    private static final int C_ORANGE      = 0xFFFFAA33;
    private static final int C_BG          = 0xCC0D0D0D;
    private static final int C_MODTAG_BG   = 0xCC338855;
    private static final int C_MODTAG_TEXT = 0xFFCCFFDD;
    private static final int C_TREE_LINE   = 0xFF334433;
    private static final int C_ARROW       = 0xFF44CC88;
    private static final int C_NAME_TEXT   = 0xFFBBCCBB;
    private static final int C_BATCH_TEXT  = 0xFF99AA99;

    private final PlanResponse plan;
    private int scrollOffset;
    private int maxScroll;
    private boolean dragging;
    private int missingAreaTop;
    private int missingAreaHeight;
    private int materialAreaTop;
    private int materialAreaHeight;
    private int ticksOpen;
    private int mouseX, mouseY;

    // OR-path selection state — equality is by recipeId only, modTypeId is
    // purely cosmetic (badge color).
    private static final class AltChoice {
        final ResourceLocation recipeId;
        final String modTypeId;

        AltChoice(ResourceLocation recipeId, String modTypeId) {
            this.recipeId = recipeId;
            this.modTypeId = modTypeId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof AltChoice that)) return false;
            return recipeId.equals(that.recipeId);
        }

        @Override
        public int hashCode() { return recipeId.hashCode(); }
    }

    private static final Map<String, String> LAST_FORCED = new HashMap<>();

    private final Map<String, List<AltChoice>> altChoices = new LinkedHashMap<>();
    private final Map<String, Integer> altSelection = new LinkedHashMap<>();
    private final List<ORHitbox> orHitboxes = new ArrayList<>();
    private final Set<String> orRendered = new HashSet<>();

    private record ORHitbox(int x, int y, int w, int h, String itemKey, int altIndex) {}

    protected CraftingPlanScreen(PlanResponse plan) {
        super(Component.translatable("rsi.plan.title", plan.targetName()));
        this.plan = plan;
    }

    @Override
    protected void init() {
        super.init();
        altChoices.clear();
        Font font = minecraft.font;
        int contentW = width - 40;

        // Compute missing items area
        int missingCount = plan.missing() != null ? plan.missing().size() : 0;
        if (missingCount > 0) {
            missingAreaHeight = font.lineHeight + 6 + missingCount * (font.lineHeight + 4) + 4;
        } else {
            missingAreaHeight = 0;
        }

        // Compute material grid layout
        int maxCountW = font.width("0/0");
        for (var entry : plan.materials().entrySet()) {
            String s = entry.getValue().available() + "/" + entry.getValue().needed();
            int w = font.width(s);
            if (w > maxCountW) maxCountW = w;
        }
        int matCols = Math.max(1, contentW / (SLOT_SIZE + maxCountW + 14));
        int matRows = (int) Math.ceil((double) plan.materials().size() / matCols);
        int matGridH = matRows * (SLOT_SIZE + 8) + 4;
        materialAreaHeight = (plan.materials().isEmpty() ? 0 : font.lineHeight + 6 + matGridH + 8);
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
        this.mouseX = mouseX;
        this.mouseY = mouseY;
        ticksOpen++;
        float fade = Math.min(1f, ticksOpen / 8f);
        float ease = UIRenderer.easeOutCubic(fade);

        renderBackground(gfx);
        super.render(gfx, mouseX, mouseY, partialTick);

        Font font = minecraft.font;
        int contentW = width - 40;
        int left = 20;

        // Title — slide in from top
        int titleY = (int) (12 - (1f - ease) * 20);
        gfx.drawCenteredString(font, title, width / 2, titleY, fadeColor(0xFFFFFF, ease));

        // Subtitle
        String statusKey = plan.success() ? "rsi.plan.status_ok" : "rsi.plan.status_fail";
        int statusColor = plan.success() ? 0x55FF55 : 0xFF5555;
        gfx.drawCenteredString(font, Component.translatable(statusKey),
                width / 2, titleY + 12, fadeColor(statusColor, ease));

        // Steps area (scrollable)
        int areaTop = STEPS_TOP;
        int bottomReserved = missingAreaHeight > 0 ? missingAreaTop : materialAreaTop;
        int areaBottom = bottomReserved - 4;
        if (missingAreaHeight == 0 && materialAreaHeight == 0) areaBottom = height - 30;

        gfx.enableScissor(left, areaTop, left + contentW, areaBottom);
        try {
            orHitboxes.clear();
            orRendered.clear();

            int y = areaTop - scrollOffset;
            int stepIdx = 0;

            // Target recipe — full-width, no indent
            int cardW0 = contentW - 8;
            y = drawStepCard(gfx, font, left + 4, y, cardW0,
                    plan.targetResult(), plan.targetResult().getHoverName().getString(),
                    null, -1, stepIdx++, 0f);
            int prevCardBottom = y;
            int prevStepX = left + 4;

            // Intermediate steps — staggered fade-in
            for (int i = 0; i < plan.steps().size(); i++) {
                PlanStep step = plan.steps().get(i);
                int stepX = left + 4 + step.depth() * INDENT;
                int cardW = contentW - 8 - step.depth() * INDENT;

                y += CONNECTOR_GAP;
                drawTreeConnector(gfx, prevCardBottom, y, stepX, prevStepX, i);

                // Staggered animation: each card fades in 3 ticks after the previous
                float cardFade = Math.min(1f, Math.max(0f,
                        (ticksOpen - 5f - i * 3f) / 6f));

                y = drawStepCard(gfx, font, stepX, y, cardW,
                        step.output(), step.output().getHoverName().getString(),
                        step, step.batches(), stepIdx++, cardFade);

                prevCardBottom = y;
                prevStepX = stepX;
            }

            maxScroll = Math.max(0, y - areaBottom + scrollOffset);
        } finally {
            gfx.disableScissor();
        }

        // Missing items
        if (missingAreaHeight > 0 && plan.missing() != null && !plan.missing().isEmpty()) {
            renderMissingArea(gfx, font, left, missingAreaTop, contentW);
        }

        // Material summary
        if (materialAreaHeight > 0) {
            renderMaterialArea(gfx, font, left, materialAreaTop, contentW);
        }
    }

    // ── Step card ─────────────────────────────────────────────────

    private static final int GRID_SLOT = 18;
    private static final int GRID_GAP = 1;

    private int drawStepCard(GuiGraphics gfx, Font font, int x, int y, int cardW,
                              ItemStack output, String outputName,
                              PlanStep step, int batches, int idx, float fade) {
        boolean isMultiblock = step != null
                && step.modType() != null
                && step.modType() != ModType.GENERIC;
        boolean isGrid = step != null && step.recipeWidth() > 0 && step.recipeHeight() > 0;

        int gridW = isGrid ? step.recipeWidth() * (GRID_SLOT + GRID_GAP) - GRID_GAP : 0;
        int gridH = isGrid ? step.recipeHeight() * (GRID_SLOT + GRID_GAP) - GRID_GAP : 0;

        // Calculate card height
        int cardH = SLOT_SIZE + CARD_PAD * 2 + font.lineHeight + 4;
        if (step != null) {
            if (isGrid) {
                cardH = Math.max(cardH, CARD_PAD * 2 + gridH + font.lineHeight + 4);
                if (isMultiblock) cardH += font.lineHeight + 4;
            } else {
                int inputCols = Math.max(1, Math.min(step.inputs().size(), 9));
                int inputRows = (int) Math.ceil((double) step.inputs().size() / inputCols);
                cardH += (SLOT_SIZE + 3) * Math.max(1, inputRows) + 4;
                if (isMultiblock) cardH += font.lineHeight + 4;
            }
        }
        int orBadgeH = (step != null && !step.alternatives().isEmpty()) ? font.lineHeight + 10 : 0;
        cardH += orBadgeH;

        // ── Card background ───────────────────────────────────
        if (fade < 1f) {
            gfx.pose().pushPose();
            gfx.pose().translate(0, (1f - fade) * 12f, 0);
        }

        int accent = isMultiblock ? accentColor(step.modType().id()) : 0xFF44AA66;
        // Multiblock cards get a slightly deeper green tint
        if (isMultiblock) {
            UIRenderer.roundedGradient(gfx, x, y, cardW, cardH, 8f, 0xE61A221E, 0xE6141A16);
            // Top highlight
            UIRenderer.rounded(gfx, x + 2, y + 1, cardW - 4, 1f, 4f, 0x18FFFFFF);
            // Bottom shadow
            gfx.fill(x + 8, y + cardH - 2, x + cardW - 8, y + cardH, 0x22000000);
            // Left accent bar
            gfx.fill(x + 1, y + 2, x + 4, y + cardH - 2, accent);
            gfx.fill(x + 1, y + 2, x + 4, y + 3, 0x44FFFFFF);
        } else {
            UIRenderer.card(gfx, x, y, cardW, cardH, 8f, accent);
        }

        int cx = x + CARD_PAD;
        int cy = y + CARD_PAD;

        // ── Mod type tag ──────────────────────────────────────
        if (isMultiblock) {
            String modLabel = Component.translatable(
                    "rsi.batch.mod." + step.modType().id()).getString();
            int labelW = font.width(modLabel) + 12;
            UIRenderer.pill(gfx, cx, cy, labelW, font.lineHeight + 4, fade, true);
            gfx.drawString(font, modLabel, cx + 6, cy + 2, C_MODTAG_TEXT);
            cy += font.lineHeight + 8;
        }

        // ── Batch count ───────────────────────────────────────
        if (batches > 1) {
            String batchStr = batches + "x";
            gfx.drawString(font, batchStr, cx, cy + SLOT_SIZE / 2 - font.lineHeight / 2, C_BATCH_TEXT);
            cx += font.width(batchStr) + 6;
        }

        // ── Input slots ───────────────────────────────────────
        if (step != null && !step.inputs().isEmpty()) {
            if (isGrid) {
                int gridLeft = cx;
                int gridTop = cy;
                for (int i = 0; i < step.inputs().size(); i++) {
                    int col = i % step.recipeWidth();
                    int row = i / step.recipeWidth();
                    int sx = gridLeft + col * (GRID_SLOT + GRID_GAP);
                    int sy = gridTop + row * (GRID_SLOT + GRID_GAP);
                    drawGridSlot(gfx, font, sx, sy, step.inputs().get(i), step.batches());
                }
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
                int cols = Math.min(step.inputs().size(), 9);
                for (int i = 0; i < step.inputs().size(); i++) {
                    ItemStack in = step.inputs().get(i);
                    int sx = cx + (i % cols) * (SLOT_SIZE + 3);
                    int sy = cy + (i / cols) * (SLOT_SIZE + 3);
                    drawSlot(gfx, font, sx, sy, in, step.batches(), true);
                }
                cx += cols * (SLOT_SIZE + 3) + ARROW_W;
            }
        }

        // ── Arrow ─────────────────────────────────────────────
        int arrowCx = cx - ARROW_W / 2;
        int arrowCy = cy + SLOT_SIZE / 2;
        UIRenderer.chevron(gfx, arrowCx - 2, arrowCy, C_ARROW);

        // ── Output slot ───────────────────────────────────────
        int outAmount = batches > 0 ? output.getCount() * batches : output.getCount();
        drawSlot(gfx, font, cx, cy, output, outAmount, false);

        // ── Output name ───────────────────────────────────────
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
            displayName = sb + "...";
        }
        gfx.drawString(font, displayName, cx + SLOT_SIZE + 6, cy + 6, C_NAME_TEXT);

        // ── OR alternative badges ─────────────────────────────
        if (orBadgeH > 0 && orRendered.add(BuiltInRegistries.ITEM.getKey(step.output().getItem()).toString())) {
            String itemKey = BuiltInRegistries.ITEM.getKey(step.output().getItem()).toString();
            List<AltChoice> choices = altChoices.computeIfAbsent(itemKey, k -> new ArrayList<>());

            String curModType = step.modType() != null ? step.modType().id() : "generic";
            AltChoice curChoice = new AltChoice(step.recipeId(), curModType);
            if (!choices.contains(curChoice)) choices.add(curChoice);

            for (int i = 0; i < step.alternatives().size() && i < step.alternativeModTypes().size(); i++) {
                AltChoice ac = new AltChoice(step.alternatives().get(i), step.alternativeModTypes().get(i));
                if (!choices.contains(ac)) choices.add(ac);
            }

            for (PlanStep other : plan.steps()) {
                if (other == step) continue;
                if (!ItemStack.isSameItem(step.output(), other.output())) continue;
                String otherModType = other.modType() != null ? other.modType().id() : "generic";
                AltChoice ac = new AltChoice(other.recipeId(), otherModType);
                if (!choices.contains(ac)) choices.add(ac);
            }

            int curIdx = altSelection.getOrDefault(itemKey, 0);
            String persisted = LAST_FORCED.get(itemKey);
            if (persisted != null) {
                for (int i = 0; i < choices.size(); i++) {
                    if (choices.get(i).recipeId.toString().equals(persisted)) {
                        curIdx = i;
                        altSelection.put(itemKey, i);
                        break;
                    }
                }
            }
            if (curIdx >= choices.size()) curIdx = 0;

            int badgeY = y + cardH - font.lineHeight - 7;
            int badgeX = x + CARD_PAD;
            int badgeH = font.lineHeight + 4;

            for (int i = 0; i < choices.size(); i++) {
                AltChoice ac = choices.get(i);
                String label = ac.recipeId.getPath();
                int bw = font.width(label) + 10;
                if (badgeX + bw > x + cardW - CARD_PAD) break;

                boolean isSelected = (i == curIdx);
                int bg = isSelected ? badgeColor(ac.modTypeId) : 0x88333333;
                int fg = isSelected ? 0xFFFFFFFF : 0xFF999999;

                // Pill badge with full rounding
                UIRenderer.pillBadge(gfx, font, badgeX, badgeY, bw, badgeH, bg, fg, label);

                // Tooltip on hover
                if (mouseX >= badgeX && mouseX <= badgeX + bw
                        && mouseY >= badgeY && mouseY <= badgeY + badgeH) {
                    String tip = ac.recipeId.toString() + "  [" + ac.modTypeId + "]";
                    gfx.renderTooltip(font, Component.literal(tip), mouseX, mouseY);
                }

                orHitboxes.add(new ORHitbox(badgeX, badgeY, bw, badgeH, itemKey, i));
                badgeX += bw + 4;
            }
        }

        if (fade < 1f) gfx.pose().popPose();

        return y + cardH + 8;
    }

    // ── Grid slot ─────────────────────────────────────────────────

    private void drawGridSlot(GuiGraphics gfx, Font font, int x, int y,
                               ItemStack stack, int needed) {
        if (stack.isEmpty()) {
            gfx.fill(x - 1, y - 1, x + GRID_SLOT + 1, y + GRID_SLOT + 1, 0xFF3A3A3A);
            gfx.fill(x, y, x + GRID_SLOT, y + GRID_SLOT, 0xFF2A2A2A);
            return;
        }

        int avail = 0;
        PlanResponse.Availability a = plan.materials().get(stack.getItem());
        if (a != null) avail = a.available();

        int border = avail >= needed ? C_GREEN : (avail > 0 ? C_ORANGE : C_RED);

        UIRenderer.slotBg(gfx, x, y, GRID_SLOT, border);
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

    private void drawGridPlaceholder(GuiGraphics gfx, int x, int y) {
        gfx.fill(x - 1, y - 1, x + GRID_SLOT + 1, y + GRID_SLOT + 1, 0xFF3A3A3A);
        gfx.fill(x, y, x + GRID_SLOT, y + GRID_SLOT, 0xFF252525);
    }

    // ── Tree connector ────────────────────────────────────────────

    private void drawTreeConnector(GuiGraphics gfx, int prevBottom, int currTop,
                                    int childX, int parentX, int stepIdx) {
        int stemX = childX - INDENT / 2;
        if (stemX < 4) stemX = 4;
        int lineColor = C_TREE_LINE;

        // Vertical line — 2px wide
        gfx.fill(stemX, prevBottom, stemX + 2, currTop, lineColor);

        // Horizontal branch — 2px tall
        if (childX > stemX + 6) {
            int hY = currTop - 4;
            gfx.fill(stemX, hY, childX, hY + 2, lineColor);
        }

        // Step number circle at branch point
        int circleX = stemX + 1;
        int circleY = currTop - 6;
        int circleR = 6;
        UIRenderer.rounded(gfx, circleX - circleR, circleY - circleR,
                circleR * 2f, circleR * 2f, circleR, 0xFF223322);
        UIRenderer.rounded(gfx, circleX - circleR + 1, circleY - circleR + 1,
                circleR * 2f - 2, circleR * 2f - 2, circleR - 1, 0xFF335533);

        String num = String.valueOf(stepIdx + 1);
        Font font = minecraft.font;
        gfx.drawString(font, num,
                circleX - font.width(num) / 2,
                circleY - font.lineHeight / 2,
                0xFFAACCCC);
    }

    // ── Alternative selection ─────────────────────────────────────

    private void selectAlternative(String itemKey, int index) {
        List<AltChoice> choices = altChoices.get(itemKey);
        if (choices == null || index < 0 || index >= choices.size()) return;
        int oldIdx = altSelection.getOrDefault(itemKey, 0);
        String persisted = LAST_FORCED.get(itemKey);
        if (persisted != null && oldIdx == 0) {
            for (int i = 0; i < choices.size(); i++) {
                if (choices.get(i).recipeId.toString().equals(persisted)) {
                    oldIdx = i;
                    break;
                }
            }
        }
        RSIntegrationMod.LOGGER.info("[RSI-OR-UI] selectAlternative itemKey={} index={} oldIdx={} choices=[{}]",
                itemKey, index, oldIdx,
                choices.stream().map(c -> c.recipeId.toString() + ":" + c.modTypeId)
                        .reduce((a, b) -> a + ", " + b).orElse(""));
        if (oldIdx == index) {
            if (index != 0) {
                altSelection.put(itemKey, 0);
                RSIntegrationMod.LOGGER.info("[RSI-OR-UI]   reverting to default");
            } else {
                RSIntegrationMod.LOGGER.info("[RSI-OR-UI]   already default, no-op");
                return;
            }
        } else {
            altSelection.put(itemKey, index);
        }

        Map<String, String> forced = new HashMap<>();
        for (var e : altSelection.entrySet()) {
            if (e.getValue() == 0) continue;
            List<AltChoice> cl = altChoices.get(e.getKey());
            if (cl != null && e.getValue() < cl.size()) {
                forced.put(e.getKey(), cl.get(e.getValue()).recipeId.toString());
            }
        }
        LAST_FORCED.clear();
        LAST_FORCED.putAll(forced);
        RSIntegrationMod.LOGGER.info("[RSI-OR-UI]   sending forced={}", forced);
        ResourceLocation rid = ResourceLocation.tryParse(plan.recipeId());
        if (rid != null) {
            BatchCraftNetworkHandler.CHANNEL.sendToServer(
                    new GenericCraftPacket(rid, true, forced));
        }
    }

    // ── Slot drawing ──────────────────────────────────────────────

    private void drawSlot(GuiGraphics gfx, Font font, int x, int y,
                           ItemStack stack, int needed, boolean isInput) {
        int avail = 0;
        PlanResponse.Availability a = plan.materials().get(stack.getItem());
        if (a != null) avail = a.available();

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

        UIRenderer.slotBg(gfx, x, y, SLOT_SIZE, border);
        gfx.renderItem(stack, x + 1, y + 1);

        if (needed > 1) {
            gfx.pose().pushPose();
            gfx.pose().translate(0, 0, 200);
            String txt = String.valueOf(needed);
            gfx.drawString(font, txt, x + SLOT_SIZE - font.width(txt),
                    y + SLOT_SIZE - font.lineHeight + 2, 0xFFFFFF);
            gfx.pose().popPose();
        } else {
            gfx.renderItemDecorations(font, stack, x + 1, y + 1);
        }
    }

    // ── Material area ─────────────────────────────────────────────

    private void renderMaterialArea(GuiGraphics gfx, Font font, int left, int top, int contentW) {
        // Background with emerald left accent
        UIRenderer.roundedGradient(gfx, left, top, contentW, materialAreaHeight, 6f,
                0xE6141E18, 0xE6101814);
        // Left accent — emerald
        gfx.fill(left + 1, top + 2, left + 4, top + materialAreaHeight - 2, 0xFF44AA66);

        // Header
        int y = top + 4;
        gfx.drawString(font, Component.translatable("rsi.plan.materials"), left + 10, y, 0xFFCCCCCC);
        y += font.lineHeight + 4;

        int maxCountW = font.width("0/0");
        for (var entry : plan.materials().entrySet()) {
            String s = entry.getValue().available() + "/" + entry.getValue().needed();
            int w = font.width(s);
            if (w > maxCountW) maxCountW = w;
        }
        int cellW = SLOT_SIZE + maxCountW + 14;
        int cols = Math.max(1, contentW / cellW);
        int cx = left + 8;
        int cy = y;
        int col = 0;

        gfx.enableScissor(left, top, left + contentW, top + materialAreaHeight);
        try {
            for (var entry : plan.materials().entrySet()) {
                int needed = entry.getValue().needed();
                int have = entry.getValue().available();
                int border = have >= needed ? C_GREEN : (have > 0 ? C_ORANGE : C_RED);

                Item item = entry.getKey();
                ItemStack stack = new ItemStack(item);
                UIRenderer.slotBg(gfx, cx, cy, SLOT_SIZE, border);
                gfx.renderItem(stack, cx + 1, cy + 1);
                gfx.renderItemDecorations(font, stack, cx + 1, cy + 1);

                // Hover tooltip
                if (mouseX >= cx - 1 && mouseX <= cx + SLOT_SIZE + 1
                        && mouseY >= cy - 1 && mouseY <= cy + SLOT_SIZE + 1) {
                    gfx.renderTooltip(font, stack, mouseX, mouseY);
                }

                String countStr = have + "/" + needed;
                int textColor = have >= needed ? 0xFF55FF55 : (have > 0 ? 0xFFFFAA33 : 0xFFFF5555);
                gfx.drawString(font, countStr, cx + SLOT_SIZE + 4,
                        cy + SLOT_SIZE / 2 - font.lineHeight / 2, textColor);

                col++;
                cx += cellW;
                if (col >= cols) {
                    col = 0;
                    cx = left + 8;
                    cy += SLOT_SIZE + 8;
                }
            }
        } finally {
            gfx.disableScissor();
        }
    }

    // ── Missing area ──────────────────────────────────────────────

    private void renderMissingArea(GuiGraphics gfx, Font font, int left, int top, int contentW) {
        // Background — dark warm tone, red accent for warnings
        UIRenderer.roundedGradient(gfx, left, top, contentW, missingAreaHeight, 6f,
                0xE61E1814, 0xE6181410);
        // Left accent — red (warnings stay red)
        gfx.fill(left + 1, top + 2, left + 4, top + missingAreaHeight - 2, 0xFFAA4444);

        int my = top + 4;
        gfx.drawString(font, Component.translatable("rsi.plan.missing_header"),
                left + 10, my, 0xFFFF6666);
        my += font.lineHeight + 4;
        for (String msg : plan.missing()) {
            gfx.drawString(font, "  " + msg, left + 10, my, 0xFFCC8888);
            my += font.lineHeight + 4;
        }
    }

    // ── Scrolling ─────────────────────────────────────────────────

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

    // ── Color helpers ─────────────────────────────────────────────

    /** Left accent bar color per mod type. */
    private static int accentColor(String modTypeId) {
        return switch (modTypeId) {
            case "malum"            -> 0xFF6633AA;
            case "eidolon"          -> 0xFF338855;
            case "forbidden_arcanus" -> 0xFF994422;
            case "goety"            -> 0xFF334488;
            case "wizards_reborn"   -> 0xFF6655AA;
            default                 -> 0xFF44AA66;
        };
    }

    /** Badge fill color per mod type. */
    private static int badgeColor(String modTypeId) {
        return switch (modTypeId) {
            case "malum"            -> 0xCC442288;
            case "eidolon"          -> 0xCC226644;
            case "forbidden_arcanus" -> 0xCC663322;
            case "goety"            -> 0xCC222244;
            case "wizards_reborn"   -> 0xCC444466;
            default                 -> 0xCC886622;
        };
    }

    private static int fadeColor(int color, float alpha) {
        int a = (int) (0xFF * alpha);
        return (a << 24) | (color & 0x00FFFFFF);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
