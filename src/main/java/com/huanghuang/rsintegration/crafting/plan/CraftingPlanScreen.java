package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.feature.ftbquests.QuestSubmissionRequestPacket;
import com.huanghuang.rsintegration.feature.ftbquests.QuestSubmissionTargetIds;
import com.huanghuang.rsintegration.recipe.viewer.LongTravailCrownRecipes;
import com.huanghuang.rsintegration.client.recipe.RecipeBrowserBridge;
import com.huanghuang.rsintegration.client.recipe.JeiCraftingPlanContext;
import com.huanghuang.rsintegration.mods.apotheosis.ApothSpawnerPlanTarget;
import com.huanghuang.rsintegration.mods.apotheosis.network.ApothSpawnerExecutePacket;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.huanghuang.rsintegration.crafting.CraftingResolver;
import com.huanghuang.rsintegration.crafting.planning.PlanningProgressSnapshot;
import com.huanghuang.rsintegration.crafting.planning.PlanningProgressOverlay;
import com.huanghuang.rsintegration.crafting.planning.PlanningProgressTracker;
import com.huanghuang.rsintegration.crafting.planning.PlanningRequestIds;
import com.huanghuang.rsintegration.crafting.planning.PlanningPreferences;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.TooltipFlag;
import net.minecraftforge.fml.ModList;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.ClientSyncedConfig;
import com.huanghuang.rsintegration.config.PlanUiConfig;
import com.huanghuang.rsintegration.config.PlanUiConfig.Color;
import com.huanghuang.rsintegration.crafting.plan.PlanWorkspaceLayout.Rect;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.batch.BatchCraftNetworkHandler;
import com.huanghuang.rsintegration.crafting.batch.GenericCraftPacket;
import com.huanghuang.rsintegration.crafting.batch.PrepareIntermediateMaterialsPacket;
import com.huanghuang.rsintegration.crafting.OutputDestination;
import com.huanghuang.rsintegration.crafting.MachineSelectionMode;
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
import com.huanghuang.rsintegration.client.input.GuiNavStack;
import com.huanghuang.rsintegration.machine.client.BindingBackendResolver;
import com.huanghuang.rsintegration.feature.sidepanel.client.SidePanelJeiBridge;
import com.huanghuang.rsintegration.network.machine.OpenBoundMachineGuiPacket;
import com.huanghuang.rsintegration.network.machine.bd.BeyondDimensionsOpenBoundMachineGuiPacket;
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
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.mods.ironsspellbooks.client.InkFluidRenderer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.nio.file.Path;
import java.util.*;

@OnlyIn(Dist.CLIENT)
public final class CraftingPlanScreen extends Screen {

    private PlanInventoryClientState inventoryState;
    private final Map<IngredientKey, List<PlanTreeNode>> inventoryNodes = new HashMap<>();
    private final Map<IngredientKey, Integer> inventoryStripIndices = new HashMap<>();

    /** 材料包不进入 updatePlan：不重建树、控件或滚动位置。 */
    public void updateInventory(PlanInventoryUpdatePacket packet) {
        if (inventoryState == null || !inventoryState.accept(packet)) return;
        this.plan = inventoryState.view();
        for (IngredientKey key : inventoryState.changed()) {
            PlanResponse.Availability value = plan.materials().get(key);
            for (PlanTreeNode node : inventoryNodes.getOrDefault(key, List.of())) {
                node.available = value.available();
                node.needed = value.needed();
                node.missingCount = value.missingCount();
            }
            Integer index = inventoryStripIndices.get(key);
            if (index != null) {
                StripEntry previous = cachedTotalStrip.get(index);
                cachedTotalStrip.set(index, new StripEntry(previous.display(), key,
                        value.needed(), value.available(), value.missingCount() == 0));
            }
        }
        JeiCraftingPlanContext.INSTANCE.updateMaterials(plan, inventoryState.changed());
        if (detailsPanel != null) detailsPanel.updateInventory(plan, displayWarnings(plan));
        if (materialTable != null) materialTable.update(plan);
        updateConfirmButton();
    }

    private void watchInventory() {
        if (externalRequest != null || plan.graph() == null || responseRequests.waitingForPreview()) return;
        if (inventoryState != null && inventoryState.needsResync()) stopInventoryWatch();
        if (inventoryState != null) return;
        inventoryState = new PlanInventoryClientState(responseRequests.activeRequestId(), PlanningRequestIds.next(), plan);
        BatchCraftNetworkHandler.CHANNEL.sendToServer(new PlanInventoryWatchPacket(
                inventoryState.requestId, inventoryState.watchId, true));
    }

    private void stopInventoryWatch() {
        if (inventoryState == null) return;
        BatchCraftNetworkHandler.CHANNEL.sendToServer(new PlanInventoryWatchPacket(
                inventoryState.requestId, inventoryState.watchId, false));
        inventoryState = null;
    }

    private static final Path PLAN_PREFS_PATH =
            ClientPlanningPreferences.preferencesPath();
    private static final int STEPS_TOP_MIN = 40;
    private int stepsTop = STEPS_TOP_MIN; // dynamic — grows when title wraps

    private PlanResponse plan;
    private int currentRepeat = 1;
    private final PlanResponseRequestState responseRequests;
    private int selectedMachineIndex = -1;
    private MachineSelectionMode machineSelectionMode = MachineSelectionMode.AUTO;
    private int countPillX, countPillY, countPillW, countPillH;
    @Nullable
    private EditBox repeatCountBox;
    private boolean updatingRepeatCountBox;
    private int planRefreshTick = -1;
    private int lastRefreshCount = 1;
    private int ticksOpen;
    private long previewStartedAt;
    @Nullable private Component previewFeedback;
    private Color previewFeedbackColor = Color.MUTED;
    private int mouseX, mouseY;

    // ── Recipe-tree view (v3.4) ──────────────────────────────────
    private PlanTreeModel treeModel;
    private final SelectedPath selectedPath = new SelectedPath();
    private final PlanTreeLayout treeLayout = new PlanTreeLayout();
    private PlanTreeRenderer treeRenderer;
    private final RecipePreviewRenderer recipePreview = new RecipePreviewRenderer();
    // Camera — logical→screen transform is screenX = logicalX*zoom + panX.
    private double treeZoom = 1.0;
    private double treePanX, treePanY;
    private boolean treeCameraInit;
    private final PlanTreeMouseGesture<PlanTreeNode> treeMouseGesture = new PlanTreeMouseGesture<>();
    // Tree viewport rect (screen space), set each render for hit-testing / centering.
    private int treeViewLeft, treeViewTop, treeViewRight, treeViewBottom;
    // 固定在树画布顶部的需求/余产物摘要，使用屏幕像素。
    private int stripPageSize = 8;
    private int stripMaxEntryWidth = 68;
    private int treeContentTop;
    private final List<CostHit> costHits = new ArrayList<>();
    private record CostHit(int x, int y, int w, int h, IngredientKey key) {}
    private record StripEntry(ItemStack display, IngredientKey key, long count, long available,
                              boolean enough) {}
    private List<StripEntry> cachedTotalStrip = List.of();
    private List<StripEntry> cachedLeftoverStrip = List.of();
    private int totalStripPage;
    private int leftoverStripPage;
    private final List<BookmarkHit> bookmarkHits = new ArrayList<>();
    private record BookmarkHit(int x, int y, int w, int h, ItemStack stack, long missingCount) {}
    @Nullable
    private BookmarkHit hoveredBookmark;
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
    // 候选配方与主视图共用服务器分支选择，右侧面板只负责展示和导航。
    private PlanTreeNode dropdownNode;
    private RecipeCandidatePanel recipeCandidatePanel;
    private PlanDetailsPanel detailsPanel;
    private PlanWorkspaceLayout workspace;
    private PlanWorkspaceChrome chrome;
    private PlanMaterialTable materialTable;
    private final PlanTreeMiniMap miniMap = new PlanTreeMiniMap();
    private EditBox workspaceSearch;
    private PlanTreeSearchPanel treeSearch;
    private Rect workspaceSearchBounds;
    private Button cancelButton;
    private boolean materialView, quantityMenuOpen, embersDetailsOpen;
    private int embersScroll;
    @Nullable
    private PendingCandidateSwitch pendingCandidateSwitch;
    @Nullable
    private PendingMaterialChoice pendingMaterialChoice;
    private final Map<String, ItemStack> materialLocks = new LinkedHashMap<>();
    /** 从偏好文件载入的软材料选择，只用于回显，不进入本次会话的硬锁。 */
    private final Map<String, ItemStack> preferredMaterials = new LinkedHashMap<>();
    /** NoRecipe 会把步骤变成叶子；保留最近步骤作为当场撤回的候选上下文。 */
    private final Map<IngredientKey, PlanStep> candidateStepHistory = new LinkedHashMap<>();
    private record PendingCandidateSwitch(IngredientKey key,
                                          SelectedPath.BranchSnapshot previous,
                                          CraftingPlanPreferences.Snapshot preferences) {}

    // Hover intent: the full JEI recipe preview only pops after the mouse rests on the same
    // target for HOVER_INTENT_MS, so a quick pass across nodes/candidates doesn't spam previews.
    private static final long HOVER_INTENT_MS = 150L;
    @Nullable
    private ResourceLocation hoverPreviewId;
    private long hoverPreviewStart;

    private int embersPedestalY;
    private int embersPedestalH;
    private int embersCardsH;          // pedestal cards height (mode toggle sits below)
    private boolean embersInferMode;   // current mode toggle state
    private boolean showEmbersModeToggle; // config-gated: only when Calculate is enabled
    private int embersModeY;           // mode toggle row Y
    private int embersModeCalcX, embersModeCalcY, embersModeCalcW, embersModeCalcH;
    private int embersModeInferX, embersModeInferY, embersModeInferW, embersModeInferH;

    // Deferred tooltip — set during draw, rendered after all scissors disabled
    private ItemStack hoveredItemForTooltip = ItemStack.EMPTY;
    private List<Component> hoveredStepWarnings = List.of();
    private int hoveredTooltipX, hoveredTooltipY;
    private long hoveredTooltipAvail, hoveredTooltipNeeded;
    /** 当前悬停的是否为引用节点（成本已在树中别处展开）。 */
    private boolean hoveredTooltipReference;

    /** 外部终端复用计划树，同时保留自己的执行入口与父界面。 */
    public interface RequestHandler {
        void request(boolean preview, long requestId, Map<String, String> forced,
                     Map<String, ItemStack> locks, PlanningPreferences preferences);
    }

    private final RequestHandler externalRequest;
    private final Runnable returnToParent;

