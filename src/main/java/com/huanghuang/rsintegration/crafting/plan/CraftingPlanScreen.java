package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.batch.BatchCraftNetworkHandler;
import com.huanghuang.rsintegration.crafting.batch.GenericCraftPacket;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.sidepanel.RSSidePanelNetworkHandler;
import com.huanghuang.rsintegration.sidepanel.client.GuiNavStack;
import com.huanghuang.rsintegration.sidepanel.network.OpenBoundMachineGuiPacket;
import com.huanghuang.rsintegration.util.UIRenderer;
import com.huanghuang.rsintegration.util.ModIds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import org.lwjgl.glfw.GLFW;
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
    private static final int STEPS_TOP_MIN = 40;
    private int stepsTop = STEPS_TOP_MIN; // dynamic — grows when title wraps
    private static final int INDENT = 28;
    private static final int CONNECTOR_GAP = 18;

    // ── Color palette ────────────────────────────────────────────
    private static final int C_GREEN       = 0xFF4AE04A;
    private static final int C_RED         = 0xFFFF4444;
    private static final int C_ORANGE      = 0xFFFFAA33;
    private static final int C_BG          = 0xCC0D0D0D;
    private static final int C_MODTAG_BG   = 0xCC338855;
    private static final int C_MODTAG_TEXT = 0xFFCCFFDD;
    private static final int C_ARROW       = 0xFF44CC88;
    private static final int C_ARROW_DIM   = 0xFF334433;
    private static final int C_NAME_TEXT   = 0xFFBBCCBB;
    private static final int C_BATCH_TEXT  = 0xFF99AA99;
    // Status-driven accent bars
    private static final int C_ACCENT_READY   = 0xFF388E3C;
    private static final int C_ACCENT_MISSING = 0xFFD32F2F;
    private static final int C_ACCENT_NEUTRAL = 0xFF44AA66;
    // Text backdrop
    private static final int C_TEXT_BACKDROP  = 0xAA0A0A0A;
    // Slot hover brightening
    private static final int C_SLOT_HOVER = 0x80FFFFFF;
    private PlanResponse plan;
    private int currentRepeat = 1;
    private int scrollOffset;
    private int maxScroll;
    private boolean dragging;
    private int missingAreaTop;
    private int missingAreaHeight;
    private int materialAreaTop;
    private int materialAreaHeight;
    private int repeatRowY;
    // repeat button hitboxes — set during render
    private final int[] repeatBtnX = new int[8], repeatBtnY = new int[8];
    private final int[] repeatBtnW = new int[8], repeatBtnH = new int[8];
    private int countPillX, countPillY, countPillW, countPillH;
    private String repeatBuf = "1";
    private long lastKeyTime;
    private int planRefreshTick = -1;
    private int lastRefreshCount = 1;
    private int ticksOpen;
    private int mouseX, mouseY;

    private PlanRenderEngine renderEngine;

    private int embersPedestalY;
    private int embersPedestalH;
    private boolean embersInferMode;   // current mode toggle state
    private boolean showEmbersModeToggle; // config-gated: only when Calculate is enabled
    private int embersModeY;           // mode toggle row Y
    private int embersModeCalcX, embersModeCalcY, embersModeCalcW, embersModeCalcH;
    private int embersModeInferX, embersModeInferY, embersModeInferW, embersModeInferH;

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

    // Deferred tooltip — set during draw, rendered after all scissors disabled
    private ItemStack hoveredItemForTooltip = ItemStack.EMPTY;
    private int hoveredTooltipX, hoveredTooltipY;
    private int hoveredTooltipAvail, hoveredTooltipNeeded;

    protected CraftingPlanScreen(PlanResponse plan) {
        super(Component.translatable("rsi.plan.title",
                plan.targetResult().getHoverName().getString()));
        this.plan = plan;
        this.renderEngine = new PlanRenderEngine(Minecraft.getInstance().font);
    }

    /** Exposed for {@link PlanResponsePacket} dedup check. */
    public String getRecipeId() {
        return plan.recipeId();
    }

    /** Update plan data in-place (OR-path switch, repeat-count change, etc.).
     *  Avoids creating a new screen which would lose UI state. */
    public void updatePlan(PlanResponse newPlan) {
        this.plan = newPlan;
        this.renderEngine = new PlanRenderEngine(Minecraft.getInstance().font);
        this.altChoices.clear();
        this.orHitboxes.clear();
        this.altSelection.clear();
        this.clearWidgets();
        this.init();
    }

    @Override
    protected void init() {
        super.init();
        currentRepeat = Math.max(1, Math.min(plan.repeatCount(), 64));
        lastRefreshCount = currentRepeat;
        altChoices.clear();
        Font font = minecraft.font;
        int contentW = width - 40;

        // Start card-entry animation (target card + intermediate steps)
        renderEngine.animation().start(1 + plan.steps().size());

        // Embers alchemy pedestal layout height
        boolean hasEmbers = plan.embersCode() != null
                && plan.embersAspectNames() != null
                && plan.embersInputNames() != null;
        boolean canInfer = plan.embersCanInfer();
        boolean codeFromCache = plan.embersCodeFromCache();
        int embersCardsH;
        if (hasEmbers) {
            int pedestalCount = plan.embersCode().length;
            int cardsPerRow = Math.min(pedestalCount, 8);
            embersCardsH = font.lineHeight + 10 + 52 * ((pedestalCount + cardsPerRow - 1) / cardsPerRow) + 8;
        } else {
            embersCardsH = 0;
        }
        // Show mode toggle only when both modes are available (calc enabled AND tablet bound)
        int embersModeH = (showEmbersModeToggle = canInfer && RSIntegrationConfig.ENABLE_EMBERS_ALCHEMY_CALC.get()) ? 28 : 0;
        embersPedestalH = embersCardsH + embersModeH;
        // Default mode: Calculate if code is known (from cache or computed), Infer otherwise
        embersInferMode = !hasEmbers;

        // Compute missing items area — items flow inline with wrapping
        int missingCount = plan.missing() != null ? plan.missing().size() : 0;
        int modWarnCount = plan.modWarnings() != null ? plan.modWarnings().size() : 0;
        if (missingCount + modWarnCount > 0) {
            int lines = 0;
            int maxLineW = contentW - 24;
            if (missingCount > 0) {
                lines++; // header
                String joined = String.join(", ", plan.missing());
                lines += UIRenderer.wrapLines(font, "  " + joined, maxLineW).size();
            }
            if (modWarnCount > 0) {
                lines += modWarnCount;
            }
            missingAreaHeight = font.lineHeight + 6 + lines * (font.lineHeight + 4) + 4;
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
        int repeatAreaH = 38;
        materialAreaTop = height - 28 - repeatAreaH - materialAreaHeight;
        missingAreaTop = materialAreaTop - missingAreaHeight;
        repeatRowY = height - 28 - repeatAreaH;
        embersPedestalY = missingAreaHeight > 0 ? missingAreaTop - embersPedestalH
                : materialAreaTop - embersPedestalH;
        if (embersPedestalH == 0) embersPedestalY = missingAreaHeight > 0 ? missingAreaTop : materialAreaTop;
        embersModeY = embersPedestalY + embersCardsH;

        int btnW = 80;
        int btnY = height - 24;

        // "Open Machine" button — only when a bound machine position is known
        // AND the execution machine actually supports remote GUI.
        boolean hasMachineGui = plan.executionModTypeId() != null
                && plan.executionMachineSupportsGui();
        if (plan.executionDim() != null && !plan.executionDim().isEmpty() && hasMachineGui) {
            int openBtnW = 90;
            addRenderableWidget(Button.builder(
                            Component.translatable("rsi.plan.open_machine"),
                            btn -> onOpenMachine())
                    .pos(width / 2 - btnW - openBtnW - 20, btnY)
                    .size(openBtnW, 20)
                    .build());
        }

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
                int repeatCount = Math.max(1, Math.min(currentRepeat, 64));
                ResourceLocation execDim = null;
                net.minecraft.core.BlockPos execPos = null;
                if (plan.executionDim() != null && !plan.executionDim().isEmpty()) {
                    execDim = ResourceLocation.tryParse(plan.executionDim());
                    execPos = new net.minecraft.core.BlockPos(
                            plan.executionPosX(), plan.executionPosY(), plan.executionPosZ());
                }
                BatchCraftNetworkHandler.CHANNEL.sendToServer(
                        new GenericCraftPacket(id, false, Collections.emptyMap(), execDim, execPos, repeatCount, embersInferMode, plan.baseItem()));
            }
        }
        onClose();
    }

    private void onOpenMachine() {
        ResourceLocation dim = ResourceLocation.tryParse(plan.executionDim());
        if (dim == null) return;
        net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(
                plan.executionPosX(), plan.executionPosY(), plan.executionPosZ());
        ResourceLocation recipeId = ResourceLocation.tryParse(plan.recipeId());
        GuiNavStack.pushCurrent();
        RSSidePanelNetworkHandler.CHANNEL.sendToServer(
                new OpenBoundMachineGuiPacket(dim, pos, plan.recipeId(), recipeId, plan.baseItem()));
    }

    private void requestPlanRefresh() {
        if (currentRepeat == lastRefreshCount) return;
        lastRefreshCount = currentRepeat;
        planRefreshTick = ticksOpen + 10;
    }

    @Override
    public void tick() {
        super.tick();
        ticksOpen++;
        if (planRefreshTick >= 0 && ticksOpen >= planRefreshTick) {
            planRefreshTick = -1;
            ResourceLocation id = ResourceLocation.tryParse(plan.recipeId());
            if (id != null) {
                ResourceLocation execDim = null;
                net.minecraft.core.BlockPos execPos = null;
                if (plan.executionDim() != null && !plan.executionDim().isEmpty()) {
                    execDim = ResourceLocation.tryParse(plan.executionDim());
                    execPos = new net.minecraft.core.BlockPos(
                            plan.executionPosX(), plan.executionPosY(), plan.executionPosZ());
                }
                Map<String, String> forced = LAST_FORCED.isEmpty() ? Collections.emptyMap()
                        : new HashMap<>(LAST_FORCED);
                BatchCraftNetworkHandler.CHANNEL.sendToServer(
                        new GenericCraftPacket(id, true, forced, execDim, execPos, currentRepeat, false, plan.baseItem()));
            }
        }
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        this.mouseX = mouseX;
        this.mouseY = mouseY;
        hoveredItemForTooltip = ItemStack.EMPTY;
        float fade = Math.min(1f, ticksOpen / 8f);
        float ease = UIRenderer.easeOutCubic(fade);

        renderBackground(gfx);
        super.render(gfx, mouseX, mouseY, partialTick);

        Font font = minecraft.font;
        int contentW = width - 40;
        int left = 20;

        // Title — slide in from top, with frosted backdrop; wraps to multiple lines
        int titleY = (int) (12 - (1f - ease) * 20);
        int titleMaxW = width - 40;
        {
            var titleLines = UIRenderer.wrapLines(font, title.getString(), titleMaxW);
            int ty = titleY;
            for (String lineStr : titleLines) {
                UIRenderer.textBackdrop(gfx, font, width / 2 - font.width(lineStr) / 2, ty,
                        lineStr, fadeColor(C_TEXT_BACKDROP, ease));
                gfx.drawString(font, lineStr,
                        width / 2 - font.width(lineStr) / 2, ty, fadeColor(0xFFFFFF, ease));
                ty += font.lineHeight + 2;
            }
            // Subtitle — with backdrop
            String statusKey = plan.success() ? "rsi.plan.status_ok" : "rsi.plan.status_fail";
            int statusColor = plan.success() ? 0x55FF55 : 0xFF5555;
            String statusStr = font.plainSubstrByWidth(
                    Component.translatable(statusKey).getString(), titleMaxW);
            UIRenderer.textBackdrop(gfx, font, width / 2 - font.width(statusStr) / 2, ty,
                    statusStr, fadeColor(C_TEXT_BACKDROP, ease));
            gfx.drawString(font, statusStr,
                    width / 2 - font.width(statusStr) / 2, ty, fadeColor(statusColor, ease));
            ty += font.lineHeight + 4;
            stepsTop = Math.max(STEPS_TOP_MIN, ty);
        }

        // Steps area (scrollable)
        int areaTop = stepsTop;
        int bottomReserved = embersPedestalH > 0 ? embersPedestalY
                : (missingAreaHeight > 0 ? missingAreaTop : materialAreaTop);
        int areaBottom = bottomReserved - 4;
        if (embersPedestalH == 0 && missingAreaHeight == 0 && materialAreaHeight == 0) areaBottom = height - 30;

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
                    null, plan.repeatCount(), stepIdx, 0);
            stepIdx++;
            int prevCardBottom = y;
            int prevStepX = left + 4;

            // Intermediate steps — staggered fade-in
            for (int i = 0; i < plan.steps().size(); i++) {
                PlanStep step = plan.steps().get(i);
                int stepX = left + 4 + step.depth() * INDENT;
                int cardW = contentW - 8 - step.depth() * INDENT;

                y += CONNECTOR_GAP;
                renderEngine.drawTreeConnector(gfx, prevCardBottom, y, stepX, prevStepX, INDENT, i);

                int animIdx = i + 1;
                y = drawStepCard(gfx, font, stepX, y, cardW,
                        step.output(), step.output().getHoverName().getString(),
                        step, step.batches(), stepIdx, animIdx);
                stepIdx++;

                prevCardBottom = y;
                prevStepX = stepX;
            }

            maxScroll = Math.max(0, y - areaBottom + scrollOffset);
        } finally {
            gfx.disableScissor();
        }

        // Embers alchemy pedestal layout
        if (embersPedestalH > 0) {
            renderEmbersPedestalArea(gfx, font, left);
        }

        // Missing items + mod warnings
        if (missingAreaHeight > 0 && (
                (plan.missing() != null && !plan.missing().isEmpty()) ||
                (plan.modWarnings() != null && !plan.modWarnings().isEmpty()))) {
            renderMissingArea(gfx, font, left, missingAreaTop, contentW);
        }

        // Material summary
        if (materialAreaHeight > 0) {
            renderEngine.renderMaterialArea(gfx, left, materialAreaTop, contentW,
                    materialAreaHeight, plan.materials(), mouseX, mouseY,
                    (stack, mx, my, avail, need) -> {
                        hoveredItemForTooltip = stack;
                        hoveredTooltipX = mx;
                        hoveredTooltipY = my;
                        hoveredTooltipAvail = avail;
                        hoveredTooltipNeeded = need;
                    });
        }

        // ── Repeat count row ─────────────────────────────────────────
        drawRepeatRow(gfx, font);

        // Deferred tooltip — rendered AFTER all scissors, so Legendary
        // Tooltips' boundary avoidance works without scissor clipping.
        renderDeferredTooltip(gfx, font);
    }

    // ── Repeat count row ─────────────────────────────────────────────

    private void drawRepeatRow(GuiGraphics gfx, Font font) {
        String[] leftLabels = {"1", "-10", "-5", "-"};
        String[] rightLabels = {"+", "+5", "+10", "64"};

        int btnW = 22, btnH = 14;
        int gap = 3;
        String countStr = Integer.toString(currentRepeat);
        int pillW = Math.max(32, font.width(countStr) + 20);
        int pillH = font.lineHeight + 6;
        int innerGap = 12;

        int leftGroupW = leftLabels.length * btnW + (leftLabels.length - 1) * gap;
        int rightGroupW = rightLabels.length * btnW + (rightLabels.length - 1) * gap;
        int contentW = leftGroupW + innerGap + pillW + innerGap + rightGroupW;
        int rowW = contentW + 28;
        int rowX = width / 2 - rowW / 2;

        // ── Label row ──
        String label = Component.translatable("rsi.plan.repeat_count").getString();
        int labelW = font.width(label);
        UIRenderer.textBackdrop(gfx, font, rowX + (rowW - labelW) / 2, repeatRowY, label, C_TEXT_BACKDROP);
        gfx.drawString(font, label, rowX + (rowW - labelW) / 2, repeatRowY, 0xFFCCCCCC);

        // ── Button card ──
        int cardY = repeatRowY + font.lineHeight + 4;
        int cardH = 22;
        UIRenderer.roundedGradient(gfx, rowX, cardY, rowW, cardH, 5f, 0xE6141E18, 0xE6101814);
        gfx.fill(rowX + 1, cardY + 2, rowX + 4, cardY + cardH - 2, 0xFF44AA66);

        int startX = rowX + 14;
        int btnY = cardY + (cardH - btnH) / 2;

        // ── Left buttons: 1, -10, -5, - ──
        int bx = startX;
        for (int i = 0; i < leftLabels.length; i++) {
            repeatBtnX[i] = bx; repeatBtnY[i] = btnY; repeatBtnW[i] = btnW; repeatBtnH[i] = btnH;
            boolean hover = mouseX >= bx && mouseX <= bx + btnW && mouseY >= btnY && mouseY <= btnY + btnH;
            int bg = hover ? 0xAA338855 : 0x771A221E;
            int fg = hover ? 0xFFFFFFFF : 0xFF88AA88;
            UIRenderer.rounded(gfx, bx, btnY, btnW, btnH, 3f, bg);
            UIRenderer.rounded(gfx, bx + 1, btnY + 1, btnW - 2, btnH - 2, 2f, 0x881A221E);
            int tw = font.width(leftLabels[i]);
            gfx.drawString(font, leftLabels[i], bx + (btnW - tw) / 2, btnY + (btnH - font.lineHeight) / 2, fg);
            bx += btnW + gap;
        }

        // ── Count pill ──
        int pillX = bx + innerGap - gap;
        countPillX = pillX; countPillY = cardY + (cardH - pillH) / 2; countPillW = pillW; countPillH = pillH;
        UIRenderer.pillBadge(gfx, font, countPillX, countPillY, pillW, pillH,
                0xCC1B5E20, 0xFFC8E6C9, countStr);

        // ── Right buttons: +, +5, +10, 64 ──
        bx = pillX + pillW + innerGap - gap;
        for (int i = 0; i < rightLabels.length; i++) {
            int idx = leftLabels.length + i;
            repeatBtnX[idx] = bx; repeatBtnY[idx] = btnY; repeatBtnW[idx] = btnW; repeatBtnH[idx] = btnH;
            boolean hover = mouseX >= bx && mouseX <= bx + btnW && mouseY >= btnY && mouseY <= btnY + btnH;
            int bg = hover ? 0xAA338855 : 0x771A221E;
            int fg = hover ? 0xFFFFFFFF : 0xFF88AA88;
            UIRenderer.rounded(gfx, bx, btnY, btnW, btnH, 3f, bg);
            UIRenderer.rounded(gfx, bx + 1, btnY + 1, btnW - 2, btnH - 2, 2f, 0x881A221E);
            int tw = font.width(rightLabels[i]);
            gfx.drawString(font, rightLabels[i], bx + (btnW - tw) / 2, btnY + (btnH - font.lineHeight) / 2, fg);
            bx += btnW + gap;
        }
    }

    // ── Step card ─────────────────────────────────────────────────

    private static final int GRID_SLOT = 18;
    private static final int GRID_GAP = 1;

    private int drawStepCard(GuiGraphics gfx, Font font, int x, int y, int cardW,
                              ItemStack output, String outputName,
                              PlanStep step, int batches, int idx, int animIdx) {
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
        int warnH = (step != null && !step.warnings().isEmpty()) ? step.warnings().size() * (font.lineHeight + 3) + 6 : 0;
        cardH += warnH;

        // ── Card entrance animation — slide in from right ──────
        int slideX = renderEngine.animation().getSlideOffset(animIdx, 30);
        if (slideX != 0) {
            gfx.pose().pushPose();
            gfx.pose().translate(slideX, 0, 0);
        }

        int accent = step != null ? stepAccent(step, batches) : C_ACCENT_NEUTRAL;
        if (isMultiblock) {
            // Multiblock card — deeper emerald tint + status accent bar
            UIRenderer.roundedGradient(gfx, x, y, cardW, cardH, 8f, 0xE61A221E, 0xE6141A16);
            UIRenderer.rounded(gfx, x + 2, y + 1, cardW - 4, 1f, 4f, 0x18FFFFFF);
            gfx.fill(x + 8, y + cardH - 2, x + cardW - 8, y + cardH, 0x22000000);
            // Status-driven accent bar
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
            UIRenderer.pill(gfx, cx, cy, labelW, font.lineHeight + 4, renderEngine.animation().getAlpha(animIdx), true);
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
                    ItemStack gs = step.inputs().get(i);
                    int gTotal = gs.getCount() * Math.max(1, step.batches());
                    int gDisp = batches > 1 ? gs.getCount() : gTotal;
                    drawGridSlot(gfx, font, sx, sy, gs, gTotal, gDisp);
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
                    int totalNeed = in.getCount() * Math.max(1, step.batches());
                    int disp = batches > 1 ? in.getCount() : totalNeed;
                    drawSlot(gfx, font, sx, sy, in, totalNeed, disp, true);
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
        drawSlot(gfx, font, cx, cy, output, outAmount, outAmount, false);

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
                String modName = PlanRenderEngine.formatModTypeLabel(ac.modTypeId);
                String label = modName;
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
                    String tip = ac.recipeId.toString() + "  [" + modName + "]";
                    gfx.renderTooltip(font, Component.literal(tip), mouseX, mouseY);
                }

                orHitboxes.add(new ORHitbox(badgeX, badgeY, bw, badgeH, itemKey, i));
                badgeX += bw + 4;
            }
        }

        // ── Warnings (research, structure, etc.) ──────────────
        if (step != null && !step.warnings().isEmpty()) {
            int warnY = y + cardH - warnH + 3;
            for (String warn : step.warnings()) {
                // Truncate to 2 lines max per warning
                String display = font.plainSubstrByWidth(warn, cardW - CARD_PAD * 2 - 4);
                if (display.length() < warn.length()) {
                    display = display.substring(0, Math.max(0, display.length() - 3)) + "...";
                }
                gfx.drawString(font, display, x + CARD_PAD + 2, warnY, 0xFFDDAA00);
                warnY += font.lineHeight + 3;
            }
        }

        if (slideX != 0) gfx.pose().popPose();

        return y + cardH + 8;
    }

    // ── Grid slot ─────────────────────────────────────────────────

    /** @param needed       threshold for border color
     *  @param displayCount number drawn in corner; 0 = hide, 1 = vanilla decorations */
    private void drawGridSlot(GuiGraphics gfx, Font font, int x, int y,
                               ItemStack stack, int needed, int displayCount) {
        boolean hovered = mouseX >= x - 1 && mouseX <= x + GRID_SLOT + 1
                && mouseY >= y - 1 && mouseY <= y + GRID_SLOT + 1;

        if (stack.isEmpty()) {
            int emptyBorder = hovered ? 0xFF555555 : 0xFF3A3A3A;
            gfx.fill(x - 1, y - 1, x + GRID_SLOT + 1, y + GRID_SLOT + 1, emptyBorder);
            gfx.fill(x, y, x + GRID_SLOT, y + GRID_SLOT, 0xFF252525);
            return;
        }

        int avail = 0;
        PlanResponse.Availability a = plan.materials().get(stack.getItem());
        if (a != null) avail = a.available();

        int border = avail >= needed ? C_GREEN : (avail > 0 ? C_ORANGE : C_RED);

        UIRenderer.slotBg(gfx, x, y, GRID_SLOT, border);
        gfx.renderItem(stack, x + 1, y + 1);

        if (hovered) drawHoverBorder(gfx, x, y, GRID_SLOT);

        if (displayCount > 1) {
            gfx.pose().pushPose();
            gfx.pose().translate(0, 0, 200);
            String txt = String.valueOf(displayCount);
            gfx.drawString(font, txt, x + GRID_SLOT - font.width(txt),
                    y + GRID_SLOT - font.lineHeight + 2, 0xFFFFFF);
            gfx.pose().popPose();
        } else if (displayCount == 1) {
            gfx.renderItemDecorations(font, stack, x + 1, y + 1);
        }
    }

    private void drawGridPlaceholder(GuiGraphics gfx, int x, int y) {
        gfx.fill(x - 1, y - 1, x + GRID_SLOT + 1, y + GRID_SLOT + 1, 0xFF3A3A3A);
        gfx.fill(x, y, x + GRID_SLOT, y + GRID_SLOT, 0xFF252525);
    }

    private static void drawHoverBorder(GuiGraphics gfx, int x, int y, int size) {
        gfx.fill(x - 1, y - 1, x + size + 1, y, C_SLOT_HOVER);
        gfx.fill(x - 1, y + size + 1, x + size + 1, y + size + 2, C_SLOT_HOVER);
        gfx.fill(x - 1, y - 1, x, y + size + 1, C_SLOT_HOVER);
        gfx.fill(x + size + 1, y - 1, x + size + 2, y + size + 1, C_SLOT_HOVER);
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
        RSIntegrationMod.LOGGER.debug("[RSI-OR-UI] selectAlternative itemKey={} index={} oldIdx={} choices=[{}]",
                itemKey, index, oldIdx,
                choices.stream().map(c -> c.recipeId.toString() + ":" + c.modTypeId)
                        .reduce((a, b) -> a + ", " + b).orElse(""));
        if (oldIdx == index) {
            if (index != 0) {
                altSelection.put(itemKey, 0);
                RSIntegrationMod.LOGGER.debug("[RSI-OR-UI]   reverting to default");
            } else {
                RSIntegrationMod.LOGGER.debug("[RSI-OR-UI]   already default, no-op");
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
        RSIntegrationMod.LOGGER.debug("[RSI-OR-UI]   sending forced={}", forced);

        // If the user clicked an OR badge on the target step (the top-level
        // recipe output), send the alternative recipe ID as the new root so
        // the server resolves different ingredients.  Forced overrides only
        // affect intermediate steps — the target output is never resolved as
        // an ingredient, so a forced override for it is silently ignored.
        boolean isTarget = plan.targetResult() != null
                && itemKey.equals(BuiltInRegistries.ITEM.getKey(plan.targetResult().getItem()).toString());
        int newIdx = altSelection.getOrDefault(itemKey, 0);
        ResourceLocation rid;
        if (isTarget && newIdx != 0 && choices != null && newIdx < choices.size()) {
            rid = choices.get(newIdx).recipeId;
            forced.remove(itemKey);
            RSIntegrationMod.LOGGER.debug("[RSI-OR-UI]   target switch → root recipe={}", rid);
        } else {
            rid = ResourceLocation.tryParse(plan.recipeId());
        }

        if (rid != null) {
            ResourceLocation execDim = null;
            net.minecraft.core.BlockPos execPos = null;
            if (plan.executionDim() != null && !plan.executionDim().isEmpty()) {
                execDim = ResourceLocation.tryParse(plan.executionDim());
                execPos = new net.minecraft.core.BlockPos(
                        plan.executionPosX(), plan.executionPosY(), plan.executionPosZ());
            }
            BatchCraftNetworkHandler.CHANNEL.sendToServer(
                    new GenericCraftPacket(rid, true, forced, execDim, execPos, currentRepeat, false, plan.baseItem()));
        }
    }

    // ── Slot drawing ──────────────────────────────────────────────

    /** @param needed       threshold for color (green/orange/red)
     *  @param displayCount number shown in the slot corner; 0 = hide */
    private void drawSlot(GuiGraphics gfx, Font font, int x, int y,
                           ItemStack stack, int needed, int displayCount, boolean isInput) {
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

        boolean hovered = mouseX >= x - 1 && mouseX <= x + SLOT_SIZE + 1
                && mouseY >= y - 1 && mouseY <= y + SLOT_SIZE + 1;
        if (hovered) {
            drawHoverBorder(gfx, x, y, SLOT_SIZE);
            // Defer tooltip — render after all scissors are disabled
            hoveredItemForTooltip = stack;
            hoveredTooltipX = mouseX;
            hoveredTooltipY = mouseY;
            hoveredTooltipAvail = avail;
            hoveredTooltipNeeded = needed;
        }

        if (displayCount > 1) {
            gfx.pose().pushPose();
            gfx.pose().translate(0, 0, 200);
            String txt = String.valueOf(displayCount);
            gfx.drawString(font, txt, x + SLOT_SIZE - font.width(txt),
                    y + SLOT_SIZE - font.lineHeight + 2, 0xFFFFFF);
            gfx.pose().popPose();
        } else if (displayCount == 1) {
            gfx.renderItemDecorations(font, stack, x + 1, y + 1);
        }
    }

    // ── Embers alchemy pedestal layout ──────────────────────────────

    private void renderEmbersPedestalArea(GuiGraphics gfx, Font font, int left) {
        int contentW = width - 40;
        int top = embersPedestalY;

        // Background with emerald left accent
        UIRenderer.roundedGradient(gfx, left, top, contentW, embersPedestalH, 6f,
                0xE6141E18, 0xE6101814);
        gfx.fill(left + 1, top + 2, left + 4, top + embersPedestalH - 2, 0xFF44AA66);

        int[] code = plan.embersCode();
        String[] aspects = plan.embersAspectNames();
        String[] inputs = plan.embersInputNames();
        boolean hasCards = code != null && aspects != null && inputs != null;

        int y = top + 4;
        if (hasCards) {
            String hdr = Component.translatable("rsi.embers.pedestal_layout").getString();
            UIRenderer.textBackdrop(gfx, font, left + 10, y, hdr, C_TEXT_BACKDROP);
            gfx.drawString(font, hdr, left + 10, y, 0xFF88CC99);
            // Show "from prior inference" badge when code was loaded from cache
            if (plan.embersCodeFromCache()) {
                String cachedNote = Component.translatable("rsi.embers.code_from_cache").getString();
                int noteW = font.width(cachedNote);
                gfx.drawString(font, cachedNote, left + contentW - noteW - 12, y, 0xFF77AA77);
            }
            y += font.lineHeight + 6;
        }

        if (hasCards) {
            int cardW = 64;
            int cardH = 44;
            int cardGap = 4;
            int cardsPerRow = Math.max(1, Math.min(code.length, (contentW - 16) / (cardW + cardGap)));
            int cx = left + 8 + (contentW - 16 - cardsPerRow * (cardW + cardGap) + cardGap) / 2;

            for (int i = 0; i < code.length; i++) {
                int col = i % cardsPerRow;
                int row = i / cardsPerRow;
                int px = cx + col * (cardW + cardGap);
                int py = y + row * (cardH + cardGap);

                // Card background
                UIRenderer.rounded(gfx, px, py, cardW, cardH, 4f, 0xCC1A221E);

                // Input name (top)
                String inputName = i < inputs.length ? I18n.get(inputs[i]) : "?";
                int maxNameW = cardW - 8;
                if (font.width(inputName) > maxNameW) {
                    inputName = font.plainSubstrByWidth(inputName, maxNameW - font.width("...")) + "...";
                }
                int nameX = px + cardW / 2 - font.width(inputName) / 2;
                gfx.drawString(font, inputName, nameX, py + 2, 0xFFCCCCCC);

                // Aspect name (bottom)
                String aspectName = i < aspects.length ? I18n.get(aspects[i]) : "?";
                if (font.width(aspectName) > maxNameW) {
                    aspectName = font.plainSubstrByWidth(aspectName, maxNameW - font.width("...")) + "...";
                }
                int aspX = px + cardW / 2 - font.width(aspectName) / 2;
                gfx.drawString(font, aspectName, aspX, py + cardH - font.lineHeight - 2, 0xFF88CC88);

                // Separator line
                int sepY = py + cardH / 2;
                gfx.fill(px + 6, sepY - 1, px + cardW - 6, sepY, 0x33338844);

                // Pedestal number
                String num = String.valueOf(i + 1);
                gfx.drawString(font, num, px + cardW - font.width(num) - 3, py + cardH - font.lineHeight - 1, 0xFF558855);
            }
        }

        // Seed indicator at bottom-right (only with cards, so it doesn't overlap toggle)
        if (hasCards && plan.embersSeed() != 0) {
            String seedStr = Component.translatable("rsi.embers.seed_label", plan.embersSeed()).getString();
            int seedW = font.width(seedStr);
            int seedX = left + contentW - seedW - 12;
            int seedY = top + embersPedestalH - font.lineHeight - 4;
            gfx.drawString(font, seedStr, seedX, seedY, 0xFF556644);
        }

        // ── Mode toggle (Calculate / Infer) ──────────────────────────
        if (showEmbersModeToggle) {
            String modeLabel = Component.translatable("rsi.embers.mode_label").getString();
            int labelW = font.width(modeLabel);
            int rowCenterX = left + contentW / 2;
            int btnW = 52;
            int btnH = 16;
            int gap = 6;
            int totalW = labelW + 6 + btnW * 2 + gap;
            int rowStartX = rowCenterX - totalW / 2;
            int btnTop = embersModeY + 6;

            // Label — match section header color
            gfx.drawString(font, modeLabel, rowStartX, btnTop + (btnH - font.lineHeight) / 2, 0xFF88CC99);

            // Calculate button — same color scheme as repeat row buttons
            int calcX = rowStartX + labelW + 6;
            embersModeCalcX = calcX; embersModeCalcY = btnTop;
            embersModeCalcW = btnW; embersModeCalcH = btnH;
            boolean hoverCalc = mouseX >= calcX && mouseX <= calcX + btnW
                    && mouseY >= btnTop && mouseY <= btnTop + btnH;
            boolean isCalc = !embersInferMode;
            int calcBg = isCalc ? 0xCC338855 : (hoverCalc ? 0x88338855 : 0x661A221E);
            int calcFg = isCalc ? 0xFFFFFFFF : (hoverCalc ? 0xFFAAFFAA : 0xFF558855);
            // Double-layer inset (matching repeat row buttons)
            UIRenderer.rounded(gfx, calcX, btnTop, btnW, btnH, 4f, calcBg);
            UIRenderer.rounded(gfx, calcX + 1, btnTop + 1, btnW - 2, btnH - 2, 3f, 0x881A221E);
            String calcLabel = Component.translatable("rsi.embers.mode_calc").getString();
            gfx.drawString(font, calcLabel,
                    calcX + btnW / 2 - font.width(calcLabel) / 2,
                    btnTop + (btnH - font.lineHeight) / 2, calcFg);

            // Infer button
            int inferX = calcX + btnW + gap;
            embersModeInferX = inferX; embersModeInferY = btnTop;
            embersModeInferW = btnW; embersModeInferH = btnH;
            boolean hoverInfer = mouseX >= inferX && mouseX <= inferX + btnW
                    && mouseY >= btnTop && mouseY <= btnTop + btnH;
            boolean isInfer = embersInferMode;
            int inferBg = isInfer ? 0xCC338855 : (hoverInfer ? 0x88338855 : 0x661A221E);
            int inferFg = isInfer ? 0xFFFFFFFF : (hoverInfer ? 0xFFAAFFAA : 0xFF558855);
            UIRenderer.rounded(gfx, inferX, btnTop, btnW, btnH, 4f, inferBg);
            UIRenderer.rounded(gfx, inferX + 1, btnTop + 1, btnW - 2, btnH - 2, 3f, 0x881A221E);
            String inferLabel = Component.translatable("rsi.embers.mode_infer").getString();
            gfx.drawString(font, inferLabel,
                    inferX + btnW / 2 - font.width(inferLabel) / 2,
                    btnTop + (btnH - font.lineHeight) / 2, inferFg);
        }
    }

    // ── Missing area ──────────────────────────────────────────────

    private void renderMissingArea(GuiGraphics gfx, Font font, int left, int top, int contentW) {
        boolean hasMissing = plan.missing() != null && !plan.missing().isEmpty();
        boolean hasModWarnings = plan.modWarnings() != null && !plan.modWarnings().isEmpty();

        // Background — dark warm tone, red accent
        UIRenderer.roundedGradient(gfx, left, top, contentW, missingAreaHeight, 6f,
                0xE61E1814, 0xE6181410);
        // Left accent — red when materials are missing, orange when only mod warnings
        int accentColor = hasMissing ? C_ACCENT_MISSING : C_ORANGE;
        gfx.fill(left + 1, top + 2, left + 4, top + missingAreaHeight - 2, accentColor);

        int my = top + 4;
        int maxLineW = contentW - 24;
        // Render missing items — inline, wrapped
        if (hasMissing) {
            String hdr = Component.translatable("rsi.plan.missing_header").getString();
            UIRenderer.textBackdrop(gfx, font, left + 10, my, hdr, C_TEXT_BACKDROP);
            gfx.drawString(font, hdr, left + 10, my, 0xFFFF6666);
            my += font.lineHeight + 4;
            String joined = String.join(", ", plan.missing());
            for (String line : UIRenderer.wrapLines(font, "  " + joined, maxLineW)) {
                gfx.drawString(font, line, left + 10, my, 0xFFCC8888);
                my += font.lineHeight + 4;
            }
        }
        // Render mod warnings (one per line — they're longer sentences)
        if (hasModWarnings) {
            if (!hasMissing) my += 4;
            for (String warn : plan.modWarnings()) {
                String display = font.plainSubstrByWidth(warn, maxLineW);
                UIRenderer.textBackdrop(gfx, font, left + 10, my, display, C_TEXT_BACKDROP);
                gfx.drawString(font, display, left + 10, my, 0xFFDDAA00);
                my += font.lineHeight + 4;
            }
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
            // Repeat row quick-set buttons (8 total)
            for (int i = 0; i < 8; i++) {
                if (mx >= repeatBtnX[i] && mx <= repeatBtnX[i] + repeatBtnW[i]
                        && my >= repeatBtnY[i] && my <= repeatBtnY[i] + repeatBtnH[i]) {
                    switch (i) {
                        case 0: currentRepeat = 1; break;
                        case 1: currentRepeat = Math.max(1, currentRepeat - 10); break;
                        case 2: currentRepeat = Math.max(1, currentRepeat - 5); break;
                        case 3: currentRepeat = Math.max(1, currentRepeat - 1); break;
                        case 4: currentRepeat = Math.min(64, currentRepeat + 1); break;
                        case 5: currentRepeat = Math.min(64, currentRepeat + 5); break;
                        case 6: currentRepeat = Math.min(64, currentRepeat + 10); break;
                        case 7: currentRepeat = 64; break;
                    }
                    requestPlanRefresh();
                    return true;
                }
            }
            // Embers mode toggle: Calculate
            if (showEmbersModeToggle
                    && mx >= embersModeCalcX && mx <= embersModeCalcX + embersModeCalcW
                    && my >= embersModeCalcY && my <= embersModeCalcY + embersModeCalcH) {
                embersInferMode = false;
                return true;
            }
            // Embers mode toggle: Infer
            if (showEmbersModeToggle
                    && mx >= embersModeInferX && mx <= embersModeInferX + embersModeInferW
                    && my >= embersModeInferY && my <= embersModeInferY + embersModeInferH) {
                embersInferMode = true;
                return true;
            }
            if (my >= stepsTop) {
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
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode >= GLFW.GLFW_KEY_0 && keyCode <= GLFW.GLFW_KEY_9) {
            long now = System.currentTimeMillis();
            if (now - lastKeyTime > 600) repeatBuf = "";
            lastKeyTime = now;
            repeatBuf += (char)('0' + (keyCode - GLFW.GLFW_KEY_0));
            try { currentRepeat = Math.max(1, Math.min(Integer.parseInt(repeatBuf), 64)); }
            catch (NumberFormatException ignored) { currentRepeat = 1; repeatBuf = "1"; }
            requestPlanRefresh();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_BACKSPACE || keyCode == GLFW.GLFW_KEY_DELETE) {
            repeatBuf = "1";
            currentRepeat = 1;
            lastKeyTime = 0;
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ── Status helpers ────────────────────────────────────────────

    /** Accent color driven by material availability, not mod type. */
    private int stepAccent(PlanStep step, int batches) {
        if (step == null || step.inputs().isEmpty()) return C_ACCENT_NEUTRAL;
        for (ItemStack in : step.inputs()) {
            if (in.isEmpty()) continue;
            PlanResponse.Availability a = plan.materials().get(in.getItem());
            int avail = a != null ? a.available() : 0;
            int need = in.getCount() * Math.max(1, batches);
            if (avail < need) return C_ACCENT_MISSING;
        }
        return C_ACCENT_READY;
    }

    // ── Deferred tooltip render ─────────────────────────────────

    /** Renders the hovered-item tooltip AFTER all scissors are disabled.
     *  Builds the component list via {@code getTooltipLines()} so Forge's
     *  {@code RenderTooltipEvent} fires and Legendary Tooltips can intercept. */
    private void renderDeferredTooltip(GuiGraphics gfx, Font font) {
        if (hoveredItemForTooltip.isEmpty()) return;

        List<net.minecraft.network.chat.Component> lines = new ArrayList<>(
                hoveredItemForTooltip.getTooltipLines(
                        minecraft.player,
                        minecraft.options.advancedItemTooltips
                                ? net.minecraft.world.item.TooltipFlag.Default.ADVANCED
                                : net.minecraft.world.item.TooltipFlag.Default.NORMAL));

        // Append availability info
        String availStr = hoveredTooltipAvail + " / " + hoveredTooltipNeeded;
        int statusColor;
        if (hoveredTooltipAvail >= hoveredTooltipNeeded)
            statusColor = 0xFF55FF55;
        else if (hoveredTooltipAvail > 0)
            statusColor = 0xFFFFAA33;
        else
            statusColor = 0xFFFF5555;

        lines.add(Component.literal(
                Component.translatable("rsi.plan.tooltip.available").getString()
                + ": " + availStr).withStyle(style -> style.withColor(statusColor)));

        // This single call triggers Forge's RenderTooltipEvent — Legendary
        // Tooltips intercepts it to draw its polished gradient-bordered cards
        // with automatic screen-boundary avoidance.
        gfx.renderComponentTooltip(font, lines, hoveredTooltipX, hoveredTooltipY);

        hoveredItemForTooltip = ItemStack.EMPTY;
    }

    // ── Color helpers ─────────────────────────────────────────────

    /** Badge fill color per mod type. */
    private static int badgeColor(String modTypeId) {
        return switch (modTypeId) {
            case ModIds.MALUM              -> 0xCC442288;
            case ModIds.EIDOLON            -> 0xCC226644;
            case ModIds.FORBIDDEN_ARCANUS  -> 0xCC663322;
            case ModIds.GOETY              -> 0xCC222244;
            case ModIds.WIZARDS_REBORN     -> 0xCC444466;
            default                        -> 0xCC886622;
        };
    }

    private static int fadeColor(int color, float alpha) {
        int a = (int) (0xFF * alpha);
        return (a << 24) | (color & 0x00FFFFFF);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
