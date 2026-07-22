package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.compat.ftbquests.QuestSubmissionRequestPacket;
import com.huanghuang.rsintegration.compat.ftbquests.QuestSubmissionTargetIds;
import com.huanghuang.rsintegration.network.RSJeiPlugin;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.batch.BatchCraftNetworkHandler;
import com.huanghuang.rsintegration.crafting.batch.GenericCraftPacket;
import com.huanghuang.rsintegration.crafting.OutputDestination;
import com.huanghuang.rsintegration.crafting.tree.IngredientKey;
import com.huanghuang.rsintegration.crafting.tree.JeiSubtreeBuilder;
import com.huanghuang.rsintegration.crafting.tree.PlanTreeLayout;
import com.huanghuang.rsintegration.crafting.tree.PlanTreeModel;
import com.huanghuang.rsintegration.crafting.tree.PlanTreeNode;
import com.huanghuang.rsintegration.crafting.tree.PlanTreeRenderer;
import com.huanghuang.rsintegration.crafting.tree.RecipePreviewRenderer;
import com.huanghuang.rsintegration.crafting.tree.SelectedPath;
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
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.util.*;

@OnlyIn(Dist.CLIENT)
public final class CraftingPlanScreen extends Screen {

    private static final int SLOT_SIZE = 18;
    private static final int CARD_PAD = 8;
    private static final int ARROW_W = 18;
    private static final int STEPS_TOP_MIN = 40;
    /** Material grid caps at this many visible rows; extras scroll (§ material overflow fix). */
    private static final int MATERIAL_MAX_ROWS = 3;
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
    private long activeRequestId;
    private long nextRequestId = 1L;
    private int scrollOffset;
    private int maxScroll;
    private boolean dragging;
    private int missingAreaTop;
    private int missingAreaHeight;
    private int materialAreaTop;
    private int materialAreaHeight;
    // Material grid vertical scroll (pixels); max set by the render engine each frame.
    private int materialScroll;
    private int materialMaxScroll;
    // Draggable scrollbars (geometry rebuilt each frame); draggingBar tracks an active thumb drag.
    private final ScrollbarUI cardBar = new ScrollbarUI();
    private final ScrollbarUI materialBar = new ScrollbarUI();
    @Nullable
    private ScrollbarUI draggingBar;
    private int scrollbarGrabDy;
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

    // ── Recipe-tree view (v3.4) ──────────────────────────────────
    private enum ViewMode { CARD, TREE }
    private ViewMode viewMode = ViewMode.CARD;
    private PlanTreeModel treeModel;
    private final SelectedPath selectedPath = new SelectedPath();
    private final PlanTreeLayout treeLayout = new PlanTreeLayout();
    private PlanTreeRenderer treeRenderer;
    private final RecipePreviewRenderer recipePreview = new RecipePreviewRenderer();
    // Camera — logical→screen transform is screenX = logicalX*zoom + panX.
    private double treeZoom = 1.0;
    private double treePanX, treePanY;
    private boolean treeCameraInit;
    // Tree viewport rect (screen space), set each render for hit-testing / centering.
    private int treeViewLeft, treeViewTop, treeViewRight, treeViewBottom;
    // Total-demand strip: raw-material totals + leftovers, drawn in the tree camera
    // layer anchored above the root node (design doc §4.1). Logical (pre-zoom) units.
    private static final int STRIP_ROW_H = 20;
    private static final int STRIP_GAP = 8;
    private final List<CostHit> costHits = new ArrayList<>();
    private record CostHit(int x, int y, int w, int h, IngredientKey key) {}
    private record StripEntry(ItemStack display, IngredientKey key, int count, int available, boolean enough) {}
    // Card-view fold toggle hitboxes (rebuilt each frame, read during mouseClicked).
    // One per rendered step card (expanded chevron OR collapsed row), so every step
    // stays clickable — not just the last one drawn.
    private final List<FoldHit> foldHits = new ArrayList<>();
    private record FoldHit(int x, int y, int w, int h, String stepId, int depth) {}
    // Card-view [Expand All] / [Collapse All] button hitbox.
    private int foldAllHitX, foldAllHitY, foldAllHitW, foldAllHitH;
    private boolean foldAllHovered;
    // Tree-view [Expand All] / [Collapse All] toolbar button hitbox (top-right of the viewport).
    private OutputDestination outputDestination = OutputDestination.RS_NETWORK;
    private int outputSelectorX, outputSelectorY, outputSegmentW, outputSelectorH;
    private int treeFoldAllHitX, treeFoldAllHitY, treeFoldAllHitW, treeFoldAllHitH;
    // Alternative-recipe dropdown (screen space); open node + row hitboxes.
    private PlanTreeNode dropdownNode;
    // Keyboard selection cursor within the open dropdown (-1 = none).
    private int dropdownCursor = -1;
    private final List<DropHit> dropHits = new ArrayList<>();
    private record DropHit(int x, int y, int w, int h, ResourceLocation recipeId) {}

    // Hover intent: the full JEI recipe preview only pops after the mouse rests on the same
    // target for HOVER_INTENT_MS, so a quick pass across nodes/candidates doesn't spam previews.
    private static final long HOVER_INTENT_MS = 150L;
    @Nullable
    private ResourceLocation hoverPreviewId;
    private long hoverPreviewStart;

    // Card view: the alternative-recipe badge under the mouse this frame. Captured during the
    // scissored card pass and rendered as a post-scissor overlay so the preview isn't clipped.
    @Nullable
    private ResourceLocation cardPreviewId;

    /**
     * Screen-space vertical scrollbar: 3px track with a proportional thumb. Geometry is rebuilt
     * each frame from the current scroll/maxScroll; the thumb is grab-draggable (see mouse handlers).
     */
    private static final class ScrollbarUI {
        int trackX, trackTop, trackH, thumbY, thumbH, maxScroll;
        boolean active;

        void update(int trackX, int trackTop, int trackH, int scroll, int maxScroll) {
            this.trackX = trackX;
            this.trackTop = trackTop;
            this.trackH = trackH;
            this.maxScroll = maxScroll;
            this.active = maxScroll > 0 && trackH > 0;
            if (!active) return;
            int contentH = trackH + maxScroll;
            this.thumbH = Math.max(12, (int) ((long) trackH * trackH / contentH));
            this.thumbY = trackTop + (int) ((long) (trackH - thumbH) * scroll / maxScroll);
        }

        void draw(GuiGraphics gfx, double mouseX, double mouseY) {
            if (!active) return;
            gfx.fill(trackX, trackTop, trackX + 3, trackTop + trackH, 0x40FFFFFF);
            int color = overThumb(mouseX, mouseY) ? 0xFF88DDAA : 0xAA66CC88;
            gfx.fill(trackX, thumbY, trackX + 3, thumbY + thumbH, color);
        }

        // Hit regions are widened ±2px beyond the 3px track so the thin bar stays easy to grab.
        boolean overThumb(double mx, double my) {
            return active && mx >= trackX - 2 && mx <= trackX + 5
                    && my >= thumbY && my <= thumbY + thumbH;
        }

        boolean overTrack(double mx, double my) {
            return active && mx >= trackX - 2 && mx <= trackX + 5
                    && my >= trackTop && my <= trackTop + trackH;
        }

        /** Scroll offset that places the thumb's top at {@code thumbTopY} (clamped to range). */
        int scrollForThumbTop(int thumbTopY) {
            int span = trackH - thumbH;
            if (span <= 0) return 0;
            int s = (int) Math.round((double) (thumbTopY - trackTop) * maxScroll / span);
            return Math.max(0, Math.min(maxScroll, s));
        }
    }

    private int embersPedestalY;
    private int embersPedestalH;
    private int embersCardsH;          // pedestal cards height (mode toggle sits below)
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