    public CraftingPlanScreen(PlanResponse plan, long requestId,
                              RequestHandler externalRequest, Runnable returnToParent) {
        super(Component.translatable("rsi.plan.title",
                plan.targetResult().getHoverName().getString()));
        this.plan = plan;
        this.externalRequest = externalRequest;
        this.returnToParent = returnToParent;
        this.responseRequests = new PlanResponseRequestState(requestId, plan.recipeId());
        this.currentRepeat = clampRepeatCount(plan.repeatCount());
        this.lastRefreshCount = currentRepeat;
        this.outputDestination = hasStorageBackend()
                ? CraftingPlanPreferences.loadOutputDestination(PLAN_PREFS_PATH)
                : OutputDestination.PLAYER_INVENTORY;
        this.storageReference = plan.storageReference();
        this.storageNetworks = plan.storageNetworks();
        // 规划统一展示递归树，选材、配方预览与材料账单放入同一侧栏。
        this.treeRenderer = new PlanTreeRenderer(Minecraft.getInstance().font);
        this.treeRenderer.setIconRenderer(recipePreview::drawCategoryIcon);
        loadStoredMaterialChoices();
        rebuildMaterialStripCache();
        rebuildTreeModel(true);
        JeiCraftingPlanContext.INSTANCE.activate(plan);
    }

    protected CraftingPlanScreen(PlanResponse plan, long requestId) {
        this(plan, requestId, null, null);
    }

    /** Exposed for {@link PlanResponsePacket} dedup check. */
    public String getRecipeId() {
        return plan.recipeId();
    }

    public long activeRequestId() {
        return responseRequests.activeRequestId();
    }

    @Override
    public void onClose() {
        preserveLatestPreview();
        stopInventoryWatch();
        responseRequests.cancelConfirmation();
        if (responseRequests.pending()) {
            PlanBackgroundClient.detach(responseRequests.activeRequestId());
        }
        if (returnToParent != null) {
            returnToParent.run();
            return;
        }
        super.onClose();
    }

    @Override
    public void removed() {
        preserveLatestPreview();
        if (responseRequests.pending()) PlanBackgroundClient.detach(responseRequests.activeRequestId());
        stopInventoryWatch();
        responseRequests.cancelConfirmation();
        treeMouseGesture.cancel();
        super.removed();
    }

    private void preserveLatestPreview() {
        if (minecraft == null || minecraft.player == null) return;
        commitRepeatCountInput();
        // 退出时不能丢失尚在防抖中的最终数量；发出一次后转后台保存结果。
        if (responseRequests.refreshScheduled()) refreshPlan();
    }

    public boolean acceptResponse(long requestId, PlanResponse newPlan) {
        if (!responseRequests.accept(requestId, newPlan.recipeId())) {
            RSIntegrationMod.LOGGER.debug(
                    "[RSI-PlanPkt] Dropping unrelated response: request={} recipe={} expectedRequest={} expectedRecipe={}",
                    requestId, newPlan.recipeId(), responseRequests.activeRequestId(),
                    responseRequests.expectedRecipeId());
            return false;
        }
        // 新数量尚未发出时，旧请求的响应也不能覆盖当前输入或触发合成。
        if (responseRequests.refreshScheduled()) return true;
        if (newPlan.recipeId() == null || newPlan.recipeId().isEmpty()) {
            responseRequests.cancelConfirmation();
            if (minecraft != null && minecraft.player != null) {
                List<Component> warnings = displayWarnings(newPlan);
                if (!warnings.isEmpty()) {
                    minecraft.player.displayClientMessage(warnings.get(0), false);
                }
            }
            rollbackPendingCandidateSwitch();
            reopenPendingCandidates(displayWarnings(newPlan));
            updateConfirmButton();
            return true;
        }
        // 预览失败且没有目标步骤时不要清空当前可用计划。压缩/分解配方很容易
        // 形成 锭→块→锭 的闭环；若直接替换计划树，原节点会消失，玩家就无法
        // 从候选面板切回上一条配方。
        if (externalRequest == null && PlanPreviewDecision.keepCurrentPlan(
                plan, newPlan, pendingCandidateSwitch != null || pendingMaterialChoice != null)) {
            responseRequests.cancelConfirmation();
            if (minecraft != null && minecraft.player != null) {
                List<Component> warnings = displayWarnings(newPlan);
                minecraft.player.displayClientMessage(warnings.isEmpty()
                        ? Component.translatable("rsi.plan.status_fail")
                        : warnings.get(0), false);
            }
            rollbackPendingCandidateSwitch();
            reopenPendingCandidates(displayWarnings(newPlan));
            updateConfirmButton();
            return true;
        }
        updatePlan(newPlan);
        previewFeedback = workspaceText("recalculated");
        previewFeedbackColor = Color.READY;
        boolean executable = (newPlan.success() && !newPlan.executionBlocked())
                || (partialPreparation && newPlan.outcome() == PlanOutcome.MISSING_INPUTS);
        if (responseRequests.consumeConfirmation(executable)) {
            onConfirm();
        } else {
            updateConfirmButton();
        }
        return true;
    }

    /** Update plan data in-place (OR-path switch, repeat-count change, etc.).
     *  Avoids creating a new screen which would lose UI state. */
    public void updatePlan(PlanResponse newPlan) {
        stopInventoryWatch();
        MachineCandidateView previous = selectedMachineCandidate();
        this.plan = newPlan;
        this.currentRepeat = clampRepeatCount(newPlan.repeatCount());
        this.lastRefreshCount = currentRepeat;
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
        this.dropdownNode = null;
        commitPendingCandidateSwitch();
        loadStoredMaterialChoices();
        rebuildMaterialStripCache();
        rebuildTreeModel(false);
        this.clearWidgets();
        this.init();
        pendingCandidateSwitch = null;
        pendingMaterialChoice = null;
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
        inventoryNodes.clear();
        Deque<PlanTreeNode> inventoryQueue = new ArrayDeque<>();
        inventoryQueue.add(treeModel.root);
        while (!inventoryQueue.isEmpty()) {
            PlanTreeNode node = inventoryQueue.removeFirst();
            inventoryNodes.computeIfAbsent(node.key, ignored -> new ArrayList<>()).add(node);
            inventoryQueue.addAll(node.children);
        }
        PlanTreeModel.applyCollapsedNodes(treeModel.root, collapsed);
        JeiSubtreeBuilder.enrichCarousels(treeModel.root, materialDisplayLocks());
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
        treeMouseGesture.cancel();
        Font font = minecraft.font;
        if (recipeCandidatePanel == null) {
            recipeCandidatePanel = new RecipeCandidatePanel(font, recipePreview,
                    this::selectTreeBranch, this::cancelStockOnly,
                    this::forgetRecipePreference, this::recipeExclusionChanged, PLAN_PREFS_PATH,
                    this::bookmarkAllMissingMaterials,
                    () -> !MissingMaterialBookmarkList.bookmarkableItems(plan).isEmpty());
        }
        if (detailsPanel == null) {
            detailsPanel = new PlanDetailsPanel(font, recipeCandidatePanel, this::candidateNodeFor,
                    this::selectMaterial, this::navigateToNode,
                    (stack, count) -> bookmarkMissingMaterial(new BookmarkHit(0, 0, 0, 0, stack, count)),
                    this::bookmarkAllMissingMaterials, responseRequests::calculating,
                    this::refreshPlan, this::openRecipeInJei, new PlanDetailsPanel.MachineControl() {
                        @Override public MachineSelectionMode mode() { return machineSelectionMode; }
                        @Override public int selectedIndex() { return selectedMachineIndex; }
                        @Override public void selectMode(MachineSelectionMode mode) {
                            machineSelectionMode = mode;
                            if (mode != MachineSelectionMode.AUTO && selectedMachineIndex < 0) {
                                selectedMachineIndex = plan.machineCandidates().stream()
                                        .filter(candidate -> candidate.state() == MachineCandidateView.State.READY)
                                        .findFirst().map(candidate -> plan.machineCandidates().indexOf(candidate)).orElse(-1);
                            }
                            cancelQueuedConfirmation();
                        }
                        @Override public void selectCandidate(int index) {
                            selectedMachineIndex = index;
                            if (machineSelectionMode == MachineSelectionMode.AUTO) machineSelectionMode = MachineSelectionMode.PREFERRED;
                            cancelQueuedConfirmation();
                        }
                        @Override public void openTarget() { onOpenMachine(); }
                    });
        }
        detailsPanel.update(plan, treeModel.root, displayWarnings(plan));
        detailsPanel.setShowAllMaterials(() -> { materialView = true; embersDetailsOpen = false; });
        dropdownNode = detailsPanel.isOpen() ? detailsPanel.selectedNode() : null;
        addWidget(recipeCandidatePanel.searchBox());
        addWidget(detailsPanel.searchBox());
        int contentW = width - 40;

        // Embers alchemy pedestal layout height
        boolean hasEmbers = plan.embersCode() != null
                && plan.embersAspectNames() != null
                && plan.embersInputNames() != null;
        boolean canInfer = plan.embersCanInfer();
        if (hasEmbers && plan.embersCode().length > 0) {
            int pedestalCount = plan.embersCode().length;
            int cardsPerRow = Math.max(1, Math.min(pedestalCount, (contentW - 16) / 68));
            embersCardsH = font.lineHeight + 18 + 48 * ((pedestalCount + cardsPerRow - 1) / cardsPerRow);
        } else {
            embersCardsH = 0;
        }
        // Show mode toggle only when both modes are available (calc enabled AND tablet bound)
        int embersModeH = (showEmbersModeToggle = canInfer && embersCalcEnabled()) ? 28 : 0;
        embersPedestalH = embersCardsH + embersModeH;
        // Default mode: Calculate if code is known (from cache or computed), Infer otherwise
        embersInferMode = !hasEmbers;

        createRepeatCountInput(font);

        chrome = new PlanWorkspaceChrome(font);
        materialTable = new PlanMaterialTable(font, this::centerOnKey,
                (stack, count) -> bookmarkMissingMaterial(new BookmarkHit(0, 0, 0, 0, stack, count)),
                this::bookmarkAllMissingMaterials);
        materialTable.update(plan);
        String oldSearch = workspaceSearch == null ? "" : workspaceSearch.getValue();
        workspaceSearch = new EditBox(font, 0, 0, 100, 14, workspaceText("search"));
        workspaceSearch.setBordered(false);
        workspaceSearch.setHint(workspaceText("search"));
        workspaceSearch.setMaxLength(100);
        treeSearch = new PlanTreeSearchPanel(font, node -> navigateToNode(node, true));
        treeSearch.update(treeModel.root);
        workspaceSearch.setResponder(value -> {
            materialTable.search(value);
            treeSearch.search(value);
            if (materialView || embersDetailsOpen || !workspaceSearch.isFocused()) treeSearch.close();
        });
        workspaceSearch.setValue(oldSearch);
        addWidget(workspaceSearch);
        confirmButton = addRenderableWidget(new PlanUiButton(0, 0, 150, 24,
                executionActionLabel(), button -> onConfirm()).primary());
        if (externalRequest == null) confirmButton.setTooltip(Tooltip.create(
                Component.translatable("rsi.plan.machine_clear_notice")));
        cancelButton = addRenderableWidget(new PlanUiButton(0, 0, 72, 24,
                Component.translatable("rsi.plan.cancel"), button -> onClose()));
        updateConfirmButton();
        layoutWorkspace();
    }

    private Component executionActionLabel() {
        if (responseRequests.confirmationRequested()) {
            return Component.translatable("rsi.plan.confirm_waiting");
        }
        if (partialPreparation) return Component.translatable("rsi.plan.prepare.action");
        PlanConfirmationAction action = inventoryConfirmationAction();
        if (action.labelKey != null) return Component.translatable(action.labelKey);
        return Component.translatable(selectedPath.isDirty()
                ? "rsi.plan.confirm_branches" : "rsi.plan.confirm");
    }

