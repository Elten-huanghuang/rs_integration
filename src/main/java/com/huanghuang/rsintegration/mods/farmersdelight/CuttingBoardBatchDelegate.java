package com.huanghuang.rsintegration.mods.farmersdelight;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoints;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientMatcher;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.ResonanceCraftingSource;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.crafting.plan.PlanStep;
import com.huanghuang.rsintegration.util.PlayerUtils;
import com.huanghuang.rsintegration.mods.farmersdelight.FarmersDelightRecipeHandler;
import com.huanghuang.rsintegration.reflection.probes.FarmersDelightReflection;
import com.huanghuang.rsintegration.feature.resonance.api.ResonanceStorageView;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.wrapper.RecipeWrapper;
import org.jetbrains.annotations.NotNull;
import com.huanghuang.rsintegration.crafting.batch.ParallelBatchSizing;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Recursive execution for Farmer's Delight cutting-board recipes.
 *
 * <p>The board is used only as the bound machine and must be empty. Recipe
 * execution happens against a temporary one-slot inventory, so a failed batch
 * cannot leave a real board half-filled. The knife/shears tool is supplied by
 * the selected storage backend, damaged exactly once per operation, and its
 * remainder is returned with the exact rolled outputs. The two FD 1.20.1
 * releases expose different {@code rollResults} signatures, so that call is
 * deliberately probed instead of linking to either version's recipe class.</p>
 */
public final class CuttingBoardBatchDelegate extends AbstractBatchDelegate {
    private static final int MAX_INSTANT_BATCH = 64;
    private static final String RECIPE_CLASS =
            "vectorwing.farmersdelight.common.crafting.CuttingBoardRecipe";
    private static final String BLOCK_ENTITY_CLASS =
            "vectorwing.farmersdelight.common.block.entity.CuttingBoardBlockEntity";

    private ServerPlayer player;
    private ServerLevel myLevel;
    private ResourceKey<Level> myDim;
    private BlockPos myPos;
    private Recipe<?> recipe;
    private int preparedExecutions = 1;
    private boolean craftDone;
    private final List<ItemStack> pendingResults = new ArrayList<>();
    private final List<ItemStack> reusableTools = new ArrayList<>();
    @Nullable
    private ResonanceStorageView resonanceToolView;
    private int resonanceToolSlot = -1;
    @Nullable
    private CraftStorageEndpoint toolReturnEndpoint;

    public record ToolPlanCheck(List<Component> warnings, boolean blocksExecution) {
        public ToolPlanCheck {
            warnings = List.copyOf(warnings);
        }
    }

    private record ToolSelection(ItemStack stack, boolean resonance) {}

