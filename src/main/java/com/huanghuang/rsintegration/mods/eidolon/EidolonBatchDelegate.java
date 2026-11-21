package com.huanghuang.rsintegration.mods.eidolon;

import com.huanghuang.rsintegration.crafting.batch.OperationOutputSource;
import com.huanghuang.rsintegration.crafting.batch.MaterialPlan;
import com.huanghuang.rsintegration.crafting.batch.NativeItemOutput;
import com.huanghuang.rsintegration.crafting.batch.OutputContract;
import com.huanghuang.rsintegration.crafting.TaskOutputReception;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import com.huanghuang.rsintegration.crafting.IngredientMatcher;
import com.huanghuang.rsintegration.crafting.batch.InstantBatchDelegate;
import java.util.Arrays;
import net.minecraft.world.phys.AABB;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.reflection.probes.EidolonReflection;
import com.huanghuang.rsintegration.util.PlayerUtils;
import elucent.eidolon.api.ritual.Ritual;
import elucent.eidolon.common.tile.BrazierTileEntity;
import elucent.eidolon.recipe.RitualRecipe;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import org.jetbrains.annotations.NotNull;
import javax.annotation.Nullable;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Batch delegate for Eidolon Worktable and Crucible. */
public final class EidolonBatchDelegate extends InstantBatchDelegate {

    // ── Shared class refs (resolved from probe) ─────────────────
    private static volatile Field boilingField;
    private static volatile Field stepsField;

    static {
        if (EidolonReflection.crucibleTileEntityClass != null) {
            try {
                boilingField = EidolonReflection.crucibleTileEntityClass.getDeclaredField("boiling");
                boilingField.setAccessible(true);
            } catch (NoSuchFieldException e) {
                RSIntegrationMod.LOGGER.warn("[RSI-Batch-Eidolon] boiling field not found");
            }
            try {
                stepsField = EidolonReflection.crucibleTileEntityClass.getDeclaredField("steps");
                stepsField.setAccessible(true);
            } catch (NoSuchFieldException e) {
                RSIntegrationMod.LOGGER.warn("[RSI-Batch-Eidolon] steps field not found");
            }
        }
    }

    // ── Instance state ───────────────────────────────────────────
    private ServerPlayer player;
    private ResourceKey<Level> myDim;
    private BlockPos myPos;
    private BlockEntity crucible;
    private Recipe<?> recipe;            // CrucibleRecipe or WorktableRecipe or RitualRecipe
    private boolean isWorktable;         // true = worktable mode, no BE interaction
    private boolean isRitual;            // true = brazier ritual mode
    private Object brazier;              // BrazierTileEntity (null except ritual mode)
    private ItemStack pendingResult;      // Stored result for takeResult()
    private boolean craftCompleted;       // Flag set after instant craft
    private final List<ItemStack> pendingRemainders = new ArrayList<>();
    private final List<RitualInputSlot> installedRitualInputs = new ArrayList<>();
    private final List<ItemStack> uninstalledRitualInputs = new ArrayList<>();
    private ItemStack installedReagent = ItemStack.EMPTY;
    private ResourceLocation ritualOutputId;
    private boolean ritualStarted;
    private TaskOutputReception.Writer production;
    private NativeItemOutput.Receiver ritualReceiver;

    // ── IBatchDelegate impl ───────────────────────────────────────

    @Override
    public PreparationResult prepare(ServerPlayer player, ResourceLocation recipeId,
                                     @Nullable ResourceLocation dim, BlockPos pos) {
        ServerLevel level = CraftPacketUtils.resolveLevel(player.server, dim, player);
        if (level == null) return PreparationResult.fatal("machine dimension unavailable");

        Recipe<?> candidate = level.getRecipeManager().byKey(recipeId).orElse(null);
        if (candidate == null) return PreparationResult.fatal("recipe not found: " + recipeId);

        boolean crucibleRecipe = EidolonReflection.crucibleRecipeClass != null
                && EidolonReflection.crucibleRecipeClass.isInstance(candidate);
        if (crucibleRecipe) {
            if (!level.isLoaded(pos)) return PreparationResult.retry("crucible chunk is not loaded");
            BlockEntity be = level.getBlockEntity(pos);
            if (be == null || EidolonReflection.crucibleTileEntityClass == null
                    || !EidolonReflection.crucibleTileEntityClass.isInstance(be)) {
                return PreparationResult.fatal("bound machine is not an Eidolon crucible");
            }
            if (!isBoiling(be)) return PreparationResult.retry("crucible requires heat");
        }

        return validateAndInit(player, recipeId, dim, pos)
                ? PreparationResult.ready()
                : PreparationResult.retry("Eidolon machine is temporarily unavailable");
    }

    @Override
    public boolean acceptsMachineWithoutBlockEntity(ServerLevel level, BlockPos pos) {
        return EidolonReflection.worktableBlockClass != null
                && EidolonReflection.worktableBlockClass.isInstance(level.getBlockState(pos).getBlock());
    }

    @Override
    public boolean validateAndInit(ServerPlayer player, ResourceLocation recipeId,
                                   @Nullable ResourceLocation dim, BlockPos pos) {

        ServerLevel level = CraftPacketUtils.resolveLevel(player.server, dim, player);
        if (level == null) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.dim_not_found"));
            return false;
        }
        this.myDim = level.dimension();
        this.machineDim = myDim.location();
        this.machineServer = player.server;
        this.myPos = pos;
        this.player = player;

        if (!level.hasChunkAt(pos)) return false;
        BlockEntity be = level.getBlockEntity(pos);
        var blockState = level.getBlockState(pos);

