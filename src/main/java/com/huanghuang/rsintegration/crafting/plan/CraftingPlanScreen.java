package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.compat.ftbquests.QuestSubmissionRequestPacket;
import com.huanghuang.rsintegration.compat.ftbquests.QuestSubmissionTargetIds;
import com.huanghuang.rsintegration.client.RecipeBrowserBridge;
import com.huanghuang.rsintegration.client.JeiCraftingPlanContext;
import com.huanghuang.rsintegration.mods.apotheosis.ApothSpawnerPlanTarget;
import com.huanghuang.rsintegration.mods.apotheosis.network.ApothSpawnerExecutePacket;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.huanghuang.rsintegration.crafting.CraftingResolver;
import com.huanghuang.rsintegration.crafting.planning.PlanningProgressTracker;
import com.huanghuang.rsintegration.crafting.planning.PlanningRequestIds;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.TooltipFlag;
import net.minecraftforge.fml.ModList;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.ClientSyncedConfig;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.batch.BatchCraftNetworkHandler;
import com.huanghuang.rsintegration.crafting.batch.GenericCraftPacket;
import com.huanghuang.rsintegration.crafting.batch.PrepareIntermediateMaterialsPacket;
import com.huanghuang.rsintegration.crafting.OutputDestination;
import com.huanghuang.rsintegration.crafting.MachineSelectionMode;
import com.huanghuang.rsintegration.crafting.MaterialLocks;
import com.huanghuang.rsintegration.storage.StorageNetworkDescriptor;
import com.huanghuang.rsintegration.storage.StorageReference;
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
import com.huanghuang.rsintegration.sidepanel.client.BindingBackendResolver;
import com.huanghuang.rsintegration.sidepanel.client.SidePanelJeiBridge;
import com.huanghuang.rsintegration.sidepanel.network.OpenBoundMachineGuiPacket;
import com.huanghuang.rsintegration.machine.BeyondDimensionsOpenBoundMachineGuiPacket;
import com.huanghuang.rsintegration.util.UIRenderer;
import com.huanghuang.rsintegration.util.ModIds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import org.lwjgl.glfw.GLFW;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.mods.ironsspellbooks.client.InkFluidRenderer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.loading.FMLPaths;

import javax.annotation.Nullable;
import java.nio.file.Path;
import java.util.*;

@OnlyIn(Dist.CLIENT)
public final class CraftingPlanScreen extends Screen {

    private static final Path PLAN_PREFS_PATH = FMLPaths.CONFIGDIR.get()
            .resolve("rs_integration").resolve("crafting_plan.json");
    private static final int SLOT_SIZE = 18;
    private static final int CARD_PAD = 8;
    private static final int ARROW_W = 18;
    private static final int STEPS_TOP_MIN = 40;
    /** Material grid caps at this many visible rows; extras scroll (§ material overflow fix). */
    private static final int MATERIAL_MAX_ROWS = 3;
    /**
     * In tree view the missing-material panel is a secondary diagnostic.  Keep it bounded so a
     * large shortage list cannot consume the whole tree viewport (or draw past its background).
     */
    private static final int TREE_MISSING_MAX_HEIGHT = 96;
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
    private int scrollOffset;
    private int maxScroll;
    private boolean dragging;
    private int missingAreaTop;
    private int missingAreaHeight;
    private int missingMaxScroll;
    private int missingScroll;
    private final ScrollbarUI missingBar = new ScrollbarUI();
    private int machineSelectorY;
    private int machineModeX, machineModeY, machineModeW, machineModeH;
    private int machineCandidateX, machineCandidateY, machineCandidateW, machineCandidateH;
    private boolean machineDropdownOpen;
    private int selectedMachineIndex = -1;
    private MachineSelectionMode machineSelectionMode = MachineSelectionMode.AUTO;
    private final List<MachineCandidateHit> machineCandidateHits = new ArrayList<>();
    private record MachineCandidateHit(int x, int y, int w, int h, int index) {}
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
    private static final int REPEAT_BUTTON_COUNT = 8;
    private static final int REPEAT_BUTTON_W = 22;
    private static final int REPEAT_BUTTON_H = 14;
    private static final int REPEAT_BUTTON_GAP = 3;
    private static final int REPEAT_INPUT_W = 57;
    private final int[] repeatBtnX = new int[REPEAT_BUTTON_COUNT];
    private final int[] repeatBtnY = new int[REPEAT_BUTTON_COUNT];
    private final int[] repeatBtnW = new int[REPEAT_BUTTON_COUNT];
    private final int[] repeatBtnH = new int[REPEAT_BUTTON_COUNT];
    private int countPillX, countPillY, countPillW, countPillH;
    @Nullable
    private EditBox repeatCountBox;
    private boolean updatingRepeatCountBox;
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
    private final List<BookmarkHit> bookmarkHits = new ArrayList<>();
    private record BookmarkHit(int x, int y, int w, int h, ItemStack stack, int missingCount) {}
    private int bookmarkAllActionX, bookmarkAllActionY;
    private int bookmarkAllActionW, bookmarkAllActionH;
    @Nullable
    private BookmarkHit hoveredBookmark;
    // Card-view fold toggle hitboxes (rebuilt each frame, read during mouseClicked).
    // One per rendered step card (expanded chevron OR collapsed row), so every step
    // stays clickable — not just the last one drawn.
    private final List<FoldHit> foldHits = new ArrayList<>();
    private record FoldHit(int x, int y, int w, int h, String stepId, int depth) {}
    // Card-view [Expand All] / [Collapse All] button hitbox.
    private int foldAllHitX, foldAllHitY, foldAllHitW, foldAllHitH;
    private boolean foldAllHovered;
    // Tree-view [Expand All] / [Collapse All] toolbar button hitbox (top-right of the viewport).
    private OutputDestination outputDestination;
    @Nullable
    private StorageReference storageReference;
    private List<StorageNetworkDescriptor> storageNetworks = List.of();
    private int outputSelectorX, outputSelectorY, outputSegmentW, outputSelectorH;
    private int preparationModeX, preparationModeY, preparationModeW, preparationModeH;
    /** Per-operation opt-in; strict terminal crafting remains the default. */
    private boolean partialPreparation;
    @Nullable
    private Button confirmButton;
    private int treeFoldAllHitX, treeFoldAllHitY, treeFoldAllHitW, treeFoldAllHitH;
    // 候选配方与主视图共用服务器分支选择，右侧面板只负责展示和导航。
    private PlanTreeNode dropdownNode;
    private RecipeCandidatePanel recipeCandidatePanel;
    private int contentLayoutWidth = -1;
    private ViewMode contentLayoutMode;
    private final Map<String, ItemStack> materialLocks = new LinkedHashMap<>();
    @Nullable
    private PlanTreeNode materialDropdownNode;
    private int materialDropdownScroll;
    @Nullable
    private EditBox materialSearchBox;
    private int materialPanelX, materialPanelY, materialPanelW, materialPanelH;
    private final List<MaterialDropHit> materialDropHits = new ArrayList<>();
    private record MaterialDropHit(int x, int y, int w, int h, ItemStack option) {}

    // Hover intent: the full JEI recipe preview only pops after the mouse rests on the same
    // target for HOVER_INTENT_MS, so a quick pass across nodes/candidates doesn't spam previews.
    private static final long HOVER_INTENT_MS = 150L;
    @Nullable
    private ResourceLocation hoverPreviewId;
    private long hoverPreviewStart;


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

    // 保留服务器预览刷新所需的分支选择，候选面板与树形视图共用。
    private static final Map<String, String> LAST_FORCED = new HashMap<>();

    // Card-view step folding (§3.17). Keyed by recipeId string; default: root unfolded, rest folded.
    private final Map<String, Boolean> collapsedSteps = new LinkedHashMap<>();

    private final List<ORHitbox> orHitboxes = new ArrayList<>();
    private final Set<String> orRendered = new HashSet<>();

    private record ORHitbox(int x, int y, int w, int h, String selectionKey) {}

    // Deferred tooltip — set during draw, rendered after all scissors disabled
    private ItemStack hoveredItemForTooltip = ItemStack.EMPTY;
    private List<Component> hoveredStepWarnings = List.of();
    private int hoveredTooltipX, hoveredTooltipY;
    private int hoveredTooltipAvail, hoveredTooltipNeeded;

    protected CraftingPlanScreen(PlanResponse plan) {
        super(Component.translatable("rsi.plan.title",
                plan.targetResult().getHoverName().getString()));
        this.plan = plan;
        this.activeRequestId = 0L;
        this.outputDestination = hasStorageBackend()
                ? CraftingPlanPreferences.loadOutputDestination(PLAN_PREFS_PATH)
                : OutputDestination.PLAYER_INVENTORY;
        this.storageReference = plan.storageReference();
        this.storageNetworks = plan.storageNetworks();
        // Adaptive view routing (§2.5): non-trivial plans open in the tree; simple ones stay on the card.
        this.viewMode = plan.steps().size() > 2 ? ViewMode.TREE : ViewMode.CARD;
        this.renderEngine = new PlanRenderEngine(Minecraft.getInstance().font);
        this.treeRenderer = new PlanTreeRenderer(Minecraft.getInstance().font);
        this.treeRenderer.setIconRenderer(recipePreview::drawCategoryIcon);
        rebuildTreeModel(true);
        JeiCraftingPlanContext.INSTANCE.activate(plan);
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
        if (newPlan.recipeId() == null || newPlan.recipeId().isEmpty()) {
            if (minecraft != null && minecraft.player != null && !newPlan.modWarnings().isEmpty()) {
                minecraft.player.displayClientMessage(newPlan.modWarnings().get(0), false);
            }
            return;
        }
        updatePlan(newPlan);
    }