    private PlanConfirmationAction inventoryConfirmationAction() {
        PlanConfirmationAction action = PlanConfirmationAction.forInventory(
                inventoryState == null ? null : inventoryState.status());
        return action == PlanConfirmationAction.CHECK_STOCK && !plan.success()
                ? PlanConfirmationAction.COMPLETE_ROUTE : action;
    }

    private void updateConfirmButton() {
        if (confirmButton == null) return;
        confirmButton.setMessage(executionActionLabel());
        confirmButton.active = !responseRequests.confirmationRequested()
                && (responseRequests.waitingForPreview() || partialPreparation
                || !plan.executionBlocked() || selectedPath.isDirty());
    }

    private void cancelQueuedConfirmation() {
        responseRequests.cancelConfirmation();
        updateConfirmButton();
    }

    private void createRepeatCountInput(Font font) {
        if (externalRequest != null) return;
        repeatCountBox = new EditBox(font, 0, 0,
                40, 14, Component.translatable("rsi.plan.repeat_count"));
        repeatCountBox.setBordered(false);
        repeatCountBox.setTextColor(Color.MUTED.value());
        repeatCountBox.setTextColorUneditable(Color.MUTED.value());
        repeatCountBox.setMaxLength(Integer.toString(repeatCountLimit()).length());
        repeatCountBox.setFilter(value -> value.isEmpty()
                || value.chars().allMatch(Character::isDigit));
        updatingRepeatCountBox = true;
        repeatCountBox.setValue(Integer.toString(currentRepeat));
        updatingRepeatCountBox = false;
        repeatCountBox.setResponder(this::onRepeatCountEdited);
        addWidget(repeatCountBox);
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
        int normalized = externalRequest == null ? clampRepeatCount(count) : 1;
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
        cancelQueuedConfirmation();
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
        if (responseRequests.confirmationRequested()) return;
        if (!partialPreparation && inventoryConfirmationAction().replan) {
            responseRequests.scheduleRefresh();
        }
        // 点击确认时立即发出防抖中的最新预览，避免执行请求打断仍在运行的规划。
        if (responseRequests.refreshRequired()) refreshPlan();
        if (responseRequests.requestConfirmation()) {
            updateConfirmButton();
            return;
        }
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
        long requestId = preview && QuestSubmissionTargetIds.isQuestSubmission(rid)
                ? 0L : PlanningRequestIds.next();
        if (preview) {
            previewStartedAt = System.currentTimeMillis();
            previewFeedback = null;
            planRefreshTick = -1;
            lastRefreshCount = repeatCount;
            long previousRequest = responseRequests.activeRequestId();
            long supersededRequestId = responseRequests.beginPreview(requestId, rid.toString());
            if (supersededRequestId > 0L) PlanningProgressOverlay.cancel(supersededRequestId);
            PlanBackgroundClient.replaceRevision(previousRequest, requestId);
            updateConfirmButton();
        }
        if (externalRequest != null) {
            externalRequest.request(preview, requestId, forced, Map.copyOf(materialLocks),
                    requestPreferences());
            return;
        }
        if (preview && QuestSubmissionTargetIds.isQuestSubmission(rid)) {
            // Quest previews use their own planner and return an uncorrelated
            // PlanResponsePacket. Starting the ordinary planning HUD here would
            // leave it waiting forever because GenericCraftPacket never handles
            // synthetic quest target IDs.
            PlanningProgressTracker.responseReceived(requestId);
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
        ItemStack requestTarget = executionTarget(plan.clickedOutput(), plan.targetResult());
        if (LongTravailCrownRecipes.isRecipeId(rid)
                && !rid.toString().equals(plan.recipeId())
                && minecraft.level != null) {
            Recipe<?> selectedRecipe = minecraft.level.getRecipeManager()
                    .byKey(rid).orElse(null);
            ItemStack selectedOutput = selectedRecipe == null ? ItemStack.EMPTY
                    : selectedRecipe.getResultItem(minecraft.level.registryAccess());
            requestTarget = LongTravailCrownRecipes.targetForSelectedRecipe(
                    rid, selectedOutput, requestTarget);
        }
        GenericCraftPacket packet = new GenericCraftPacket(rid, preview, forced, execDim, execPos,
                        repeatCount, inferMode, plan.baseItem(),
                        requestTarget, requestId,
                        outputDestination).withMachineSelectionMode(machineSelectionMode)
                .withMaterialLocks(materialLocks)
                .withPlanningPreferences(requestPreferences())
                .withStorageReference(storageReference);
        if (!preview && !partialPreparation && !selectedPath.isDirty()) {
            packet.withConfirmedPreview(responseRequests.activeRequestId());
            if (minecraft.screen instanceof PlanScreenHost host) {
                host.rsi$planSubmitted(responseRequests.activeRequestId());
            }
        }
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

    private PlanningPreferences requestPreferences() {
        CraftingPlanPreferences.Snapshot stored = pendingCandidateSwitch == null
                ? CraftingPlanPreferences.load(PLAN_PREFS_PATH) : pendingCandidateSwitch.preferences();
        return (pendingMaterialChoice == null ? stored : pendingMaterialChoice.preview(stored))
                .toPlanningPreferences();
    }

    /** Open the active recipe browser for a specific recipe id. */
    private void openRecipeInJei(ResourceLocation recipeId) {
        if (minecraft.level == null) return;
        var vanillaRecipe = minecraft.level.getRecipeManager().byKey(recipeId).orElse(null);
        if (vanillaRecipe == null) return;
        SidePanelJeiBridge.showJeiForItem(false,
                vanillaRecipe.getResultItem(minecraft.level.registryAccess()));
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
        MachineCandidateView selected = machineSelectionMode == MachineSelectionMode.AUTO ? null : selectedMachineCandidate();
        String dimension = selected == null ? plan.executionDim() : selected.dimension();
        ResourceLocation dim = dimension == null ? null : ResourceLocation.tryParse(dimension);
        if (dim == null) return;
        BlockPos pos = selected == null ? new BlockPos(
                plan.executionPosX(), plan.executionPosY(), plan.executionPosZ())
                : new BlockPos(selected.x(), selected.y(), selected.z());
        ResourceLocation recipeId = ResourceLocation.tryParse(plan.recipeId());
        GuiNavStack.pushCurrent();
        if (BindingBackendResolver.isBeyondDimensionsBinding(dim, pos)) {
            NetworkHandler.CHANNEL.sendToServer(
                    new BeyondDimensionsOpenBoundMachineGuiPacket(dim, pos));
        } else {
            NetworkHandler.CHANNEL.sendToServer(
                    new OpenBoundMachineGuiPacket(dim, pos, plan.recipeId(), recipeId, plan.baseItem()));
        }
    }

    private void requestPlanRefresh() {
        if (currentRepeat == lastRefreshCount) return;
        lastRefreshCount = currentRepeat;
        planRefreshTick = ticksOpen + 10;
        responseRequests.scheduleRefresh();
        updateConfirmButton();
    }

    private void refreshPlan() {
        if (responseRequests.pending() && !responseRequests.refreshScheduled()) return;
        String recipeId = pendingCandidateSwitch == null
                ? plan.recipeId() : responseRequests.expectedRecipeId();
        ResourceLocation id = ResourceLocation.tryParse(recipeId);
        sendCraftPacket(id, true, exportForcedSelections(), currentRepeat, false);
    }

    @Override
    public void tick() {
        super.tick();
        watchInventory();
        if (repeatCountBox != null) repeatCountBox.tick();
        if (workspaceSearch != null) workspaceSearch.tick();
        if (detailsPanel != null) detailsPanel.tick();
        ticksOpen++;
        if (planRefreshTick >= 0 && ticksOpen >= planRefreshTick
                && (repeatCountBox == null || !repeatCountBox.isFocused())) {
            refreshPlan();
        }
        long requestId = responseRequests.activeRequestId();
        PlanningProgressSnapshot progress = PlanningProgressTracker.get(requestId);
        boolean endedWithoutResult = progress == null
                ? !PlanningProgressTracker.acceptsResponse(requestId)
                : progress.terminal() && progress.state() != PlanningProgressSnapshot.State.SUCCEEDED;
        if (!responseRequests.refreshScheduled() && endedWithoutResult
                && responseRequests.failPreview(requestId)) {
            // 部分准入失败只回传进度终态，解除等待并让下次确认重新规划。
            rollbackPendingCandidateSwitch();
            reopenPendingCandidates(progress == null ? List.of() : List.of(progress.detail()));
            updateConfirmButton();
        }
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        this.mouseX = mouseX;
        this.mouseY = mouseY;
        hoveredItemForTooltip = ItemStack.EMPTY;
        hoveredStepWarnings = List.of();
        hoveredBookmark = null;
        bookmarkHits.clear();
        costHits.clear();
        layoutWorkspace();
        chrome.begin(gfx, workspace);
        renderWorkspaceHeader(gfx);
        renderWorkspaceSummary(gfx);
        renderWorkspaceToolbar(gfx, partialTick);
        if (embersDetailsOpen) renderEmbersSheet(gfx);
        else if (materialView) materialTable.render(gfx, workspace.content(), mouseX, mouseY);
        else {
            Rect tree = workspace.tree();
            renderTreeArea(gfx, tree.x(), tree.y(), tree.width(), tree.bottom(), mouseX, mouseY);
            renderRecipeCandidates(gfx, mouseX, mouseY);
        }
        renderWorkspaceFooter(gfx);
        super.render(gfx, mouseX, mouseY, partialTick);
        if (quantityMenuOpen) renderQuantityMenu(gfx);
        if (!materialView && !embersDetailsOpen && workspaceSearch.isFocused()) {
            treeSearch.render(gfx, workspaceSearchBounds, workspace.frame(), mouseX, mouseY);
            if (treeSearch.contains(mouseX, mouseY)) {
                hoveredItemForTooltip = ItemStack.EMPTY;
                hoveredStepWarnings = List.of();
                hoveredBookmark = null;
            }
        } else treeSearch.close();
        renderDeferredTooltip(gfx, font);
    }

    private void layoutWorkspace() {
        workspace = PlanWorkspaceLayout.create(width, height,
                !materialView && !embersDetailsOpen && detailsPanel != null && detailsPanel.isOpen(), PlanUiConfig.panelWidth());
        if (detailsPanel != null) detailsPanel.layout(workspace.inspector());
        if (confirmButton != null) {
            Rect r = workspace.confirm();
            confirmButton.setPosition(r.x(), r.y());
            confirmButton.setWidth(r.width());
            confirmButton.setHeight(r.height());
            r = workspace.cancel();
            cancelButton.setPosition(r.x(), r.y());
            cancelButton.setWidth(r.width());
            cancelButton.setHeight(r.height());
        }
    }

    private void renderWorkspaceHeader(GuiGraphics g) {
        Rect r = workspace.header();
        boolean compact = workspace.compact();
        int iconSize = compact ? 18 : 24;
        g.pose().pushPose();
        g.pose().translate(r.x() + 3, r.y() + 3, 0);
        g.pose().scale(iconSize / 16f, iconSize / 16f, 1);
        InkFluidRenderer.render(g, plan.targetResult(), 0, 0);
        g.pose().popPose();
        String shortages = workspaceText("shortage_status", detailsPanel.shortageCount()).getString();
        String conditions = workspaceText("condition_status", detailsPanel.conditionCount()).getString();
        int maxBadgeWidth = Math.max(40, (r.width() - (compact ? 90 : 190) - (compact ? 0 : 26) - 5) / 2);
        int firstWidth = Math.min(maxBadgeWidth, font.width(shortages) + 12);
        int secondWidth = Math.min(maxBadgeWidth, font.width(conditions) + 12);
        int buttonsX = r.right() - firstWidth - secondWidth - 5 - (compact ? 0 : 26);
        int textX = r.x() + (compact ? 26 : 35);
        chrome.heading(g, title.getString(), textX, r.y() + 1, buttonsX - textX - 8, compact ? 1 : 1.4f);
        String status = I18n.get(plan.success() && !plan.executionBlocked() ? "rsi.plan.status_ok" : "rsi.plan.status_fail");
        Color statusColor = Color.MUTED;
        if (inventoryState != null && inventoryState.status() != null) status = I18n.get("rsi.plan.inventory."
                + inventoryState.status().name().toLowerCase(Locale.ROOT));
        if (responseRequests.refreshScheduled()) {
            status = workspaceText("recalculate_scheduled").getString();
            statusColor = Color.WARNING;
        } else if (responseRequests.pending()) {
            PlanningProgressSnapshot progress = PlanningProgressTracker.get(responseRequests.activeRequestId());
            String phase = progress == null ? I18n.get("rsi.planning.phase.accepting")
                    : I18n.get(PlanningProgressOverlay.phaseKey(progress.phase()));
            long elapsed = Math.max(progress == null ? 0L : progress.elapsedMillis(),
                    System.currentTimeMillis() - previewStartedAt);
            status = workspaceText("recalculating", phase, String.format(Locale.ROOT, "%.1f", elapsed / 1000.0)).getString();
            statusColor = Color.WARNING;
            int span = Math.max(1, r.width() / 5);
            int offset = (int) ((System.currentTimeMillis() / 12L) % Math.max(1, r.width() - span));
            g.fill(r.x() + offset, r.bottom() - 1, r.x() + offset + span, r.bottom(), Color.ACCENT.value());
        } else if (previewFeedback != null) {
            status = previewFeedback.getString();
            statusColor = previewFeedbackColor;
        }
        chrome.label(g, status, textX, r.y() + (compact ? 14 : 22), buttonsX - textX - 8, statusColor);
        if (mouseX >= textX && mouseX < buttonsX && mouseY >= r.y() + (compact ? 14 : 22) && mouseY < r.bottom()) {
            hoveredStepWarnings = List.of(Component.literal(status));
            hoveredTooltipX = mouseX; hoveredTooltipY = mouseY;
        }
        // 将缺料/条件合并为标题栏中的两个状态入口：显示数量，同时点击直接打开右侧对应列表。
        chrome.statusButton(g, new Rect(buttonsX, r.y() + 3, firstWidth, 20), shortages,
                detailsPanel.shortageCount() == 0 ? Color.READY : Color.WARNING,
                mouseX, mouseY, () -> showDiagnostics(false));
        chrome.statusButton(g, new Rect(buttonsX + firstWidth + 5, r.y() + 3, secondWidth, 20), conditions,
                detailsPanel.conditionCount() == 0 ? Color.READY : Color.WARNING,
                mouseX, mouseY, () -> showDiagnostics(true));
        if (!compact) chrome.button(g, new Rect(r.right() - 20, r.y() + 3, 20, 20), "×", false, true, mouseX, mouseY, this::onClose);
    }

    private void renderWorkspaceSummary(GuiGraphics g) {
        Rect r = workspace.summary();
        boolean compact = workspace.compact();
        int labelWidth = compact ? 45 : 90;
        chrome.heading(g, workspaceText("total_materials").getString(), r.x() + 9, r.y() + 9, labelWidth - 9, compact ? .85f : 1.05f);
        if (!compact) chrome.label(g, workspaceText("need_stock").getString(), r.x() + 8, r.y() + 27, labelWidth - 6, Color.MUTED);
        int left = r.x() + labelWidth;
        int leftoversWidth = cachedLeftoverStrip.isEmpty() || r.width() < 500 ? 0 : Math.min(140, r.width() / 5);
        int end = r.right() - leftoversWidth - 30;
        int cardW = compact ? 70 : 80;
        stripPageSize = Math.max(1, (end - left) / (cardW + 5));
        totalStripPage = MaterialStripPaging.clampPage(totalStripPage, cachedTotalStrip.size(), stripPageSize);
        int start = totalStripPage * stripPageSize;
        int visibleCount = Math.min(stripPageSize, cachedTotalStrip.size() - start);
        left += Math.max(0, (end - left - visibleCount * (cardW + 5) + 5) / 2);
        for (int i = start; i < Math.min(cachedTotalStrip.size(), start + stripPageSize); i++) {
            StripEntry e = cachedTotalStrip.get(i);
            Rect card = new Rect(left + (i - start) * (cardW + 5), r.y() + (compact ? 4 : 9), cardW, r.height() - (compact ? 8 : 18));
            PlanWorkspaceChrome.panel(g, card, e.enough() ? Color.BACKGROUND : Color.WARNING_SURFACE,
                    e.enough() ? Color.BORDER : Color.WARNING_BORDER);
            InkFluidRenderer.render(g, e.display(), card.x() + 3, card.y() + (card.height() - 16) / 2);
            chrome.label(g, stripCountText(e, false), card.x() + 23, card.y() + (card.height() - font.lineHeight) / 2,
                    cardW - 26, e.enough() ? Color.TEXT : Color.WARNING);
            costHits.add(new CostHit(card.x(), card.y(), card.width(), card.height(), e.key()));
            if (card.contains(mouseX, mouseY)) setStripTooltip(e);
        }
        if (cachedTotalStrip.size() > stripPageSize) {
            int x = r.right() - leftoversWidth - 26;
            chrome.button(g, new Rect(x, r.y() + 4, 11, r.height() - 8), "‹", false,
                    totalStripPage > 0, mouseX, mouseY, () -> totalStripPage--);
            chrome.button(g, new Rect(x + 12, r.y() + 4, 11, r.height() - 8), "›", false,
                    start + stripPageSize < cachedTotalStrip.size(), mouseX, mouseY, () -> totalStripPage++);
        }
        if (leftoversWidth > 0) {
            int x = r.right() - leftoversWidth;
            g.fill(x, r.y() + 5, x + 1, r.bottom() - 5, Color.BORDER.value());
            chrome.label(g, workspaceText("leftovers").getString(), x + 7, r.y() + (compact ? 2 : 5),
                    leftoversWidth - 22, Color.MUTED);
            int iconSize = compact ? 12 : 16, iconY = r.y() + (compact ? 12 : 18);
            int slots = Math.max(1, (leftoversWidth - 25) / 46);
            leftoverStripPage = MaterialStripPaging.clampPage(leftoverStripPage, cachedLeftoverStrip.size(), slots);
            for (int i = 0; i < slots; i++) {
                int index = leftoverStripPage * slots + i;
                if (index >= cachedLeftoverStrip.size()) break;
                StripEntry e = cachedLeftoverStrip.get(index);
                int cx = x + 7 + i * 46;
                g.pose().pushPose();
                g.pose().translate(cx, iconY, 0);
                g.pose().scale(iconSize / 16f, iconSize / 16f, 1);
                InkFluidRenderer.render(g, e.display(), 0, 0);
                g.pose().popPose();
                chrome.label(g, "×" + PlanRenderEngine.formatCompact(e.count()), cx + iconSize + 2,
                        iconY + (iconSize - font.lineHeight) / 2, 28, Color.WARNING);
                Rect hit = new Rect(cx, iconY, 44, iconSize);
                costHits.add(new CostHit(hit.x(), hit.y(), hit.width(), hit.height(), e.key()));
                if (hit.contains(mouseX, mouseY)) setStripTooltip(e);
            }
            chrome.button(g, new Rect(r.right() - 15, r.y() + (compact ? 9 : 18), 11, compact ? 16 : 18), "›", false,
                    cachedLeftoverStrip.size() > slots, mouseX, mouseY,
                    () -> leftoverStripPage = (leftoverStripPage + 1) % MaterialStripPaging.pageCount(cachedLeftoverStrip.size(), slots));
        }
    }

    private void setStripTooltip(StripEntry e) {
        hoveredItemForTooltip = e.display();
        hoveredTooltipAvail = e.available();
        hoveredTooltipNeeded = e.count();
        hoveredTooltipReference = false;
        hoveredTooltipX = mouseX; hoveredTooltipY = mouseY;
    }

    private void renderWorkspaceToolbar(GuiGraphics g, float partialTick) {
        Rect r = workspace.toolbar();
        if (!materialView && !embersDetailsOpen && detailsPanel.isOpen()) {
            r = new Rect(r.x(), r.y(), workspace.inspector().x() - r.x() - 8, r.height());
        }
        boolean narrow = width < 720;
        chrome.button(g, new Rect(r.x(), r.y(), 67, 20), workspaceText("tree").getString(),
                !materialView && !embersDetailsOpen, true, mouseX, mouseY, () -> { materialView = false; embersDetailsOpen = false; });
        chrome.button(g, new Rect(r.x() + 72, r.y(), 74, 20), workspaceText("materials").getString(),
                materialView, true, mouseX, mouseY, () -> { materialView = true; embersDetailsOpen = false; });
        int searchWidth = narrow ? Math.max(35, r.width() - 156) : Math.min(215, Math.max(60, r.width() - 450 - (embersPedestalH > 0 ? 39 : 0)));
        Rect search = new Rect(r.x() + 156, r.y(), searchWidth, 20);
        workspaceSearchBounds = search;
        PlanWorkspaceChrome.panel(g, search, Color.BACKGROUND, Color.BORDER);
        workspaceSearch.setPosition(search.x() + 5, search.y() + 5);
        workspaceSearch.setWidth(search.width() - 10);
        workspaceSearch.setTextColor(Color.TEXT.value());
        workspaceSearch.setHint(workspaceText(materialView ? "search_materials" : "search_tree"));
        workspaceSearch.render(g, mouseX, mouseY, partialTick);
        if (search.contains(mouseX, mouseY)) {
            hoveredStepWarnings = List.of(workspaceText(materialView ? "search_materials_help" : "search_tree_help"));
            hoveredTooltipX = mouseX; hoveredTooltipY = mouseY;
        }
        int x = narrow ? r.x() : search.right() + 6, y = narrow ? r.y() + 20 : r.y();
        int fitWidth = narrow ? 44 : 64;
        chrome.button(g, new Rect(x, y, narrow ? 44 : 95, 20), workspaceText("steps").getString(), false, true,
                mouseX, mouseY, () -> { showTreeInspector(); detailsPanel.showSteps(); }); x += (narrow ? 44 : 95) + 4;
        chrome.button(g, new Rect(x, y, fitWidth, 18), workspaceText("fit").getString(), false, true,
                mouseX, mouseY, () -> { materialView = false; embersDetailsOpen = false; layoutWorkspace(); updateTreeViewport(); fitTree(); }); x += fitWidth + 4;
        boolean expanded = treeAllExpanded(treeModel.root);
        chrome.button(g, new Rect(x, y, fitWidth, 18), I18n.get(expanded ? "rsi.plan.collapse_all" : "rsi.plan.expand_all"),
                false, true, mouseX, mouseY, () -> { setAllExpanded(treeModel.root, !expanded); treeLayout.markDirty(); }); x += fitWidth + 4;
        chrome.button(g, new Rect(x, y, narrow ? 35 : 48, 18), workspaceText("machines").getString(), false, true,
                mouseX, mouseY, () -> { showTreeInspector(); detailsPanel.showMachines(); }); x += (narrow ? 35 : 48) + 4;
        if (embersPedestalH > 0) {
            chrome.button(g, new Rect(x, y, 35, 18), workspaceText("embers").getString(), embersDetailsOpen, true,
                    mouseX, mouseY, () -> { embersDetailsOpen = !embersDetailsOpen; materialView = false; }); x += 39;
        }
        int right = r.right();
        if (right - x >= 22) chrome.button(g, new Rect(right - 18, y, 18, 18), "?", false, true, mouseX, mouseY,
                () -> { hoveredStepWarnings = List.of(Component.translatable("rsi.plan.tree_help")); });
        if (mouseX >= right - 18 && mouseX < right && mouseY >= y && mouseY < y + 18) {
            hoveredStepWarnings = Arrays.stream(I18n.get("rsi.plan.tree_help").split("\n")).map(Component::literal).map(c -> (Component) c).toList();
            hoveredTooltipX = mouseX; hoveredTooltipY = mouseY;
        }
        if (right - x >= 104) {
            chrome.button(g, new Rect(right - 100, y, 18, 18), "−", false, true, mouseX, mouseY, () -> zoomTree(1 / 1.1));
            chrome.label(g, Math.round(treeZoom * 100) + "%", right - 77, y + 5, 37, Color.MUTED);
            chrome.button(g, new Rect(right - 40, y, 18, 18), "+", false, true, mouseX, mouseY, () -> zoomTree(1.1));
        }
    }

    private void showTreeInspector() {
        materialView = false; embersDetailsOpen = false;
        unfocusRepeatCountInput(); workspaceSearch.setFocused(false);
        treeSearch.close();
    }

    private void showDiagnostics(boolean conditions) {
        showTreeInspector();
        if (conditions) detailsPanel.showConditions(); else detailsPanel.showIssues();
        dropdownNode = detailsPanel.selectedNode();
    }

    private void updateTreeViewport() {
        Rect r = workspace.tree();
        treeViewLeft = r.x(); treeViewRight = r.right(); treeViewTop = r.y();
        treeContentTop = r.y() + 22; treeViewBottom = r.bottom();
    }

    private void zoomTree(double factor) {
        double x = (treeViewLeft + treeViewRight) / 2.0, y = (treeContentTop + treeViewBottom) / 2.0;
        double lx = (x - treePanX) / treeZoom, ly = (y - treePanY) / treeZoom;
        treeZoom = Math.max(0.15, Math.min(2.5, treeZoom * factor));
        treePanX = x - lx * treeZoom; treePanY = y - ly * treeZoom;
    }

    private void centerMiniMap(double x, double y) {
        treePanX = (treeViewLeft + treeViewRight) / 2.0 - x * treeZoom;
        treePanY = (treeContentTop + treeViewBottom) / 2.0 - y * treeZoom;
    }

    private void renderWorkspaceFooter(GuiGraphics g) {
        Rect r = workspace.footer();
        int y = r.y() + (workspace.compact() ? 5 : 9), x = r.x();
        if (externalRequest == null) {
            if (width >= 720) { chrome.label(g, workspaceText("quantity").getString(), x, y + 6, 48, Color.MUTED); x += 50; }
            chrome.button(g, new Rect(x, y, 18, 18), "−", false, currentRepeat > 1, mouseX, mouseY,
                    () -> setRepeatCount(currentRepeat - 1, true)); x += 22;
            countPillX = x; countPillY = y + 4; countPillW = 40; countPillH = 14;
            PlanWorkspaceChrome.panel(g, new Rect(x, y, 40, 18), Color.BACKGROUND, Color.BORDER);
            renderRepeatCountInput(g, font); x += 44;
            chrome.button(g, new Rect(x, y, 18, 18), "+", false, true, mouseX, mouseY,
                    () -> setRepeatCount(clampRepeatCount(currentRepeat + 1L), true)); x += 22;
            if (width >= 380) {
                chrome.button(g, new Rect(x, y, 28, 18), "+10", false, true, mouseX, mouseY,
                        () -> setRepeatCount(clampRepeatCount(currentRepeat + 10L), true)); x += 32;
                chrome.button(g, new Rect(x, y, 28, 18), "+64", false, true, mouseX, mouseY,
                        () -> setRepeatCount(clampRepeatCount(currentRepeat + 64L), true)); x += 32;
            }
            chrome.button(g, new Rect(x, y, 16, 18), "▾", quantityMenuOpen, true, mouseX, mouseY,
                    () -> quantityMenuOpen = !quantityMenuOpen); x += 22;
        } else { countPillW = countPillH = 0; }
        boolean quest = QuestSubmissionTargetIds.isQuestSubmission(ResourceLocation.tryParse(plan.recipeId()));
        if (!quest && externalRequest == null) {
            if (width >= 900) {
                chrome.label(g, workspaceText("mode_label").getString(), x, y + 5, 45, Color.MUTED);
                x += 50;
            }
            int available = (width < 720 ? r.right() : workspace.confirm().x() - 6) - x;
            preparationModeX = x; preparationModeY = y; preparationModeW = Math.min(72, Math.max(48, available / 2 - 3)); preparationModeH = 18;
            PlanWorkspaceChrome.panel(g, new Rect(x, y, preparationModeW, 18), partialPreparation ? Color.SELECTED : Color.SURFACE, Color.BORDER);
            chrome.label(g, I18n.get(partialPreparation ? "rsi.plan.mode.prepare" : "rsi.plan.mode.strict"), x + 5, y + 5, preparationModeW - 10, Color.TEXT);
            x += preparationModeW + 6;
            if (width >= 900) {
                chrome.label(g, workspaceText("destination_label").getString(), x + 4, y + 5, 42, Color.MUTED);
                x += 50;
            }
            outputSelectorX = x; outputSelectorY = y; outputSegmentW = Math.max(35, Math.min(110,
                    (width < 720 ? r.right() : workspace.confirm().x() - 6) - x)); outputSelectorH = 18;
            PlanWorkspaceChrome.panel(g, new Rect(x, y, outputSegmentW, 18), Color.SURFACE, Color.BORDER);
            Component destination = outputDestination == OutputDestination.PLAYER_INVENTORY
                    ? Component.translatable("rsi.plan.output.player") : Component.translatable("rsi.plan.output.storage");
            if (outputDestination == OutputDestination.RS_NETWORK && storageReference != null) {
                for (StorageNetworkDescriptor network : storageNetworks) if (storageReference.equals(network.reference())) destination = storageTargetLabel(network);
            }
            chrome.label(g, destination.getString() + " ↔", x + 5, y + 5, outputSegmentW - 10, Color.TEXT);
            if (new Rect(x, y, outputSegmentW, 18).contains(mouseX, mouseY)) {
                hoveredStepWarnings = List.of(Component.translatable("rsi.plan.output.tooltip"));
                hoveredTooltipX = mouseX; hoveredTooltipY = mouseY;
            }
        } else { outputSelectorH = preparationModeH = 0; }
        if (width >= 900 && workspace.confirm().x() - (outputSelectorX + outputSegmentW) > 120) {
            chrome.label(g, workspaceText("live_inventory").getString(), workspace.confirm().x() - 120, y + 5, 110, Color.MUTED);
        }
        if (width < 720) chrome.label(g, workspaceText("live_inventory").getString(), r.x(), r.bottom() - 14,
                Math.max(1, workspace.confirm().x() - r.x() - 6), Color.MUTED);
    }

    private void renderQuantityMenu(GuiGraphics g) {
        Rect r = new Rect(workspace.footer().x(), workspace.footer().y() - 51, 164, 48);
        g.pose().pushPose();
        g.pose().translate(0, 0, 600);
        PlanWorkspaceChrome.panel(g, r, Color.PANEL, Color.BORDER);
        int[] offsets = {0, -10, -5, -1, 1, 5, 10, 64};
        String[] labels = {"1", "−10", "−5", "−1", "+1", "+5", "+10", "+64"};
        for (int i = 0; i < labels.length; i++) {
            int offset = offsets[i];
            chrome.button(g, new Rect(r.x() + 4 + i % 4 * 39, r.y() + 4 + i / 4 * 21, 36, 18), labels[i], false, true,
                    mouseX, mouseY, () -> { setRepeatCount(offset == 0 ? 1 : clampRepeatCount(currentRepeat + (long) offset), true); quantityMenuOpen = false; });
        }
        g.pose().popPose();
    }

    private void renderEmbersSheet(GuiGraphics g) {
        Rect r = workspace.content();
        PlanWorkspaceChrome.panel(g, r, Color.BACKGROUND, Color.BORDER);
        if (plan.embersCode() != null && plan.embersCode().length > 0) {
            int cardsPerRow = Math.max(1, Math.min(plan.embersCode().length, (r.width() - 28) / 68));
            embersCardsH = font.lineHeight + 18 + 48 * ((plan.embersCode().length + cardsPerRow - 1) / cardsPerRow);
            embersPedestalH = embersCardsH + (showEmbersModeToggle ? 28 : 0);
        }
        int maxScroll = Math.max(0, embersPedestalH + 12 - r.height());
        embersScroll = Math.max(0, Math.min(embersScroll, maxScroll));
        embersPedestalY = r.y() + 6 - embersScroll;
        embersModeY = embersPedestalY + embersCardsH;
        g.enableScissor(r.x() + 1, r.y() + 1, r.right() - 1, r.bottom() - 1);
        try { renderEmbersPedestalArea(g, font, r.x() + 6, r.width() - 12); }
        finally { g.disableScissor(); }
    }

    private static Component workspaceText(String key, Object... args) {
        return Component.translatable("rsi.plan.workspace." + key, args);
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
        repeatCountBox.setTextColor(repeatCountBox.isFocused()
                ? Color.TEXT.value() : Color.MUTED.value());
        repeatCountBox.render(gfx, mouseX, mouseY, 0f);
        int underlineY = countPillY + countPillH - 2;
        gfx.fill(countPillX + 7, underlineY, countPillX + countPillW - 7,
                underlineY + 1, Color.ACCENT.value());
    }

    private static void drawHoverBorder(GuiGraphics gfx, int x, int y, int size) {
        gfx.fill(x - 1, y - 1, x + size + 1, y, Color.HOVER_OVERLAY.value());
        gfx.fill(x - 1, y + size + 1, x + size + 1, y + size + 2, Color.HOVER_OVERLAY.value());
        gfx.fill(x - 1, y - 1, x, y + size + 1, Color.HOVER_OVERLAY.value());
        gfx.fill(x + size + 1, y - 1, x + size + 2, y + size + 1, Color.HOVER_OVERLAY.value());
    }

    // ── 余烬基座 ─────────────────────────────────────────────────

    private void renderEmbersPedestalArea(GuiGraphics gfx, Font font, int left, int contentW) {
        int top = embersPedestalY;

        // Background with emerald left accent
        PlanUiDraw.roundedGradient(gfx, left, top, contentW, embersPedestalH, 6f,
                Color.SURFACE.value(), Color.SURFACE.value());
        gfx.fill(left + 1, top + 2, left + 4, top + embersPedestalH - 2, Color.ACCENT.value());

        int[] code = plan.embersCode();
        Component[] aspects = plan.embersAspectNames();
        Component[] inputs = plan.embersInputNames();
        boolean hasCards = code != null && aspects != null && inputs != null;

        int y = top + 4;
        if (hasCards) {
            String hdr = Component.translatable("rsi.embers.pedestal_layout").getString();
            PlanUiDraw.textBackdrop(gfx, font, left + 10, y, hdr, Color.TEXT_BACKDROP.value());
            gfx.drawString(font, hdr, left + 10, y, Color.ACCENT.value());
            // Show "from prior inference" badge when code was loaded from cache
            if (plan.embersCodeFromCache()) {
                String cachedNote = Component.translatable("rsi.embers.code_from_cache").getString();
                int noteW = font.width(cachedNote);
                gfx.drawString(font, cachedNote, left + contentW - noteW - 12, y, Color.ACCENT.value());
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
                PlanUiDraw.rounded(gfx, px, py, cardW, cardH, 4f, Color.SURFACE.value());

                // Input name (top)
                String inputName = i < inputs.length ? inputs[i].getString() : "?";
                int maxNameW = cardW - 8;
                if (font.width(inputName) > maxNameW) {
                    inputName = font.plainSubstrByWidth(inputName, maxNameW - font.width("...")) + "...";
                }
                int nameX = px + cardW / 2 - font.width(inputName) / 2;
                gfx.drawString(font, inputName, nameX, py + 2, Color.TEXT.value());

                // Aspect name (bottom)
                String aspectName = i < aspects.length ? aspects[i].getString() : "?";
                if (font.width(aspectName) > maxNameW) {
                    aspectName = font.plainSubstrByWidth(aspectName, maxNameW - font.width("...")) + "...";
                }
                int aspX = px + cardW / 2 - font.width(aspectName) / 2;
                gfx.drawString(font, aspectName, aspX, py + cardH - font.lineHeight - 2, Color.ACCENT.value());

                // Separator line
                int sepY = py + cardH / 2;
                gfx.fill(px + 6, sepY - 1, px + cardW - 6, sepY, Color.HOVER_OVERLAY.value());

                // Pedestal number
                String num = String.valueOf(i + 1);
                gfx.drawString(font, num, px + cardW - font.width(num) - 3, py + cardH - font.lineHeight - 1, Color.READY.value());
            }
        }

        // Seed indicator at bottom-right (only with cards, so it doesn't overlap toggle)
        if (hasCards && plan.embersSeed() != 0) {
            String seedStr = Component.translatable("rsi.embers.seed_label", plan.embersSeed()).getString();
            int seedW = font.width(seedStr);
            int seedX = left + contentW - seedW - 12;
            int seedY = top + embersCardsH - font.lineHeight - 4;
            gfx.drawString(font, seedStr, seedX, seedY, Color.DISABLED.value());
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
            gfx.drawString(font, modeLabel, rowStartX, btnTop + (btnH - font.lineHeight) / 2, Color.ACCENT.value());

            // Calculate button — same color scheme as repeat row buttons
            int calcX = rowStartX + labelW + 6;
            embersModeCalcX = calcX; embersModeCalcY = btnTop;
            embersModeCalcW = btnW; embersModeCalcH = btnH;
            boolean hoverCalc = mouseX >= calcX && mouseX <= calcX + btnW
                    && mouseY >= btnTop && mouseY <= btnTop + btnH;
            boolean isCalc = !embersInferMode;
            int calcBg = isCalc ? Color.HOVER.value() : (hoverCalc ? Color.SURFACE.value() : Color.SURFACE.value());
            int calcFg = isCalc ? Color.TEXT.value() : (hoverCalc ? Color.READY.value() : Color.READY.value());
            // Double-layer inset (matching repeat row buttons)
            PlanUiDraw.rounded(gfx, calcX, btnTop, btnW, btnH, 4f, calcBg);
            PlanUiDraw.rounded(gfx, calcX + 1, btnTop + 1, btnW - 2, btnH - 2, 3f, Color.SURFACE.value());
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
            int inferBg = isInfer ? Color.HOVER.value() : (hoverInfer ? Color.SURFACE.value() : Color.SURFACE.value());
            int inferFg = isInfer ? Color.TEXT.value() : (hoverInfer ? Color.READY.value() : Color.READY.value());
            PlanUiDraw.rounded(gfx, inferX, btnTop, btnW, btnH, 4f, inferBg);
            PlanUiDraw.rounded(gfx, inferX + 1, btnTop + 1, btnW - 2, btnH - 2, 3f, Color.SURFACE.value());
            String inferLabel = Component.translatable("rsi.embers.mode_infer").getString();
            gfx.drawString(font, inferLabel,
                    inferX + btnW / 2 - font.width(inferLabel) / 2,
                    btnTop + (btnH - font.lineHeight) / 2, inferFg);
        }
    }

    // ── Missing area ──────────────────────────────────────────────

    private static List<Component> displayWarnings(PlanResponse plan) {
        LinkedHashSet<Component> warnings = new LinkedHashSet<>();
        if (plan.modWarnings() != null) warnings.addAll(plan.modWarnings());
        for (IntegrationDiagnostic diagnostic : plan.diagnostics()) {
            if (diagnostic.severity() == IntegrationDiagnostic.Severity.INFO) continue;
            warnings.add(diagnostic.clientMessage());
        }
        return List.copyOf(warnings);
    }

    // ── 递归树与相机 ─────────────────────────────────────────────

    private void renderTreeArea(GuiGraphics gfx, int left, int areaTop, int contentW,
                                int areaBottom, int mouseX, int mouseY) {
        if (areaBottom <= areaTop) return;
        if (detailsPanel != null && detailsPanel.contains(mouseX, mouseY)) {
            mouseX = mouseY = Integer.MIN_VALUE;
        }
        treeLayout.ensureLayout(treeModel.root);

        // Backdrop over the whole area.
        PlanWorkspaceChrome.panel(gfx, new Rect(left, areaTop, contentW, areaBottom - areaTop), Color.BACKGROUND, Color.BORDER);
        int gridColor = Color.GRID.value();
        gfx.drawManaged(() -> {
            for (int gx = left + 11; gx < left + contentW - 5; gx += 13) {
                for (int gy = areaTop + 12; gy < areaBottom - 5; gy += 13) gfx.fill(gx, gy, gx + 1, gy + 1, gridColor);
            }
        });

        // 材料摘要固定在画布顶部；树相机只在摘要下方缩放和平移。
        if (treeCameraInit && treeViewRight > treeViewLeft) {
            treePanX += (left + contentW - treeViewRight) / 2.0;
        }
        treeViewLeft = left;
        treeViewTop = areaTop;
        treeViewRight = left + contentW;
        treeViewBottom = areaBottom;
        stripPageSize = Math.max(1, Math.min(8, (contentW - 150) / Math.max(1, stripMaxEntryWidth)));
        treeContentTop = Math.min(areaBottom - 1, areaTop + 22);
        chrome.label(gfx, I18n.get(treeModel.truncated() ? "rsi.plan.tree_truncated" : selectedPath.isDirty()
                ? "rsi.plan.status_custom" : "rsi.plan.workspace.dependencies"), left + 7, areaTop + 4, contentW - 14, Color.MUTED);
        if (!treeCameraInit) centerCamera();
        treeRenderer.setInspectedNode(detailsPanel != null && detailsPanel.isOpen() ? detailsPanel.selectedNode() : dropdownNode);

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
                hoveredTooltipReference = hovered.referenceOnly;
            }
        }

        gfx.enableScissor(left, treeContentTop, left + contentW, areaBottom);
        try {
            int logicalLeft = (int) Math.floor((treeViewLeft - treePanX) / treeZoom);
            int logicalTop = (int) Math.floor((treeContentTop - treePanY) / treeZoom);
            int logicalRight = (int) Math.ceil((treeViewRight - treePanX) / treeZoom);
            int logicalBottom = (int) Math.ceil((treeViewBottom - treePanY) / treeZoom);
            gfx.pose().pushPose();
            gfx.pose().translate(treePanX, treePanY, 0);
            gfx.pose().scale((float) treeZoom, (float) treeZoom, 1f);
            treeRenderer.render(gfx, treeLayout, selectedPath, hovered,
                    logicalLeft, logicalTop, logicalRight, logicalBottom);
            gfx.pose().popPose();
        } finally {
            gfx.disableScissor();
        }

        miniMap.render(gfx, font, treeLayout,
                new Rect(treeViewLeft, treeContentTop, treeViewRight - treeViewLeft, treeViewBottom - treeContentTop),
                treePanX, treePanY, treeZoom, detailsPanel.isOpen() ? detailsPanel.selectedNode() : dropdownNode);
    }