    // Card-view step folding (§3.17). Keyed by recipeId string; default: root unfolded, rest folded.
    private final Map<String, Boolean> collapsedSteps = new LinkedHashMap<>();

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
        this.activeRequestId = 0L;
        // Adaptive view routing (§2.5): non-trivial plans open in the tree; simple ones stay on the card.
        this.viewMode = plan.steps().size() > 2 ? ViewMode.TREE : ViewMode.CARD;
        this.renderEngine = new PlanRenderEngine(Minecraft.getInstance().font);
        this.treeRenderer = new PlanTreeRenderer(Minecraft.getInstance().font);
        this.treeRenderer.setIconRenderer(recipePreview::drawCategoryIcon);
        rebuildTreeModel(true);
    }

    /** Exposed for {@link PlanResponsePacket} dedup check. */
    public String getRecipeId() {
        return plan.recipeId();
    }

    public long activeRequestId() {
        return activeRequestId;
    }

    public void acceptResponse(long requestId, PlanResponse newPlan) {
        if (requestId == 0L) {
            if (activeRequestId != 0L) return;
        } else if (requestId < activeRequestId) {
            return;
        } else {
            activeRequestId = requestId;
        }
        updatePlan(newPlan);
    }

    /** Update plan data in-place (OR-path switch, repeat-count change, etc.).
     *  Avoids creating a new screen which would lose UI state. */
    public void updatePlan(PlanResponse newPlan) {
        this.plan = newPlan;
        this.renderEngine = new PlanRenderEngine(Minecraft.getInstance().font);
        this.dropdownNode = null;
        this.materialScroll = 0;
        rebuildTreeModel(false);
        this.altChoices.clear();
        this.orHitboxes.clear();
        this.altSelection.clear();
        this.clearWidgets();
        this.init();
    }

    /**
     * Build (or rebuild) the client-side recipe tree from the current {@link #plan},
     * preserving collapse state and branch selections across rebuilds via IngredientKey
     * reconciliation (v3.4 §4.3). On the first build, alternatives auto-select to the
     * server-chosen recipe ({@link SelectedPath#initDefaults}).
     */
    private void rebuildTreeModel(boolean firstBuild) {
        Set<PlanTreeModel.CollapseKey> collapsed = new LinkedHashSet<>();
        if (treeModel != null) {
            PlanTreeModel.collectCollapsedNodes(treeModel.root, collapsed);
        }

        treeModel = PlanTreeModel.from(plan);
        PlanTreeModel.applyCollapsedNodes(treeModel.root, collapsed);
        JeiSubtreeBuilder.enrichCarousels(treeModel.root);
        recipePreview.clear();

        selectedPath.bindTree(treeModel);
        if (firstBuild) {
            selectedPath.initDefaults();
        }
        treeLayout.markDirty();

        if (RSIntegrationConfig.DIAGNOSTIC_VERBOSE_LOGGING.get()) {
            StringBuilder sb = new StringBuilder("\n[PlanTree] rebuild firstBuild=").append(firstBuild)
                    .append(" pendingBranches=").append(selectedPath.pendingBranches());
            dumpTree(treeModel.root, sb);
            RSIntegrationMod.debug(sb.toString());
        }
    }

    private void dumpTree(PlanTreeNode node, StringBuilder sb) {
        sb.append('\n');
        for (int i = 0; i < node.depth; i++) sb.append("  ");
        sb.append("- ").append(node.displayStack.getHoverName().getString())
                .append(" x").append(node.amount)
                .append(node.step == null ? " [leaf]" : "")
                .append(node.hasAlternatives() ? " [alt]" : "")
                .append(node.cycle ? " [cycle]" : "")
                .append(" (").append(node.available).append('/').append(node.needed).append(')');
        for (PlanTreeNode child : node.children) dumpTree(child, sb);
    }

    @Override
    protected void init() {
        super.init();
        currentRepeat = Math.max(1, Math.min(plan.repeatCount(), 64));
        repeatBuf = Integer.toString(currentRepeat);
        lastKeyTime = 0;
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
        int visibleMatRows = Math.min(matRows, MATERIAL_MAX_ROWS);
        int matGridH = visibleMatRows * (SLOT_SIZE + 8) + 4;
        materialAreaHeight = (plan.materials().isEmpty() ? 0 : font.lineHeight + 6 + matGridH + 8);
        layoutBottomStack();

        int btnW = 80;
        int btnY = height - 24;

        boolean questSubmission = QuestSubmissionTargetIds.isQuestSubmission(
                ResourceLocation.tryParse(plan.recipeId()));
        if (!questSubmission) {
            outputSegmentW = 132;
            outputSelectorH = 20;
            outputSelectorX = width / 2 - outputSegmentW / 2;
            outputSelectorY = btnY - 27;
        } else {
            outputSelectorH = 0;
        }

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
                        Component.translatable(selectedPath.isDirty()
                                ? "rsi.plan.confirm_branches" : "rsi.plan.confirm"),
                        btn -> onConfirm())
                .pos(width / 2 - btnW - 10, btnY)
                .size(selectedPath.isDirty() ? btnW + 20 : btnW, 20)
                .build());

        addRenderableWidget(Button.builder(
                        Component.translatable("rsi.plan.cancel"),
                        btn -> onClose())
                .pos(width / 2 + 10, btnY)
                .size(btnW, 20)
                .build());

        // View-mode toggle (top-right). Flips card ↔ tree and recenters the tree camera.
        addRenderableWidget(Button.builder(viewToggleLabel(), btn -> {
                    viewMode = (viewMode == ViewMode.CARD) ? ViewMode.TREE : ViewMode.CARD;
                    treeCameraInit = false;
                    dropdownNode = null;
                    btn.setMessage(viewToggleLabel());
                })
                .pos(width - 78, 6)
                .size(68, 18)
                .build());
    }

    private Component viewToggleLabel() {
        return Component.translatable(
                viewMode == ViewMode.TREE ? "rsi.plan.view_tree" : "rsi.plan.view_card");
    }

    private void selectOutputDestination(OutputDestination destination) {
        outputDestination = destination;
    }

    private void onConfirm() {
        String recipeId = plan.recipeId();
        ResourceLocation targetId = ResourceLocation.tryParse(recipeId);
        if (QuestSubmissionTargetIds
                .isQuestSubmission(targetId)) {
            BatchCraftNetworkHandler.CHANNEL.sendToServer(
                    new QuestSubmissionRequestPacket(
                            QuestSubmissionTargetIds
                                    .questId(targetId), false));
            onClose();
            return;
        }
        if (recipeId != null && !recipeId.isEmpty()) {
            Map<String, String> forced = exportForcedSelections();
            sendCraftPacket(ResourceLocation.tryParse(recipeId), false, forced,
                    Math.max(1, Math.min(currentRepeat, 64)),
                    plan.embersCode() != null && embersInferMode);
        }
        onClose();
    }

    private Map<String, String> exportForcedSelections() {
        Map<String, String> forced = new LinkedHashMap<>();
        for (Map.Entry<IngredientKey, ResourceLocation> entry : selectedPath.exportSelections().entrySet()) {
            forced.put(entry.getKey().stack(1).getItem().builtInRegistryHolder().key().location().toString(),
                    entry.getValue().toString());
        }
        return forced;
    }

    static ItemStack executionTarget(ItemStack clickedOutput, ItemStack targetResult) {
        if (clickedOutput != null && !clickedOutput.isEmpty()) return clickedOutput.copy();
        return targetResult == null ? ItemStack.EMPTY : targetResult.copy();
    }

    /** Build the execution target from the current plan and send a craft/preview packet. */
    private void sendCraftPacket(ResourceLocation rid, boolean preview,
                                 Map<String, String> forced, int repeatCount, boolean inferMode) {
        if (rid == null) return;
        ResourceLocation execDim = null;
        net.minecraft.core.BlockPos execPos = null;
        if (plan.executionDim() != null && !plan.executionDim().isEmpty()) {
            execDim = ResourceLocation.tryParse(plan.executionDim());
            execPos = new net.minecraft.core.BlockPos(
                    plan.executionPosX(), plan.executionPosY(), plan.executionPosZ());
        }
        long requestId = nextRequestId++;
        if (nextRequestId <= 0 || nextRequestId > 0x7FFF_FFFF_FFFF_FFFFL) nextRequestId = 1L;
        if (preview) activeRequestId = requestId;
        BatchCraftNetworkHandler.CHANNEL.sendToServer(
                new GenericCraftPacket(rid, preview, forced, execDim, execPos,
                        repeatCount, inferMode, plan.baseItem(),
                        executionTarget(plan.clickedOutput(), plan.targetResult()), requestId,
                        outputDestination));
    }

    /** Open JEI for a specific recipe id. No-op when JEI is unavailable. */
    private void openRecipeInJei(ResourceLocation recipeId) {
        mezz.jei.api.runtime.IJeiRuntime runtime =
                RSJeiPlugin.getRuntime();
        if (runtime == null || minecraft.level == null) return;
        var vanillaRecipe = minecraft.level.getRecipeManager().byKey(recipeId).orElse(null);
        if (vanillaRecipe == null) return;
        try {
            runtime.getRecipesGui().show(List.of(
                    runtime.getJeiHelpers().getFocusFactory().createFocus(
                            mezz.jei.api.recipe.RecipeIngredientRole.OUTPUT,
                            mezz.jei.api.constants.VanillaTypes.ITEM_STACK,
                            vanillaRecipe.getResultItem(minecraft.level.registryAccess()))
            ));
        } catch (Exception e) {
            // JEI integration unavailable — silently no-op
        }
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
            Map<String, String> forced = LAST_FORCED.isEmpty() ? Collections.emptyMap()
                    : new HashMap<>(LAST_FORCED);
            sendCraftPacket(id, true, forced, currentRepeat, false);
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
        // Recompute the bottom stack for the current view — the material panel is card-view
        // only (design doc §4), so tree view reclaims its band for the tree.
        layoutBottomStack();
        int bottomReserved = embersPedestalH > 0 ? embersPedestalY
                : (missingAreaHeight > 0 ? missingAreaTop : materialAreaTop);
        int areaBottom = bottomReserved - 4;
        if (embersPedestalH == 0 && missingAreaHeight == 0 && materialAreaHeight == 0) areaBottom = height - 53;

        if (viewMode == ViewMode.TREE) {
            cardBar.active = false; // card list not shown in tree mode
            renderTreeArea(gfx, left, areaTop, contentW, areaBottom, mouseX, mouseY);
        } else {
        gfx.enableScissor(left, areaTop, left + contentW, areaBottom);
        try {
            orHitboxes.clear();
            orRendered.clear();
            cardPreviewId = null;

            // v3.4: card view only renders steps on the selected green path.
            Set<ResourceLocation> activeIds = treeModel != null
                    ? selectedPath.deriveActiveRecipeIds(treeModel)
                    : Collections.emptySet();
            String rootId = plan.recipeId();

            int y = areaTop - scrollOffset;
            int stepIdx = 0;
            foldHits.clear();

            // Pending-branch hint when tree has unselected alternatives.
            int pending = selectedPath.pendingBranches();
            if (pending > 0) {
                String hint = I18n.get("rsi.plan.branches_pending", pending);
                gfx.drawString(font, hint, left + 8, y, 0xFFFFAA33, false);
                y += font.lineHeight + 6;
            }

            // [Expand All] / [Collapse All] toggle row.
            boolean allFolded = plan.steps().stream()
                    .allMatch(s -> isStepCollapsed(s.recipeId().toString(), s.depth()));
            String foldLabel = allFolded ? "▶ " + I18n.get("rsi.plan.expand_all")
                    : "▼ " + I18n.get("rsi.plan.collapse_all");
            int foldBtnW = font.width(foldLabel) + 8;
            int foldAllX = left + contentW - foldBtnW - 4;
            gfx.fill(foldAllX, y, foldAllX + foldBtnW, y + font.lineHeight + 4, 0x331A3E1A);
            gfx.drawString(font, foldLabel, foldAllX + 4, y + 2, 0xFF44AA66, false);
            // Store hitbox for click handling.
            foldAllHitX = foldAllX;
            foldAllHitY = y;
            foldAllHitW = foldBtnW;
            foldAllHitH = font.lineHeight + 4;
            if (mouseX >= foldAllHitX && mouseX < foldAllHitX + foldAllHitW
                    && mouseY >= foldAllHitY && mouseY < foldAllHitY + foldAllHitH) {
                gfx.fill(foldAllHitX, foldAllHitY, foldAllHitX + foldAllHitW,
                        foldAllHitY + foldAllHitH, 0x22FFFFFF);
            }
            y += foldAllHitH + 4;

            // Target recipe card — always rendered, full-width.
            int cardW0 = contentW - 8;
            y = drawStepCard(gfx, font, left + 4, y, cardW0,
                    plan.targetResult(), plan.targetResult().getHoverName().getString(),
                    null, plan.repeatCount(), stepIdx, 0, false);
            stepIdx++;
            int prevCardBottom = y;
            int prevStepX = left + 4;

            // Intermediate steps — only active path + collapsible.
            for (int i = 0; i < plan.steps().size(); i++) {
                PlanStep step = plan.steps().get(i);
                String stepRid = step.recipeId().toString();

                // Filter: only show steps on the selected path.
                if (selectedPath.isDirty() && !activeIds.contains(step.recipeId())) continue;

                int stepX = left + 4 + step.depth() * INDENT;
                int cardW = contentW - 8 - step.depth() * INDENT;

                if (isStepCollapsed(stepRid, step.depth())) {
                    // Collapsed: single-row summary.
                    y += CONNECTOR_GAP;
                    renderEngine.drawTreeConnector(gfx, prevCardBottom, y, stepX, prevStepX, INDENT, i);
                    y = drawCollapsedStepRow(gfx, font, stepX, y, cardW, step, stepIdx, i + 1);
                    prevCardBottom = y;
                    prevStepX = stepX;
                } else {
                    y += CONNECTOR_GAP;
                    renderEngine.drawTreeConnector(gfx, prevCardBottom, y, stepX, prevStepX, INDENT, i);

                    int animIdx = i + 1;
                    y = drawStepCard(gfx, font, stepX, y, cardW,
                            step.output(), step.output().getHoverName().getString(),
                            step, step.batches(), stepIdx, animIdx, true);
                    prevCardBottom = y;
                    prevStepX = stepX;
                }
                stepIdx++;
            }

            maxScroll = Math.max(0, y - areaBottom + scrollOffset);
        } finally {
            gfx.disableScissor();
        }
        // Draggable scrollbar for the card list (see mouse handlers).
        cardBar.update(left + contentW - 5, areaTop, areaBottom - areaTop, scrollOffset, maxScroll);
        cardBar.draw(gfx, mouseX, mouseY);
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

        // Material summary — card view only; tree view shows the total-demand strip instead.
        if (viewMode == ViewMode.CARD && materialAreaHeight > 0) {
            materialMaxScroll = renderEngine.renderMaterialArea(gfx, left, materialAreaTop, contentW,
                    materialAreaHeight, plan.materials(), mouseX, mouseY, materialScroll,
                    (stack, mx, my, avail, need) -> {
                        hoveredItemForTooltip = stack;
                        hoveredTooltipX = mx;
                        hoveredTooltipY = my;
                        hoveredTooltipAvail = avail;
                        hoveredTooltipNeeded = need;
                    });
            if (materialScroll > materialMaxScroll) materialScroll = materialMaxScroll;
            // Draggable scrollbar — grid begins below the header row (top+4 + lineHeight+4).
            int gridTop = materialAreaTop + 8 + font.lineHeight;
            materialBar.update(left + contentW - 5, gridTop,
                    materialAreaTop + materialAreaHeight - gridTop, materialScroll, materialMaxScroll);
            materialBar.draw(gfx, mouseX, mouseY);
        } else {
            materialBar.active = false;
        }

        // ── Repeat count row ─────────────────────────────────────────
        drawRepeatRow(gfx, font);

        // Card view: JEI recipe preview for a hovered alternative-recipe badge (mirrors the
        // tree-view dropdown preview). Drawn here, post-scissor, so it's not clipped.
        renderCardPreview(gfx, font);

        renderOutputDestinationSelector(gfx, font, mouseX, mouseY);

        // Deferred tooltip — rendered AFTER all scissors, so Legendary
        // Tooltips' boundary avoidance works without scissor clipping.
        renderDeferredTooltip(gfx, font);
    }

    private void renderOutputDestinationSelector(GuiGraphics gfx, Font font, int mouseX, int mouseY) {
        if (outputSelectorH <= 0) return;

        String label = I18n.get("rsi.plan.output.label");
        int labelW = font.width(label);
        int gap = 8;
        int totalW = labelW + gap + outputSegmentW;
        int labelX = width / 2 - totalW / 2;
        outputSelectorX = labelX + labelW + gap;

        int textY = outputSelectorY + (outputSelectorH - font.lineHeight) / 2;
        gfx.drawString(font, label, labelX, textY, 0xFF99AA99, false);

        boolean hovered = mouseX >= outputSelectorX && mouseX < outputSelectorX + outputSegmentW
                && mouseY >= outputSelectorY && mouseY < outputSelectorY + outputSelectorH;
        int background = hovered ? 0xCC3B5948 : 0xCC2F7D4A;
        int textColor = hovered ? 0xFFFFFFFF : 0xFFF0FFF4;
        UIRenderer.rounded(gfx, outputSelectorX, outputSelectorY,
                outputSegmentW, outputSelectorH, 4f, 0xDD101512);
        UIRenderer.rounded(gfx, outputSelectorX + 1, outputSelectorY + 1,
                outputSegmentW - 2, outputSelectorH - 2, 3f, background);
        gfx.fill(outputSelectorX + 10, outputSelectorY + outputSelectorH - 2,
                outputSelectorX + outputSegmentW - 10, outputSelectorY + outputSelectorH - 1, 0xFF69D98A);

        Component destination = Component.translatable(outputDestination == OutputDestination.RS_NETWORK
                ? "rsi.plan.output.rs" : "rsi.plan.output.player");
        String value = destination.getString();
        int arrowW = font.width(" ↔");
        gfx.drawString(font, value + " ↔", outputSelectorX + (outputSegmentW - font.width(value + " ↔")) / 2,
                textY, textColor, false);
    }
    /** Card view: JEI recipe preview for the alternative-recipe badge under the mouse. */
    private void renderCardPreview(GuiGraphics gfx, Font font) {
        if (viewMode != ViewMode.CARD) return;
        if (cardPreviewId == null) {
            resetHoverIntent();
            return;
        }
        if (hoverIntentReady(cardPreviewId)) {
            recipePreview.renderRecipeTooltip(gfx, font, cardPreviewId,
                    mouseX, mouseY, width, height, mouseX, mouseY);
        }
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
        return drawStepCard(gfx, font, x, y, cardW, output, outputName, step, batches, idx, animIdx, false);
    }

    private int drawStepCard(GuiGraphics gfx, Font font, int x, int y, int cardW,
                              ItemStack output, String outputName,
                              PlanStep step, int batches, int idx, int animIdx,
                              boolean showFoldToggle) {
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

        // ── Fold toggle (▼/▶) — top-right corner ─────────────
        if (showFoldToggle && step != null) {
            String stepRid = step.recipeId().toString();
            boolean folded = isStepCollapsed(stepRid, step.depth());
            String icon = folded ? "▶" : "▼";
            int iconX = x + cardW - font.width(icon) - 8;
            int iconY = cy + 2;
            gfx.drawString(font, icon, iconX, iconY, 0xFF88AA88, false);
            // Record hitbox for mouseClicked (one per card, keyed by step + depth).
            int hitX = iconX - 2, hitY = iconY - 2;
            int hitW = font.width(icon) + 4, hitH = font.lineHeight + 4;
            foldHits.add(new FoldHit(hitX, hitY, hitW, hitH, stepRid, step.depth()));
            boolean hov = mouseX >= hitX && mouseX < hitX + hitW
                    && mouseY >= hitY && mouseY < hitY + hitH;
            if (hov) gfx.fill(hitX, hitY, hitX + hitW, hitY + hitH, 0x22FFFFFF);
        }

        // ── OR alternative badges ─────────────────────────────
        if (orBadgeH > 0 && orRendered.add(BuiltInRegistries.ITEM.getKey(step.output().getItem()).toString())) {
            String itemKey = BuiltInRegistries.ITEM.getKey(step.output().getItem()).toString();
            List<AltChoice> choices = altChoices.computeIfAbsent(itemKey, k -> new ArrayList<>());

            String curModType = step.modType() != null ? step.modType().id() : "generic";
            AltChoice curChoice = new AltChoice(step.recipeId(), curModType);
            if (!choices.contains(curChoice)) choices.add(curChoice);

            // Same source as the tree-view dropdown: the server-resolved sibling alternatives
            // (already filtered for materials + bound machines). Do NOT scan plan.steps() for
            // same-output steps — that pulled in unrelated recipes and produced bogus choices.
            for (int i = 0; i < step.alternatives().size() && i < step.alternativeModTypes().size(); i++) {
                AltChoice ac = new AltChoice(step.alternatives().get(i), step.alternativeModTypes().get(i));
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

                // Recipe preview on hover — deferred to the post-scissor overlay so the JEI
                // panel renders above every card and isn't clipped by the card scissor.
                if (mouseX >= badgeX && mouseX <= badgeX + bw
                        && mouseY >= badgeY && mouseY <= badgeY + badgeH) {
                    cardPreviewId = ac.recipeId;
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
        PlanResponse.Availability a = plan.availability(stack);
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
        IngredientKey selectionKey = findTreeKey(itemKey);
        if (selectionKey == null) return;
        int oldIdx = selectedPath.selectedIndex(selectionKey);
        if (oldIdx < 0) oldIdx = 0;
        int selected = oldIdx == index && index != 0 ? 0 : index;
        altSelection.put(itemKey, selected);
        selectedPath.selectBranch(selectionKey, selected, choices.get(selected).recipeId);

        Map<String, String> forced = exportForcedSelections();
        boolean isTarget = plan.targetResult() != null
                && itemKey.equals(BuiltInRegistries.ITEM.getKey(plan.targetResult().getItem()).toString());
        ResourceLocation rid = ResourceLocation.tryParse(plan.recipeId());
        if (isTarget && selected != 0) {
            rid = choices.get(selected).recipeId;
            forced.remove(itemKey);
        }
        LAST_FORCED.clear();
        LAST_FORCED.putAll(forced);
        RSIntegrationMod.debug("[RSI-OR-UI] selectAlternative itemKey={} index={} forced={} root={}",
                itemKey, selected, forced, rid);
        sendCraftPacket(rid, true, forced, currentRepeat, false);
    }

    @Nullable
    private IngredientKey findTreeKey(String itemKey) {
        if (treeModel == null) return null;
        return findTreeKey(treeModel.root, itemKey);
    }

    @Nullable
    private IngredientKey findTreeKey(PlanTreeNode node, String itemKey) {
        if (BuiltInRegistries.ITEM.getKey(node.displayStack.getItem()).toString().equals(itemKey)) {
            return node.key;
        }
        for (PlanTreeNode child : node.children) {
            IngredientKey key = findTreeKey(child, itemKey);
            if (key != null) return key;
        }
        return null;
    }

    // ── Slot drawing ──────────────────────────────────────────────

    /** @param needed       threshold for color (green/orange/red)
     *  @param displayCount number shown in the slot corner; 0 = hide */
    private void drawSlot(GuiGraphics gfx, Font font, int x, int y,
                           ItemStack stack, int needed, int displayCount, boolean isInput) {
        int avail = 0;
        PlanResponse.Availability a = plan.availability(stack);
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

    // ── Recipe-tree rendering + camera (v3.4) ────────────────────

    private void renderTreeArea(GuiGraphics gfx, int left, int areaTop, int contentW,
                                int areaBottom, int mouseX, int mouseY) {
        if (areaBottom <= areaTop) return;
        treeLayout.ensureLayout(treeModel.root);

        // Backdrop over the whole area.
        UIRenderer.rounded(gfx, left, areaTop, contentW, areaBottom - areaTop, 6f, 0x66060A08);

        // The tree viewport fills the whole area; the total-demand strip is drawn inside
        // the camera layer, anchored above the root — it no longer eats layout height.
        treeViewLeft = left;
        treeViewTop = areaTop;
        treeViewRight = left + contentW;
        treeViewBottom = areaBottom;
        if (!treeCameraInit) centerCamera();

        PlanTreeNode hovered = nodeAt(mouseX, mouseY);
        // Reset the hover-intent timer whenever the mouse isn't resting on a previewable node
        // (dropdown-driven previews manage their own timer, so leave it alone while one is open).
        if (dropdownNode == null && (hovered == null || hovered.step == null)) {
            resetHoverIntent();
        }
        if (hovered != null && !hovered.displayStack.isEmpty()) {
            // Recipe preview takes priority over the plain item tooltip (§3.7). Only fall back
            // to the item tooltip when no preview was drawn (leaf node, or JEI unavailable), or
            // while the 150ms hover-intent delay hasn't elapsed yet.
            boolean previewShown = false;
            if (dropdownNode == null && hovered.step != null
                    && hoverIntentReady(hovered.step.recipeId())) {
                previewShown = recipePreview.renderRecipeTooltip(gfx, font, hovered.step.recipeId(),
                        mouseX, mouseY, width, height, mouseX, mouseY);
            }
            if (!previewShown && hoveredItemForTooltip.isEmpty()) {
                hoveredItemForTooltip = hovered.displayStack;
                hoveredTooltipX = mouseX;
                hoveredTooltipY = mouseY;
                hoveredTooltipAvail = hovered.available;
                hoveredTooltipNeeded = hovered.needed > 0 ? hovered.needed : hovered.amount;
            }
        }

        costHits.clear();
        gfx.enableScissor(left, treeViewTop, left + contentW, areaBottom);
        try {
            gfx.pose().pushPose();
            gfx.pose().translate(treePanX, treePanY, 0);
            gfx.pose().scale((float) treeZoom, (float) treeZoom, 1f);
            treeRenderer.render(gfx, treeLayout, selectedPath, hovered);
            // Total-demand + leftover strip, anchored above the root in logical space so it
            // pans/zooms with the tree. Data is server-authoritative plan.materials()/leftovers()
            // — same source as the card material panel, so the two never diverge (design doc §4).
            renderRootStrip(gfx, minecraft.font, mouseX, mouseY);
            gfx.pose().popPose();
        } finally {
            gfx.disableScissor();
        }

        // Top toolbar is a HUD overlay: elevate it above the tree (whose batched nodes/item icons
        // flush after this and would otherwise overdraw the immediate-mode info-icon circle).
        gfx.pose().pushPose();
        gfx.pose().translate(0, 0, 250);

        // Branch-customized status marker (screen space, top-left of the viewport).
        if (selectedPath.isDirty()) {
            gfx.drawString(font, I18n.get("rsi.plan.status_custom"),
                    left + 6, treeViewTop + 2, 0xFF4AE04A, false);
        }

        // Top-right toolbar: a circled "i" help icon (hover for controls) + expand/collapse-all.
        int infoSize = 13;
        int infoX = left + contentW - infoSize - 4;
        int infoY = treeViewTop + 2;
        boolean infoHov = mouseX >= infoX && mouseX < infoX + infoSize
                && mouseY >= infoY && mouseY < infoY + infoSize;
        UIRenderer.rounded(gfx, infoX, infoY, infoSize, infoSize, infoSize / 2f,
                infoHov ? 0xFF4A8A4A : 0xFF2D5A2D);
        UIRenderer.rounded(gfx, infoX + 1, infoY + 1, infoSize - 2, infoSize - 2, (infoSize - 2) / 2f,
                infoHov ? 0xFF204020 : 0xFF13260F);
        int glyphX = infoX + infoSize / 2;
        int glyphCol = infoHov ? 0xFFE0FFE0 : 0xFF9BD59B;
        gfx.fill(glyphX, infoY + 3, glyphX + 1, infoY + 4, glyphCol);             // dot of the "i"
        gfx.fill(glyphX, infoY + 5, glyphX + 1, infoY + infoSize - 3, glyphCol);  // stem of the "i"

        // Expand/Collapse-all toolbar button, left of the info icon (mirrors card view).
        boolean allExpanded = treeAllExpanded(treeModel.root);
        String faLabel = allExpanded ? "▼ " + I18n.get("rsi.plan.collapse_all")
                : "▶ " + I18n.get("rsi.plan.expand_all");
        treeFoldAllHitW = font.width(faLabel) + 8;
        treeFoldAllHitH = font.lineHeight + 4;
        treeFoldAllHitX = infoX - treeFoldAllHitW - 4;
        treeFoldAllHitY = treeViewTop + 2;
        boolean faHov = mouseX >= treeFoldAllHitX && mouseX < treeFoldAllHitX + treeFoldAllHitW
                && mouseY >= treeFoldAllHitY && mouseY < treeFoldAllHitY + treeFoldAllHitH;
        gfx.fill(treeFoldAllHitX, treeFoldAllHitY, treeFoldAllHitX + treeFoldAllHitW,
                treeFoldAllHitY + treeFoldAllHitH, faHov ? 0x551A3E1A : 0x331A3E1A);
        gfx.drawString(font, faLabel, treeFoldAllHitX + 4, treeFoldAllHitY + 2, 0xFF44AA66, false);

        gfx.pose().popPose();

        // Alternative-recipe dropdown (+ its hover preview), topmost.
        renderDropdown(gfx, minecraft.font, mouseX, mouseY);

        // Control-help tooltip — shown only when hovering the info icon (replaces the old hint bar).
        if (infoHov) {
            hoveredItemForTooltip = ItemStack.EMPTY;
            List<Component> lines = new ArrayList<>();
            lines.add(Component.translatable("rsi.plan.tree_help_title").withStyle(ChatFormatting.GREEN));
            for (String ln : I18n.get("rsi.plan.tree_help").split("\n")) {
                lines.add(Component.literal(ln).withStyle(ChatFormatting.GRAY));
            }
            gfx.renderComponentTooltip(font, lines, mouseX, mouseY);
        }
    }

    /**
     * Total-demand + leftover rows, drawn in the tree's logical coordinate space just above
     * the root node so they pan/zoom with the tree. Data is server-authoritative
     * {@link PlanResponse#materials()} / {@link PlanResponse#leftovers()} — the same source as
     * the card-view material panel, so the two can never diverge (design doc §4).
     */
    private void renderRootStrip(GuiGraphics gfx, Font font, int mouseX, int mouseY) {
        PlanTreeLayout.Box rootBox = treeLayout.boxFor(treeModel.root);
        if (rootBox == null) return;
        int rows = stripRowCount();
        if (rows == 0) return;

        int anchorX = rootBox.itemCenterX();
        int rowY = rootBox.y() - STRIP_GAP - rows * STRIP_ROW_H;
        if (!plan.materials().isEmpty()) {
            renderStripRow(gfx, font, anchorX, rowY, I18n.get("rsi.plan.cost_total"),
                    0xFF76B16F, buildTotalEntries(), false, mouseX, mouseY);
            rowY += STRIP_ROW_H;
        }
        if (!plan.leftovers().isEmpty()) {
            renderStripRow(gfx, font, anchorX, rowY, I18n.get("rsi.plan.cost_leftover"),
                    0xFFF08A53, buildLeftoverEntries(), true, mouseX, mouseY);
        }
    }

    private int stripRowCount() {
        return (plan.materials().isEmpty() ? 0 : 1) + (plan.leftovers().isEmpty() ? 0 : 1);
    }

    private List<StripEntry> buildTotalEntries() {
        List<StripEntry> out = new ArrayList<>(plan.materials().size());
        for (var e : plan.materials().entrySet()) {
            ItemStack st = e.getKey().stack(1);
            out.add(new StripEntry(st, e.getKey(), e.getValue().needed(),
                    e.getValue().available(), e.getValue().isEnough()));
        }
        return out;
    }

    private List<StripEntry> buildLeftoverEntries() {
        List<StripEntry> out = new ArrayList<>(plan.leftovers().size());
        for (var e : plan.leftovers().entrySet()) {
            ItemStack st = e.getKey().stack(1);
            out.add(new StripEntry(st, e.getKey(), e.getValue(), 0, true));
        }
        return out;
    }

    /**
     * One strip row in logical (pre-zoom) space, centered on {@code anchorX}. Hitboxes are stored
     * in screen space (logical×zoom + pan) so click-to-center and hover work under the transform.
     */
    private void renderStripRow(GuiGraphics gfx, Font font, int anchorX, int rowY, String label,
                                int accent, List<StripEntry> entries, boolean leftover,
                                int mouseX, int mouseY) {
        int labelW = font.width(label);
        int rowW = labelW + 8;
        for (StripEntry e : entries) rowW += 18 + font.width(stripCountText(e, leftover)) + 6;

        int startX = anchorX - rowW / 2;

        int textY = rowY + (STRIP_ROW_H - font.lineHeight) / 2;
        gfx.drawString(font, label, startX, textY, accent, false);
        int sx = startX + labelW + 8;
        int slotY = rowY + (STRIP_ROW_H - 16) / 2;
        for (StripEntry e : entries) {
            gfx.renderItem(e.display(), sx, slotY);
            String cnt = stripCountText(e, leftover);
            int cntCol = leftover ? 0xFFF0B37A : (e.enough() ? 0xFFD8E8DC : 0xFFE0533B);
            gfx.drawString(font, cnt, sx + 18, textY, cntCol, false);

            int scrX = (int) Math.round(sx * treeZoom + treePanX);
            int scrY = (int) Math.round(slotY * treeZoom + treePanY);
            int scrS = Math.max(1, (int) Math.round(16 * treeZoom));
            if (mouseX >= scrX && mouseX < scrX + scrS && mouseY >= scrY && mouseY < scrY + scrS) {
                drawHoverBorder(gfx, sx, slotY, 16);
                hoveredItemForTooltip = e.display();
                hoveredTooltipX = mouseX;
                hoveredTooltipY = mouseY;
                PlanResponse.Availability a = plan.availability(e.key());
                hoveredTooltipAvail = a != null ? a.available() : 0;
                hoveredTooltipNeeded = e.count();
            }
            costHits.add(new CostHit(scrX, scrY, scrS, scrS, e.key()));
            sx += 18 + font.width(cnt) + 6;
        }
    }

    /** Total-demand entries show "have/need" (e.g. "3/128"); leftover entries show a single surplus count. */
    private static String stripCountText(StripEntry e, boolean leftover) {
        return leftover ? String.valueOf(e.count()) : e.available() + "/" + e.count();
    }

    /** Pan the camera so the node with this key sits at the viewport center. */
    private void centerOnKey(IngredientKey key) {
        treeLayout.ensureLayout(treeModel.root);
        PlanTreeLayout.Box b = treeLayout.boxForKey(key);
        if (b == null) return;
        double viewCx = (treeViewLeft + treeViewRight) / 2.0;
        double viewCy = (treeViewTop + treeViewBottom) / 2.0;
        treePanX = viewCx - b.itemCenterX() * treeZoom;
        treePanY = viewCy - (b.y() + PlanTreeLayout.NODE_HEIGHT / 2.0) * treeZoom;
    }

    /**
     * Switch a node to an alternative recipe. Reuses the card-mode server round-trip: update the
     * shared {@code forced} overrides ({@link #LAST_FORCED}) and send a preview
     * {@link GenericCraftPacket}; the server re-resolves and the returned {@link PlanResponse}
     * flows through {@link #updatePlan}, which rebuilds the tree. Target-node switches replace the
     * root recipe; intermediate switches go into the forced map keyed by item id.
     */
    private void selectTreeBranch(PlanTreeNode node, ResourceLocation recipeId) {
        dropdownNode = null;
        if (node.step == null) return;

        int idx = Math.max(0, node.step.alternatives().indexOf(recipeId));
        selectedPath.selectBranch(node.key, idx, recipeId);

        Map<String, String> forced = exportForcedSelections();
        ResourceLocation rootRid = ResourceLocation.tryParse(plan.recipeId());
        String itemKey = BuiltInRegistries.ITEM.getKey(node.displayStack.getItem()).toString();

        boolean isTarget = plan.targetResult() != null
                && node.displayStack.getItem() == plan.targetResult().getItem();
        if (isTarget) {
            rootRid = recipeId;
            forced.remove(itemKey);
        } else {
            forced.put(itemKey, recipeId.toString());
        }

        LAST_FORCED.clear();
        LAST_FORCED.putAll(forced);
        sendCraftPacket(rootRid, true, forced, currentRepeat, false);
    }

    /** Render the open alternative-recipe dropdown below its node (screen space). */
    /**
     * Hover-intent gate: returns true only once the mouse has rested on {@code id} for at least
     * {@link #HOVER_INTENT_MS}. Switching to a different target restarts the timer, so sweeping
     * the mouse across nodes/candidates never flashes previews.
     */
    private boolean hoverIntentReady(ResourceLocation id) {
        long now = System.currentTimeMillis();
        if (!id.equals(hoverPreviewId)) {
            hoverPreviewId = id;
            hoverPreviewStart = now;
            return false;
        }
        return now - hoverPreviewStart >= HOVER_INTENT_MS;
    }

    /** Clear the hover-intent timer when the mouse leaves every preview target. */
    private void resetHoverIntent() {
        hoverPreviewId = null;
    }

    private void renderDropdown(GuiGraphics gfx, Font font, int mouseX, int mouseY) {
        dropHits.clear();
        if (dropdownNode == null) return;
        PlanTreeLayout.Box box = treeLayout.boxFor(dropdownNode);
        if (box == null) {
            dropdownNode = null;
            return;
        }
        List<ResourceLocation> alts = dropdownNode.step.alternatives();
        List<String> mods = dropdownNode.step.alternativeModTypes();
        if (alts.isEmpty()) return;

        int maxShown = Math.min(alts.size(), RSIntegrationConfig.RECIPE_TREE_MAX_CANDIDATES.get());
        boolean truncated = maxShown < alts.size();

        Set<String> passport = plan.boundMachineTypes() != null
                ? plan.boundMachineTypes() : Collections.emptySet();

        // Fit-content width: measure every row's label (lock icon + machine title) once and size
        // the panel to the widest, clamped so it never collapses too small nor runs off-viewport.
        int rowH = 18;
        int textLeft = 22;   // 2px pad + 16px icon + 4px gap
        String[] labels = new String[maxShown];
        int contentW = 0;
        for (int i = 0; i < maxShown; i++) {
            ResourceLocation rid = alts.get(i);
            String mod = i < mods.size() && mods.get(i) != null ? mods.get(i) : "";
            boolean machineBound = mod.isEmpty() || passport.contains(mod);
            String base = recipePreview.categoryTitle(rid).map(Component::getString)
                    .orElseGet(() -> {
                        String recipeName = PlanRenderEngine.formatRecipeName(rid);
                        return mod.isEmpty() ? recipeName
                                : PlanRenderEngine.formatModTypeLabel(mod) + " · " + recipeName;
                    });
            labels[i] = (machineBound ? "" : "🔒") + base;
            contentW = Math.max(contentW, font.width(labels[i]));
        }
        if (truncated) {
            contentW = Math.max(contentW,
                    font.width(I18n.get("rsi.plan.candidates_more", alts.size() - maxShown)));
        }

        int panelW = Math.max(120, Math.min(260, textLeft + contentW + 8));
        int sx = (int) Math.round(box.x() * treeZoom + treePanX);
        int sy = (int) Math.round(box.bottom() * treeZoom + treePanY) + 2;
        sx = Math.max(treeViewLeft, Math.min(sx, treeViewRight - panelW));
        int panelH = (maxShown + (truncated ? 1 : 0)) * rowH + 2;

        // Suppress the node tooltip while hovering the panel.
        if (mouseX >= sx && mouseX < sx + panelW && mouseY >= sy && mouseY < sy + panelH) {
            hoveredItemForTooltip = ItemStack.EMPTY;
        }

        gfx.fill(sx, sy, sx + panelW, sy + panelH, 0xF00A140E);
        gfx.fill(sx, sy, sx + panelW, sy + 1, 0xFF1FB6D6);

        int hoverIdx = -1;
        for (int i = 0; i < maxShown; i++) {
            ResourceLocation rid = alts.get(i);
            int ry = sy + 1 + i * rowH;
            boolean hov = mouseX >= sx && mouseX < sx + panelW && mouseY >= ry && mouseY < ry + rowH;
            if (hov) hoverIdx = i;
            boolean active = hov || i == dropdownCursor;   // mouse hover or keyboard cursor
            boolean sel = rid.equals(dropdownNode.step.recipeId());
            // v3.4 availability passport: check if machine is bound.
            String mod = i < mods.size() && mods.get(i) != null ? mods.get(i) : "";
            boolean machineBound = mod.isEmpty() || passport.contains(mod);
            int bgColor = active ? 0x33FFFFFF : (!machineBound ? 0x18101010 : 0x00000000);
            if (bgColor != 0) gfx.fill(sx, ry, sx + panelW, ry + rowH, bgColor);
            // Per-recipe machine icon so alternatives are visually distinct (all share one output).
            boolean drewIcon = recipePreview.drawCategoryIcon(gfx, rid, sx + 2, ry + 1, 16);
            if (!drewIcon) gfx.renderItem(dropdownNode.displayStack, sx + 2, ry + 1);
            int textColor = sel ? 0xFF4AE04A
                    : machineBound ? 0xFFCCCCCC : 0xFF666666;
            String label = font.plainSubstrByWidth(labels[i], panelW - textLeft - 2);
            gfx.drawString(font, label, sx + textLeft, ry + (rowH - font.lineHeight) / 2,
                    textColor, false);
            dropHits.add(new DropHit(sx, ry, panelW, rowH, rid));
        }

        // Truncation row: "+N more" when the candidate cap hides alternatives.
        if (truncated) {
            int ry = sy + 1 + maxShown * rowH;
            String more = I18n.get("rsi.plan.candidates_more", alts.size() - maxShown);
            gfx.drawString(font, more, sx + textLeft, ry + (rowH - font.lineHeight) / 2, 0xFF889988, false);
        }

        // Preview: draw the active alternative's full JEI recipe layout so switching feels like a
        // proper picker (mirrors the node-hover preview). The active row is the mouse-hovered one,
        // or the keyboard cursor when the mouse is elsewhere; the preview anchors to it accordingly.
        // Falls back to the output item's tooltip when JEI has no layout for that recipe.
        int activeIdx = hoverIdx >= 0 ? hoverIdx
                : (dropdownCursor >= 0 && dropdownCursor < maxShown ? dropdownCursor : -1);
        if (activeIdx < 0) {
            resetHoverIntent();
        } else {
            boolean byMouse = hoverIdx >= 0;
            // Mouse-hover previews wait out the 150ms hover-intent; keyboard-cursor selection is
            // deliberate navigation, so its preview shows immediately.
            boolean ready = !byMouse || hoverIntentReady(alts.get(activeIdx));
            int anchorX = byMouse ? mouseX : sx + panelW + 6;
            int anchorY = byMouse ? mouseY : sy + 1 + activeIdx * rowH;
            boolean shown = ready && recipePreview.renderRecipeTooltip(gfx, font, alts.get(activeIdx),
                    anchorX, anchorY, width, height, mouseX, mouseY);
            if (!shown && byMouse) {
                hoveredItemForTooltip = dropdownNode.displayStack;
                hoveredTooltipX = mouseX;
                hoveredTooltipY = mouseY;
                hoveredTooltipAvail = hoveredTooltipNeeded = 0;
            }
        }
    }

    /**
     * Recompute the bottom region stack (embers → missing → material panel → repeat row).
     * The material panel is card-view only (design doc §4.4); in tree view its band collapses
     * to zero so the tree reclaims the space and everything above shifts down accordingly.
     */
    private void layoutBottomStack() {
        int repeatAreaH = 38;
        repeatRowY = height - 51 - repeatAreaH;
        int effMaterialH = (viewMode == ViewMode.CARD) ? materialAreaHeight : 0;
        materialAreaTop = repeatRowY - effMaterialH;
        missingAreaTop = materialAreaTop - missingAreaHeight;
        int stackTop = missingAreaHeight > 0 ? missingAreaTop : materialAreaTop;
        embersPedestalY = embersPedestalH > 0 ? stackTop - embersPedestalH : stackTop;
        embersModeY = embersPedestalY + embersCardsH;
    }

    /** Center the root near the top-center of the viewport (leaving room for the strip) and reset zoom. */
    private void centerCamera() {
        treeLayout.ensureLayout(treeModel.root);
        PlanTreeLayout.Box rootBox = treeLayout.boxFor(treeModel.root);
        int rootCenter = rootBox != null ? rootBox.itemCenterX() : treeLayout.totalWidth() / 2;
        double viewW = treeViewRight - treeViewLeft;
        treeZoom = 1.0;
        treePanX = treeViewLeft + viewW / 2 - rootCenter * treeZoom;
        int rows = stripRowCount();
        double reserved = rows > 0 ? rows * STRIP_ROW_H + STRIP_GAP : 0;
        treePanY = treeViewTop + 12 + reserved;
        treeCameraInit = true;
    }

    private boolean inTreeViewport(double sx, double sy) {
        return sx >= treeViewLeft && sx < treeViewRight
                && sy >= treeViewTop && sy < treeViewBottom;
    }

    /** Node under a screen point via the single screenToTree inverse transform, or null. */
    private PlanTreeNode nodeAt(double sx, double sy) {
        if (viewMode != ViewMode.TREE || !inTreeViewport(sx, sy)) return null;
        int lx = (int) Math.round((sx - treePanX) / treeZoom);
        int ly = (int) Math.round((sy - treePanY) / treeZoom);
        PlanTreeLayout.Box box = treeLayout.hitTest(lx, ly);
        return box == null ? null : box.node();
    }

    /** Collapse/expand every node sharing the target's key (same-key sync, §3.6). */
    private void toggleCollapseSameKey(PlanTreeNode target) {
        if (target == treeModel.root || target.isLeaf()) return;
        boolean expand = !target.expanded;
        setExpandedForKey(treeModel.root, target.key, expand);
        treeLayout.markDirty();
    }

    private void setExpandedForKey(PlanTreeNode node, IngredientKey key, boolean expand) {
        if (node.key.equals(key) && !node.isLeaf()) node.expanded = expand;
        for (PlanTreeNode child : node.children) setExpandedForKey(child, key, expand);
    }

    /** True when every foldable (non-leaf, non-root) node in the tree is expanded. */
    private boolean treeAllExpanded(PlanTreeNode node) {
        if (!node.isLeaf() && node.depth != 0 && !node.expanded) return false;
        for (PlanTreeNode child : node.children) {
            if (!treeAllExpanded(child)) return false;
        }
        return true;
    }

    /** Expand or collapse every foldable node. Collapse keeps the root open so the tree never vanishes. */
    private void setAllExpanded(PlanTreeNode node, boolean expand) {
        boolean keepOpen = !expand && node.depth == 0;
        if (!node.isLeaf() && !keepOpen) node.expanded = expand;
        for (PlanTreeNode child : node.children) setAllExpanded(child, expand);
    }

    /** Node whose left-edge [+]/[−] fold indicator is under a screen point, or null. */
    private PlanTreeNode foldIndicatorAt(double sx, double sy) {
        if (viewMode != ViewMode.TREE || !inTreeViewport(sx, sy)) return null;
        int lx = (int) Math.round((sx - treePanX) / treeZoom);
        int ly = (int) Math.round((sy - treePanY) / treeZoom);
        for (PlanTreeLayout.Box b : treeLayout.boxes()) {
            PlanTreeNode n = b.node();
            if (n.isLeaf() || n.depth == 0 || n.children.isEmpty()) continue;
            int cx = b.x() - 6;                 // matches PlanTreeRenderer.drawFoldIndicator anchor
            int cy = b.y() + b.h() / 2;
            if (lx >= cx - 5 && lx <= cx + 5 && ly >= cy - 5 && ly <= cy + 5) return n;
        }
        return null;
    }

    /** Index of the currently-selected recipe within a node's (capped) alternative list, else 0. */
    private int selectedAltIndex(PlanTreeNode node) {
        List<ResourceLocation> alts = node.step.alternatives();
        int cap = Math.min(alts.size(), RSIntegrationConfig.RECIPE_TREE_MAX_CANDIDATES.get());
        for (int i = 0; i < cap; i++) {
            if (alts.get(i).equals(node.step.recipeId())) return i;
        }
        return 0;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (viewMode == ViewMode.TREE && inTreeViewport(mouseX, mouseY)) {
            if (hasControlDown()) {
                double factor = delta > 0 ? 1.1 : 1 / 1.1;
                double newZoom = Math.max(0.5, Math.min(2.5, treeZoom * factor));
                // Keep the point under the cursor fixed while zooming.
                double lx = (mouseX - treePanX) / treeZoom;
                double ly = (mouseY - treePanY) / treeZoom;
                treeZoom = newZoom;
                treePanX = mouseX - lx * treeZoom;
                treePanY = mouseY - ly * treeZoom;
                return true;
            }
            // Wheel over the target (root) node adjusts the whole-plan repeat count;
            // the debounced refresh (requestPlanRefresh) re-resolves after scrolling stops.
            PlanTreeNode node = nodeAt(mouseX, mouseY);
            if (node != null && node == treeModel.root) {
                int stepBy = hasShiftDown() ? 10 : 1;
                int dir = delta > 0 ? 1 : -1;
                currentRepeat = Math.max(1, Math.min(64, currentRepeat + dir * stepBy));
                requestPlanRefresh();
                return true;
            }
            treePanY += delta * 20;
            return true;
        }
        // Material grid scroll — card view only (the panel is hidden in tree view).
        if (viewMode == ViewMode.CARD && materialAreaHeight > 0 && materialMaxScroll > 0
                && mouseX >= 20 && mouseX <= width - 20
                && mouseY >= materialAreaTop && mouseY <= materialAreaTop + materialAreaHeight) {
            materialScroll = Math.max(0, Math.min(materialMaxScroll,
                    materialScroll - (int) delta * (SLOT_SIZE + 8)));
            return true;
        }
        scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset - (int) delta * 10));
        return true;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        // Scrollbar thumb drag takes precedence — map the cursor to a scroll offset.
        if (draggingBar != null) {
            int s = draggingBar.scrollForThumbTop((int) my - scrollbarGrabDy);
            if (draggingBar == cardBar) scrollOffset = s;
            else if (draggingBar == materialBar) materialScroll = s;
            return true;
        }
        if (viewMode == ViewMode.TREE && (button == 1 || button == 2)) {
            treePanX += dx;
            treePanY += dy;
            return true;
        }
        if (dragging) {
            scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset - (int) dy));
        }
        return true;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0 && outputSelectorH > 0
                && mx >= outputSelectorX && mx < outputSelectorX + outputSegmentW
                && my >= outputSelectorY && my < outputSelectorY + outputSelectorH) {
            selectOutputDestination(outputDestination == OutputDestination.RS_NETWORK
                    ? OutputDestination.PLAYER_INVENTORY : OutputDestination.RS_NETWORK);
            return true;
        }        // Scrollbar thumbs first (both view modes) — grab the thumb, or click the track to jump.
        if (button == 0) {
            for (ScrollbarUI bar : new ScrollbarUI[]{cardBar, materialBar}) {
                if (bar.overThumb(mx, my)) {
                    draggingBar = bar;
                    scrollbarGrabDy = (int) my - bar.thumbY;
                    return true;
                }
                if (bar.overTrack(mx, my)) {
                    draggingBar = bar;
                    scrollbarGrabDy = bar.thumbH / 2; // center the thumb under the cursor
                    int s = bar.scrollForThumbTop((int) my - scrollbarGrabDy);
                    if (bar == cardBar) scrollOffset = s; else materialScroll = s;
                    return true;
                }
            }
        }
        if (viewMode == ViewMode.TREE) {
            // Expand/Collapse-all toolbar button (top-right of the viewport).
            if (button == 0 && treeFoldAllHitW > 0
                    && mx >= treeFoldAllHitX && mx < treeFoldAllHitX + treeFoldAllHitW
                    && my >= treeFoldAllHitY && my < treeFoldAllHitY + treeFoldAllHitH) {
                setAllExpanded(treeModel.root, !treeAllExpanded(treeModel.root));
                treeLayout.markDirty();
                return true;
            }
            // Dropdown rows first — they may extend outside the tree viewport.
            if (button == 0 && dropdownNode != null) {
                for (DropHit dh : dropHits) {
                    if (mx >= dh.x() && mx < dh.x() + dh.w()
                            && my >= dh.y() && my < dh.y() + dh.h()) {
                        selectTreeBranch(dropdownNode, dh.recipeId());
                        return true;
                    }
                }
            }
            if (button == 0) {
                for (CostHit ch : costHits) {
                    if (mx >= ch.x() && mx < ch.x() + ch.w()
                            && my >= ch.y() && my < ch.y() + ch.h()) {
                        centerOnKey(ch.key());
                        return true;
                    }
                }
                // Left-edge [+]/[−] fold indicator (sits just outside the node box).
                PlanTreeNode fold = foldIndicatorAt(mx, my);
                if (fold != null) {
                    toggleCollapseSameKey(fold);
                    return true;
                }
            }
            if (inTreeViewport(mx, my)) {
                if (button == 1) {
                    PlanTreeNode node = nodeAt(mx, my);
                    if (node != null) toggleCollapseSameKey(node);
                } else if (button == 0) {
                    PlanTreeNode node = nodeAt(mx, my);
                    if (node != null && node.hasAlternatives()) {
                        boolean opening = node != dropdownNode;
                        dropdownNode = opening ? node : null;
                        dropdownCursor = opening ? selectedAltIndex(node) : -1;
                    } else if (node != null && node.step != null) {
                        // No alternatives → open recipe in JEI directly.
                        openRecipeInJei(node.step.recipeId());
                    }
                } else if (button == 2) {
                    // Middle-click → open recipe in JEI.
                    PlanTreeNode node = nodeAt(mx, my);
                    if (node != null && node.step != null) {
                        openRecipeInJei(node.step.recipeId());
                    }
                }
                // Consume in-viewport clicks so card-mode drag/scroll logic doesn't fire.
                return true;
            }
            // Clicked elsewhere → dismiss any open dropdown.
            dropdownNode = null;
        }
        // Card-view fold toggle hitboxes (active in card mode).
        if (button == 0 && viewMode == ViewMode.CARD) {
            // [Expand All] / [Collapse All] button.
            if (foldAllHitW > 0 && mx >= foldAllHitX && mx < foldAllHitX + foldAllHitW
                    && my >= foldAllHitY && my < foldAllHitY + foldAllHitH) {
                boolean allFolded = plan.steps().stream()
                        .allMatch(s -> isStepCollapsed(s.recipeId().toString(), s.depth()));
                for (PlanStep s : plan.steps()) {
                    collapsedSteps.put(s.recipeId().toString(), !allFolded);
                }
                return true;
            }
            // Individual step fold toggle — any card chevron or collapsed row.
            for (FoldHit fh : foldHits) {
                if (mx >= fh.x() && mx < fh.x() + fh.w()
                        && my >= fh.y() && my < fh.y() + fh.h()) {
                    toggleStepCollapsed(fh.stepId(), fh.depth());
                    return true;
                }
            }
        }
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
                    repeatBuf = Integer.toString(currentRepeat);
                    lastKeyTime = 0;
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
        draggingBar = null;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        boolean ctrl = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0;
        // Ctrl+0 — reset camera. Must precede the digit handler, which also matches KEY_0.
        if (ctrl && keyCode == GLFW.GLFW_KEY_0) {
            if (viewMode == ViewMode.TREE) {
                treeZoom = 1.0;
                treePanX = 0;
                treePanY = 0;
                treeCameraInit = false;
            }
            return true;
        }
        // Open dropdown captures navigation keys: Up/Down move, Enter confirms, Esc closes.
        if (dropdownNode != null && dropdownNode.step != null) {
            List<ResourceLocation> alts = dropdownNode.step.alternatives();
            int cap = Math.min(alts.size(), RSIntegrationConfig.RECIPE_TREE_MAX_CANDIDATES.get());
            if (cap > 0) {
                if (keyCode == GLFW.GLFW_KEY_UP) {
                    dropdownCursor = dropdownCursor <= 0 ? cap - 1 : dropdownCursor - 1;
                    return true;
                }
                if (keyCode == GLFW.GLFW_KEY_DOWN) {
                    dropdownCursor = (dropdownCursor + 1) % cap;
                    return true;
                }
                if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                    int idx = dropdownCursor >= 0 && dropdownCursor < cap ? dropdownCursor : 0;
                    selectTreeBranch(dropdownNode, alts.get(idx));
                    return true;
                }
                if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                    dropdownNode = null;
                    dropdownCursor = -1;
                    return true;
                }
            }
        }
        // Enter — one-click start (§2.5 speedrun): apply branch selections and execute.
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            onConfirm();
            return true;
        }
        // Digit keys accumulate the whole-plan repeat count (ignored while Ctrl is held).
        if (!ctrl && keyCode >= GLFW.GLFW_KEY_0 && keyCode <= GLFW.GLFW_KEY_9) {
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
    // ── Card folding helpers (§3.17) ──────────────────────────

    private boolean isStepCollapsed(String stepRid, int depth) {
        return collapsedSteps.getOrDefault(stepRid, depth > 0);
    }

    private void toggleStepCollapsed(String stepRid, int depth) {
        boolean cur = isStepCollapsed(stepRid, depth);
        collapsedSteps.put(stepRid, !cur);
    }

    /** Single-row collapsed card: mod dot + item icon + step number + batch count. */
    private int drawCollapsedStepRow(GuiGraphics gfx, Font font, int x, int y, int cardW,
                                     PlanStep step, int idx, int animIdx) {
        int rowH = font.lineHeight + 6;
        int slideX = renderEngine.animation().getSlideOffset(animIdx, 30);
        if (slideX != 0) {
            gfx.pose().pushPose();
            gfx.pose().translate(slideX, 0, 0);
        }

        // Thin rounded row background.
        UIRenderer.rounded(gfx, x, y, cardW, rowH, 4f, 0xE61A221E);
        gfx.fill(x + 1, y + 1, x + 4, y + rowH - 1, stepAccent(step, step.batches()));

        int tx = x + 8;

        // Mod color dot.
        if (step.modType() != null) {
            int dotColor = modLabelColor(step.modType().id());
            gfx.fill(tx, y + (rowH - 4) / 2, tx + 4, y + (rowH - 4) / 2 + 4, dotColor);
            tx += 8;
        }

        // Item icon (small).
        gfx.renderItem(step.output(), tx, y + (rowH - 16) / 2);
        tx += 18;

        // Name.
        String name = step.output().getHoverName().getString();
        int nameMaxW = x + cardW - tx - 30;
        name = font.plainSubstrByWidth(name, nameMaxW);
        gfx.drawString(font, name, tx, y + (rowH - font.lineHeight) / 2, 0xFFBBCCBB, false);

        // Step index + batch (▶ signals the row expands on click).
        String summary = "▶ Step " + (idx + 1) + "  ×" + step.batches();
        int sw = font.width(summary);
        gfx.drawString(font, summary, x + cardW - sw - 8, y + (rowH - font.lineHeight) / 2, 0xFF889988, false);

        // Whole collapsed row is clickable to expand it back.
        foldHits.add(new FoldHit(x, y, cardW, rowH, step.recipeId().toString(), step.depth()));

        if (slideX != 0) gfx.pose().popPose();
        return y + rowH;
    }

    private static int modLabelColor(String modTypeId) {
        if (modTypeId == null) return 0xFF8B8B8B;
        return switch (modTypeId) {
            case ModIds.MALUM -> 0xFF442288;
            case ModIds.EIDOLON -> 0xFF226644;
            case ModIds.FORBIDDEN_ARCANUS -> 0xFF663322;
            case ModIds.GOETY -> 0xFF222244;
            case ModIds.WIZARDS_REBORN -> 0xFF444466;
            case ModIds.EMBERS -> 0xFFCC6633;
            case ModIds.AETHERWORKS -> 0xFF3388AA;
            default -> 0xFF8B8B8B;
        };
    }

    private int stepAccent(PlanStep step, int batches) {
        if (step == null || step.inputs().isEmpty()) return C_ACCENT_NEUTRAL;
        for (ItemStack in : step.inputs()) {
            if (in.isEmpty()) continue;
            PlanResponse.Availability a = plan.availability(in);
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