    /** Update plan data in-place (OR-path switch, repeat-count change, etc.).
     *  Avoids creating a new screen which would lose UI state. */
    public void updatePlan(PlanResponse newPlan) {
        MachineCandidateView previous = selectedMachineCandidate();
        this.plan = newPlan;
        JeiCraftingPlanContext.INSTANCE.activate(newPlan);
        // The server response is authoritative.  Accepting only non-null
        // references leaves a stale RS selection alive after a backend switch
        // or an unavailable network response.
        this.storageReference = newPlan.storageReference();
        this.storageNetworks = newPlan.storageNetworks();
        if (previous != null) {
            selectedMachineIndex = findMachineCandidate(previous.dimension(), previous.x(), previous.y(), previous.z());
            if (selectedMachineIndex < 0) {
                selectedMachineIndex = -1;
                machineSelectionMode = MachineSelectionMode.AUTO;
            }
        }
        machineDropdownOpen = false;
        this.renderEngine = new PlanRenderEngine(Minecraft.getInstance().font);
        this.dropdownNode = null;
        this.materialScroll = 0;
        rebuildTreeModel(false);
        this.orHitboxes.clear();
        closeMaterialDropdown();
        this.clearWidgets();
        this.init();
    }

    private int findMachineCandidate(String dimension, int x, int y, int z) {
        List<MachineCandidateView> candidates = plan.machineCandidates();
        for (int i = 0; i < candidates.size(); i++) {
            MachineCandidateView candidate = candidates.get(i);
            if (candidate.dimension().equals(dimension) && candidate.x() == x
                    && candidate.y() == y && candidate.z() == z) return i;
        }
        return -1;
    }

    @Nullable
    private MachineCandidateView selectedMachineCandidate() {
        List<MachineCandidateView> candidates = plan == null ? List.of() : plan.machineCandidates();
        return selectedMachineIndex >= 0 && selectedMachineIndex < candidates.size()
                ? candidates.get(selectedMachineIndex) : null;
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
        JeiSubtreeBuilder.enrichCarousels(treeModel.root, materialLocks);
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
        currentRepeat = clampRepeatCount(plan.repeatCount());
        lastRefreshCount = currentRepeat;
        Font font = minecraft.font;
        if (recipeCandidatePanel == null) {
            recipeCandidatePanel = new RecipeCandidatePanel(font, recipePreview, this::selectTreeBranch);
        }
        closeRecipeCandidates();
        addWidget(recipeCandidatePanel.searchBox());
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
        int embersModeH = (showEmbersModeToggle = canInfer && embersCalcEnabled()) ? 28 : 0;
        embersPedestalH = embersCardsH + embersModeH;
        // Default mode: Calculate if code is known (from cache or computed), Infer otherwise
        embersInferMode = !hasEmbers;

        contentLayoutWidth = -1;
        layoutContentSections(font, contentW);
        layoutBottomStack();
        createRepeatCountInput(font);
        createMaterialSearchInput(font);

        int btnW = 80;
        int btnY = height - 24;

        boolean questSubmission = QuestSubmissionTargetIds.isQuestSubmission(
                ResourceLocation.tryParse(plan.recipeId()));
        if (!questSubmission) {
            outputSegmentW = 132;
            outputSelectorH = 20;
            outputSelectorX = width / 2 - outputSegmentW / 2;
            outputSelectorY = btnY - 27;
            preparationModeW = 124;
            preparationModeH = 20;
            preparationModeX = width / 2 - preparationModeW / 2;
            preparationModeY = outputSelectorY - 24;
        } else {
            outputSelectorH = 0;
            preparationModeH = 0;
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

        confirmButton = Button.builder(
                        executionActionLabel(),
                        btn -> onConfirm())
                .pos(width / 2 - btnW - 10, btnY)
                .size(selectedPath.isDirty() ? btnW + 20 : btnW, 20)
                .build();
        confirmButton.setTooltip(Tooltip.create(
                Component.translatable("rsi.plan.machine_clear_notice")));
        confirmButton.active = partialPreparation
                || !plan.executionBlocked() || selectedPath.isDirty();
        addRenderableWidget(confirmButton);

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
                    closeRecipeCandidates();
                    closeMaterialDropdown();
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

    private Component executionActionLabel() {
        if (partialPreparation) return Component.translatable("rsi.plan.prepare.action");
        return Component.translatable(selectedPath.isDirty()
                ? "rsi.plan.confirm_branches" : "rsi.plan.confirm");
    }

    private record RepeatRowLayout(int rowX, int rowW, int cardY, int buttonY,
                                   int inputX, int inputY, int inputH) {}

    private RepeatRowLayout repeatRowLayout(Font font) {
        int groupW = 4 * REPEAT_BUTTON_W + 3 * REPEAT_BUTTON_GAP;
        int innerGap = 12;
        int contentW = groupW + innerGap + REPEAT_INPUT_W + innerGap + groupW;
        int rowW = contentW + 28;
        int rowX = width / 2 - rowW / 2;
        int cardY = repeatRowY + font.lineHeight + 4;
        int cardH = 22;
        int buttonY = cardY + (cardH - REPEAT_BUTTON_H) / 2;
        int inputH = font.lineHeight + 6;
        int startX = rowX + 14;
        int inputX = startX + groupW + innerGap;
        int inputY = cardY + (cardH - inputH) / 2;
        return new RepeatRowLayout(rowX, rowW, cardY, buttonY,
                inputX, inputY, inputH);
    }

    private void createRepeatCountInput(Font font) {
        RepeatRowLayout layout = repeatRowLayout(font);
        repeatCountBox = new EditBox(font, layout.inputX(), layout.inputY(),
                REPEAT_INPUT_W, layout.inputH(), Component.translatable("rsi.plan.repeat_count"));
        repeatCountBox.setBordered(false);
        repeatCountBox.setTextColor(0xFFC8E6C9);
        repeatCountBox.setTextColorUneditable(0xFF8CB795);
        repeatCountBox.setMaxLength(Integer.toString(repeatCountLimit()).length());
        repeatCountBox.setFilter(value -> value.isEmpty()
                || value.chars().allMatch(Character::isDigit));
        updatingRepeatCountBox = true;
        repeatCountBox.setValue(Integer.toString(currentRepeat));
        updatingRepeatCountBox = false;
        repeatCountBox.setResponder(this::onRepeatCountEdited);
        addWidget(repeatCountBox);
    }

    private void createMaterialSearchInput(Font font) {
        materialSearchBox = new EditBox(font, 0, 0, 100, 16,
                Component.translatable("rsi.plan.material.search"));
        materialSearchBox.setHint(Component.translatable("rsi.plan.material.search"));
        materialSearchBox.setMaxLength(80);
        materialSearchBox.setBordered(false);
        materialSearchBox.setResponder(ignored -> materialDropdownScroll = 0);
        materialSearchBox.visible = false;
        materialSearchBox.active = false;
        addWidget(materialSearchBox);
    }

    private void onRepeatCountEdited(String value) {
        if (updatingRepeatCountBox || value.isEmpty()) return;
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 1L) return;
            int normalized = clampRepeatCount(parsed);
            setRepeatCount(normalized, parsed != normalized);
        } catch (NumberFormatException ignored) {
            setRepeatCount(repeatCountLimit(), true);
        }
    }

    private void setRepeatCount(int count, boolean updateInput) {
        int normalized = clampRepeatCount(count);
        boolean changed = normalized != currentRepeat;
        currentRepeat = normalized;
        if (updateInput && repeatCountBox != null
                && !Integer.toString(normalized).equals(repeatCountBox.getValue())) {
            updatingRepeatCountBox = true;
            repeatCountBox.setValue(Integer.toString(normalized));
            updatingRepeatCountBox = false;
        }
        if (changed) requestPlanRefresh();
    }

    private void commitRepeatCountInput() {
        if (repeatCountBox == null) return;
        long requested = currentRepeat;
        try {
            if (!repeatCountBox.getValue().isEmpty()) {
                requested = Long.parseLong(repeatCountBox.getValue());
            }
        } catch (NumberFormatException ignored) {
            requested = repeatCountLimit();
        }
        setRepeatCount(clampRepeatCount(requested), true);
    }

    private void focusRepeatCountInput() {
        if (repeatCountBox == null) return;
        setFocused(repeatCountBox);
        repeatCountBox.setFocused(true);
        repeatCountBox.setCursorPosition(repeatCountBox.getValue().length());
        repeatCountBox.setHighlightPos(0);
    }

    private void unfocusRepeatCountInput() {
        if (repeatCountBox == null || !repeatCountBox.isFocused()) return;
        commitRepeatCountInput();
        repeatCountBox.setFocused(false);
        setFocused(null);
    }

    private void selectOutputDestination(OutputDestination destination) {
        if (!hasStorageBackend()) {
            outputDestination = OutputDestination.PLAYER_INVENTORY;
            return;
        }
        OutputDestination selected = destination == null
                ? OutputDestination.RS_NETWORK : destination;
        if (selected == outputDestination) return;
        outputDestination = selected;
        if (!CraftingPlanPreferences.saveOutputDestination(PLAN_PREFS_PATH, selected)) {
            RSIntegrationMod.LOGGER.debug("[RSI-Plan] Failed to save output destination preference");
        }
    }

    private void cycleStorageTarget() {
        if (storageNetworks.isEmpty()) return;
        int current = -1;
        if (storageReference != null) {
            for (int i = 0; i < storageNetworks.size(); i++) {
                if (storageReference.equals(storageNetworks.get(i).reference())) {
                    current = i;
                    break;
                }
            }
        }
        StorageNetworkDescriptor next = storageNetworks.get((current + 1) % storageNetworks.size());
        storageReference = next.reference();
        RSIntegrationMod.debug("[RSI-Plan] selected storage target {}", storageReference);
        lastRefreshCount = -1;
        requestPlanRefresh();
    }