    /** 固定材料/余产物摘要，与侧栏共用服务端账单，不跟随相机移动。 */

    /** 计划响应是材料条数据的唯一来源，派生显示项只在响应切换时生成。 */
    private void rebuildMaterialStripCache() {
        cachedTotalStrip = buildTotalEntries();
        inventoryStripIndices.clear();
        for (int i = 0; i < cachedTotalStrip.size(); i++) inventoryStripIndices.put(cachedTotalStrip.get(i).key(), i);
        cachedLeftoverStrip = buildLeftoverEntries();
        stripMaxEntryWidth = 68;
        Font font = Minecraft.getInstance().font;
        for (StripEntry entry : cachedTotalStrip) stripMaxEntryWidth = Math.max(stripMaxEntryWidth,
                24 + font.width(stripCountText(entry, false)));
        for (StripEntry entry : cachedLeftoverStrip) stripMaxEntryWidth = Math.max(stripMaxEntryWidth,
                24 + font.width(stripCountText(entry, true)));
        totalStripPage = MaterialStripPaging.clampPage(
                totalStripPage, cachedTotalStrip.size(), stripPageSize);
        leftoverStripPage = MaterialStripPaging.clampPage(
                leftoverStripPage, cachedLeftoverStrip.size(), stripPageSize);
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

    /** 摘要行和命中框均使用屏幕坐标，翻页不会受到树缩放影响。 */

    /** 总需求先显示需求量再显示库存，余产物仅显示剩余量。 */
    private static String stripCountText(StripEntry e, boolean leftover) {
        return leftover
                ? PlanRenderEngine.formatCompact(e.count())
                : PlanRenderEngine.formatCompact(e.count()) + "/"
                + PlanRenderEngine.formatCompact(e.available());
    }

    /** Pan the camera so the node with this key sits at the viewport center. */
    private void centerOnKey(IngredientKey key) {
        PlanTreeNode node = treeModel.findByKey(key);
        if (node != null) navigateToNode(node);
    }

    /** 定位时展开祖先；不改变玩家选择的配方和材料。 */
    private void navigateToNode(PlanTreeNode node) {
        navigateToNode(node, false);
    }

    private void navigateToNode(PlanTreeNode node, boolean magnify) {
        if (node == null) return;
        showTreeInspector();
        detailsPanel.locate(node);
        dropdownNode = node;
        layoutWorkspace();
        updateTreeViewport();
        if (revealNode(treeModel.root, node)) treeLayout.markDirty();
        treeLayout.ensureLayout(treeModel.root);
        PlanTreeLayout.Box b = treeLayout.boxFor(node);
        if (b == null) return;
        int visualHeight = b.h() + (PlanTreeLayout.showsLabel(node) ? 0 : font.lineHeight + 3
                + (node.step != null && node.step.modType() != null ? font.lineHeight + 6 : 0));
        if (magnify) {
            // 搜索结果至少放大到易读比例，小画布先保证完整卡片能够放下；已有更大缩放予以保留。
            double fit = Math.min(Math.max(1, treeViewRight - treeViewLeft - 16) / (double) b.w(),
                    Math.max(1, treeViewBottom - treeContentTop - 12) / (double) visualHeight);
            treeZoom = Math.max(treeZoom, Math.min(1.5, fit));
        }
        double viewCx = (treeViewLeft + treeViewRight) / 2.0;
        double viewCy = (treeContentTop + treeViewBottom) / 2.0;
        treePanX = viewCx - (magnify ? b.centerX() : b.connectorX()) * treeZoom;
        treePanY = viewCy - (b.y() + (magnify ? visualHeight : b.h()) / 2.0) * treeZoom;
        treeCameraInit = true;
    }

    private boolean revealNode(PlanTreeNode parent, PlanTreeNode target) {
        List<PlanTreeNode> path = new ArrayList<>();
        if (!findNodePath(parent, target, path)) return false;
        boolean changed = false;
        for (PlanTreeNode ancestor : path) {
            if (ancestor != target && !ancestor.expanded) { ancestor.expanded = true; changed = true; }
        }
        return changed;
    }

    private boolean findNodePath(PlanTreeNode current, PlanTreeNode target, List<PlanTreeNode> path) {
        path.add(current);
        if (current == target) return true;
        for (PlanTreeNode child : current.children) if (findNodePath(child, target, path)) return true;
        path.remove(path.size() - 1);
        return false;
    }

    private void fitTree() {
        treeLayout.ensureLayout(treeModel.root);
        double availableWidth = Math.max(1, treeViewRight - treeViewLeft - 28);
        double availableHeight = Math.max(1, treeViewBottom - treeContentTop - 28);
        treeZoom = Math.max(0.15, Math.min(1.0, Math.min(
                availableWidth / Math.max(1, treeLayout.totalWidth()),
                availableHeight / Math.max(1, treeLayout.totalHeight() + 24))));
        treePanX = (treeViewLeft + treeViewRight - treeLayout.totalWidth() * treeZoom) / 2.0;
        treePanY = treeContentTop + 12;
        treeCameraInit = true;
    }

    /**
     * Switch a node to an alternative recipe. Reuses the card-mode server round-trip: update the
     * current forced overrides and send a preview
     * {@link GenericCraftPacket}; the server re-resolves and the returned {@link PlanResponse}
     * flows through {@link #updatePlan}, which rebuilds the tree. Target-node switches replace the
     * root recipe; intermediate switches go into the forced map keyed by item id.
     */
    private void selectTreeBranch(PlanTreeNode node, @Nullable ResourceLocation recipeId) {
        if (node == null || !RecipeCandidateRules.canSelect(node, recipeId)) return;

        ResourceLocation preferenceKey = CraftingResolver
                .preferenceKey(node.key.stack(1));
        boolean isTarget = node.depth == 0;
        pendingCandidateSwitch = new PendingCandidateSwitch(
                node.key, selectedPath.snapshotBranch(node.key), recipeCandidatePanel.draftPreferences());
        if (recipeId == null || isTarget) {
            // 根配方由请求 recipeId 表达，不能再作为中间材料约束带到确认包。
            selectedPath.clearBranch(node.key);
        } else {
            int idx = node.step == null ? 0
                    : Math.max(0, node.step.alternatives().indexOf(recipeId));
            selectedPath.selectBranch(node.key, idx, recipeId);
        }

        Map<String, String> forced = exportForcedSelections();
        ResourceLocation rootRid = ResourceLocation.tryParse(plan.recipeId());
        if (isTarget && recipeId != null) {
            rootRid = recipeId;
            if (preferenceKey != null) forced.remove(preferenceKey.toString());
        } else if (recipeId == null && preferenceKey != null) {
            forced.remove(preferenceKey.toString());
        }

        sendCraftPacket(rootRid, true, forced, currentRepeat, false);
    }

    private void cancelStockOnly(PlanTreeNode node, ResourceLocation fallbackRecipe) {
        selectTreeBranch(node, fallbackRecipe);
    }

    private void recipeExclusionChanged(PlanTreeNode node) {
        if (node == null) return;
        ResourceLocation preferenceKey = CraftingResolver
                .preferenceKey(node.key.stack(1));
        ResourceLocation currentRecipe = node.step == null ? null : node.step.recipeId();
        pendingCandidateSwitch = new PendingCandidateSwitch(
                node.key, selectedPath.snapshotBranch(node.key),
                recipeCandidatePanel.draftPreferences());
        Set<String> excluded = pendingCandidateSwitch.preferences().excludedRecipes();
        if (currentRecipe != null && excluded.contains(currentRecipe.toString())) {
            selectedPath.clearBranch(node.key);
        }
        Map<String, String> forced = exportForcedSelections();
        if (preferenceKey != null && currentRecipe != null && excluded.contains(currentRecipe.toString())) {
            forced.remove(preferenceKey.toString());
        }
        sendCraftPacket(ResourceLocation.tryParse(plan.recipeId()), true, forced,
                currentRepeat, false);
    }

    /** 配方切换响应重建树后，重新打开原节点的候选卡片，便于继续切换。 */
    private void reopenPendingCandidates(List<Component> failure) {
        previewFeedback = failure.stream().filter(message -> !message.getString().isBlank()).findFirst()
                .orElse(workspaceText("recalculate_failed"));
        previewFeedbackColor = Color.ERROR;
        pendingCandidateSwitch = null;
        pendingMaterialChoice = null;
        if (detailsPanel != null) {
            detailsPanel.update(plan, treeModel.root, displayWarnings(plan));
            detailsPanel.showPreviewFailure(failure);
            dropdownNode = detailsPanel.isOpen() ? detailsPanel.selectedNode() : null;
        }
    }

    private void commitPendingCandidateSwitch() {
        if (pendingMaterialChoice != null) pendingMaterialChoice.commit(PLAN_PREFS_PATH);
        PendingCandidateSwitch pending = pendingCandidateSwitch;
        if (pending == null) return;
        CraftingPlanPreferences.Snapshot stored = CraftingPlanPreferences.load(PLAN_PREFS_PATH);
        CraftingPlanPreferences.Snapshot draft = pending.preferences();
        if (CraftingPlanPreferences.save(PLAN_PREFS_PATH, new CraftingPlanPreferences.Snapshot(
                stored.outputDestination(), draft.recipeChoices(), stored.ingredientChoices(), draft.excludedRecipes()))
                && !stored.excludedRecipes().equals(draft.excludedRecipes())) {
            ClientRecipeExclusions.invalidate();
        }
    }

    private void rollbackPendingCandidateSwitch() {
        if (pendingMaterialChoice != null) pendingMaterialChoice.rollback(materialLocks);
        PendingCandidateSwitch pending = pendingCandidateSwitch;
        if (pending == null) return;
        selectedPath.restoreBranch(pending.key(), pending.previous());
    }

    private void forgetRecipePreference(PlanTreeNode node) {
        if (node == null) return;
        ResourceLocation preferenceKey = CraftingResolver.preferenceKey(node.key.stack(1));
        pendingCandidateSwitch = new PendingCandidateSwitch(
                node.key, selectedPath.snapshotBranch(node.key),
                recipeCandidatePanel.draftPreferences());
        selectedPath.clearBranch(node.key);
        Map<String, String> forced = exportForcedSelections();
        if (preferenceKey != null) forced.remove(preferenceKey.toString());
        sendCraftPacket(ResourceLocation.tryParse(plan.recipeId()), true, forced,
                currentRepeat, false);
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
        materialView = false;
        embersDetailsOpen = false;
        if (workspaceSearch != null) workspaceSearch.setFocused(false);
        if (node == null) return;
        unfocusRepeatCountInput();
        setFocused(null);
        dropdownNode = node;
        detailsPanel.open(node);
        resetHoverIntent();
    }

    @Nullable
    private PlanTreeNode candidateNodeFor(PlanTreeNode node) {
        if (node == null) return null;
        if (node.step != null) {
            candidateStepHistory.put(node.key, node.step);
            return node;
        }
        PlanStep retained = candidateStepHistory.get(node.key);
        if (retained == null) {
            return canConfigureRecipeBranch(node) ? node : null;
        }
        PlanTreeNode candidateNode = new PlanTreeNode(
                node.key, node.displayStack, node.amount, node.depth,
                retained, node.graphNodeId, node.demandRole);
        candidateNode.productionAmount = node.productionAmount;
        candidateNode.available = node.available;
        candidateNode.needed = node.needed;
        candidateNode.missingCount = node.missingCount;
        candidateNode.cycle = node.cycle;
        candidateNode.limited = node.limited;
        candidateNode.lockedMaterial = node.lockedMaterial;
        candidateNode.materialLockKey = node.materialLockKey;
        candidateNode.materialOptions = node.materialOptions;
        candidateNode.recipeOptions = node.recipeOptions;
        candidateNode.ingredient = node.ingredient;
        return candidateNode;
    }

    private boolean canConfigureRecipeBranch(PlanTreeNode node) {
        if (node == null || node.containsCycle()) return false;
        if (node.step != null) return true;
        return node.depth > 0
                && CraftingResolver.preferenceKey(node.key.stack(1)) != null;
    }

    private void renderRecipeCandidates(GuiGraphics graphics, int mouseX, int mouseY) {
        if (detailsPanel == null || !detailsPanel.isOpen()) return;
        detailsPanel.render(graphics, mouseX, mouseY);
        if (detailsPanel.contains(mouseX, mouseY)) {
            hoveredItemForTooltip = ItemStack.EMPTY;
            hoveredStepWarnings = List.of();
            hoveredBookmark = null;
        }
    }

    private void selectMaterial(PlanTreeNode node, ItemStack option) {
        if (node.materialLockKey == null || responseRequests.calculating()) return;
        ResourceLocation rootRecipe = ResourceLocation.tryParse(plan.recipeId());
        if (rootRecipe == null) return;
        pendingMaterialChoice = new PendingMaterialChoice(materialLocks, node.materialLockKey, option);
        detailsPanel.expectMaterial(option);
        sendCraftPacket(rootRecipe, true, exportForcedSelections(), currentRepeat, false);
    }

    private void loadStoredMaterialChoices() {
        preferredMaterials.clear();
        preferredMaterials.putAll(
                CraftingPlanPreferences.load(PLAN_PREFS_PATH).ingredientChoices());
    }

    private Map<String, ItemStack> materialDisplayLocks() {
        if (preferredMaterials.isEmpty()) return materialLocks;
        Map<String, ItemStack> merged = new LinkedHashMap<>(preferredMaterials);
        merged.putAll(materialLocks);
        return merged;
    }

    /** 侧栏开关或视图变化时，按主内容实际宽度重新排材料和缺料提示。 */

    /** Center the root near the top-center of the viewport (leaving room for the strip) and reset zoom. */
    private void centerCamera() {
        treeLayout.ensureLayout(treeModel.root);
        PlanTreeLayout.Box rootBox = treeLayout.boxFor(treeModel.root);
        int rootCenter = rootBox != null ? rootBox.connectorX() : treeLayout.totalWidth() / 2;
        double viewW = treeViewRight - treeViewLeft;
        double fitWidth = (viewW - 24) / Math.max(1, treeLayout.totalWidth());
        double fitHeight = (treeViewBottom - treeContentTop - 24.0) / Math.max(1, treeLayout.totalHeight() + 14);
        treeZoom = Math.max(0.6, Math.min(1.0, Math.min(fitWidth, fitHeight)));
        treePanX = treeViewLeft + viewW / 2 - rootCenter * treeZoom;
        treePanY = treeContentTop + 12;
        treeCameraInit = true;
    }

    private boolean inTreeViewport(double sx, double sy) {
        return !materialView && !embersDetailsOpen && sx >= treeViewLeft && sx < treeViewRight
                && sy >= treeViewTop && sy < treeViewBottom;
    }

    /** Node under a screen point via the single screenToTree inverse transform, or null. */
    private PlanTreeNode nodeAt(double sx, double sy) {
        if (!inTreeViewport(sx, sy) || sy < treeContentTop) return null;
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
        if (!inTreeViewport(sx, sy) || sy < treeContentTop) return null;
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

    @Nullable
    private PlanTreeNode gestureNodeAt(double mouseX, double mouseY) {
        if (detailsPanel != null && detailsPanel.contains(mouseX, mouseY)
) return null;
        return nodeAt(mouseX, mouseY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (!materialView && !embersDetailsOpen && workspaceSearch != null && workspaceSearch.isFocused()
                && treeSearch.scroll(mouseX, mouseY, delta)) return true;
        if (workspace != null && workspace.content().contains(mouseX, mouseY)) {
            if (materialView) { materialTable.scroll(delta); return true; }
            if (embersDetailsOpen) { embersScroll -= (int) (delta * 24); return true; }
        }
        if (!materialView && !embersDetailsOpen && detailsPanel != null && detailsPanel.mouseScrolled(mouseX, mouseY, delta)) return true;

        if (inTreeViewport(mouseX, mouseY)) {
            if (hasControlDown()) {
                double factor = delta > 0 ? 1.1 : 1 / 1.1;
                double newZoom = Math.max(0.15, Math.min(2.5, treeZoom * factor));
                // Keep the point under the cursor fixed while zooming.
                double lx = (mouseX - treePanX) / treeZoom;
                double ly = (mouseY - treePanY) / treeZoom;
                treeZoom = newZoom;
                treePanX = mouseX - lx * treeZoom;
                treePanY = mouseY - ly * treeZoom;
                return true;
            }
            treePanY += delta * 20;
            return true;
        }

        return true;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (!materialView && !embersDetailsOpen && miniMap.drag(mx, my, this::centerMiniMap)) return true;
        if (treeMouseGesture.captures(button)) {
            PlanTreeMouseGesture.PanDelta movement = treeMouseGesture.drag(mx, my, button);
            treePanX += movement.deltaX();
            treePanY += movement.deltaY();
            return true;
        }
        // Scrollbar thumb drag takes precedence — map the cursor to a scroll offset.

        if (detailsPanel != null && (detailsPanel.contains(mx, my)
                || detailsPanel.contains(mx - dx, my - dy))) return true;

        return true;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        treeMouseGesture.cancel();
        if (treeSearch != null) {
            if (!materialView && !embersDetailsOpen && workspaceSearch.isFocused()
                    && treeSearch.click(mx, my, button)) return true;
            treeSearch.close();
        }
        if (button == 0 && workspaceSearchBounds != null && workspaceSearchBounds.contains(mx, my)) {
            workspaceSearch.mouseClicked(mx, my, button);
            workspaceSearch.setFocused(true);
            quantityMenuOpen = false;
            unfocusRepeatCountInput();
            detailsPanel.releaseFocus();
            if (!materialView && !embersDetailsOpen) treeSearch.show();
            return true;
        }
        if (workspaceSearch != null) workspaceSearch.setFocused(false);
        boolean inspectorHit = !materialView && !embersDetailsOpen && detailsPanel != null && detailsPanel.contains(mx, my);
        if (chrome != null && (!inspectorHit || quantityMenuOpen && workspace != null
                && new Rect(workspace.footer().x(), workspace.footer().y() - 51, 164, 48).contains(mx, my))
                && chrome.click(mx, my, button)) return true;
        if (inspectorHit) {
            workspaceSearch.setFocused(false);
            unfocusRepeatCountInput();
            detailsPanel.mouseClicked(mx, my, button);
            if (!detailsPanel.isOpen()) dropdownNode = null;
            return true;
        }
        quantityMenuOpen = false;
        if (workspaceSearch != null) {
            boolean searching = workspaceSearch.mouseClicked(mx, my, button);
            workspaceSearch.setFocused(searching);
            if (searching) { unfocusRepeatCountInput(); detailsPanel.releaseFocus(); return true; }
        }
        if (materialView && materialTable.click(mx, my, button)) return true;
        if (!materialView && !embersDetailsOpen && miniMap.click(mx, my, button, this::centerMiniMap)) return true;
        if (!materialView && !embersDetailsOpen && detailsPanel != null && detailsPanel.mouseClicked(mx, my, button)) {
            if (!detailsPanel.isOpen()) dropdownNode = null;
            return true;
        }

        if (button == 0) {
            boolean overRepeatInput = mx >= countPillX && mx < countPillX + countPillW
                    && my >= countPillY && my < countPillY + countPillH;
            if (!overRepeatInput) unfocusRepeatCountInput();
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
        for (BookmarkHit hit : bookmarkHits) {
            if (mx >= hit.x() && mx < hit.x() + hit.w()
                    && my >= hit.y() && my < hit.y() + hit.h()) {
                if (button == 0 || button == 2) {
                    bookmarkMissingMaterial(hit);
                }
                return true;
            }
        }
        if ((button == 0 || button == 1) && outputSelectorH > 0
                && mx >= preparationModeX && mx < preparationModeX + preparationModeW
                && my >= preparationModeY && my < preparationModeY + preparationModeH) {
            if (button == 0) {
                partialPreparation = !partialPreparation;
                if (partialPreparation) selectOutputDestination(OutputDestination.RS_NETWORK);
                cancelQueuedConfirmation();
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
        }
        if (treeModel != null) {
                for (CostHit hit : costHits) {
                if (mx >= hit.x() && mx < hit.x() + hit.w()
                        && my >= hit.y() && my < hit.y() + hit.h()) {
                    if (button == 0) centerOnKey(hit.key());
                    return true;
                }
            }
            PlanTreeNode fold = foldIndicatorAt(mx, my);
            if (fold != null) {
                if (button == 0) toggleCollapseSameKey(fold);
                return true;
            }
            if (inTreeViewport(mx, my)) {
                if (treeMouseGesture.begin(mx, my, button, nodeAt(mx, my))) return true;
                if (button == 0) {
                    PlanTreeNode node = nodeAt(mx, my);
                    if (node != null) {
                        openRecipeCandidates(node);
                    }
                }
                // 画布点击在这里结束，避免交给底部控件。
                return true;
            }
        }

        if (button == 0) {
            // Embers mode toggle: Calculate
            if (embersDetailsOpen && showEmbersModeToggle
                    && mx >= embersModeCalcX && mx <= embersModeCalcX + embersModeCalcW
                    && my >= embersModeCalcY && my <= embersModeCalcY + embersModeCalcH) {
                embersInferMode = false;
                cancelQueuedConfirmation();
                return true;
            }
            // Embers mode toggle: Infer
            if (embersDetailsOpen && showEmbersModeToggle
                    && mx >= embersModeInferX && mx <= embersModeInferX + embersModeInferW
                    && my >= embersModeInferY && my <= embersModeInferY + embersModeInferH) {
                embersInferMode = true;
                cancelQueuedConfirmation();
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        miniMap.release();
        if (treeMouseGesture.captures(button)) {
            PlanTreeNode selected = treeMouseGesture.release(mx, my, button, gestureNodeAt(mx, my));
            if (selected != null && selected.step != null) {
                openRecipeInJei(selected.step.recipeId());
            }
            return true;
        }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (workspaceSearch != null && workspaceSearch.isFocused()) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                treeSearch.close(); workspaceSearch.setFocused(false); return true;
            }
            if (!materialView && !embersDetailsOpen && treeSearch.keyPressed(keyCode)) return true;
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                return true;
            }
            workspaceSearch.keyPressed(keyCode, scanCode, modifiers);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && quantityMenuOpen) { quantityMenuOpen = false; return true; }

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

        // Ctrl+0 — reset camera. Must precede the digit handler, which also matches KEY_0.
        if (ctrl && keyCode == GLFW.GLFW_KEY_0) {
            if (treeModel != null) {
                treeZoom = 1.0;
                treePanX = 0;
                treePanY = 0;
                treeCameraInit = false;
            }
            return true;
        }
        if (!materialView && !embersDetailsOpen && detailsPanel != null && detailsPanel.keyPressed(keyCode, scanCode, modifiers)) {
            if (!detailsPanel.isOpen()) dropdownNode = null;
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
        if (workspaceSearch != null && workspaceSearch.isFocused()) return workspaceSearch.charTyped(character, modifiers);
        if (!materialView && !embersDetailsOpen && detailsPanel != null && detailsPanel.charTyped(character, modifiers)) return true;
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

    /** Single-row collapsed card: mod dot + item icon + step number + batch count. */
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
            statusColor = Color.READY.value();
        else if (hoveredTooltipAvail > 0)
            statusColor = Color.WARNING.value();
        else
            statusColor = Color.ERROR.value();

        lines.add(Component.literal(
                Component.translatable("rsi.plan.tooltip.available").getString()
                + ": " + availStr).withStyle(style -> style.withColor(statusColor)));

        // 引用节点：这里不是另一批生产，只是同一批产出在另一个消耗分支上的引用。
        if (hoveredTooltipReference) {
            lines.add(Component.translatable("rsi.plan.tooltip.reference_only")
                    .withStyle(ChatFormatting.GRAY));
            hoveredTooltipReference = false;
        }

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

    @Override
    public boolean isPauseScreen() { return false; }
}