        Recipe<?> foundRecipe = level.getRecipeManager().byKey(recipeId).orElse(null);
        if (foundRecipe == null) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.recipe_not_found", recipeId.toString()));
            return false;
        }
        this.recipe = foundRecipe;

        // Detect worktable mode
        boolean isWtRecipe = EidolonReflection.worktableRecipeClass != null && EidolonReflection.worktableRecipeClass.isInstance(foundRecipe);
        boolean isWtBlock = EidolonReflection.worktableBlockClass != null && EidolonReflection.worktableBlockClass.isInstance(blockState.getBlock());
        this.isWorktable = isWtRecipe || isWtBlock;

        if (isWorktable) {
            if (!isWtRecipe) {
                RSIntegrationMod.LOGGER.debug("[RSI-Batch-Eidolon] Machine-type mismatch: recipe={} is {} but bound machine is Worktable — trying next binding",
                        recipeId, foundRecipe.getClass().getSimpleName());
                return false;
            }
            this.isRitual = false;
            this.brazier = null;
            this.crucible = null;
            this.pendingResult = ItemStack.EMPTY;
            this.craftCompleted = false;
            this.pendingRemainders.clear();
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-Eidolon] validateAndInit OK (worktable): recipe={}", recipeId);
            return true;
        }

        // Ritual mode (Brazier)
        boolean isRitualRecipe = EidolonReflection.ritualRecipeClass != null && EidolonReflection.ritualRecipeClass.isInstance(foundRecipe);
        boolean isBrazier = EidolonReflection.brazierTileEntityClass != null && be != null && EidolonReflection.brazierTileEntityClass.isInstance(be);
        if (isRitualRecipe && isBrazier) {
            Ritual ritual = ((RitualRecipe) foundRecipe).getRitual();
            ItemStack declared = foundRecipe.getResultItem(level.registryAccess());
            if (!declared.isEmpty() && (!(ritual instanceof EidolonRitualOutput)
                    || !(be instanceof NativeItemOutput) || ritual.getRegistryName() == null)) return false;
            Object currentRitual = null;
            try {
                Field f = EidolonReflection.brazierTileEntityClass.getDeclaredField("ritual");
                f.setAccessible(true);
                currentRitual = f.get(be);
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Eidolon] reflection probe failed", e); }
            if (currentRitual != null) {
                player.sendSystemMessage(Component.translatable("rsi.eidolon.error.ritual_busy"));
                return false;
            }
            boolean burning = false;
            try {
                Field f = EidolonReflection.brazierTileEntityClass.getDeclaredField("burning");
                f.setAccessible(true);
                burning = f.getBoolean(be);
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Eidolon] reflection probe failed", e); }
            if (burning) {
                player.sendSystemMessage(Component.translatable("rsi.eidolon.error.ritual_busy"));
                return false;
            }
            this.isRitual = true;
            this.brazier = be;
            this.isWorktable = false;
            this.crucible = null;
            this.pendingResult = declared.copy();
            this.ritualOutputId = declared.isEmpty() ? null : ritual.getRegistryName();
            this.craftCompleted = false;
            this.pendingRemainders.clear();
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-Eidolon] validateAndInit OK (ritual): recipe={}", recipeId);
            return true;
        }

        // Crucible mode
        if (EidolonReflection.crucibleTileEntityClass == null || EidolonReflection.crucibleRecipeClass == null) {
            player.sendSystemMessage(Component.translatable("rsi.batch.error.mod_missing", "Eidolon"));
            return false;
        }
        if (!EidolonReflection.crucibleRecipeClass.isInstance(foundRecipe)) {
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-Eidolon] Machine-type mismatch: recipe={} is {} but bound machine is Crucible — trying next binding",
                    recipeId, foundRecipe.getClass().getSimpleName());
            return false;
        }
        if (be == null || !EidolonReflection.crucibleTileEntityClass.isInstance(be)) {
            player.sendSystemMessage(Component.translatable("rsi.eidolon.error.crucible_not_found"));
            return false;
        }
        this.isRitual = false;
        this.brazier = null;
        this.crucible = be;

        // Validate state
        boolean hasWater;
        try {
            var f = be.getClass().getDeclaredField("hasWater");
            f.setAccessible(true);
            hasWater = f.getBoolean(be);
        } catch (Exception e) { hasWater = false; }

        boolean boiling = isBoiling(be);

        // Default to NOT empty: if we can't read the field we must assume
        // the crucible is busy to avoid starting a second craft on top of
        // an already-running one.
        boolean stepsEmpty = false;
        try {
            if (stepsField != null) {
                List<?> steps = (List<?>) stepsField.get(be);
                stepsEmpty = steps == null || steps.isEmpty();
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-Eidolon] Cannot read steps field — assuming crucible is busy", e);
        }

        if (!hasWater) {
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-Eidolon] Crucible at {} will be filled when the selected operation starts", pos);
        }

        if (!boiling) {
            return false;
        }

        if (!stepsEmpty) {
            player.sendSystemMessage(Component.translatable("rsi.eidolon.warn.steps_not_empty"));
            return false;
        }

        this.pendingResult = ItemStack.EMPTY;
        this.craftCompleted = false;
        this.pendingRemainders.clear();

        RSIntegrationMod.LOGGER.debug("[RSI-Batch-Eidolon] validateAndInit OK: recipe={}", recipeId);
        return true;
    }

    @Override
    public boolean startSingleOperation(ServerPlayer player) {
        this.player = player;
        this.ledger = materialAccess().newLedger();
        if (storageEndpoint() == null) {
            setStorageEndpoint(CraftPacketUtils.resolveEndpointForCraft(player, myDim, myPos).orElse(null));
        }
        this.ledger.setStorageEndpoint(storageEndpoint());

        // Ritual mode
        if (isRitual) {
            return tryStartRitualCraft();
        }

        // Worktable mode: extract ingredients, produce output directly
        if (isWorktable) {
            return tryStartWorktableCraft();
        }

        // Verify the cached BlockEntity is still valid
        if (myPos != null && resolveMachineLevel(player).isLoaded(myPos)) {
            BlockEntity current = resolveMachineLevel(player).getBlockEntity(myPos);
            if (current == null || current.isRemoved()) {
                player.sendSystemMessage(Component.translatable("rsi.error.machine_missing"));
                if (ledger != null && ledger.isCommitted()) {
                    ledger.refundCommittedToEndpoint(storageEndpoint(), player);
                }
                return false;
            }
        }

        // Re-validate crucible state before each iteration
        boolean hasWater;
                try {
            var f = crucible.getClass().getDeclaredField("hasWater");
            f.setAccessible(true);
            hasWater = f.getBoolean(crucible);
        } catch (Exception e) { hasWater = false; }

        boolean boiling = EidolonWaterSupply.isHeated(crucible);

        boolean stepsEmpty = true;
        try {
            if (stepsField != null) {
                List<?> steps = (List<?>) stepsField.get(crucible);
                stepsEmpty = steps == null || steps.isEmpty();
            }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Batch-Eidolon] Reflection probe failed", e); }

        if (!boiling) return false;
        if (!hasWater) {
            if (!EidolonWaterSupply.ensureWater(materialAccess(), crucible, readWaterAmount(), storageEndpoint(), player)) return false;
        }
        if (!stepsEmpty) {
            try {
                if (stepsField != null) stepsField.set(crucible, new ArrayList<>());
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Batch-Eidolon] Reflection probe failed", e); }
        }

        // Collect needed items from recipe steps
        List<StepInput> stepInputs = collectSteps(recipe);
        if (stepInputs.isEmpty()) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Eidolon] No steps found for recipe");
            return false;
        }

        // Phase 1: reserve all ingredients via ledger
        List<Object> crucibleSteps = new ArrayList<>();

        try {
            for (StepInput si : stepInputs) {
                List<ItemStack> stepItems = new ArrayList<>();
                for (Ingredient ing : si.ingredients) {
                    if (ing.isEmpty()) continue;
                    ItemStack stack = CraftPacketUtils.ensureMaterialAvailable(player, myDim, myPos, ing, 1, ledger);
                    if (stack.isEmpty()) return false;
                    stepItems.add(stack);
                }

                Constructor<?> ctor = EidolonReflection.crucibleStepInnerClass.getConstructor(int.class, List.class);
                Object step = ctor.newInstance(si.stirs, stepItems);
                crucibleSteps.add(step);
            }

            boolean matches = (boolean) recipe.getClass()
                    .getMethod("matches", List.class)
                    .invoke(recipe, crucibleSteps);
            if (!matches) return false;

        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Eidolon] Extraction/step creation failed:", e);
            return false;
        }

        // Phase 1.5: check water amount before committing materials
        if (!checkCrucibleWater()) return false;

        // Phase 2: commit all extractions atomically
        if (!ledger.commitToEndpoint(storageEndpoint(), player)) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Eidolon] Ledger commit failed");
            return false;
        }

        // Get result first — validate before consuming resources
        try {
            this.pendingResult = ((ItemStack) recipe.getClass().getMethod("getResult")
                    .invoke(recipe)).copy();
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Eidolon] Failed to get result:", e);
            refundAll();
            ledger = null;
            return false;
        }

        // Drain water, stop boiling, clear steps (consume resources)
        try {
            Field tankField = crucible.getClass().getDeclaredField("tank");
            tankField.setAccessible(true);
            Object tank = tankField.get(crucible);
            tank.getClass()
                    .getMethod("drain", int.class, IFluidHandler.FluidAction.class)
                    .invoke(tank, readWaterAmount(), IFluidHandler.FluidAction.EXECUTE);
            crucible.getClass().getDeclaredField("hasWater").set(crucible, false);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Eidolon] Crucible drain/clear failed", e);
            refundAll();
            ledger = null;
            return false;
        }

        try {
            if (stepsField != null) stepsField.set(crucible, new ArrayList<>());
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Eidolon] Crucible drain/clear failed", e);
            refundAll();
            ledger = null;
            return false;
        }

        try {
            ((BlockEntity) crucible).setChanged();
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Eidolon] Crucible setChanged failed", e);
        }

        this.craftCompleted = true;
        return true;
    }

    @Override
    protected boolean supportsInstantBatch() {
        return isWorktable;
    }

    // ── Worktable crafting ───────────────────────────────────────

    private boolean tryStartWorktableCraft() {
        // WorktableRecipe uses vanilla's hasCraftingRemainingItem() / getCraftingRemainingItem()
        // for both core (3x3) and extras (4 corners). Items are consumed by default;
        // only items with a crafting remainder (e.g. water_bucket→bucket, or tools
        // that survive crafting) leave something behind.
        List<Ingredient> coreIngs = getWorktableCoreIngredients();
        List<Ingredient> extraIngs = getWorktableOuterIngredients();

        if (coreIngs == null || coreIngs.isEmpty()) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-Eidolon] No core ingredients in worktable recipe: {}", recipe.getId());
            return false;
        }

        // Phase 1: Reserve all ingredients (core + extras)
        List<ItemStack> extracted = new ArrayList<>();
        List<Ingredient> allIngs = new ArrayList<>(coreIngs);
        if (extraIngs != null) {
            for (Ingredient ing : extraIngs) {
                if (!ing.isEmpty()) allIngs.add(ing);
            }
        }
        for (Ingredient ing : allIngs) {
            ItemStack taken = CraftPacketUtils.ensureMaterialAvailable(player, myDim, myPos, ing, 1, ledger);
            if (taken.isEmpty()) return false;
            extracted.add(taken);
        }

        // Phase 2: Commit
        if (!ledger.commitToEndpoint(storageEndpoint(), player)) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Eidolon] Worktable ledger commit failed");
            return false;
        }

        // Phase 3: Get result first — no side effects if this fails
        try {
            this.pendingResult = recipe.getResultItem(player.serverLevel().registryAccess()).copy();
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Eidolon] Failed to get worktable result:", e);
            refundAll();
            ledger = null;
            return false;
        }

        // Phase 4: Handle crafting remainders.
        // Vanilla WorktableRecipe.getRemainingItems() checks hasCraftingRemainingItem()
        // on every slot in both core and extras containers; items without a remainder
        // are consumed, items with a remainder leave getCraftingRemainingItem() behind.
        for (ItemStack stack : extracted) {
            if (stack.hasCraftingRemainingItem()) {
                ItemStack remainder = stack.getCraftingRemainingItem();
                if (!remainder.isEmpty()) {
                    ItemStack leftover = insertIntoStorage(player, remainder, false);
                    if (!leftover.isEmpty()) ItemHandlerHelper.giveItemToPlayer(player, leftover);
                }
            }
        }

        this.craftCompleted = true;
        return true;
    }

    // ── Ritual (Brazier / Crystal Ritual) crafting ──────────────

    private boolean tryStartRitualCraft() {
        ServerLevel level = resolveMachineLevel(player);
        if (!canStartRitual(level)) return false;
        if (!hasRequiredRitualHealth(level, player)) return false;
        List<IngredientSpec> required = getRitualRequiredMaterials();
        if (required == null || required.isEmpty()) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Eidolon] No inputs in ritual recipe: {}", recipe.getId());
            return false;
        }
        List<ItemStack> materials = new ArrayList<>(required.size());
        for (IngredientSpec spec : required) {
            ItemStack stack = CraftPacketUtils.ensureMaterialAvailable(
                    player, myDim, myPos, spec.ingredient(), spec.count(), ledger);
            if (stack.isEmpty()) return false;
            materials.add(stack);
        }
        if (!ledger.commitToEndpoint(storageEndpoint(), player)) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Eidolon] Ritual ledger commit failed");
            return false;
        }
        try {
            if (tryStartRitualWithMaterials(player, materials)) return true;
        } catch (RuntimeException e) {
            recoverRitualInputs(level, player);
            throw e;
        }
        recoverRitualInputs(level, player);
        return false;
    }

    private Ingredient getRitualReagent() {
        try {
            Field f = EidolonReflection.ritualRecipeClass.getField("reagent");
            return (Ingredient) f.get(recipe);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-Eidolon] getRitualReagent failed", e);
            return null;
        }
    }

    /**
     * Eidolon discovers ritual recipes from the item providers already placed around
     * the brazier. Check that structure before committing the reagent to storage.
     */
    private boolean matchesSelectedRitual(ServerLevel level, ItemStack reagentStack) {
        BrazierTileEntity tile = (BrazierTileEntity) brazier;
        try {
            tile.setStack(reagentStack);
            return BrazierTileEntity.getRitualRecipes(level).stream()
                    .filter(candidate -> candidate.matches(tile, level))
                    .findFirst().map(candidate -> candidate.getId().equals(recipe.getId())).orElse(false);
        } finally {
            if (tile.getStack() == reagentStack) tile.setStack(ItemStack.EMPTY);
        }
    }

    private List<Ingredient> getRitualPedestalItems() {
        try {
            Field f = EidolonReflection.ritualRecipeClass.getField("pedestalItems");
            @SuppressWarnings("unchecked")
            List<Ingredient> items = (List<Ingredient>) f.get(recipe);
            return items;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Eidolon] Failed to read pedestal items", e);
            return null;
        }
    }

    private List<Ingredient> getRitualFocusItems() {
        try {
            Field f = EidolonReflection.ritualRecipeClass.getField("focusItems");
            @SuppressWarnings("unchecked")
            List<Ingredient> items = (List<Ingredient>) f.get(recipe);
            return items;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Eidolon] Failed to read focus items", e);
            return null;
        }
    }

    private List<Ingredient> getRitualInvariantItems() {
        try {
            Field f = EidolonReflection.ritualRecipeClass.getField("invariantItems");
            @SuppressWarnings("unchecked")
            List<Ingredient> items = (List<Ingredient>) f.get(recipe);
            return items;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Eidolon] Failed to read invariant items", e);
            return null;
        }
    }

    private boolean canStartRitual(ServerLevel level) {
        if (level == null || myPos == null || !level.isLoaded(myPos)
                || level.getBlockEntity(myPos) != brazier || !(brazier instanceof BrazierTileEntity tile)
                || tile.isRemoved() || !tile.getStack().isEmpty()) return false;
        if (ritualOutputId != null && (production == null || !(brazier instanceof NativeItemOutput hook)
                || hook.rsi$getOutputReceiver() != null)) return false;
        try {
            Field f = EidolonReflection.brazierTileEntityClass.getDeclaredField("ritual");
            f.setAccessible(true);
            if (f.get(brazier) != null) return false;
            Field bf = EidolonReflection.brazierTileEntityClass.getDeclaredField("burning");
            bf.setAccessible(true);
            if (bf.getBoolean(brazier)) return false;
        } catch (Exception e) { return false; }
        return true;
    }

    private boolean tryStartRitualWithMaterials(ServerPlayer player, List<ItemStack> materials) {
        uninstalledRitualInputs.addAll(materials.stream().map(ItemStack::copy).toList());
        ServerLevel level = resolveMachineLevel(player);
        if (materials.isEmpty() || !canStartRitual(level)) return false;
        if (!hasRequiredRitualHealth(level, player)) return false;
        if (!prepareRitualStructure(level, materials)) {
            player.sendSystemMessage(Component.translatable("rsi.eidolon.error.ritual_structure_mismatch"));
            return false;
        }
        return placeReagentAndStartRitual(level, materials.get(0), player);
    }

    /**
     * Place the network-reserved sacrificial inputs into empty Eidolon providers.
     * RitualRecipe requires the complete provider list to match exactly, so inputs
     * already placed by a player are deliberately not overwritten.
     */
    private boolean prepareRitualStructure(ServerLevel level, List<ItemStack> materials) {
        List<IngredientSpec> required = getRitualRequiredMaterials();
        if (required == null || materials.size() != required.size()) return false;
        for (int i = 0; i < materials.size(); i++) {
            if (materials.get(i).getCount() != 1
                    || !IngredientMatcher.test(required.get(i).ingredient(), materials.get(i))) return false;
        }
        int[] index = {1}; // Reagent is installed on the brazier only after validation.
        try {
            if (!placeRitualInputs(level, getRitualPedestalItems(), materials, index, false)
                    || !placeRitualInputs(level, getRitualFocusItems(), materials, index, true)) {
                return false;
            }
            ItemStack reagent = materials.get(0).copyWithCount(1);
            if (!matchesSelectedRitual(level, reagent)) {
                return false;
            }
            return true;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Eidolon] Failed to populate ritual structure", e);
            return false;
        }
    }

    private boolean placeRitualInputs(ServerLevel level, List<Ingredient> ingredients,
                                      List<ItemStack> materials, int[] index, boolean focus) throws Exception {
        if (ingredients == null || ingredients.isEmpty()) return true;
        List<?> providers = findRitualProviders(level, focus);
        Set<Object> used = new HashSet<>();
        for (Ingredient ingredient : ingredients) {
            if (ingredient == null || ingredient.isEmpty()) continue;
            if (index[0] >= materials.size()) return false;
            Object provider = findEmptyProvider(providers, used);
            if (provider == null) return false;
            int materialIndex = index[0]++;
            ItemStack stack = materials.get(materialIndex).copyWithCount(1);
            if (!IngredientMatcher.test(ingredient, stack)) return false;
            Field stackField = findField(provider.getClass(), "stack");
            stackField.setAccessible(true);
            installedRitualInputs.add(new RitualInputSlot(provider, focus, stackField, stack));
            uninstalledRitualInputs.set(materialIndex, ItemStack.EMPTY);
            writeRitualProvider(provider, focus, stack);
            used.add(provider);
        }
        return true;
    }

    private List<?> findRitualProviders(ServerLevel level, boolean focus) throws Exception {
        Class<?> ritual = Class.forName("elucent.eidolon.api.ritual.Ritual");
        Class<?> provider = Class.forName(focus
                ? "elucent.eidolon.api.ritual.IRitualItemFocus"
                : "elucent.eidolon.api.ritual.IRitualItemProvider");
        Object bounds = ritualDefaultBounds(ritual);
        @SuppressWarnings("unchecked")
        List<?> providers = (List<?>) ritual.getMethod("getTilesWithinAABB", Class.class, Level.class,
                        Class.forName("net.minecraft.world.phys.AABB"))
                .invoke(null, provider, level, bounds);
        if (focus) return providers;
        Class<?> focusProvider = Class.forName("elucent.eidolon.api.ritual.IRitualItemFocus");
        List<Object> nonFocus = new ArrayList<>();
        for (Object candidate : providers) if (!focusProvider.isInstance(candidate)) nonFocus.add(candidate);
        return nonFocus;
    }

    private Object findEmptyProvider(List<?> providers, Set<Object> used) throws Exception {
        for (Object provider : providers) {
            if (used.contains(provider)) continue;
            ItemStack stack = (ItemStack) provider.getClass().getMethod("provide").invoke(provider);
            if (stack == null || stack.isEmpty()) return provider;
        }
        return null;
    }

    private void writeRitualProvider(Object provider, boolean focus, ItemStack stack) throws Exception {
        if (focus) {
            provider.getClass().getMethod("replace", ItemStack.class).invoke(provider, stack);
        } else {
            try {
                // SingleItemTile-based pedestals expose a public setter.
                provider.getClass().getMethod("setStack", ItemStack.class).invoke(provider, stack);
            } catch (NoSuchMethodException noSetter) {
                // HandTileEntity is also an IRitualItemProvider, but intentionally
                // exposes only provide()/take(). Its stack needs this compatibility
                // path so a hand pedestal can receive an RSI-reserved sacrifice.
                Field itemStack = findField(provider.getClass(), "stack");
                itemStack.setAccessible(true);
                itemStack.set(provider, stack);
                provider.getClass().getMethod("sync").invoke(provider);
            }
        }
        if (provider instanceof BlockEntity blockEntity) blockEntity.setChanged();
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // Keep looking through TileEntityBase and the provider's parents.
            }
        }
        throw new NoSuchFieldException(type.getName() + '.' + name);
    }

    private boolean hasRequiredRitualHealth(ServerLevel level, ServerPlayer player) {
        float required = ritualHealthRequirement();
        if (required <= 0.0F) return true;
        try {
            Class<?> ritual = Class.forName("elucent.eidolon.api.ritual.Ritual");
            AABB bounds = (AABB) ritualDefaultBounds(ritual);
            float available = 0.0F;
            for (Mob mob : level.getEntitiesOfClass(Mob.class, bounds, mob -> !mob.isInvulnerable())) {
                available += mob.getHealth();
            }
            for (Player nearbyPlayer : level.getEntitiesOfClass(Player.class, bounds)) {
                available += nearbyPlayer.getHealth();
            }
            if (available >= required) return true;
            player.sendSystemMessage(Component.translatable(
                    "rsi.eidolon.error.insufficient_ritual_health", required, available));
            return false;
        } catch (Exception e) {
            // Do not reject a ritual just because a future Eidolon release changes
            // its reflection surface; Eidolon still performs its own authoritative check.
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-Eidolon] Failed to inspect ritual health requirement", e);
            return true;
        }
    }

    private Object ritualDefaultBounds(Class<?> ritualClass) throws ReflectiveOperationException {
        return ritualClass.getMethod("getDefaultBounds", BlockPos.class).invoke(null, myPos);
    }

    private float ritualHealthRequirement() {
        try {
            Field health = EidolonReflection.ritualRecipeClass
                    .getDeclaredField("healthRequirement");
            health.setAccessible(true);
            return health.getFloat(recipe);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-Eidolon] No readable ritual health requirement", e);
            return 0.0F;
        }
    }

    private boolean placeReagentAndStartRitual(ServerLevel level, ItemStack reagent, ServerPlayer player) {
        BrazierTileEntity tile = (BrazierTileEntity) brazier;
        try {
            installedReagent = reagent.copyWithCount(1);
            uninstalledRitualInputs.set(0, ItemStack.EMPTY);
            tile.setStack(installedReagent);
            tile.setChanged();
            if (ritualOutputId != null) {
                ritualReceiver = this::receiveRitualOutput;
                ((NativeItemOutput) brazier).rsi$setOutputReceiver(ritualReceiver);
            }
            ritualStarted = true;
            tile.startBurning(player, level, myPos);
            return true;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Eidolon] Failed to start brazier ritual", e);
            return false;
        }
    }

    boolean receiveRitualOutput(Object active, ItemStack stack, boolean primary) {
        if (craftCompleted || !primary || !ritualOutputId.equals(active)
                || !ItemStack.isSameItem(stack, pendingResult)
                || !production.tryHold(stack)) return false;
        craftCompleted = true;
        return true;
    }

    private void recoverRitualInputs(@Nullable ServerLevel level, @Nullable ServerPlayer player) {
        releasePreparationResources();
        List<ItemStack> recovered = new ArrayList<>(uninstalledRitualInputs);
        uninstalledRitualInputs.clear();
        if (brazier instanceof BrazierTileEntity tile && isPresent(level, tile)) {
            boolean ownsReagent = !installedReagent.isEmpty() && tile.getStack() == installedReagent;
            try {
                Field active = findField(tile.getClass(), "ritual");
                active.setAccessible(true);
                if (ritualStarted && (ownsReagent || active.get(tile) instanceof Ritual ritual
                        && ritualOutputId != null && ritualOutputId.equals(ritual.getRegistryName()))) {
                    Method extinguish = EidolonReflection.brazierTileEntityClass.getDeclaredMethod("extinguish");
                    extinguish.setAccessible(true);
                    extinguish.invoke(tile);
                    tile.setChanged();
                }
                if (ownsReagent) {
                    tile.setStack(ItemStack.EMPTY);
                    recovered.add(installedReagent);
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-Batch-Eidolon] Failed to recover ritual reagent", e);
            }
        }
        for (RitualInputSlot slot : installedRitualInputs) {
            try {
                if (slot.provider instanceof BlockEntity tile && isPresent(level, tile)
                        && slot.stackField.get(slot.provider) == slot.stack) {
                    writeRitualProvider(slot.provider, slot.focus, ItemStack.EMPTY);
                    recovered.add(slot.stack);
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-Batch-Eidolon] Failed to clear ritual input", e);
            }
        }
        installedRitualInputs.clear();
        installedReagent = ItemStack.EMPTY;
        ritualStarted = false;
        settleFailureRecoveredInputs(recovered, player);
    }

    private static boolean isPresent(@Nullable ServerLevel level, BlockEntity tile) {
        return level != null && !tile.isRemoved() && level.hasChunkAt(tile.getBlockPos())
                && level.getBlockEntity(tile.getBlockPos()) == tile;
    }

    private List<IngredientSpec> getRitualRequiredMaterials() {
        Ingredient reagent = getRitualReagent();
        if (reagent == null || reagent.isEmpty()) return null;
        List<IngredientSpec> result = new ArrayList<>();
        result.add(new IngredientSpec(reagent, 1));
        addRitualIngredientSpecs(result, getRitualPedestalItems());
        addRitualIngredientSpecs(result, getRitualFocusItems());
        return result;
    }

    private static void addRitualIngredientSpecs(List<IngredientSpec> result, List<Ingredient> ingredients) {
        if (ingredients == null) return;
        for (Ingredient ingredient : ingredients) {
            if (ingredient != null && !ingredient.isEmpty()) result.add(new IngredientSpec(ingredient, 1));
        }
    }

    private static final class RitualInputSlot {
        private final Object provider;
        private final boolean focus;
        private final Field stackField;
        private final ItemStack stack;

        private RitualInputSlot(Object provider, boolean focus, Field stackField, ItemStack stack) {
            this.provider = provider;
            this.focus = focus;
            this.stackField = stackField;
            this.stack = stack;
        }
    }

    // ── Worktable ingredient helpers ────────────────────────────

    private List<Ingredient> getWorktableCoreIngredients() {
        try {
            Ingredient[] core = (Ingredient[]) recipe.getClass()
                    .getMethod("getCore").invoke(recipe);
            return core != null ? Arrays.asList(core) : null;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-Eidolon] getCore failed", e);
            return null;
        }
    }

    private List<Ingredient> getWorktableOuterIngredients() {
        try {
            Ingredient[] outer = (Ingredient[]) recipe.getClass()
                    .getMethod("getOuter").invoke(recipe);
            return outer != null ? Arrays.asList(outer) : null;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-Eidolon] getOuter failed", e);
            return null;
        }
    }

    // ── Chain support: pre-reserved materials ────────────────────

    @Override
    public MaterialPlan materialPlan() {
        return MaterialPlan.graphSlots(recipeSlots());
    }

    List<IngredientSpec> recipeSlots() {
        if (recipe == null) return null;
        if (isRitual) {
            return getRitualRequiredMaterials();
        }
        if (isWorktable) {
            // Both core and extras are consumed by default;
            // hasCraftingRemainingItem() determines what remains.
            List<Ingredient> core = getWorktableCoreIngredients();
            List<Ingredient> extra = getWorktableOuterIngredients();
            if (core == null || core.isEmpty()) return null;
            List<IngredientSpec> specs = new ArrayList<>();
            for (Ingredient ing : core) {
                if (!ing.isEmpty()) specs.add(new IngredientSpec(ing, 1));
            }
            if (extra != null) {
                for (Ingredient ing : extra) {
                    if (!ing.isEmpty()) specs.add(new IngredientSpec(ing, 1));
                }
            }
            return specs.isEmpty() ? null : specs;
        }
        List<StepInput> stepInputs = collectSteps(recipe);
        if (stepInputs.isEmpty()) return null;
        List<IngredientSpec> specs = new ArrayList<>();
        for (StepInput si : stepInputs) {
            for (Ingredient ing : si.ingredients) {
                if (!ing.isEmpty()) {
                    specs.add(new IngredientSpec(ing, 1));
                }
            }
        }
        return specs.isEmpty() ? null : specs;
    }

    @Override
    public boolean startPreparedOperation(ServerPlayer player, List<ItemStack> materials,
                                         ExtractionLedger sharedLedger) {
        this.player = player;
        useSharedLedger(sharedLedger);

        // Ritual mode: materials were pre-extracted by chain in reagent,
        // pedestal, then focus order. All of them are Eidolon consumables.
        if (isRitual) {
            return tryStartRitualWithMaterials(player, materials);
        }

        // Worktable mode: materials (core + extras) were pre-extracted by chain.
        // Hold crafting remainders until result collection so an aborted shared
        // ledger can still refund inputs without duplicating their containers.
        if (isWorktable) {
            pendingRemainders.clear();
            int executions = preparedExecutions();
            for (ItemStack mat : materials) {
                if (mat.hasCraftingRemainingItem()) {
                    ItemStack remainder = mat.getCraftingRemainingItem();
                    if (!remainder.isEmpty()) {
                        pendingRemainders.add(scaledCopy(remainder, executions));
                    }
                }
            }
            try {
                this.pendingResult = scaledCopy(
                        recipe.getResultItem(player.serverLevel().registryAccess()), executions);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.error("[RSI-Batch-Eidolon] Failed to get worktable result:", e);
                return false;
            }
            this.craftCompleted = true;
            return true;
        }

        // Verify the cached BlockEntity is still valid
        if (myPos != null && resolveMachineLevel(player).isLoaded(myPos)) {
            BlockEntity current = resolveMachineLevel(player).getBlockEntity(myPos);
            if (current == null || current.isRemoved()) {
                player.sendSystemMessage(Component.translatable("rsi.error.machine_missing"));
                if (ledger != null && ledger.isCommitted()) {
                    ledger.refundCommittedToEndpoint(storageEndpoint(), player);
                }
                return false;
            }
        }

        // Re-validate crucible state
        boolean hasWater;
                try {
            var f = crucible.getClass().getDeclaredField("hasWater");
            f.setAccessible(true);
            hasWater = f.getBoolean(crucible);
        } catch (Exception e) { hasWater = false; }
        boolean boiling = EidolonWaterSupply.isHeated(crucible);
        boolean stepsEmpty = true;
        try {
            if (stepsField != null) {
                List<?> steps = (List<?>) stepsField.get(crucible);
                stepsEmpty = steps == null || steps.isEmpty();
            }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Batch-Eidolon] Reflection probe failed", e); }

        if (!boiling) return false;
        if (!hasWater) {
            if (!EidolonWaterSupply.ensureWater(materialAccess(), crucible, readWaterAmount(), storageEndpoint(), player)) return false;
        }
        if (!stepsEmpty) {
            try {
                if (stepsField != null) stepsField.set(crucible, new ArrayList<>());
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Batch-Eidolon] Reflection probe failed", e); }
        }

        // Check water amount before using pre-extracted materials
        if (!checkCrucibleWater()) return false;

        // Collect steps to know counts
        List<StepInput> stepInputs = collectSteps(recipe);
        if (stepInputs.isEmpty()) return false;

        // Build crucible step objects from pre-reserved materials
        List<Object> crucibleSteps = new ArrayList<>();
        try {
            int matIdx = 0;
            for (StepInput si : stepInputs) {
                List<ItemStack> stepItems = new ArrayList<>();
                for (int j = 0; j < si.ingredients.size() && matIdx < materials.size(); j++) {
                    ItemStack mat = materials.get(matIdx++);
                    if (!mat.isEmpty()) stepItems.add(mat);
                }
                Constructor<?> ctor = EidolonReflection.crucibleStepInnerClass.getConstructor(int.class, List.class);
                Object step = ctor.newInstance(si.stirs, stepItems);
                crucibleSteps.add(step);
            }

            boolean matches = (boolean) recipe.getClass()
                    .getMethod("matches", List.class)
                    .invoke(recipe, crucibleSteps);
            if (!matches) return false;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Eidolon] Step creation failed:", e);
            return false;
        }

        // Get result first — validate before consuming resources
        try {
            this.pendingResult = ((ItemStack) recipe.getClass().getMethod("getResult")
                    .invoke(recipe)).copy();
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Eidolon] Failed to get result:", e);
            return false;
        }

        // Drain water, stop boiling, clear steps
        try {
            Field tankField = crucible.getClass().getDeclaredField("tank");
            tankField.setAccessible(true);
            Object tank = tankField.get(crucible);
            tank.getClass()
                    .getMethod("drain", int.class, IFluidHandler.FluidAction.class)
                    .invoke(tank, readWaterAmount(), IFluidHandler.FluidAction.EXECUTE);
            crucible.getClass().getDeclaredField("hasWater").set(crucible, false);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Eidolon] Crucible drain/clear failed", e);
            return false;
        }

        try {
            if (stepsField != null) stepsField.set(crucible, new ArrayList<>());
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Eidolon] Crucible drain/clear failed", e);
            return false;
        }

        try {
            ((BlockEntity) crucible).setChanged();
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Eidolon] Crucible drain/clear failed", e);
            return false;
        }

        this.craftCompleted = true;
        return true;
    }

    @Override
    protected boolean isMachineCraftFinished(ServerLevel level, BlockEntity be) {
        if (isRitual) {
            if (be != brazier || !ritualStarted) return false;
            if (ritualOutputId != null) return craftCompleted;
            try {
                Field f = EidolonReflection.brazierTileEntityClass.getDeclaredField("ritualDone");
                f.setAccessible(true);
                return f.getBoolean(be);
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Eidolon] reflection probe failed", e); }
            return false;
        }
        // Crucible & worktable crafts are instant
        return craftCompleted;
    }

    @Override
    protected CraftObservation observeMachineCraft(ServerLevel level, BlockEntity be) {
        if (!isRitual) return super.observeMachineCraft(level, be);
        if (be != brazier) return failObservation("brazier was replaced");
        if (isMachineCraftFinished(level, be)) return doneObservation();
        try {
            Field burning = findField(be.getClass(), "burning");
            burning.setAccessible(true);
            if (!burning.getBoolean(be)) return failObservation("brazier stopped without completing the ritual");
        } catch (ReflectiveOperationException e) {
            return failObservation("could not read brazier state");
        }
        return workingObservation();
    }

    @Override public OperationOutputSource outputSource() {
        return player -> OperationOutputSource.Handoff.physical(takeResults(player), outputContract());
    }

    private List<ItemStack> takeResults(ServerPlayer player) {
        if (isRitual) return List.of();
        List<ItemStack> results = new ArrayList<>(pendingRemainders.size() + 1);
        if (pendingResult != null && !pendingResult.isEmpty()) results.add(pendingResult.copy());
        results.addAll(pendingRemainders.stream().map(ItemStack::copy).toList());
        pendingResult = ItemStack.EMPTY;
        pendingRemainders.clear();
        craftCompleted = false;
        return List.copyOf(results);
    }

    private void returnPendingRemainders(ServerPlayer player) {
        for (ItemStack remainder : pendingRemainders) {
            ItemStack leftover = insertIntoStorage(player, remainder.copy(), false);
            if (!leftover.isEmpty()) PlayerUtils.safeGiveToStorage(player, leftover, storageEndpoint());
        }
        pendingRemainders.clear();
    }

    @Override
    public void bindProduction(TaskOutputReception.Writer writer) {
        production = writer;
    }

    @Override
    public void releasePreparationResources() {
        if (brazier instanceof NativeItemOutput hook && ritualReceiver != null) {
            hook.rsi$clearOutputReceiver(ritualReceiver);
        }
        ritualReceiver = null;
    }

    @Override
    protected void clearMissingMachineState(@Nullable ServerPlayer player) {
        if (isRitual) recoverRitualInputs(resolveMachineLevel(player), player);
    }

    @Override
    protected void clearUnavailableMachineState(@Nullable ServerPlayer player) {
        if (isRitual) recoverRitualInputs(null, player);
    }

    @Override
    protected void clearMachineState(BlockEntity be, ServerPlayer player) {
        if (isRitual) {
            recoverRitualInputs(resolveMachineLevel(player), player);
        } else {
            refundAll();
        }
        if (!isRitual && !isWorktable && crucible != null) {
            try {
                if (stepsField != null) stepsField.set(crucible, new ArrayList<>());
                ((BlockEntity) crucible).setChanged();
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-Batch-Eidolon] Failed to clear crucible state during recovery", e);
            }
        }
        pendingResult = ItemStack.EMPTY;
        pendingRemainders.clear();
        craftCompleted = false;
        resetInstantBatch();
        resetState();
    }

    @Override
    protected void clearVirtualState(ServerPlayer player) {
        if (!isWorktable) return;
        pendingResult = ItemStack.EMPTY;
        pendingRemainders.clear();
        craftCompleted = false;
    }

    @Override
    public void cleanup(@NotNull ServerPlayer player) {
        releasePreparationResources();
        ritualStarted = false;
        installedReagent = ItemStack.EMPTY;
        installedRitualInputs.clear();
        uninstalledRitualInputs.clear();
        pendingResult = ItemStack.EMPTY;
        pendingRemainders.clear();
        craftCompleted = false;
        resetInstantBatch();
        resetState();
    }

    @Override
    public BlockPos getMachinePos() {
        // Eidolon worktables have no BlockEntity and complete instantly.
        return isWorktable ? null : myPos;
    }

    @Override
    public ExpectedProduction getExpectedProduction() {
        return isRitual && ritualOutputId != null && !pendingResult.isEmpty()
                ? new ExpectedProduction(pendingResult, pendingResult.getCount()) : null;
    }

    @Override
    public OutputContract outputContract() {
        ExpectedProduction expected = getExpectedProduction();
        if (expected == null) return OutputContract.none();
        return new OutputContract(List.of(new OutputContract.Port("eidolon:brazier", null,
                new ItemStack(expected.item().getItem()), expected.count(),
                OutputContract.Kind.PRIMARY, OutputContract.Source.WORLD)));
    }

    @Override
    public AABB getOutputCaptureRegion() {
        return null;
    }

    // ── Step collection ──────────────────────────────────────────

    private static List<StepInput> collectSteps(Recipe<?> recipe) {
        List<StepInput> result = new ArrayList<>();
        try {
            List<?> steps = (List<?>) recipe.getClass().getMethod("getSteps").invoke(recipe);
            if (steps != null) {
                for (Object step : steps) {
                    var stirsField = step.getClass().getDeclaredField("stirs");
                    stirsField.setAccessible(true);
                    int stirs = stirsField.getInt(step);
                    @SuppressWarnings("unchecked")
                    var matchesField = step.getClass().getDeclaredField("matches");
                    matchesField.setAccessible(true);
                    List<Ingredient> matches = (List<Ingredient>) matchesField.get(step);
                    List<Ingredient> ingredients = new ArrayList<>(matches);
                    result.add(new StepInput(stirs, ingredients));
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Eidolon] Failed to collect steps", e);
        }
        return result;
    }

    private static boolean isBoiling(BlockEntity be) {
        return be != null && EidolonWaterSupply.isHeated(be);
    }

    // ── Water amount ──────────────────────────────────────────────

    private int readWaterAmount() {
        try {
            var m = recipe.getClass().getMethod("getWaterAmount");
            return (int) m.invoke(recipe);
        } catch (Exception e) { /* fall through */ }
        try {
            var f = recipe.getClass().getDeclaredField("waterAmount");
            f.setAccessible(true);
            return f.getInt(recipe);
        } catch (Exception e) { /* fall through */ }
        try {
            var m = recipe.getClass().getMethod("getWater");
            return (int) m.invoke(recipe);
        } catch (Exception e) { /* fall through */ }
        return 1000; // sensible default
    }

    /**
     * Check whether the crucible has enough water for the recipe.
     * Must be called BEFORE ledger commit to avoid extracting items
     * that can't be used due to insufficient water.
     */
    private boolean checkCrucibleWater() {
        if (crucible == null) return true;
        int required = readWaterAmount();
        if (required <= 0) return true;
        if (EidolonWaterSupply.ensureWater(materialAccess(), crucible, required, storageEndpoint(), player)) return true;
        int current = readCurrentWaterAmount();
        player.sendSystemMessage(Component.translatable(
                "rsi.eidolon.error.insufficient_water", current, required));
        return false;
    }

    private int readCurrentWaterAmount() {
        if (crucible == null) return Integer.MAX_VALUE;
        try {
            Field tankField = crucible.getClass().getDeclaredField("tank");
            tankField.setAccessible(true);
            Object tank = tankField.get(crucible);
            // Try getFluidAmount() first (common Forge tank pattern)
            try {
                Method m = tank.getClass().getMethod("getFluidAmount");
                return (int) m.invoke(tank);
            } catch (NoSuchMethodException e) { /* fall through */ }
            // Try getFluidInTank(0).getAmount()
            try {
                Method m = tank.getClass().getMethod("getFluidInTank", int.class);
                Object fluidStack = m.invoke(tank, 0);
                if (fluidStack != null) {
                    Method am = fluidStack.getClass().getMethod("getAmount");
                    return (int) am.invoke(fluidStack);
                }
            } catch (NoSuchMethodException e) { /* fall through */ }
            // Try IFluidHandler.getTankCapacity(0) - not useful for current amount
            // Try reading a public `amount` field on the tank
            try {
                Field f = tank.getClass().getDeclaredField("amount");
                f.setAccessible(true);
                return f.getInt(tank);
            } catch (NoSuchFieldException e) { /* fall through */ }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-Eidolon] Failed to read current water amount", e);
        }
        return Integer.MAX_VALUE; // can't determine -- don't block
    }

    // ── Refund ───────────────────────────────────────────────────

    private void refundAll() {
        ExtractionLedger activeLedger = usingSharedLedger && sharedLedger != null ? sharedLedger : ledger;
        if (activeLedger == null || !activeLedger.isCommitted()) return;
        activeLedger.refundCommittedToEndpoint(storageEndpoint(), player);
    }

    // ── Plan warnings ─────────────────────────────────────────────

    public static List<Component> getPlanWarnings(ServerPlayer player, Recipe<?> recipe,
                                               @Nullable ResourceLocation dim,
                                               @Nullable BlockPos pos) {
        List<Component> warnings = new ArrayList<>();

        boolean isCrucible = EidolonReflection.crucibleRecipeClass != null && EidolonReflection.crucibleRecipeClass.isInstance(recipe);
        boolean isRitual = EidolonReflection.ritualRecipeClass != null && EidolonReflection.ritualRecipeClass.isInstance(recipe);
        if (!isCrucible && !isRitual) return warnings;

        if (isCrucible) {
            // Water amount warning
            int water = readWaterAmountStatic(recipe);
            if (water > 0) {
                warnings.add(Component.translatable(
                        "rsi.eidolon.warn.water_required", water));
            }

            // Boiling requirement warning
            warnings.add(Component.translatable("rsi.eidolon.warn.boiling_required"));

            // If crucible is bound, check current state
            if (dim != null && pos != null) {
                try {
                    ServerLevel level = CraftPacketUtils.resolveLevel(player.server, dim, player);
                    if (level != null && level.isLoaded(pos)) {
                        BlockEntity be = level.getBlockEntity(pos);
                        if (be != null && EidolonReflection.crucibleTileEntityClass.isInstance(be)) {
                            boolean hasWater = false;
                            try {
                                Field f = be.getClass().getDeclaredField("hasWater");
                                f.setAccessible(true);
                                hasWater = f.getBoolean(be);
                            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Eidolon] reflection probe failed", e); }
                            if (!hasWater) {
                                warnings.add(Component.translatable("rsi.eidolon.warn.needs_water_fill"));
                            }
                            boolean boiling = EidolonWaterSupply.isHeated(be);
                            if (!boiling) {
                                warnings.add(Component.translatable("rsi.eidolon.warn.needs_heat"));
                            }
                        }
                    }
                } catch (Exception e) {
                    RSIntegrationMod.LOGGER.debug("[RSI-Batch-Eidolon] Plan warning check failed", e);
                }
            }
        }

        if (isRitual) {
            // Number of pedestal / focus items required
            try {
                Field pf = EidolonReflection.ritualRecipeClass.getField("pedestalItems");
                @SuppressWarnings("unchecked")
                List<Ingredient> pi = (List<Ingredient>) pf.get(recipe);
                if (pi != null && !pi.isEmpty()) {
                    warnings.add(Component.translatable(
                            "rsi.eidolon.warn.pedestal_items", pi.size()));
                }
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Eidolon] reflection probe failed", e); }

            warnings.add(Component.translatable("rsi.eidolon.warn.needs_focus"));

            if (dim != null && pos != null) {
                try {
                    ServerLevel level = CraftPacketUtils.resolveLevel(player.server, dim, player);
                    if (level != null && level.isLoaded(pos)) {
                        BlockEntity be = level.getBlockEntity(pos);
                        if (be != null && EidolonReflection.brazierTileEntityClass != null
                                && EidolonReflection.brazierTileEntityClass.isInstance(be)) {
                            boolean burning = false;
                            try {
                                Field bf = EidolonReflection.brazierTileEntityClass.getDeclaredField("burning");
                                bf.setAccessible(true);
                                burning = bf.getBoolean(be);
                            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Eidolon] reflection probe failed", e); }
                            if (burning) {
                                warnings.add(Component.translatable("rsi.eidolon.error.ritual_busy"));
                            }
                        }
                    }
                } catch (Exception e) {
                    RSIntegrationMod.LOGGER.debug("[RSI-Batch-Eidolon] Plan warning check failed", e);
                }
            }
        }

        return warnings;
    }

    static int readWaterAmountStatic(Recipe<?> recipe) {
        try {
            Method m = recipe.getClass().getMethod("getWaterAmount");
            return (int) m.invoke(recipe);
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Eidolon] reflection probe failed", e); }
        try {
            Field f = recipe.getClass().getDeclaredField("waterAmount");
            f.setAccessible(true);
            return f.getInt(recipe);
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Eidolon] reflection probe failed", e); }
        try {
            Method m = recipe.getClass().getMethod("getWater");
            return (int) m.invoke(recipe);
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Eidolon] reflection probe failed", e); }
        return 1000;
    }

    // ── Inner types ──────────────────────────────────────────────

    private static final class StepInput {
        final int stirs;
        final List<Ingredient> ingredients;

        StepInput(int stirs, List<Ingredient> ingredients) {
            this.stirs = stirs;
            this.ingredients = ingredients;
        }
    }
}