    private void onConfirm() {
        commitRepeatCountInput();
        if (!partialPreparation && plan.executionBlocked() && !selectedPath.isDirty()) return;
        String recipeId = plan.recipeId();
        ResourceLocation targetId = ResourceLocation.tryParse(recipeId);
        if (QuestSubmissionTargetIds
                .isQuestSubmission(targetId)) {
            BatchCraftNetworkHandler.CHANNEL.sendToServer(
                    new QuestSubmissionRequestPacket(
                            QuestSubmissionTargetIds.questId(targetId), false, currentRepeat));
            onClose();
            return;
        }
        if (ApothSpawnerPlanTarget.ID.equals(targetId) && plan.executionDim() != null) {
            ResourceLocation dimension = ResourceLocation.tryParse(plan.executionDim());
            if (dimension != null) {
                NetworkHandler.CHANNEL.sendToServer(new ApothSpawnerExecutePacket(dimension,
                        new BlockPos(plan.executionPosX(), plan.executionPosY(),
                                plan.executionPosZ()), Map.of(), false));
            }
            onClose();
            return;
        }
        if (recipeId != null && !recipeId.isEmpty()) {
            Map<String, String> forced = exportForcedSelections();
            sendCraftPacket(ResourceLocation.tryParse(recipeId), false, forced,
                    clampRepeatCount(currentRepeat),
                    plan.embersCode() != null && embersInferMode);
        }
        onClose();
    }

    private Map<String, String> exportForcedSelections() {
        Map<String, String> forced = new LinkedHashMap<>();
        for (Map.Entry<IngredientKey, ResourceLocation> entry : selectedPath.exportSelections().entrySet()) {
            ResourceLocation preferenceKey = CraftingResolver
                    .preferenceKey(entry.getKey().stack(1));
            if (preferenceKey == null) continue;
            forced.put(preferenceKey.toString(),
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
        if (preview && QuestSubmissionTargetIds.isQuestSubmission(rid)) {
            // Quest previews use their own planner and return an uncorrelated
            // PlanResponsePacket. Starting the ordinary planning HUD here would
            // leave it waiting forever because GenericCraftPacket never handles
            // synthetic quest target IDs.
            activeRequestId = 0L;
            PlanningProgressTracker.clear();
            BatchCraftNetworkHandler.CHANNEL.sendToServer(
                    new QuestSubmissionRequestPacket(
                            QuestSubmissionTargetIds.questId(rid), true, repeatCount));
            return;
        }
        ResourceLocation execDim = null;
        BlockPos execPos = null;
        if (plan.executionDim() != null && !plan.executionDim().isEmpty()) {
            execDim = ResourceLocation.tryParse(plan.executionDim());
            execPos = new BlockPos(
                    plan.executionPosX(), plan.executionPosY(), plan.executionPosZ());
        }
        MachineCandidateView selected = selectedMachineCandidate();
        if (machineSelectionMode != MachineSelectionMode.AUTO) {
            execDim = selected == null ? null : ResourceLocation.tryParse(selected.dimension());
            execPos = selected == null ? null
                    : new BlockPos(selected.x(), selected.y(), selected.z());
        }
        long requestId = PlanningRequestIds.next();
        if (preview) activeRequestId = requestId;
        GenericCraftPacket packet = new GenericCraftPacket(rid, preview, forced, execDim, execPos,
                        repeatCount, inferMode, plan.baseItem(),
                        executionTarget(plan.clickedOutput(), plan.targetResult()), requestId,
                        outputDestination).withMachineSelectionMode(machineSelectionMode)
                .withMaterialLocks(materialLocks)
                .withStorageReference(storageReference);
        if (preview) {
            PlanningProgressTracker.start(
                    requestId, rid);
        }
        if (!preview && partialPreparation) {
            RSIntegrationMod.LOGGER.info(
                    "[RSI-Preparation] sending dedicated request recipe={} repeat={} storage={}",
                    rid, repeatCount, storageReference == null ? "default" : storageReference);
            BatchCraftNetworkHandler.CHANNEL.sendToServer(
                    new PrepareIntermediateMaterialsPacket(packet));
        } else {
            BatchCraftNetworkHandler.CHANNEL.sendToServer(packet);
        }
    }

    /** Open the active recipe browser for a specific recipe id. */
    private void openRecipeInJei(ResourceLocation recipeId) {
        if (minecraft.level == null) return;
        var vanillaRecipe = minecraft.level.getRecipeManager().byKey(recipeId).orElse(null);
        if (vanillaRecipe == null) return;
        SidePanelJeiBridge.showJeiForItem(false,
                vanillaRecipe.getResultItem(minecraft.level.registryAccess()));
    }

    private void registerBookmarkHit(ItemStack stack, int x, int y, int width, int height,
                                     int missingCount) {
        BookmarkHit hit = new BookmarkHit(x, y, width, height,
                stack.copyWithCount(1), Math.max(1, missingCount));
        bookmarkHits.add(hit);
        if (mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height) {
            hoveredBookmark = hit;
        }
    }

    private void bookmarkMissingMaterial(BookmarkHit hit) {
        RecipeBrowserBridge.FavoriteResult result = RecipeBrowserBridge.addFavorite(hit.stack());
        showBookmarkMessage(switch (result) {
            case ADDED -> "rsi.plan.bookmark_added";
            case EXISTS -> "rsi.plan.bookmark_exists";
            case UNAVAILABLE -> "rsi.plan.bookmark_unavailable";
        }, hit.stack());
    }

    private void bookmarkAllMissingMaterials() {
        List<ItemStack> missing = MissingMaterialBookmarkList.bookmarkableItems(plan);
        int added = 0;
        int existing = 0;
        int unavailable = 0;
        for (ItemStack stack : missing) {
            switch (RecipeBrowserBridge.addFavorite(stack)) {
                case ADDED -> added++;
                case EXISTS -> existing++;
                case UNAVAILABLE -> unavailable++;
            }
        }
        showBookmarkBatchMessage("rsi.plan.bookmark_all.result", added, existing, unavailable);
    }

    private void showBookmarkMessage(String key, ItemStack stack) {
        if (minecraft != null && minecraft.player != null) {
            minecraft.player.displayClientMessage(
                    Component.translatable(key, stack.getHoverName()), true);
        }
    }

    private void showBookmarkBatchMessage(String key, Object... args) {
        if (minecraft != null && minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.translatable(key, args), true);
        }
    }

    private void onOpenMachine() {
        ResourceLocation dim = ResourceLocation.tryParse(plan.executionDim());
        if (dim == null) return;
        BlockPos pos = new BlockPos(
                plan.executionPosX(), plan.executionPosY(), plan.executionPosZ());
        ResourceLocation recipeId = ResourceLocation.tryParse(plan.recipeId());
        GuiNavStack.pushCurrent();
        if (BindingBackendResolver.isBeyondDimensionsBinding(dim, pos)) {
            NetworkHandler.CHANNEL.sendToServer(
                    new BeyondDimensionsOpenBoundMachineGuiPacket(dim, pos));
        } else {
            RSSidePanelNetworkHandler.CHANNEL.sendToServer(
                    new OpenBoundMachineGuiPacket(dim, pos, plan.recipeId(), recipeId, plan.baseItem()));
        }
    }

    private void requestPlanRefresh() {
        if (currentRepeat == lastRefreshCount) return;
        lastRefreshCount = currentRepeat;
        planRefreshTick = ticksOpen + 10;
    }

    @Override
    public void tick() {
        super.tick();
        if (repeatCountBox != null) repeatCountBox.tick();
        if (materialSearchBox != null && materialSearchBox.visible) materialSearchBox.tick();
        if (recipeCandidatePanel != null) recipeCandidatePanel.tick();
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
        hoveredStepWarnings = List.of();
        bookmarkHits.clear();
        bookmarkAllActionW = 0;
        bookmarkAllActionH = 0;
        hoveredBookmark = null;
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
        if (recipeCandidatePanel != null) {
            recipeCandidatePanel.layout(width, height, areaTop);
            contentW = Math.max(1, recipeCandidatePanel.contentRight(width - 20) - left);
        }
        layoutContentSections(font, contentW);
        // Recompute the bottom stack for the current view — the material panel is card-view
        // only (design doc §4), so tree view reclaims its band for the tree.
        layoutBottomStack();
        int bottomReserved = embersPedestalH > 0 ? embersPedestalY
                : (missingAreaHeight > 0 ? missingAreaTop : materialAreaTop);
        int areaBottom = bottomReserved - 4;
        if (embersPedestalH == 0 && missingAreaHeight == 0 && materialAreaHeight == 0) areaBottom = height - 53;

        boolean dockedCandidates = recipeCandidatePanel != null && recipeCandidatePanel.isOpen();
        if (dockedCandidates) gfx.enableScissor(0, 0, left + contentW, height);
        try {
        if (viewMode == ViewMode.TREE) {
            cardBar.active = false; // card list not shown in tree mode
            renderTreeArea(gfx, left, areaTop, contentW, areaBottom, mouseX, mouseY);
        } else {
        gfx.enableScissor(left, areaTop, left + contentW, areaBottom);
        try {
            orHitboxes.clear();
            orRendered.clear();

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
            renderEmbersPedestalArea(gfx, font, left, contentW);
        }

        // Missing items + mod warnings
        if (missingAreaHeight > 0 && (
                !MissingMaterialBookmarkList.textEntries(plan).isEmpty() ||
                (plan.modWarnings() != null && !plan.modWarnings().isEmpty()) ||
                (plan.machineCandidates() != null && !plan.machineCandidates().isEmpty()))) {
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
                    }, this::registerBookmarkHit);
            if (materialScroll > materialMaxScroll) materialScroll = materialMaxScroll;
            // Draggable scrollbar — grid begins below the header row (top+4 + lineHeight+4).
            int gridTop = materialAreaTop + 8 + font.lineHeight;
            materialBar.update(left + contentW - 5, gridTop,
                    materialAreaTop + materialAreaHeight - gridTop, materialScroll, materialMaxScroll);
            materialBar.draw(gfx, mouseX, mouseY);
        } else {
            materialBar.active = false;
        }
        } finally {
            if (dockedCandidates) {
                gfx.flush();
                gfx.disableScissor();
            }
        }

        // ── Repeat count row ─────────────────────────────────────────
        drawRepeatRow(gfx, font);


        renderOutputDestinationSelector(gfx, font, mouseX, mouseY);
        renderPreparationModeSelector(gfx, font, mouseX, mouseY);
        renderMachineCandidateDropdown(gfx, font);
        renderRecipeCandidates(gfx, mouseX, mouseY);

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

        Component destination;
        if (outputDestination == OutputDestination.PLAYER_INVENTORY) {
            destination = Component.translatable("rsi.plan.output.player");
        } else if (storageReference != null) {
            StorageNetworkDescriptor selected = storageNetworks.stream()
                    .filter(network -> storageReference.equals(network.reference()))
                    .findFirst().orElse(null);
            destination = selected == null
                    ? Component.translatable("rsi.plan.output.storage")
                    : storageTargetLabel(selected);
        } else {
            destination = Component.translatable("rsi.plan.output.storage");
        }
        String value = destination.getString();
        int arrowW = font.width(" ↔");
        gfx.drawString(font, value + " ↔", outputSelectorX + (outputSegmentW - font.width(value + " ↔")) / 2,
                textY, textColor, false);
    }