    @Override
    public boolean validateAndInit(@NotNull ServerPlayer player, @NotNull ResourceLocation recipeId,
                                   @Nullable ResourceLocation dim, @NotNull BlockPos pos) {
        ServerLevel level = CraftPacketUtils.resolveLevel(player.server, dim, player);
        if (level == null) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.dim_not_found"));
            return false;
        }
        Recipe<?> found = level.getRecipeManager().byKey(recipeId).orElse(null);
        if (found == null || !RECIPE_CLASS.equals(found.getClass().getName())) {
            player.sendSystemMessage(Component.translatable(
                    "rsi.generic.error.recipe_not_found", recipeId.toString()));
            return false;
        }
        this.player = player;
        this.myLevel = level;
        this.myDim = level.dimension();
        this.myPos = pos.immutable();
        this.recipe = found;
        this.preparedExecutions = 1;
        this.craftDone = false;
        this.pendingResults.clear();
        this.reusableTools.clear();
        this.resonanceToolView = null;
        this.resonanceToolSlot = -1;
        this.toolReturnEndpoint = null;
        return true;
    }

    @Override
    public void prepareGraphBatch(int executions) {
        this.preparedExecutions = Math.max(1, executions);
    }

    @Override
    public int prepareFlatBatch(int remainingOperations) {
        int limit = supportsRepeatedRolls()
                ? Math.min(MAX_INSTANT_BATCH, selectedToolOperationCapacity()) : 1;
        this.preparedExecutions = Math.min(limit, Math.max(0, remainingOperations));
        return preparedExecutions;
    }

    @Override
    public int flatBatchOperationLimit(int configuredLimit) {
        return supportsRepeatedRolls()
                ? Math.max(Math.max(1, configuredLimit), MAX_INSTANT_BATCH)
                : Math.max(1, configuredLimit);
    }

    @Override
    public boolean expandsFlatBatchOperationLimit() {
        return supportsRepeatedRolls();
    }

    @Override
    public int preferredParallelBatchSize(int totalOperations, int workerCount) {
        if (!supportsRepeatedRolls()) return 1;
        return ParallelBatchSizing.boundedEvenShare(
                totalOperations, workerCount,
                Math.min(MAX_INSTANT_BATCH, selectedToolOperationCapacity()));
    }

    @Override
    public boolean supportsReusableBatchAggregation() {
        return supportsRepeatedRolls();
    }

    private boolean supportsRepeatedRolls() {
        return recipe != null && supportsRepeatedRolls(recipe.getClass());
    }

    static boolean supportsRepeatedRolls(Class<?> recipeClass) {
        if (recipeClass == null) return false;
        try {
            recipeClass.getMethod("rollResults",
                    RandomSource.class, int.class, RecipeWrapper.class);
            return true;
        } catch (NoSuchMethodException ignored) {
            return false;
        }
    }

    @Nullable
    @Override
    public List<IngredientSpec> getRequiredMaterials() {
        if (recipe == null || !RECIPE_CLASS.equals(recipe.getClass().getName())) return null;
        var handler = ModRecipeHandlers.handlerFor(recipe);
        return handler == null ? null : handler.getIngredients(recipe);
    }

    @Override
    public List<MaterialReservationScope> getMaterialReservationScopes() {
        List<IngredientSpec> specs = getRequiredMaterials();
        if (specs == null || specs.size() != 2) return List.of();
        return List.of(MaterialReservationScope.PER_OPERATION,
                specs.get(1).consumption().reusableWithinWorkerBatch()
                        ? MaterialReservationScope.PER_WORKER_REUSABLE
                        : MaterialReservationScope.PER_OPERATION);
    }

    @Override
    public List<IngredientSpec> getGraphSpecs() {
        return FarmersDelightRecipeHandler.cuttingBoardGraphIngredients(
                getRequiredMaterials());
    }

    @Override
    public List<IngredientSpec> getSupplementalSpecs() {
        // 工具已进入递归图，不能再作为 supplemental 重复预留。
        return List.of();
    }

    @Override
    public void configureMaterialReservation(@NotNull ExtractionLedger ledger,
                                             @NotNull ServerPlayer player) {
        List<IngredientSpec> specs = getRequiredMaterials();
        if (specs != null && specs.size() == 2) {
            ledger.preferResonanceFor(specs.get(1).ingredient());
        }
    }

    @Nullable
    @Override
    public Component materialReservationFailureMessage(@NotNull ServerPlayer player) {
        return selectTool(player, recipe, storageEndpoint()) == null
                ? Component.translatable("rsi.farmersdelight.cutting_board.tool_missing")
                : null;
    }

    @Override
    public boolean startSingleOperation(@NotNull ServerPlayer player) {
        List<IngredientSpec> specs = getRequiredMaterials();
        if (specs == null || specs.size() != 2) return false;
        List<ItemStack> materials = new ArrayList<>();
        try (ExtractionLedger local = new ExtractionLedger()) {
            if (storageEndpoint() == null) {
                setStorageEndpoint(CraftPacketUtils.resolveEndpointForCraft(player, myDim, myPos).orElse(null));
            }
            if (storageEndpoint() == null && !hasStorageAccess()) return false;
            local.setStorageEndpoint(storageEndpoint());
            configureMaterialReservation(local, player);
            for (IngredientSpec spec : specs) {
                ItemStack stack = CraftPacketUtils.ensureMaterialAvailable(
                        player, myDim, myPos, spec.ingredient(), spec.count(), local);
                if (stack.isEmpty()) return false;
                materials.add(stack.copyWithCount(spec.count()));
            }
            if (!local.commitToEndpoint(storageEndpoint(), player)) return false;
            usingSharedLedger = false;
            if (!startPreparedOperation(player, materials, local)) {
                for (ItemStack stack : materials) {
                    if (!stack.isEmpty()) insertIntoStorage(player, stack, false);
                }
                return false;
            }
            return true;
        }
    }

    @Override
    public boolean startPreparedOperation(@NotNull ServerPlayer player,
                                         @NotNull List<ItemStack> materials,
                                         @NotNull ExtractionLedger sharedLedger) {
        if (recipe == null || materials.size() != 2) return false;
        this.player = player;
        this.sharedLedger = sharedLedger;
        this.usingSharedLedger = true;
        if (sharedLedger.storageEndpoint() != null) setStorageEndpoint(sharedLedger.storageEndpoint());
        pendingResults.clear();
        reusableTools.clear();
        resonanceToolView = null;
        resonanceToolSlot = -1;
        toolReturnEndpoint = sharedLedger.storageEndpoint();
        craftDone = false;

        BlockEntity board = myLevel.getBlockEntity(myPos);
        if (!isBoard(board)) return false;
        if (!isBoardEmpty(board)) return false;

        int executions = executionsFor(materials);
        if (executions <= 0) return false;
        List<IngredientSpec> specs = getRequiredMaterials();
        if (specs == null || specs.size() != 2
                || !specs.get(0).ingredient().test(materials.get(0))
                || !specs.get(1).ingredient().test(materials.get(1))) return false;

        ItemStack toolTemplate = materials.get(1).copyWithCount(1);
        int toolUnits = materials.get(1).getCount();
        if (toolUnits <= 0 || toolTemplate.isEmpty()) return false;
        if (sharedLedger != null) {
            if (toolReturnEndpoint == null) toolReturnEndpoint = sharedLedger.storageEndpoint();
            ExtractionLedger.ResonanceReturnTarget target = sharedLedger
                    .resonanceReturnTarget(toolTemplate, specs.get(1).ingredient());
            if (target != null) {
                resonanceToolView = target.view();
                resonanceToolSlot = target.slot();
            }
        }
        long availableUses = CuttingBoardToolSemantics.remainingDurability(toolTemplate);
        if (toolTemplate.isDamageableItem()
                && availableUses < (long) executions
                && availableUses * toolUnits < (long) executions) {
            player.sendSystemMessage(Component.translatable(
                    "rsi.farmersdelight.cutting_board.tool_durability_insufficient",
                    toolTemplate.getHoverName(), availableUses * toolUnits, executions));
            return false;
        }

        try {
            Method rollMethod = findRollResultsMethod(recipe.getClass());
            List<ItemStack> stagedResults = new ArrayList<>();
            ItemStackHandler inputInventory = new ItemStackHandler(1);
            Component toolName = toolTemplate.getHoverName().copy();
            boolean toolBroke = false;
            int unusedToolUnits = toolUnits;
            ItemStack tool = toolTemplate.copy();
            unusedToolUnits--;
            for (int operation = 0; operation < executions; operation++) {
                if (tool.isEmpty()) {
                    if (unusedToolUnits <= 0) return false;
                    tool = toolTemplate.copy();
                    unusedToolUnits--;
                }
                ItemStack input = materials.get(0).copyWithCount(1);
                inputInventory.setStackInSlot(0, input);
                stagedResults.addAll(rollResults(recipe, rollMethod, myLevel.getRandom(),
                        fortuneLevel(tool), inputInventory));
                CuttingBoardToolSemantics.damageOnce(tool, myLevel.getRandom(), player);
                toolBroke |= tool.isEmpty();
            }
            if (!tool.isEmpty()) reusableTools.add(tool.copy());
            if (unusedToolUnits > 0) {
                reusableTools.add(toolTemplate.copyWithCount(unusedToolUnits));
            }
            pendingResults.addAll(stagedResults);
            craftDone = true;
            if (toolBroke) {
                player.sendSystemMessage(Component.translatable(
                        "rsi.farmersdelight.cutting_board.tool_broke", toolName));
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            RSIntegrationMod.LOGGER.error("[RSI-CuttingBoard] Failed to execute {}", recipe.getId(), exception);
            return false;
        }
    }

    @Override
    protected boolean isMachineCraftFinished(@NotNull ServerLevel level, @NotNull BlockEntity be) {
        return craftDone;
    }

    @Override
    public ItemStack collectResult(@NotNull ServerPlayer player) {
        if (pendingResults.isEmpty()) return ItemStack.EMPTY;
        return pendingResults.remove(0);
    }

    @Override
    public List<ItemStack> collectAllResults(@NotNull ServerPlayer player) {
        List<ItemStack> results = new ArrayList<>(pendingResults);
        pendingResults.clear();
        return List.copyOf(results);
    }

    @Override
    public void releaseReusableMaterials(@NotNull ServerPlayer player) {
        if (reusableTools.isEmpty()) return;
        boolean resonanceAvailable = resonanceToolView != null && resonanceToolSlot >= 0;
        for (ItemStack stored : List.copyOf(reusableTools)) {
            ItemStack leftover = stored.copy();
            if (resonanceAvailable) {
                leftover = resonanceToolView.insertView(
                        resonanceToolSlot, leftover, leftover.getCount(), false);
                resonanceToolView.markDirty(player);
                resonanceAvailable = false;
            }
            CraftStorageEndpoint endpoint = toolReturnEndpoint;
            if (!leftover.isEmpty() && endpoint != null) {
                leftover = endpoint.insert(player, leftover, false)
                        .remainder().orElse(ItemStack.EMPTY);
            }
            if (!leftover.isEmpty()) PlayerUtils.safeGiveToStorage(player, leftover, endpoint);
        }
        reusableTools.clear();
        resonanceToolView = null;
        resonanceToolSlot = -1;
        toolReturnEndpoint = null;
    }

    @Override
    public boolean collectsPhysicalSecondaryOutputs() {
        return true;
    }

    @Override
    public boolean canCollectResultWithoutWorldCapture() {
        return true;
    }

    @Override
    protected void clearMachineState(BlockEntity be, ServerPlayer player) {
        // Execution is staged in memory. On failure the ledger refunds the
        // original inputs, so unpublished staged outputs must be discarded.
        pendingResults.clear();
        reusableTools.clear();
        resonanceToolView = null;
        resonanceToolSlot = -1;
        toolReturnEndpoint = null;
        craftDone = false;
        resetState();
    }

    @Override
    public void onBatchFinished(@Nullable ServerPlayer player) {
        if (!markTerminalCleanup()) return;
        pendingResults.clear();
        craftDone = false;
        resetState();
    }

    @Override
    public BlockPos getMachinePos() { return myPos; }

    public static ToolPlanCheck getPlanCheck(ServerPlayer player, Recipe<?> recipe,
                                             @Nullable CraftStorageEndpoint endpoint) {
        return getPlanCheck(player, recipe, endpoint, List.of());
    }

    public static ToolPlanCheck getPlanCheck(ServerPlayer player, Recipe<?> recipe,
                                             @Nullable CraftStorageEndpoint endpoint,
                                             List<PlanStep> plannedSteps) {
        ToolSelection selection = selectTool(player, recipe, endpoint);
        if (selection == null) {
            return missingToolCheck(
                    FarmersDelightRecipeHandler.getCuttingBoardToolIngredient(recipe),
                    plannedSteps);
        }
        ItemStack tool = selection.stack();
        Component source = Component.translatable(selection.resonance()
                ? "rsi.farmersdelight.cutting_board.source_resonance"
                : "rsi.farmersdelight.cutting_board.source_storage");
        Component durability = tool.isDamageableItem()
                ? Component.literal(CuttingBoardToolSemantics.remainingDurability(tool)
                        + "/" + tool.getMaxDamage())
                : Component.translatable("rsi.farmersdelight.cutting_board.unbreakable");
        int unbreaking = EnchantmentHelper
                .getItemEnchantmentLevel(
                        Enchantments.UNBREAKING, tool);
        return new ToolPlanCheck(List.of(
                Component.translatable("rsi.farmersdelight.cutting_board.tool_selected",
                        tool.getHoverName(), source, durability, unbreaking),
                Component.translatable("rsi.farmersdelight.cutting_board.tool_warning")), false);
    }

    static ToolPlanCheck missingToolCheck(@Nullable Ingredient toolIngredient,
                                          List<PlanStep> plannedSteps) {
        ItemStack plannedTool = plannedTool(toolIngredient, plannedSteps);
        if (plannedTool != null) {
            return new ToolPlanCheck(List.of(Component.translatable(
                    "rsi.farmersdelight.cutting_board.tool_planned",
                    plannedTool.getHoverName())), false);
        }
        return new ToolPlanCheck(List.of(Component.translatable(
                "rsi.farmersdelight.cutting_board.tool_missing")), true);
    }

    @Nullable
    private static ItemStack plannedTool(@Nullable Ingredient toolIngredient,
                                         List<PlanStep> plannedSteps) {
        if (toolIngredient == null || toolIngredient.isEmpty()
                || plannedSteps == null || plannedSteps.isEmpty()) return null;
        for (PlanStep step : plannedSteps) {
            if (step == null || step.output().isEmpty()
                    || !IngredientMatcher.test(toolIngredient, step.output())
                    || CuttingBoardToolSemantics.remainingDurability(step.output()) <= 0) {
                continue;
            }
            return step.output().copyWithCount(1);
        }
        return null;
    }

    public static List<Component> getPlanWarnings(ServerPlayer player, Recipe<?> recipe,
                                                   @Nullable ResourceLocation dim,
                                                   @Nullable BlockPos pos,
                                                   @Nullable CraftStorageEndpoint endpoint) {
        return getPlanCheck(player, recipe, endpoint).warnings();
    }

    @Nullable
    private static ToolSelection selectTool(ServerPlayer player, Recipe<?> recipe,
                                            @Nullable CraftStorageEndpoint endpoint) {
        var toolIngredient = FarmersDelightRecipeHandler.getCuttingBoardToolIngredient(recipe);
        if (toolIngredient == null || endpoint == null) return null;
        for (ResonanceStorageView view : ResonanceCraftingSource.viewsFor(endpoint, player)) {
            for (ResonanceStorageView.StoredStack stored : view.storedStacks()) {
                ItemStack stack = stored.stack();
                if (!stack.isEmpty() && IngredientMatcher.test(toolIngredient, stack)
                        && CuttingBoardToolSemantics.remainingDurability(stack) > 0) {
                    return new ToolSelection(stack.copyWithCount(1), true);
                }
            }
        }
        var snapshot = endpoint.snapshot(player).snapshot().orElse(null);
        if (snapshot == null) return null;
        for (var stored : snapshot.match(toolIngredient).items()) {
            ItemStack stack = stored.stack();
            if (!stack.isEmpty()
                    && CuttingBoardToolSemantics.remainingDurability(stack) > 0) {
                return new ToolSelection(stack.copyWithCount(1), false);
            }
        }
        return null;
    }

    private int executionsFor(List<ItemStack> materials) {
        List<IngredientSpec> specs = getRequiredMaterials();
        if (specs == null || specs.size() != 2) return -1;
        int executions = Math.max(1, preparedExecutions);
        if (materials.get(0).isEmpty() || materials.get(1).isEmpty()) return -1;
        if (materials.get(0).getCount() != executions || materials.get(1).getCount() <= 0) return -1;
        return executions;
    }

    private int selectedToolOperationCapacity() {
        if (player == null || recipe == null) return 1;
        ToolSelection selection = selectTool(player, recipe, storageEndpoint());
        if (selection == null) return 1;
        int remaining = CuttingBoardToolSemantics.remainingDurability(selection.stack());
        return Math.max(1, Math.min(MAX_INSTANT_BATCH, remaining));
    }

    private static boolean isBoard(BlockEntity be) {
        return be != null && FarmersDelightReflection.cuttingBoardBEClass != null
                && FarmersDelightReflection.cuttingBoardBEClass.isInstance(be);
    }

    private static boolean isBoardEmpty(BlockEntity be) {
        try {
            Object empty = be.getClass().getMethod("isEmpty").invoke(be);
            return Boolean.TRUE.equals(empty);
        } catch (ReflectiveOperationException exception) {
            return false;
        }
    }

    private static int fortuneLevel(ItemStack tool) {
        return EnchantmentHelper.getItemEnchantmentLevel(
                Enchantments.BLOCK_FORTUNE, tool);
    }

    private static Method findRollResultsMethod(Class<?> recipeClass) throws NoSuchMethodException {
        try {
            return recipeClass.getMethod("rollResults",
                    RandomSource.class, int.class, RecipeWrapper.class);
        } catch (NoSuchMethodException ignored) {
            return recipeClass.getMethod("rollResults", RandomSource.class, int.class);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<ItemStack> rollResults(Recipe<?> recipe, Method method,
                                               RandomSource random, int fortune,
                                               ItemStackHandler inventory)
            throws ReflectiveOperationException {
        Object value = method.getParameterCount() == 3
                ? method.invoke(recipe, random, fortune, new RecipeWrapper(inventory))
                : method.invoke(recipe, random, fortune);
        if (!(value instanceof List<?> list)) return List.of();
        List<ItemStack> result = new ArrayList<>();
        for (Object entry : list) if (entry instanceof ItemStack stack && !stack.isEmpty()) result.add(stack.copy());
        return result;
    }
}
