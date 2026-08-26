package com.huanghuang.rsintegration.mods.farmersdelight;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.CraftStorageEndpoints;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientMatcher;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.ResonanceCraftingSource;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.util.PlayerUtils;
import com.huanghuang.rsintegration.recipe.FarmersDelightRecipeHandler;
import com.huanghuang.rsintegration.reflection.probes.FarmersDelightReflection;
import com.huanghuang.rsintegration.resonance.api.ResonanceStorageView;
import com.refinedmods.refinedstorage.api.network.INetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.wrapper.RecipeWrapper;
import org.jetbrains.annotations.NotNull;

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
    private ItemStack reusableTool = ItemStack.EMPTY;
    @Nullable
    private ResonanceStorageView resonanceToolView;
    private int resonanceToolSlot = -1;
    @Nullable
    private CraftStorageEndpoint toolReturnEndpoint;
    @Nullable
    private INetwork toolReturnNetwork;

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
        this.reusableTool = ItemStack.EMPTY;
        this.resonanceToolView = null;
        this.resonanceToolSlot = -1;
        this.toolReturnEndpoint = null;
        this.toolReturnNetwork = null;
        return true;
    }

    @Override
    public void prepareGraphBatch(int executions) {
        this.preparedExecutions = Math.max(1, executions);
    }

    @Nullable
    @Override
    public List<IngredientSpec> getRequiredMaterials() {
        if (recipe == null || !RECIPE_CLASS.equals(recipe.getClass().getName())) return null;
        var handler = com.huanghuang.rsintegration.recipe.ModRecipeHandlers.handlerFor(recipe);
        return handler == null ? null : handler.getIngredients(recipe);
    }

    @Override
    public List<MaterialReservationScope> getMaterialReservationScopes() {
        List<IngredientSpec> specs = getRequiredMaterials();
        if (specs == null || specs.size() != 2) return List.of();
        return List.of(MaterialReservationScope.PER_OPERATION,
                MaterialReservationScope.PER_WORKER_REUSABLE);
    }

    @Override
    public List<IngredientSpec> getGraphSpecs() {
        return FarmersDelightRecipeHandler.cuttingBoardGraphIngredients(
                getRequiredMaterials());
    }

    @Override
    public List<IngredientSpec> getSupplementalSpecs() {
        List<IngredientSpec> specs = getRequiredMaterials();
        if (specs == null || specs.size() != 2) return List.of();
        return List.of(specs.get(1));
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
    public boolean tryStartSingleCraft(@NotNull ServerPlayer player) {
        List<IngredientSpec> specs = getRequiredMaterials();
        if (specs == null || specs.size() != 2) return false;
        List<ItemStack> materials = new ArrayList<>();
        try (ExtractionLedger local = new ExtractionLedger()) {
            if (storageEndpoint() == null) {
                network = CraftPacketUtils.resolveNetworkForCraft(player, myDim, myPos);
            }
            if (network == null && !hasStorageAccess()) return false;
            local.setStorageEndpoint(storageEndpoint());
            configureMaterialReservation(local, player);
            for (IngredientSpec spec : specs) {
                ItemStack stack = CraftPacketUtils.ensureMaterialAvailable(
                        player, myDim, myPos, spec.ingredient(), spec.count(), local);
                if (stack.isEmpty()) return false;
                materials.add(stack.copyWithCount(spec.count()));
            }
            if (!local.commit(network, player)) return false;
            usingSharedLedger = false;
            if (!tryStartWithMaterials(player, materials, local)) {
                for (ItemStack stack : materials) {
                    if (!stack.isEmpty()) insertIntoStorage(player, stack, false);
                }
                return false;
            }
            return true;
        }
    }

    @Override
    public boolean tryStartWithMaterials(@NotNull ServerPlayer player,
                                         @NotNull List<ItemStack> materials,
                                         @NotNull ExtractionLedger sharedLedger) {
        if (recipe == null || materials.size() != 2) return false;
        this.player = player;
        this.sharedLedger = sharedLedger;
        this.usingSharedLedger = true;
        if (sharedLedger.storageEndpoint() != null) setStorageEndpoint(sharedLedger.storageEndpoint());
        pendingResults.clear();
        reusableTool = ItemStack.EMPTY;
        resonanceToolView = null;
        resonanceToolSlot = -1;
        toolReturnEndpoint = sharedLedger.storageEndpoint();
        toolReturnNetwork = network;
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

        ItemStack tool = materials.get(1).copyWithCount(1);
        if (sharedLedger != null) {
            if (toolReturnEndpoint == null) toolReturnEndpoint = sharedLedger.storageEndpoint();
            ExtractionLedger.ResonanceReturnTarget target = sharedLedger
                    .resonanceReturnTarget(tool, specs.get(1).ingredient());
            if (target != null) {
                resonanceToolView = target.view();
                resonanceToolSlot = target.slot();
            }
        }
        if (!CuttingBoardToolSemantics.canPerformOperations(tool, executions)) {
            player.sendSystemMessage(Component.translatable(
                    "rsi.farmersdelight.cutting_board.tool_durability_insufficient",
                    tool.getHoverName(), CuttingBoardToolSemantics.remainingDurability(tool), executions));
            return false;
        }

        try {
            Method rollMethod = findRollResultsMethod(recipe.getClass());
            List<ItemStack> stagedResults = new ArrayList<>();
            ItemStackHandler inputInventory = new ItemStackHandler(1);
            Component toolName = tool.getHoverName().copy();
            boolean toolBroke = false;
            for (int operation = 0; operation < executions; operation++) {
                ItemStack input = materials.get(0).copyWithCount(1);
                inputInventory.setStackInSlot(0, input);
                stagedResults.addAll(rollResults(recipe, rollMethod, myLevel.getRandom(),
                        fortuneLevel(tool), inputInventory));
                CuttingBoardToolSemantics.damageOnce(tool, myLevel.getRandom(), player);
                toolBroke |= tool.isEmpty();
            }
            if (!tool.isEmpty()) reusableTool = tool.copy();
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
        if (reusableTool.isEmpty()) return;
        ItemStack leftover = reusableTool.copy();
        if (resonanceToolView != null && resonanceToolSlot >= 0) {
            leftover = resonanceToolView.insertView(
                    resonanceToolSlot, leftover, leftover.getCount(), false);
            resonanceToolView.markDirty(player);
        }
        CraftStorageEndpoint endpoint = toolReturnEndpoint;
        if (endpoint == null && toolReturnNetwork != null) {
            endpoint = CraftStorageEndpoints.fromLegacyNetwork(toolReturnNetwork);
        }
        if (!leftover.isEmpty() && endpoint != null) {
            leftover = endpoint.insert(player, leftover, false)
                    .remainder().orElse(ItemStack.EMPTY);
        }
        if (!leftover.isEmpty()) PlayerUtils.safeGiveToPlayer(player, leftover, toolReturnNetwork);
        reusableTool = ItemStack.EMPTY;
        resonanceToolView = null;
        resonanceToolSlot = -1;
        toolReturnEndpoint = null;
        toolReturnNetwork = null;
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
        reusableTool = ItemStack.EMPTY;
        resonanceToolView = null;
        resonanceToolSlot = -1;
        toolReturnEndpoint = null;
        toolReturnNetwork = null;
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
        ToolSelection selection = selectTool(player, recipe, endpoint);
        if (selection == null) {
            return new ToolPlanCheck(List.of(Component.translatable(
                    "rsi.farmersdelight.cutting_board.tool_missing")), true);
        }
        ItemStack tool = selection.stack();
        Component source = Component.translatable(selection.resonance()
                ? "rsi.farmersdelight.cutting_board.source_resonance"
                : "rsi.farmersdelight.cutting_board.source_storage");
        Component durability = tool.isDamageableItem()
                ? Component.literal(CuttingBoardToolSemantics.remainingDurability(tool)
                        + "/" + tool.getMaxDamage())
                : Component.translatable("rsi.farmersdelight.cutting_board.unbreakable");
        int unbreaking = net.minecraft.world.item.enchantment.EnchantmentHelper
                .getItemEnchantmentLevel(
                        net.minecraft.world.item.enchantment.Enchantments.UNBREAKING, tool);
        return new ToolPlanCheck(List.of(
                Component.translatable("rsi.farmersdelight.cutting_board.tool_selected",
                        tool.getHoverName(), source, durability, unbreaking),
                Component.translatable("rsi.farmersdelight.cutting_board.tool_warning")), false);
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
                if (!stack.isEmpty() && IngredientMatcher.test(toolIngredient, stack)) {
                    return new ToolSelection(stack.copyWithCount(1), true);
                }
            }
        }
        var snapshot = endpoint.snapshot(player).snapshot().orElse(null);
        if (snapshot == null) return null;
        for (var stored : snapshot.match(toolIngredient).items()) {
            ItemStack stack = stored.stack();
            if (!stack.isEmpty()) return new ToolSelection(stack.copyWithCount(1), false);
        }
        return null;
    }

    private int executionsFor(List<ItemStack> materials) {
        List<IngredientSpec> specs = getRequiredMaterials();
        if (specs == null || specs.size() != 2) return -1;
        int executions = Math.max(1, preparedExecutions);
        if (materials.get(0).isEmpty() || materials.get(1).isEmpty()) return -1;
        if (materials.get(0).getCount() != executions || materials.get(1).getCount() != 1) return -1;
        return executions;
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
        return net.minecraft.world.item.enchantment.EnchantmentHelper.getItemEnchantmentLevel(
                net.minecraft.world.item.enchantment.Enchantments.BLOCK_FORTUNE, tool);
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