    private void renderPreparationModeSelector(GuiGraphics gfx, Font font, int mouseX, int mouseY) {
        if (preparationModeH <= 0 || outputSelectorH <= 0) return;
        boolean hovered = mouseX >= preparationModeX && mouseX < preparationModeX + preparationModeW
                && mouseY >= preparationModeY && mouseY < preparationModeY + preparationModeH;
        int background = partialPreparation ? 0xCC765A24 : 0xCC2F7D4A;
        if (hovered) background = partialPreparation ? 0xCC98752F : 0xCC3B5948;
        UIRenderer.rounded(gfx, preparationModeX, preparationModeY,
                preparationModeW, preparationModeH, 4f, 0xDD101512);
        UIRenderer.rounded(gfx, preparationModeX + 1, preparationModeY + 1,
                preparationModeW - 2, preparationModeH - 2, 3f, background);
        String value = I18n.get(partialPreparation
                ? "rsi.plan.mode.prepare" : "rsi.plan.mode.strict");
        gfx.drawString(font, value,
                preparationModeX + (preparationModeW - font.width(value)) / 2,
                preparationModeY + (preparationModeH - font.lineHeight) / 2,
                0xFFF0FFF4, false);
        if (hovered) {
            gfx.renderTooltip(font,
                    Component.translatable("rsi.plan.mode.prepare.tooltip"), mouseX, mouseY);
        }
    }

    /**
     * Network references are protocol data, not player-facing names. Older
     * discovery paths used the canonical RS id (v1|...) as the descriptor
     * display name; keep accepting that data but never render it directly.
     */
    private static Component storageTargetLabel(StorageNetworkDescriptor descriptor) {
        String displayName = descriptor.displayName();
        String backend = descriptor.reference().backendId().value();
        String networkId = descriptor.reference().networkId();
        if ("refinedstorage".equals(backend)
                && (displayName.startsWith("v1|") || displayName.equals(networkId))) {
            return Component.translatable("rsi.plan.output.rs_target");
        }
        if ("beyonddimensions".equals(backend) && displayName.equals(networkId)) {
            return Component.translatable("rsi.plan.output.bd_target", networkId);
        }
        return Component.literal(displayName);
    }

    private static boolean hasStorageBackend() {
        return ModList.get().isLoaded("refinedstorage")
                || ModList.get().isLoaded("beyonddimensions");
    }
    // ── Repeat count row ─────────────────────────────────────────────

    private void drawRepeatRow(GuiGraphics gfx, Font font) {
        String[] leftLabels = {"1", "-10", "-5", "-"};
        String[] rightLabels = {"+", "+5", "+10", "+64"};

        int btnW = REPEAT_BUTTON_W, btnH = REPEAT_BUTTON_H;
        int gap = REPEAT_BUTTON_GAP;
        int pillW = REPEAT_INPUT_W;
        int pillH = font.lineHeight + 6;
        int innerGap = 12;

        int leftGroupW = leftLabels.length * btnW + (leftLabels.length - 1) * gap;
        int rightGroupW = rightLabels.length * btnW + (rightLabels.length - 1) * gap;
        int contentW = leftGroupW + innerGap + pillW + innerGap + rightGroupW;
        int rowW = contentW + 28;
        int rowX = width / 2 - rowW / 2;

        // ── Label row ──
        ResourceLocation rootRecipe = ResourceLocation.tryParse(plan.recipeId());
        boolean pmmoSalvage = rootRecipe != null
                && "rs_integration".equals(rootRecipe.getNamespace())
                && rootRecipe.getPath().startsWith("pmmo_salvage/");
        String label = Component.translatable(pmmoSalvage
                ? "rsi.plan.salvage_attempt_count" : "rsi.plan.repeat_count").getString();
        int labelW = font.width(label);
        UIRenderer.textBackdrop(gfx, font, rowX + (rowW - labelW) / 2, repeatRowY, label, C_TEXT_BACKDROP);
        gfx.drawString(font, label, rowX + (rowW - labelW) / 2, repeatRowY, 0xFFCCCCCC);
        renderBookmarkAllAction(gfx, font, 20, repeatRowY);

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
        UIRenderer.rounded(gfx, countPillX, countPillY, pillW, pillH,
                pillH / 2f, 0xCC1B5E20);
        renderRepeatCountInput(gfx, font);

        // ── Right buttons: +, +5, +10, +64 ──
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

    private void renderRepeatCountInput(GuiGraphics gfx, Font font) {
        if (repeatCountBox == null) return;
        String value = repeatCountBox.getValue();
        if (repeatCountBox.isFocused()) {
            repeatCountBox.setX(countPillX + 6);
            repeatCountBox.setWidth(countPillW - 12);
        } else {
            int textWidth = Math.max(1, font.width(value));
            repeatCountBox.setX(countPillX + (countPillW - textWidth) / 2);
            repeatCountBox.setWidth(textWidth + 2);
        }
        repeatCountBox.setY(countPillY);
        repeatCountBox.render(gfx, mouseX, mouseY, 0f);
    }

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
                && step.modType() != ModType.GENERIC
                && !step.modType().isVirtual();
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
                    int gTotal = step.totalInputCount(i, step.batches());
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
                    int totalNeed = step.totalInputCount(i, step.batches());
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

        // 每个材料只保留一个入口，大量候选在右侧面板中滚动、搜索。
        String selectionKey = step == null ? null : selectionKey(step.output());
        if (orBadgeH > 0 && selectionKey != null && orRendered.add(selectionKey)) {
            long candidates = step.alternatives().stream().distinct().count();
            if (!step.alternatives().contains(step.recipeId())) candidates++;
            String label = Component.translatable("rsi.plan.recipe_picker.button", candidates).getString();
            int badgeY = y + cardH - font.lineHeight - 7;
            int badgeX = x + CARD_PAD;
            int badgeH = font.lineHeight + 4;
            int badgeW = Math.min(cardW - CARD_PAD * 2, font.width(label) + 14);
            UIRenderer.pillBadge(gfx, font, badgeX, badgeY, badgeW, badgeH,
                    0xCC2C6040, 0xFFE0F2E6, font.plainSubstrByWidth(label, badgeW - 10));
            orHitboxes.add(new ORHitbox(badgeX, badgeY, badgeW, badgeH, selectionKey));
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
        InkFluidRenderer.render(gfx, stack, x + 1, y + 1);

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

    @Nullable
    private PlanTreeNode findCandidateNode(PlanTreeNode node, String key) {
        if (node.step != null && key.equals(selectionKey(node.displayStack))) return node;
        for (PlanTreeNode child : node.children) {
            PlanTreeNode match = findCandidateNode(child, key);
            if (match != null) return match;
        }
        return null;
    }

    @Nullable
    private static String selectionKey(ItemStack stack) {
        ResourceLocation key = CraftingResolver
                .preferenceKey(stack);
        return key == null ? null : key.toString();
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
        InkFluidRenderer.render(gfx, stack, x + 1, y + 1);

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

    private void renderEmbersPedestalArea(GuiGraphics gfx, Font font, int left, int contentW) {
        int top = embersPedestalY;

        // Background with emerald left accent
        UIRenderer.roundedGradient(gfx, left, top, contentW, embersPedestalH, 6f,
                0xE6141E18, 0xE6101814);
        gfx.fill(left + 1, top + 2, left + 4, top + embersPedestalH - 2, 0xFF44AA66);

        int[] code = plan.embersCode();
        Component[] aspects = plan.embersAspectNames();
        Component[] inputs = plan.embersInputNames();
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
                String inputName = i < inputs.length ? inputs[i].getString() : "?";
                int maxNameW = cardW - 8;
                if (font.width(inputName) > maxNameW) {
                    inputName = font.plainSubstrByWidth(inputName, maxNameW - font.width("...")) + "...";
                }
                int nameX = px + cardW / 2 - font.width(inputName) / 2;
                gfx.drawString(font, inputName, nameX, py + 2, 0xFFCCCCCC);

                // Aspect name (bottom)
                String aspectName = i < aspects.length ? aspects[i].getString() : "?";
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
        List<MissingMaterialBookmarkList.TextEntry> missingEntries =
                MissingMaterialBookmarkList.textEntries(plan);
        boolean hasMissing = !missingEntries.isEmpty();
        boolean hasModWarnings = plan.modWarnings() != null && !plan.modWarnings().isEmpty();

        // Background — dark warm tone, red accent
        UIRenderer.roundedGradient(gfx, left, top, contentW, missingAreaHeight, 6f,
                0xE61E1814, 0xE6181410);
        // Left accent — red when materials are missing, orange when only mod warnings
        int accentColor = hasMissing ? C_ACCENT_MISSING
                : (plan.machineCandidates() != null && !plan.machineCandidates().isEmpty()
                ? C_ACCENT_READY : C_ORANGE);
        gfx.fill(left + 1, top + 2, left + 4, top + missingAreaHeight - 2, accentColor);

        int my = top + 4;
        int maxLineW = contentW - 24;
        // Render missing items — inline, wrapped
        if (hasMissing) {
            String hdr = Component.translatable("rsi.plan.missing_header").getString();
            UIRenderer.textBackdrop(gfx, font, left + 10, my, hdr, C_TEXT_BACKDROP);
            gfx.drawString(font, hdr, left + 10, my, 0xFFFF6666);

            my += font.lineHeight + 4;
            my = renderMissingTextEntries(gfx, font,
                    missingEntries, left + 10, my, maxLineW);
        }
        // Render mod warnings (one per line — they're longer sentences)
        if (hasModWarnings) {
            if (!hasMissing) my += 4;
            for (Component warn : plan.modWarnings()) {
                String display = font.plainSubstrByWidth(warn.getString(), maxLineW);
                UIRenderer.textBackdrop(gfx, font, left + 10, my, display, C_TEXT_BACKDROP);
                gfx.drawString(font, display, left + 10, my, 0xFFDDAA00);
                my += font.lineHeight + 4;
            }
        }
        if (plan.machineCandidates() != null && !plan.machineCandidates().isEmpty()) {
            if (my > top + 4) my += 2;
            renderMachineSelector(gfx, font, left + 10, my, contentW - 20);
        }
    }

    private void renderMachineSelector(GuiGraphics gfx, Font font, int left, int y, int width) {
        machineSelectorY = y;
        machineModeY = y;
        machineModeH = 20;
        machineModeW = 44;
        String label = Component.translatable("rsi.machine_candidate.label").getString();
        gfx.drawString(font, label, left, y + 3, 0xFFBBD8C2, false);

        int modeX = left + font.width(label) + 8;
        machineModeX = modeX;
        String[] modeKeys = {"rsi.machine_candidate.auto", "rsi.machine_candidate.preferred",
                "rsi.machine_candidate.exclusive"};
        MachineSelectionMode[] modes = {MachineSelectionMode.AUTO, MachineSelectionMode.PREFERRED,
                MachineSelectionMode.EXCLUSIVE};
        for (int i = 0; i < modes.length; i++) {
            int x = modeX + i * (machineModeW + 3);
            boolean selected = machineSelectionMode == modes[i];
            boolean hovered = mouseX >= x && mouseX < x + machineModeW
                    && mouseY >= y && mouseY < y + machineModeH;
            UIRenderer.rounded(gfx, x, y, machineModeW, machineModeH, 3f,
                    selected ? 0xCC338855 : hovered ? 0x88385546 : 0x661A221E);
            String text = Component.translatable(modeKeys[i]).getString();
            String clipped = font.plainSubstrByWidth(text, machineModeW - 6);
            gfx.drawString(font, clipped, x + (machineModeW - font.width(clipped)) / 2,
                    y + (machineModeH - font.lineHeight) / 2,
                    selected ? 0xFFFFFFFF : 0xFF9DB9A4, false);
        }

        int candidateX = modeX + 3 * (machineModeW + 3) + 8;
        machineCandidateX = candidateX;
        machineCandidateY = y;
        machineCandidateH = machineModeH;
        machineCandidateW = Math.max(60, left + width - candidateX);
        MachineCandidateView selected = selectedMachineCandidate();
        List<MachineCandidateView> candidates = plan.machineCandidates();
        long ready = candidates.stream().filter(c -> c.state() == MachineCandidateView.State.READY).count();
        String value;
        if (machineSelectionMode == MachineSelectionMode.AUTO) {
            value = Component.translatable("rsi.machine_candidate.auto_value", ready, candidates.size()).getString();
        } else if (selected == null) {
            value = Component.translatable("rsi.machine_candidate.none").getString();
        } else {
            value = selected.dimension() + " " + selected.x() + "," + selected.y() + "," + selected.z();
        }
        boolean hovered = mouseX >= candidateX && mouseX < candidateX + machineCandidateW
                && mouseY >= y && mouseY < y + machineCandidateH;
        UIRenderer.rounded(gfx, candidateX, y, machineCandidateW, machineCandidateH, 3f,
                hovered ? 0x88385546 : 0x661A221E);
        MachineCandidateView iconCandidate = selected != null ? selected
                : candidates.stream().filter(c -> !c.icon().isEmpty()).findFirst().orElse(null);
        int textX = candidateX + 6;
        if (iconCandidate != null && !iconCandidate.icon().isEmpty()) {
            gfx.renderItem(iconCandidate.icon(), candidateX + 3, y + 2);
            textX = candidateX + 23;
        }
        String clipped = font.plainSubstrByWidth(value,
                Math.max(1, candidateX + machineCandidateW - 12 - textX));
        gfx.drawString(font, clipped, textX, y + (machineCandidateH - font.lineHeight) / 2,
                hovered ? 0xFFFFFFFF : 0xFFBBD8C2, false);
        gfx.drawString(font, machineDropdownOpen ? "^" : "v",
                candidateX + machineCandidateW - 10,
                y + (machineCandidateH - font.lineHeight) / 2, 0xFF79C995, false);
    }

    private void renderMachineCandidateDropdown(GuiGraphics gfx, Font font) {
        machineCandidateHits.clear();
        if (!machineDropdownOpen || plan.machineCandidates().isEmpty()) return;
        int rowH = 22;
        int width = Math.min(260, Math.max(170, machineCandidateW));
        int left = Math.max(8, machineCandidateX);
        int top = Math.max(8, machineCandidateY - rowH * plan.machineCandidates().size() - 4);
        for (int i = 0; i < plan.machineCandidates().size(); i++) {
            MachineCandidateView candidate = plan.machineCandidates().get(i);
            int y = top + i * rowH;
            boolean hovered = mouseX >= left && mouseX < left + width
                    && mouseY >= y && mouseY < y + rowH;
            int bg = hovered ? 0xDD31523E : 0xEE111A15;
            UIRenderer.rounded(gfx, left, y, width, rowH, 3f, bg);
            int color = candidate.state() == MachineCandidateView.State.READY ? C_GREEN
                    : candidate.state() == MachineCandidateView.State.TEMPORARY ? C_ORANGE : C_RED;
            gfx.fill(left + 3, y + 3, left + 5, y + rowH - 3, color);
            int textX = left + 10;
            if (!candidate.icon().isEmpty()) {
                gfx.renderItem(candidate.icon(), left + 7, y + 3);
                textX = left + 28;
            }
            String text = candidate.dimension() + " " + candidate.x() + "," + candidate.y()
                    + "," + candidate.z() + " · " + candidate.status().getString();
            text = font.plainSubstrByWidth(text, Math.max(1, left + width - 8 - textX));
            gfx.drawString(font, text, textX, y + (rowH - font.lineHeight) / 2,
                    0xFFE2F2E5, false);
            machineCandidateHits.add(new MachineCandidateHit(left, y, width, rowH, i));
        }
    }

    private int renderMissingTextEntries(
            GuiGraphics gfx, Font font,
            List<MissingMaterialBookmarkList.TextEntry> entries,
            int left, int y, int maxLineWidth) {
        int cursorX = left;
        boolean firstOnLine = true;
        for (MissingMaterialBookmarkList.TextEntry entry : entries) {
            String prefix = firstOnLine ? "  " : ", ";
            int prefixWidth = font.width(prefix);
            int labelWidth = font.width(entry.label());
            if (!firstOnLine && cursorX + prefixWidth + labelWidth > left + maxLineWidth) {
                y += font.lineHeight + 4;
                cursorX = left;
                firstOnLine = true;
                prefix = "  ";
                prefixWidth = font.width(prefix);
            }

            gfx.drawString(font, prefix, cursorX, y, 0xFFCC8888, false);
            cursorX += prefixWidth;
            int availableWidth = Math.max(1, left + maxLineWidth - cursorX);
            String visibleLabel = font.plainSubstrByWidth(entry.label(), availableWidth);
            int visibleWidth = font.width(visibleLabel);
            boolean hovered = entry.bookmarkable()
                    && mouseX >= cursorX && mouseX < cursorX + visibleWidth
                    && mouseY >= y && mouseY < y + font.lineHeight;
            gfx.drawString(font, visibleLabel, cursorX, y,
                    hovered ? 0xFFFFBBBB : 0xFFCC8888, false);
            if (hovered) {
                gfx.fill(cursorX, y + font.lineHeight,
                        cursorX + visibleWidth, y + font.lineHeight + 1, 0xFFFF9999);
            }
            if (entry.bookmarkable() && visibleWidth > 0) {
                registerBookmarkHit(entry.bookmark(), cursorX, y,
                        visibleWidth, font.lineHeight, entry.missingCount());
            }
            cursorX += visibleWidth;
            firstOnLine = false;
        }
        return entries.isEmpty() ? y : y + font.lineHeight + 4;
    }

    private static int missingTextLineCount(
            Font font, List<MissingMaterialBookmarkList.TextEntry> entries, int maxLineWidth) {
        if (entries.isEmpty()) return 0;
        int lines = 1;
        int used = 0;
        boolean firstOnLine = true;
        for (MissingMaterialBookmarkList.TextEntry entry : entries) {
            int width = font.width(firstOnLine ? "  " : ", ") + font.width(entry.label());
            if (!firstOnLine && used + width > maxLineWidth) {
                lines++;
                used = font.width("  ") + Math.min(font.width(entry.label()), maxLineWidth);
                firstOnLine = false;
            } else {
                used += width;
                firstOnLine = false;
            }
        }
        return lines;
    }

    private void renderBookmarkAllAction(GuiGraphics gfx, Font font, int x, int y) {
        if (MissingMaterialBookmarkList.bookmarkableItems(plan).isEmpty()) return;

        String action = Component.translatable("rsi.plan.bookmark_all.action").getString();
        int accentW = 3;
        int accentGap = 6;
        int textX = x + accentW + accentGap;
        bookmarkAllActionX = x;
        bookmarkAllActionY = y - 2;
        bookmarkAllActionW = accentW + accentGap + font.width(action);
        bookmarkAllActionH = font.lineHeight + 4;
        boolean hovered = mouseX >= bookmarkAllActionX
                && mouseX < bookmarkAllActionX + bookmarkAllActionW
                && mouseY >= bookmarkAllActionY
                && mouseY < bookmarkAllActionY + bookmarkAllActionH;
        gfx.fill(x, y - 2, x + accentW, y + font.lineHeight + 2,
                hovered ? 0xFF69D98A : C_ACCENT_NEUTRAL);
        gfx.drawString(font, action, textX, y,
                hovered ? 0xFFA8E6B5 : 0xFF78B889, false);
        if (hovered) {
            gfx.fill(textX, y + font.lineHeight + 1,
                    textX + font.width(action), y + font.lineHeight + 2, 0xCC78D891);
        }
    }

    // ── Scrolling ─────────────────────────────────────────────────

    // ── Recipe-tree rendering + camera (v3.4) ────────────────────

    private void renderTreeArea(GuiGraphics gfx, int left, int areaTop, int contentW,
                                int areaBottom, int mouseX, int mouseY) {
        if (areaBottom <= areaTop) return;
        if (recipeCandidatePanel != null && recipeCandidatePanel.contains(mouseX, mouseY)) {
            mouseX = mouseY = Integer.MIN_VALUE;
        }
        treeLayout.ensureLayout(treeModel.root);

        // Backdrop over the whole area.
        UIRenderer.rounded(gfx, left, areaTop, contentW, areaBottom - areaTop, 6f, 0x66060A08);

        // The tree viewport fills the whole area; the total-demand strip is drawn inside
        // the camera layer, anchored above the root — it no longer eats layout height.
        if (treeCameraInit && treeViewRight > treeViewLeft) {
            treePanX += (left + contentW - treeViewRight) / 2.0;
        }
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
        if (hovered != null && (!hovered.warnings.isEmpty() || hovered.prerequisiteBlocked
                || !hovered.hints.isEmpty())) {
            List<Component> tooltip = new ArrayList<>(hovered.warnings);
            for (Component hint : hovered.hints) {
                tooltip.add(hint.copy().withStyle(ChatFormatting.AQUA));
            }
            hoveredStepWarnings = tooltip;
            hoveredTooltipX = mouseX;
            hoveredTooltipY = mouseY;
            resetHoverIntent();
        } else if (hovered != null && !hovered.displayStack.isEmpty()) {
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
                hoveredItemForTooltip = hovered.lockedMaterial != null
                        ? hovered.lockedMaterial : hovered.displayStack;
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
        renderMaterialDropdown(gfx, minecraft.font, mouseX, mouseY);

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
                    e.getValue().available(), e.getValue().missingCount() == 0));
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
            InkFluidRenderer.render(gfx, e.display(), sx, slotY);
            if (!leftover && !e.enough()) {
                int buttonX = sx + 8;
                int buttonY = slotY;
                int buttonSize = 8;
                int screenX = (int) Math.round(buttonX * treeZoom + treePanX);
                int screenY = (int) Math.round(buttonY * treeZoom + treePanY);
                int screenSize = Math.max(1, (int) Math.round(buttonSize * treeZoom));
                boolean buttonHovered = mouseX >= screenX && mouseX < screenX + screenSize
                        && mouseY >= screenY && mouseY < screenY + screenSize;
                PlanRenderEngine.drawBookmarkAddButton(gfx, buttonX, buttonY,
                        buttonSize, buttonHovered);
                int hitLeft = Math.max(screenX, treeViewLeft);
                int hitTop = Math.max(screenY, treeViewTop);
                int hitRight = Math.min(screenX + screenSize, treeViewRight);
                int hitBottom = Math.min(screenY + screenSize, treeViewBottom);
                if (hitRight > hitLeft && hitBottom > hitTop) {
                    registerBookmarkHit(e.display(), hitLeft, hitTop,
                            hitRight - hitLeft, hitBottom - hitTop,
                            Math.max(1, plan.availability(e.key()).missingCount()));
                }
            }
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
        closeRecipeCandidates();
        if (node.step == null) return;

        int idx = Math.max(0, node.step.alternatives().indexOf(recipeId));
        selectedPath.selectBranch(node.key, idx, recipeId);

        Map<String, String> forced = exportForcedSelections();
        ResourceLocation rootRid = ResourceLocation.tryParse(plan.recipeId());
        boolean isTarget = node.depth == 0;
        if (isTarget) {
            rootRid = recipeId;
            ResourceLocation preferenceKey = CraftingResolver
                    .preferenceKey(node.key.stack(1));
            if (preferenceKey != null) forced.remove(preferenceKey.toString());
        }

        LAST_FORCED.clear();
        LAST_FORCED.putAll(forced);
        sendCraftPacket(rootRid, true, forced, currentRepeat, false);
    }

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

    /** Embers Calculate mode is a COMMON config; the server's value governs. */
    private static boolean embersCalcEnabled() {
        return ClientSyncedConfig.isSynced()
                ? ClientSyncedConfig.ENABLE_EMBERS_ALCHEMY_CALC
                : RSIntegrationConfig.ENABLE_EMBERS_ALCHEMY_CALC.get();
    }

    private void openRecipeCandidates(PlanTreeNode node) {
        if (node == dropdownNode) {
            closeRecipeCandidates();
            return;
        }
        closeMaterialDropdown();
        unfocusRepeatCountInput();
        setFocused(null);
        dropdownNode = node;
        recipeCandidatePanel.open(node, plan.boundMachineTypes() == null ? Set.of() : plan.boundMachineTypes());
        resetHoverIntent();
    }

    private void closeRecipeCandidates() {
        dropdownNode = null;
        if (recipeCandidatePanel != null) recipeCandidatePanel.close();
    }

    private void renderRecipeCandidates(GuiGraphics graphics, int mouseX, int mouseY) {
        if (dropdownNode == null || recipeCandidatePanel == null) return;
        recipeCandidatePanel.render(graphics, mouseX, mouseY);
        if (recipeCandidatePanel.contains(mouseX, mouseY)) {
            hoveredItemForTooltip = ItemStack.EMPTY;
            hoveredStepWarnings = List.of();
            hoveredBookmark = null;
        }
    }

    private void renderMaterialDropdown(GuiGraphics gfx, Font font, int mouseX, int mouseY) {
        materialDropHits.clear();
        PlanTreeNode node = materialDropdownNode;
        if (node == null || node.materialLockKey == null || node.materialOptions.size() <= 1) {
            hideMaterialSearchInput();
            return;
        }
        PlanTreeLayout.Box box = treeLayout.boxFor(node);
        if (box == null) {
            closeMaterialDropdown();
            return;
        }

        int rowH = 20;
        int searchAreaH = 24;
        List<ItemStack> options = filteredMaterialOptions(node);
        int topLimit = Math.max(4, treeViewTop + 4);
        int bottomLimit = Math.min(height - 4, treeViewBottom - 4);
        int rowCapacity = Math.max(2, (bottomLimit - topLimit - searchAreaH - 2) / rowH);
        int maxVisibleOptions = Math.min(options.size(), rowCapacity - 1);
        materialDropdownScroll = Math.max(0,
                Math.min(materialDropdownScroll, Math.max(0, options.size() - maxVisibleOptions)));
        int panelW = Math.min(190, Math.max(120, width - 16));
        int sx = Math.max(4, width - panelW - 8);
        int sy = (int) Math.round(box.y() * treeZoom + treePanY);
        int panelH = searchAreaH + (1 + maxVisibleOptions) * rowH + 2;
        sy = Math.max(topLimit, Math.min(sy, Math.max(topLimit, bottomLimit - panelH)));
        materialPanelX = sx;
        materialPanelY = sy;
        materialPanelW = panelW;
        materialPanelH = panelH;

        gfx.fill(sx, sy, sx + panelW, sy + panelH, 0xF00A140E);
        gfx.fill(sx, sy, sx + panelW, sy + 1, 0xFF55D080);
        gfx.fill(sx + 4, sy + 3, sx + panelW - 4, sy + searchAreaH - 3, 0xFF15231A);

        int firstRowY = sy + searchAreaH;
        renderMaterialOption(gfx, font, mouseX, mouseY, node, ItemStack.EMPTY,
                sx, firstRowY, panelW, rowH);
        for (int visible = 0; visible < maxVisibleOptions; visible++) {
            ItemStack option = options.get(materialDropdownScroll + visible);
            int ry = firstRowY + (visible + 1) * rowH;
            renderMaterialOption(gfx, font, mouseX, mouseY, node, option,
                    sx, ry, panelW, rowH);
        }

        if (materialSearchBox != null) {
            materialSearchBox.setPosition(sx + 7, sy + 5);
            materialSearchBox.setWidth(panelW - 14);
            materialSearchBox.visible = true;
            materialSearchBox.active = true;
            materialSearchBox.render(gfx, mouseX, mouseY, 0f);
        }
    }

    private void renderMaterialOption(GuiGraphics gfx, Font font, int mouseX, int mouseY,
                                      PlanTreeNode node, ItemStack option,
                                      int sx, int ry, int panelW, int rowH) {
        boolean hovered = mouseX >= sx && mouseX < sx + panelW
                && mouseY >= ry && mouseY < ry + rowH;
        if (hovered) gfx.fill(sx, ry, sx + panelW, ry + rowH, 0x33FFFFFF);

        boolean selected = option.isEmpty()
                ? !materialLocks.containsKey(node.materialLockKey)
                : node.lockedMaterial != null
                && ItemStack.isSameItemSameTags(node.lockedMaterial, option);
        if (!option.isEmpty()) InkFluidRenderer.render(gfx, option, sx + 2, ry + 2);
        String label = option.isEmpty()
                ? I18n.get("rsi.plan.material.auto") : option.getHoverName().getString();
        int textX = option.isEmpty() ? sx + 6 : sx + 22;
        gfx.drawString(font, font.plainSubstrByWidth(label, panelW - (textX - sx) - 5),
                textX, ry + (rowH - font.lineHeight) / 2,
                selected ? C_GREEN : 0xFFDDDDDD, false);
        materialDropHits.add(new MaterialDropHit(sx, ry, panelW, rowH, option));
    }

    private List<ItemStack> filteredMaterialOptions(PlanTreeNode node) {
        String query = materialSearchBox == null ? "" : materialSearchBox.getValue();
        if (query == null || query.isBlank()) return node.materialOptions;
        List<ItemStack> filtered = new ArrayList<>();
        for (ItemStack option : node.materialOptions) {
            ResourceLocation key = BuiltInRegistries.ITEM.getKey(option.getItem());
            if (MaterialSearchMatcher.matches(option.getHoverName().getString(),
                    key == null ? "" : key.toString(), query)) {
                filtered.add(option);
            }
        }
        return filtered;
    }

    private void selectMaterial(PlanTreeNode node, ItemStack option) {
        if (node.materialLockKey == null) return;
        if (option.isEmpty()) {
            materialLocks.remove(node.materialLockKey);
        } else {
            materialLocks.put(node.materialLockKey, option.copyWithCount(1));
        }
        closeMaterialDropdown();
        ResourceLocation rootRecipe = ResourceLocation.tryParse(plan.recipeId());
        sendCraftPacket(rootRecipe, true, exportForcedSelections(), currentRepeat, false);
    }

    private void openMaterialDropdown(PlanTreeNode node) {
        if (node == materialDropdownNode) {
            closeMaterialDropdown();
            return;
        }
        unfocusRepeatCountInput();
        materialDropdownNode = node;
        materialDropdownScroll = 0;
        closeRecipeCandidates();
        if (materialSearchBox != null) {
            materialSearchBox.visible = true;
            materialSearchBox.active = true;
            materialSearchBox.setValue("");
            materialSearchBox.setFocused(true);
            setFocused(materialSearchBox);
        }
    }

    private void closeMaterialDropdown() {
        materialDropdownNode = null;
        materialDropdownScroll = 0;
        materialDropHits.clear();
        materialPanelW = 0;
        materialPanelH = 0;
        hideMaterialSearchInput();
    }

    private void hideMaterialSearchInput() {
        if (materialSearchBox == null) return;
        boolean focused = materialSearchBox.isFocused();
        materialSearchBox.visible = false;
        materialSearchBox.active = false;
        materialSearchBox.setFocused(false);
        if (focused) setFocused(null);
    }

    /** 侧栏开关或视图变化时，按主内容实际宽度重新排材料和缺料提示。 */
    private void layoutContentSections(Font font, int contentWidth) {
        if (contentLayoutWidth == contentWidth && contentLayoutMode == viewMode) return;
        contentLayoutWidth = contentWidth;
        contentLayoutMode = viewMode;
        var missingEntries = MissingMaterialBookmarkList.textEntries(plan);
        int warningCount = plan.modWarnings() == null ? 0 : plan.modWarnings().size();
        boolean machineCandidates = plan.machineCandidates() != null && !plan.machineCandidates().isEmpty();
        if (!missingEntries.isEmpty() || warningCount > 0 || machineCandidates) {
            int lines = warningCount;
            if (!missingEntries.isEmpty()) {
                lines += 1 + missingTextLineCount(font, missingEntries, Math.max(1, contentWidth - 24));
            }
            int warningHeight = lines > 0 ? font.lineHeight + 6 + lines * (font.lineHeight + 4) + 4 : 0;
            int fullHeight = warningHeight + (machineCandidates ? 34 : 0);
            missingAreaHeight = viewMode == ViewMode.TREE ? Math.min(fullHeight, TREE_MISSING_MAX_HEIGHT) : fullHeight;
            missingMaxScroll = viewMode == ViewMode.TREE ? Math.max(0, fullHeight - missingAreaHeight) : 0;
            missingScroll = Math.min(missingScroll, missingMaxScroll);
        } else {
            missingAreaHeight = missingMaxScroll = missingScroll = 0;
        }
        int countWidth = font.width("0/0");
        for (var material : plan.materials().values()) {
            countWidth = Math.max(countWidth, font.width(material.available() + "/" + material.needed()));
        }
        int columns = Math.max(1, contentWidth / (SLOT_SIZE + countWidth + 14));
        int rows = Math.min(MATERIAL_MAX_ROWS, (plan.materials().size() + columns - 1) / columns);
        materialAreaHeight = plan.materials().isEmpty() ? 0 : font.lineHeight + 6 + rows * (SLOT_SIZE + 8) + 12;
    }

    /**
     * Recompute the bottom region stack (embers → missing → material panel → repeat row).
     * The material panel is card-view only (design doc §4.4); in tree view its band collapses
     * to zero so the tree reclaims the space and everything above shifts down accordingly.
     */
    private void layoutBottomStack() {
        int repeatAreaH = 38;
        // Leave separate rows for preparation mode and output destination.
        repeatRowY = height - 75 - repeatAreaH;
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

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (recipeCandidatePanel != null && recipeCandidatePanel.mouseScrolled(mouseX, mouseY, delta)) return true;
        if (viewMode == ViewMode.TREE && missingMaxScroll > 0
                && mouseY >= missingAreaTop && mouseY < missingAreaTop + missingAreaHeight) {
            missingScroll = Math.max(0, Math.min(missingMaxScroll,
                    missingScroll - (int) delta * 10));
            return true;
        }
        if (materialDropdownNode != null) {
            int total = filteredMaterialOptions(materialDropdownNode).size();
            int maxVisible = Math.max(1, materialDropHits.size() - 1);
            materialDropdownScroll = Math.max(0, Math.min(Math.max(0, total - maxVisible),
                    materialDropdownScroll - (delta > 0 ? 1 : -1)));
            return true;
        }
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
                setRepeatCount(clampRepeatCount(currentRepeat + (long) dir * stepBy), true);
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
        if (recipeCandidatePanel != null && (recipeCandidatePanel.contains(mx, my)
                || recipeCandidatePanel.contains(mx - dx, my - dy))) return true;
        // Scrollbar thumb drag takes precedence — map the cursor to a scroll offset.
        if (draggingBar != null) {
            int s = draggingBar.scrollForThumbTop((int) my - scrollbarGrabDy);
            if (draggingBar == cardBar) scrollOffset = s;
            else if (draggingBar == materialBar) materialScroll = s;
            else if (draggingBar == missingBar) missingScroll = s;
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
        if (recipeCandidatePanel != null && recipeCandidatePanel.mouseClicked(mx, my, button)) {
            if (!recipeCandidatePanel.isOpen()) dropdownNode = null;
            return true;
        }
        if (dropdownNode != null && (viewMode != ViewMode.TREE || nodeAt(mx, my) != dropdownNode)) {
            closeRecipeCandidates();
        }
        if (materialDropdownNode != null && materialPanelW > 0
                && mx >= materialPanelX && mx < materialPanelX + materialPanelW
                && my >= materialPanelY && my < materialPanelY + materialPanelH) {
            if (button == 0 && materialSearchBox != null
                    && mx >= materialSearchBox.getX()
                    && mx < materialSearchBox.getX() + materialSearchBox.getWidth()
                    && my >= materialSearchBox.getY()
                    && my < materialSearchBox.getY() + materialSearchBox.getHeight()) {
                materialSearchBox.mouseClicked(mx, my, button);
                materialSearchBox.setFocused(true);
                setFocused(materialSearchBox);
                return true;
            }
            if (button == 0) {
                for (MaterialDropHit hit : materialDropHits) {
                    if (mx >= hit.x() && mx < hit.x() + hit.w()
                            && my >= hit.y() && my < hit.y() + hit.h()) {
                        selectMaterial(materialDropdownNode, hit.option());
                        return true;
                    }
                }
            }
            return true;
        }
        if (button == 0) {
            if (machineDropdownOpen) {
                for (MachineCandidateHit hit : machineCandidateHits) {
                    if (mx >= hit.x() && mx < hit.x() + hit.w()
                            && my >= hit.y() && my < hit.y() + hit.h()) {
                        selectedMachineIndex = hit.index();
                        if (machineSelectionMode == MachineSelectionMode.AUTO) {
                            machineSelectionMode = MachineSelectionMode.PREFERRED;
                        }
                        machineDropdownOpen = false;
                        return true;
                    }
                }
            }
            if (!plan.machineCandidates().isEmpty()
                    && mx >= machineModeX && mx < machineModeX + 3 * (machineModeW + 3)
                    && my >= machineModeY && my < machineModeY + machineModeH) {
                int index = (int) ((mx - machineModeX) / (machineModeW + 3));
                if (index >= 0 && index < 3) {
                    machineSelectionMode = MachineSelectionMode.values()[index];
                    if (machineSelectionMode != MachineSelectionMode.AUTO
                            && selectedMachineIndex < 0) {
                        selectedMachineIndex = plan.machineCandidates().stream()
                                .filter(c -> c.state() == MachineCandidateView.State.READY)
                                .findFirst().map(c -> plan.machineCandidates().indexOf(c)).orElse(-1);
                    }
                    return true;
                }
            }
            if (!plan.machineCandidates().isEmpty()
                    && mx >= machineCandidateX && mx < machineCandidateX + machineCandidateW
                    && my >= machineCandidateY && my < machineCandidateY + machineCandidateH) {
                machineDropdownOpen = !machineDropdownOpen;
                return true;
            }
            boolean overRepeatInput = mx >= countPillX && mx < countPillX + countPillW
                    && my >= countPillY && my < countPillY + countPillH;
            if (!overRepeatInput) unfocusRepeatCountInput();
            if (bookmarkAllActionW > 0
                    && mx >= bookmarkAllActionX && mx < bookmarkAllActionX + bookmarkAllActionW
                    && my >= bookmarkAllActionY && my < bookmarkAllActionY + bookmarkAllActionH) {
                bookmarkAllMissingMaterials();
                return true;
            }
            for (BookmarkHit hit : bookmarkHits) {
                if (mx >= hit.x() && mx < hit.x() + hit.w()
                        && my >= hit.y() && my < hit.y() + hit.h()) {
                    bookmarkMissingMaterial(hit);
                    return true;
                }
            }
            if (overRepeatInput) {
                if (repeatCountBox != null && repeatCountBox.isFocused()) {
                    repeatCountBox.setX(countPillX + 6);
                    repeatCountBox.setWidth(countPillW - 12);
                    repeatCountBox.mouseClicked(mx, my, button);
                } else {
                    focusRepeatCountInput();
                }
                return true;
            }
        }
        // Middle-clicking a missing material is the quick restock/bookmark
        // action. Keep it available in both RS and BD plans; the actual
        // storage selection remains server-side and JEI is only the fallback
        // destination for what cannot be supplied.
        if (button == 2) {
            for (BookmarkHit hit : bookmarkHits) {
                if (mx >= hit.x() && mx < hit.x() + hit.w()
                        && my >= hit.y() && my < hit.y() + hit.h()) {
                    bookmarkMissingMaterial(hit);
                    return true;
                }
            }
        }
        if ((button == 0 || button == 1) && outputSelectorH > 0
                && mx >= preparationModeX && mx < preparationModeX + preparationModeW
                && my >= preparationModeY && my < preparationModeY + preparationModeH) {
            if (button == 0) {
                partialPreparation = !partialPreparation;
                if (partialPreparation) selectOutputDestination(OutputDestination.RS_NETWORK);
                if (confirmButton != null) {
                    confirmButton.setMessage(executionActionLabel());
                    confirmButton.active = partialPreparation
                            || !plan.executionBlocked() || selectedPath.isDirty();
                }
            }
            return true;
        }
        if ((button == 0 || button == 1) && outputSelectorH > 0
                && mx >= outputSelectorX && mx < outputSelectorX + outputSegmentW
                && my >= outputSelectorY && my < outputSelectorY + outputSelectorH) {
            if (partialPreparation) return true;
            if (button == 1) {
                if (!storageNetworks.isEmpty()) {
                    if (outputDestination != OutputDestination.RS_NETWORK) {
                        selectOutputDestination(OutputDestination.RS_NETWORK);
                    } else {
                        cycleStorageTarget();
                    }
                }
            } else {
                // Left click is the stable primary toggle: player inventory
                // versus the currently selected backend network. Right click
                // cycles concrete networks without making the player target
                // unreachable when RS and BD are installed together.
                selectOutputDestination(outputDestination == OutputDestination.RS_NETWORK
                        ? OutputDestination.PLAYER_INVENTORY : OutputDestination.RS_NETWORK);
            }
            return true;
        }        // Scrollbar thumbs first (both view modes) — grab the thumb, or click the track to jump.
        if (button == 0) {
            for (ScrollbarUI bar : new ScrollbarUI[]{cardBar, materialBar, missingBar}) {
                if (bar.overThumb(mx, my)) {
                    draggingBar = bar;
                    scrollbarGrabDy = (int) my - bar.thumbY;
                    return true;
                }
                if (bar.overTrack(mx, my)) {
                    draggingBar = bar;
                    scrollbarGrabDy = bar.thumbH / 2; // center the thumb under the cursor
                    int s = bar.scrollForThumbTop((int) my - scrollbarGrabDy);
                    if (bar == cardBar) scrollOffset = s; else if (bar == materialBar) materialScroll = s; else missingScroll = s;
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
                    if (node != null && node.materialLockKey != null
                            && node.materialOptions.size() > 1) {
                        openMaterialDropdown(node);
                    } else if (node != null && node.hasAlternatives()) {
                        openRecipeCandidates(node);
                    } else if (node != null && node.step != null) {
                        // No alternatives → open recipe in JEI directly.
                        closeMaterialDropdown();
                        openRecipeInJei(node.step.recipeId());
                    } else {
                        closeMaterialDropdown();
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
            closeRecipeCandidates();
            closeMaterialDropdown();
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
            // Repeat row quick-set buttons.
            for (int i = 0; i < repeatBtnX.length; i++) {
                if (mx >= repeatBtnX[i] && mx <= repeatBtnX[i] + repeatBtnW[i]
                        && my >= repeatBtnY[i] && my <= repeatBtnY[i] + repeatBtnH[i]) {
                    int requested = currentRepeat;
                    switch (i) {
                        case 0: requested = 1; break;
                        case 1: requested = Math.max(1, currentRepeat - 10); break;
                        case 2: requested = Math.max(1, currentRepeat - 5); break;
                        case 3: requested = Math.max(1, currentRepeat - 1); break;
                        case 4: requested = clampRepeatCount(currentRepeat + 1L); break;
                        case 5: requested = clampRepeatCount(currentRepeat + 5L); break;
                        case 6: requested = clampRepeatCount(currentRepeat + 10L); break;
                        case 7: requested = clampRepeatCount(currentRepeat + 64L); break;
                    }
                    setRepeatCount(requested, true);
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
                        PlanTreeNode node = findCandidateNode(treeModel.root, hb.selectionKey);
                        if (node != null) openRecipeCandidates(node);
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
        if (recipeCandidatePanel != null && recipeCandidatePanel.keyPressed(keyCode, scanCode, modifiers)) {
            if (!recipeCandidatePanel.isOpen()) dropdownNode = null;
            return true;
        }
        if (materialSearchBox != null && materialSearchBox.isFocused()) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                closeMaterialDropdown();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (repeatCountBox != null && repeatCountBox.isFocused()) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                unfocusRepeatCountInput();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                unfocusRepeatCountInput();
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        boolean ctrl = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0;
        if (materialDropdownNode != null && keyCode == GLFW.GLFW_KEY_ESCAPE) {
            closeMaterialDropdown();
            return true;
        }
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
        // Enter — one-click start (§2.5 speedrun): apply branch selections and execute.
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            onConfirm();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char character, int modifiers) {
        if (recipeCandidatePanel != null && recipeCandidatePanel.charTyped(character, modifiers)) return true;
        return super.charTyped(character, modifiers);
    }

    private static int repeatCountLimit() {
        int limit = ClientSyncedConfig.isSynced()
                ? ClientSyncedConfig.REPEAT_COUNT_MAX
                : RSIntegrationConfig.REPEAT_COUNT_MAX.get();
        return Math.max(1, Math.min(limit, RSIntegrationConfig.REPEAT_COUNT_ABSOLUTE_MAX));
    }

    private static int clampRepeatCount(long requested) {
        return (int) Math.max(1L, Math.min(requested, repeatCountLimit()));
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
        InkFluidRenderer.render(gfx, step.output(), tx, y + (rowH - 16) / 2);
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
            case ModIds.GOETY, "goety_brazier" -> 0xFF222244;
            case ModIds.WIZARDS_REBORN -> 0xFF444466;
            case ModIds.EMBERS -> 0xFFCC6633;
            case ModIds.AETHERWORKS -> 0xFF3388AA;
            default -> 0xFF8B8B8B;
        };
    }

    private int stepAccent(PlanStep step, int batches) {
        if (step == null || step.inputs().isEmpty()) return C_ACCENT_NEUTRAL;
        for (int inputIndex = 0; inputIndex < step.inputs().size(); inputIndex++) {
            ItemStack in = step.inputs().get(inputIndex);
            if (in.isEmpty()) continue;
            PlanResponse.Availability a = plan.availability(in);
            int avail = a != null ? a.available() : 0;
            int need = step.totalInputCount(inputIndex, batches);
            if (avail < need) return C_ACCENT_MISSING;
        }
        return C_ACCENT_READY;
    }

    // ── Deferred tooltip render ─────────────────────────────────

    /** Renders the hovered-item tooltip AFTER all scissors are disabled.
     *  Builds the component list via {@code getTooltipLines()} so Forge's
     *  {@code RenderTooltipEvent} fires and Legendary Tooltips can intercept. */
    private void renderDeferredTooltip(GuiGraphics gfx, Font font) {
        if (!hoveredStepWarnings.isEmpty()) {
            gfx.renderComponentTooltip(font, hoveredStepWarnings, hoveredTooltipX, hoveredTooltipY);
            hoveredStepWarnings = List.of();
            return;
        }
        if (hoveredBookmark != null) {
            gfx.renderComponentTooltip(font, List.of(
                    hoveredBookmark.stack().getHoverName(),
                    Component.translatable("rsi.plan.bookmark_missing",
                            hoveredBookmark.missingCount()).withStyle(ChatFormatting.GREEN),
                    Component.translatable("rsi.plan.bookmark_middle_hint")
                            .withStyle(ChatFormatting.GRAY)),
                    mouseX, mouseY);
            return;
        }
        if (hoveredItemForTooltip.isEmpty()) return;

        List<Component> lines = new ArrayList<>(
                hoveredItemForTooltip.getTooltipLines(
                        minecraft.player,
                        minecraft.options.advancedItemTooltips
                                ? TooltipFlag.Default.ADVANCED
                                : TooltipFlag.Default.NORMAL));

        // Append availability info
        String availStr = hoveredTooltipAvail + " / " + hoveredTooltipNeeded
                + (InkFluidSupport.isToken(hoveredItemForTooltip) ? " mB" : "");
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

        PlanResponse.Availability availability = plan.availability(hoveredItemForTooltip);
        if (availability != null && !availability.isEnough() && availability.missingCount() == 0) {
            lines.add(Component.translatable("rsi.plan.tooltip.planned")
                    .withStyle(ChatFormatting.GOLD));
        }

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
            case ModIds.GOETY, "goety_brazier" -> 0xCC222244;
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
