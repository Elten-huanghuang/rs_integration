package com.huanghuang.rsintegration.mods.eidolon;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.util.ChunkUtils;

import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import com.huanghuang.rsintegration.network.RSIntegrationNetwork;
import com.huanghuang.rsintegration.reflection.probes.EidolonReflection;
import com.refinedmods.refinedstorage.api.network.INetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import javax.annotation.Nullable;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

/** Batch delegate for Eidolon Worktable and Crucible. */
public final class EidolonBatchDelegate extends AbstractBatchDelegate {

    // ── Shared class refs (resolved from probe) ─────────────────
    private static volatile java.lang.reflect.Field boilingField;
    private static volatile java.lang.reflect.Field stepsField;

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
    private Object crucible;             // CrucibleTileEntity (null for worktable/ritual)
    private Recipe<?> recipe;            // CrucibleRecipe or WorktableRecipe or RitualRecipe
    private boolean isWorktable;         // true = worktable mode, no BE interaction
    private boolean isRitual;            // true = brazier ritual mode
    private Object brazier;              // BrazierTileEntity (null except ritual mode)
    private ItemStack pendingResult;      // Stored result for collectResult()
    private boolean craftCompleted;       // Flag set after instant craft

    // ── IBatchDelegate impl ───────────────────────────────────────

    @Override
    public boolean validateAndInit(ServerPlayer player, ResourceLocation recipeId,
                                   @Nullable ResourceLocation dim, BlockPos pos) {

        ServerLevel level = CraftPacketUtils.resolveLevel(player.server, dim, player);
        if (level == null) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.dim_not_found"));
            return false;
        }
        this.myDim = level.dimension();
        this.myPos = pos;
        this.player = player;

        ChunkUtils.loadChunk(level, pos);
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
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-Eidolon] validateAndInit OK (worktable): recipe={}", recipeId);
            return true;
        }

        // Ritual mode (Brazier)
        boolean isRitualRecipe = EidolonReflection.ritualRecipeClass != null && EidolonReflection.ritualRecipeClass.isInstance(foundRecipe);
        boolean isBrazier = EidolonReflection.brazierTileEntityClass != null && be != null && EidolonReflection.brazierTileEntityClass.isInstance(be);
        if (isRitualRecipe && isBrazier) {
            Object currentRitual = null;
            try {
                java.lang.reflect.Field f = EidolonReflection.brazierTileEntityClass.getDeclaredField("ritual");
                f.setAccessible(true);
                currentRitual = f.get(be);
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Eidolon] reflection probe failed", e); }
            if (currentRitual != null) {
                player.sendSystemMessage(Component.translatable("rsi.eidolon.error.ritual_busy"));
                return false;
            }
            boolean burning = false;
            try {
                java.lang.reflect.Field f = EidolonReflection.brazierTileEntityClass.getDeclaredField("burning");
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
            this.pendingResult = ItemStack.EMPTY;
            this.craftCompleted = false;
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

        boolean boiling = false;
        try {
            if (boilingField != null) boiling = boilingField.getBoolean(be);
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Batch-Eidolon] Reflection probe failed", e); }

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
            player.sendSystemMessage(Component.translatable("rsi.eidolon.warn.needs_water"));
            try {
                be.getClass().getMethod("fill").invoke(be);
                hasWater = true;
                player.sendSystemMessage(Component.translatable("rsi.eidolon.info.auto_filled"));
            } catch (Exception ex) {
                player.sendSystemMessage(Component.translatable("rsi.eidolon.error.fill_failed"));
                return false;
            }
        }

        if (!boiling) {
            player.sendSystemMessage(Component.translatable("rsi.eidolon.warn.needs_heat"));
            return false;
        }

        if (!stepsEmpty) {
            player.sendSystemMessage(Component.translatable("rsi.eidolon.warn.steps_not_empty"));
            return false;
        }

        this.pendingResult = ItemStack.EMPTY;
        this.craftCompleted = false;

        RSIntegrationMod.LOGGER.debug("[RSI-Batch-Eidolon] validateAndInit OK: recipe={}", recipeId);
        return true;
    }

    @Override
    public boolean tryStartSingleCraft(ServerPlayer player) {
        this.player = player;
        this.ledger = new ExtractionLedger();
        this.network = CraftPacketUtils.resolveNetworkForCraft(player, myDim, myPos);

        // Ritual mode
        if (isRitual) {
            return tryStartRitualCraft();
        }

        // Worktable mode: extract ingredients, produce output directly
        if (isWorktable) {
            return tryStartWorktableCraft();
        }

        // Verify the cached BlockEntity is still valid
        if (myPos != null && player.serverLevel().isLoaded(myPos)) {
            BlockEntity current = player.serverLevel().getBlockEntity(myPos);
            if (current == null || current.isRemoved()) {
                player.sendSystemMessage(Component.translatable("rsi.error.machine_missing"));
                if (ledger != null && ledger.isCommitted()) {
                    ledger.refundCommitted(network, player);
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

        boolean boiling = false;
        try {
            if (boilingField != null) boiling = boilingField.getBoolean(crucible);
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Batch-Eidolon] Reflection probe failed", e); }

        boolean stepsEmpty = true;
        try {
            if (stepsField != null) {
                List<?> steps = (List<?>) stepsField.get(crucible);
                stepsEmpty = steps == null || steps.isEmpty();
            }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Batch-Eidolon] Reflection probe failed", e); }

        if (!hasWater) {
            try {
                crucible.getClass().getMethod("fill").invoke(crucible);
            } catch (Exception ex) {
                return false;
            }
        }

        if (!boiling) return false;
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
        if (!ledger.commit(network, player)) {
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
            java.lang.reflect.Field tankField = crucible.getClass().getDeclaredField("tank");
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
            crucible.getClass().getMethod("setChanged").invoke(crucible);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Eidolon] Crucible drain/clear failed", e);
            return false;
        }

        this.craftCompleted = true;
        return true;
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
        if (!ledger.commit(network, player)) {
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
                    if (network != null) {
                        network.insertItem(remainder, remainder.getCount(),
                                com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                    } else {
                        ItemHandlerHelper.giveItemToPlayer(player, remainder);
                    }
                }
            }
        }

        this.craftCompleted = true;
        return true;
    }

    // ── Ritual (Brazier / Crystal Ritual) crafting ──────────────

    private boolean tryStartRitualCraft() {
        if (brazier == null || recipe == null) return false;

        ServerLevel level = player.serverLevel();
        if (myPos != null && level.isLoaded(myPos)) {
            BlockEntity current = level.getBlockEntity(myPos);
            if (current == null || current.isRemoved()
                    || !EidolonReflection.brazierTileEntityClass.isInstance(current)) {
                player.sendSystemMessage(Component.translatable("rsi.error.machine_missing"));
                return false;
            }
            this.brazier = current;
        }

        // Check brazier is not busy
        try {
            java.lang.reflect.Field f = EidolonReflection.brazierTileEntityClass.getDeclaredField("ritual");
            f.setAccessible(true);
            if (f.get(brazier) != null) return false;
            java.lang.reflect.Field bf = EidolonReflection.brazierTileEntityClass.getDeclaredField("burning");
            bf.setAccessible(true);
            if (bf.getBoolean(brazier)) return false;
        } catch (Exception e) { return false; }

        // Extract reagent from RS
        Ingredient reagent = getRitualReagent();
        if (reagent == null || reagent.isEmpty()) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Eidolon] No reagent in ritual recipe: {}", recipe.getId());
            return false;
        }
        ItemStack reagentStack = CraftPacketUtils.ensureMaterialAvailable(
                player, myDim, myPos, reagent, 1, ledger);
        if (reagentStack.isEmpty()) return false;

        if (!ledger.commit(network, player)) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Eidolon] Ritual ledger commit failed");
            return false;
        }

        // Place reagent on brazier
        try {
            java.lang.reflect.Method setStack = brazier.getClass().getMethod("setStack", ItemStack.class);
            setStack.invoke(brazier, reagentStack.copy());
            brazier.getClass().getMethod("setChanged").invoke(brazier);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Eidolon] Failed to place reagent on brazier", e);
            refundAll();
            ledger = null;
            return false;
        }

        // Start burning
        try {
            java.lang.reflect.Method startBurning = EidolonReflection.brazierTileEntityClass.getMethod(
                    "startBurning", net.minecraft.world.entity.player.Player.class,
                    Level.class, BlockPos.class);
            startBurning.invoke(brazier, player, level, myPos);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Eidolon] Failed to start brazier burning", e);
            refundAll();
            ledger = null;
            return false;
        }

        // Store expected result (fallback in case ItemEntity collection fails)
        try {
            this.pendingResult = recipe.getResultItem(level.registryAccess()).copy();
        } catch (Exception ex) {
            this.pendingResult = ItemStack.EMPTY;
        }

        this.craftCompleted = false;
        RSIntegrationMod.LOGGER.debug("[RSI-Batch-Eidolon] Ritual started: recipe={} reagent={}",
                recipe.getId(), reagentStack.getHoverName().getString());
        return true;
    }

    private Ingredient getRitualReagent() {
        try {
            java.lang.reflect.Field f = EidolonReflection.ritualRecipeClass.getField("reagent");
            return (Ingredient) f.get(recipe);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-Eidolon] getRitualReagent failed", e);
            return null;
        }
    }

    private List<Ingredient> getRitualPedestalItems() {
        try {
            java.lang.reflect.Field f = EidolonReflection.ritualRecipeClass.getField("pedestalItems");
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
            java.lang.reflect.Field f = EidolonReflection.ritualRecipeClass.getField("focusItems");
            @SuppressWarnings("unchecked")
            List<Ingredient> items = (List<Ingredient>) f.get(recipe);
            return items;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Eidolon] Failed to read focus items", e);
            return null;
        }
    }

    private boolean tryStartRitualWithMaterials(ServerPlayer player, List<ItemStack> materials) {
        if (brazier == null || recipe == null || materials.isEmpty()) return false;

        ServerLevel level = player.serverLevel();
        if (myPos != null && level.isLoaded(myPos)) {
            BlockEntity current = level.getBlockEntity(myPos);
            if (current == null || current.isRemoved()
                    || !EidolonReflection.brazierTileEntityClass.isInstance(current)) {
                player.sendSystemMessage(Component.translatable("rsi.error.machine_missing"));
                return false;
            }
            this.brazier = current;
        }

        // Check brazier not busy
        try {
            java.lang.reflect.Field f = EidolonReflection.brazierTileEntityClass.getDeclaredField("ritual");
            f.setAccessible(true);
            if (f.get(brazier) != null) return false;
            java.lang.reflect.Field bf = EidolonReflection.brazierTileEntityClass.getDeclaredField("burning");
            bf.setAccessible(true);
            if (bf.getBoolean(brazier)) return false;
        } catch (Exception e) { return false; }

        // First material is reagent
        ItemStack reagentStack = materials.get(0).copy();
        reagentStack.setCount(1);
        try {
            java.lang.reflect.Method setStack = brazier.getClass().getMethod("setStack", ItemStack.class);
            setStack.invoke(brazier, reagentStack);
            brazier.getClass().getMethod("setChanged").invoke(brazier);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Eidolon] Failed to place reagent on brazier", e);
            return false;
        }

        // Start burning
        try {
            java.lang.reflect.Method startBurning = EidolonReflection.brazierTileEntityClass.getMethod(
                    "startBurning", net.minecraft.world.entity.player.Player.class,
                    Level.class, BlockPos.class);
            startBurning.invoke(brazier, player, level, myPos);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Eidolon] Failed to start brazier burning", e);
            return false;
        }

        try {
            this.pendingResult = recipe.getResultItem(level.registryAccess()).copy();
        } catch (Exception ex) {
            this.pendingResult = ItemStack.EMPTY;
        }
        this.craftCompleted = false;
        return true;
    }

    // ── Worktable ingredient helpers ────────────────────────────

    private List<Ingredient> getWorktableCoreIngredients() {
        try {
            Ingredient[] core = (Ingredient[]) recipe.getClass()
                    .getMethod("getCore").invoke(recipe);
            return core != null ? java.util.Arrays.asList(core) : null;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-Eidolon] getCore failed", e);
            return null;
        }
    }

    private List<Ingredient> getWorktableOuterIngredients() {
        try {
            Ingredient[] outer = (Ingredient[]) recipe.getClass()
                    .getMethod("getOuter").invoke(recipe);
            return outer != null ? java.util.Arrays.asList(outer) : null;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-Eidolon] getOuter failed", e);
            return null;
        }
    }

    // ── Chain support: pre-reserved materials ────────────────────

    @Override
    @Nullable
    public List<IngredientSpec> getRequiredMaterials() {
        if (recipe == null) return null;
        if (isRitual) {
            List<IngredientSpec> specs = new ArrayList<>();
            Ingredient reagent = getRitualReagent();
            if (reagent != null && !reagent.isEmpty()) specs.add(new IngredientSpec(reagent, 1));
            List<Ingredient> pedestal = getRitualPedestalItems();
            if (pedestal != null) {
                for (Ingredient ing : pedestal)
                    if (!ing.isEmpty()) specs.add(new IngredientSpec(ing, 1));
            }
            List<Ingredient> focus = getRitualFocusItems();
            if (focus != null) {
                for (Ingredient ing : focus)
                    if (!ing.isEmpty()) specs.add(new IngredientSpec(ing, 1));
            }
            return specs.isEmpty() ? null : specs;
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
    public boolean tryStartWithMaterials(ServerPlayer player, List<ItemStack> materials,
                                         ExtractionLedger sharedLedger) {
        this.player = player;
        this.sharedLedger = sharedLedger;
        this.usingSharedLedger = true;

        // Ritual mode: materials were pre-extracted by chain.
        // First item is reagent, rest are pedestal/focus items (not consumed).
        if (isRitual) {
            return tryStartRitualWithMaterials(player, materials);
        }

        // Worktable mode: materials (core + extras) were pre-extracted by chain.
        // Apply crafting remainders: items with hasCraftingRemainingItem() leave
        // getCraftingRemainingItem() behind; everything else is consumed.
        if (isWorktable) {
            INetwork net = RSIntegrationNetwork.resolveNetworkFromPlayer(player);
            for (ItemStack mat : materials) {
                if (mat.hasCraftingRemainingItem()) {
                    ItemStack remainder = mat.getCraftingRemainingItem();
                    if (!remainder.isEmpty()) {
                        if (net != null) {
                            net.insertItem(remainder, remainder.getCount(),
                                    com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                        } else {
                            ItemHandlerHelper.giveItemToPlayer(player, remainder);
                        }
                    }
                }
            }
            try {
                this.pendingResult = recipe.getResultItem(player.serverLevel().registryAccess()).copy();
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.error("[RSI-Batch-Eidolon] Failed to get worktable result:", e);
                return false;
            }
            this.craftCompleted = true;
            return true;
        }

        // Verify the cached BlockEntity is still valid
        if (myPos != null && player.serverLevel().isLoaded(myPos)) {
            BlockEntity current = player.serverLevel().getBlockEntity(myPos);
            if (current == null || current.isRemoved()) {
                player.sendSystemMessage(Component.translatable("rsi.error.machine_missing"));
                if (ledger != null && ledger.isCommitted()) {
                    ledger.refundCommitted(network, player);
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
        boolean boiling = false;
        try {
            if (boilingField != null) boiling = boilingField.getBoolean(crucible);
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Batch-Eidolon] Reflection probe failed", e); }
        boolean stepsEmpty = true;
        try {
            if (stepsField != null) {
                List<?> steps = (List<?>) stepsField.get(crucible);
                stepsEmpty = steps == null || steps.isEmpty();
            }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Batch-Eidolon] Reflection probe failed", e); }

        if (!hasWater) {
            try {
                crucible.getClass().getMethod("fill").invoke(crucible);
            } catch (Exception ex) {
                return false;
            }
        }
        if (!boiling) return false;
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
            java.lang.reflect.Field tankField = crucible.getClass().getDeclaredField("tank");
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
            crucible.getClass().getMethod("setChanged").invoke(crucible);
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
            if (craftCompleted) return true;
            // Check ritualDone flag on brazier
            try {
                java.lang.reflect.Field f = EidolonReflection.brazierTileEntityClass.getDeclaredField("ritualDone");
                f.setAccessible(true);
                if (f.getBoolean(be)) return true;
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Eidolon] reflection probe failed", e); }
            // Check item entity above brazier
            BlockPos pos = be.getBlockPos();
            for (net.minecraft.world.entity.item.ItemEntity entity :
                    level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                            new net.minecraft.world.phys.AABB(
                                    pos.getX() - 0.5, pos.getY() + 2.0, pos.getZ() - 0.5,
                                    pos.getX() + 1.5, pos.getY() + 3.5, pos.getZ() + 1.5))) {
                if (!entity.getItem().isEmpty()) return true;
            }
            return false;
        }
        // Crucible & worktable crafts are instant
        return craftCompleted;
    }

    @Override
    public ItemStack collectResult(ServerPlayer player) {
        if (isRitual) return collectRitualResult(player);
        ItemStack result = pendingResult.copy();
        pendingResult = ItemStack.EMPTY;
        craftCompleted = false;
        return result;
    }

    private ItemStack collectRitualResult(ServerPlayer player) {
        // Priority 1: Collect ItemEntity above brazier
        ServerLevel level = player.serverLevel();
        if (myPos != null) {
            List<net.minecraft.world.entity.item.ItemEntity> entities =
                    level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                            new net.minecraft.world.phys.AABB(
                                    myPos.getX() - 0.5, myPos.getY() + 2.0, myPos.getZ() - 0.5,
                                    myPos.getX() + 1.5, myPos.getY() + 3.5, myPos.getZ() + 1.5));
            for (net.minecraft.world.entity.item.ItemEntity entity : entities) {
                ItemStack stack = entity.getItem().copy();
                if (!stack.isEmpty()) {
                    entity.discard();
                    RSIntegrationMod.LOGGER.debug("[RSI-Batch-Eidolon] Collected ritual ItemEntity: {} x{}",
                            stack.getHoverName().getString(), stack.getCount());
                    craftCompleted = false;
                    pendingResult = ItemStack.EMPTY;
                    return stack;
                }
            }
        }
        // Priority 2: Fall back to recipe result
        ItemStack result = pendingResult.copy();
        pendingResult = ItemStack.EMPTY;
        craftCompleted = false;
        RSIntegrationMod.LOGGER.debug("[RSI-Batch-Eidolon] Collected ritual result from recipe: {}",
                result.isEmpty() ? "EMPTY" : result.getHoverName().getString());
        return result;
    }

    @Override
    protected void clearMachineState(BlockEntity be, ServerPlayer player) {
        refundAll();
        if (isRitual) cleanupBrazier();
        pendingResult = ItemStack.EMPTY;
        craftCompleted = false;
        resetState();
    }

    @Override
    public void onBatchFinished(ServerPlayer player) {
        if (isRitual) cleanupBrazier();
        pendingResult = ItemStack.EMPTY;
        craftCompleted = false;
        resetState();
    }

    private void cleanupBrazier() {
        if (brazier == null || myPos == null) return;
        try {
            // Clear reagent from brazier if ritual didn't consume it
            java.lang.reflect.Method setStack = brazier.getClass().getMethod("setStack", ItemStack.class);
            setStack.invoke(brazier, ItemStack.EMPTY);
            brazier.getClass().getMethod("setChanged").invoke(brazier);

            java.lang.reflect.Field f = EidolonReflection.brazierTileEntityClass.getDeclaredField("ritualDone");
            f.setAccessible(true);
            f.setBoolean(brazier, false);

            java.lang.reflect.Field rf = EidolonReflection.brazierTileEntityClass.getDeclaredField("ritual");
            rf.setAccessible(true);
            rf.set(brazier, null);

            java.lang.reflect.Field bf = EidolonReflection.brazierTileEntityClass.getDeclaredField("burning");
            bf.setAccessible(true);
            bf.setBoolean(brazier, false);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-Eidolon] cleanupBrazier failed", e);
        }
        brazier = null;
    }

    @Override
    public BlockPos getMachinePos() {
        return myPos;
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
        int current = readCurrentWaterAmount();
        if (current < required) {
            player.sendSystemMessage(Component.translatable(
                    "rsi.eidolon.error.insufficient_water",
                    current, required));
            return false;
        }
        return true;
    }

    private int readCurrentWaterAmount() {
        if (crucible == null) return Integer.MAX_VALUE;
        try {
            java.lang.reflect.Field tankField = crucible.getClass().getDeclaredField("tank");
            tankField.setAccessible(true);
            Object tank = tankField.get(crucible);
            // Try getFluidAmount() first (common Forge tank pattern)
            try {
                java.lang.reflect.Method m = tank.getClass().getMethod("getFluidAmount");
                return (int) m.invoke(tank);
            } catch (NoSuchMethodException e) { /* fall through */ }
            // Try getFluidInTank(0).getAmount()
            try {
                java.lang.reflect.Method m = tank.getClass().getMethod("getFluidInTank", int.class);
                Object fluidStack = m.invoke(tank, 0);
                if (fluidStack != null) {
                    java.lang.reflect.Method am = fluidStack.getClass().getMethod("getAmount");
                    return (int) am.invoke(fluidStack);
                }
            } catch (NoSuchMethodException e) { /* fall through */ }
            // Try IFluidHandler.getTankCapacity(0) - not useful for current amount
            // Try reading a public `amount` field on the tank
            try {
                java.lang.reflect.Field f = tank.getClass().getDeclaredField("amount");
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
        if (ledger == null || !ledger.isCommitted()) return;
        ledger.refundCommitted(network, player);
    }

    // ── Plan warnings ─────────────────────────────────────────────

    public static List<String> getPlanWarnings(ServerPlayer player, Recipe<?> recipe,
                                               @Nullable ResourceLocation dim,
                                               @Nullable BlockPos pos) {
        List<String> warnings = new ArrayList<>();

        boolean isCrucible = EidolonReflection.crucibleRecipeClass != null && EidolonReflection.crucibleRecipeClass.isInstance(recipe);
        boolean isRitual = EidolonReflection.ritualRecipeClass != null && EidolonReflection.ritualRecipeClass.isInstance(recipe);
        if (!isCrucible && !isRitual) return warnings;

        if (isCrucible) {
            // Water amount warning
            int water = readWaterAmountStatic(recipe);
            if (water > 0) {
                warnings.add(Component.translatable(
                        "rsi.eidolon.warn.water_required", water).getString());
            }

            // Boiling requirement warning
            warnings.add(Component.translatable("rsi.eidolon.warn.boiling_required").getString());

            // If crucible is bound, check current state
            if (dim != null && pos != null) {
                try {
                    ServerLevel level = CraftPacketUtils.resolveLevel(player.server, dim, player);
                    if (level != null && level.isLoaded(pos)) {
                        BlockEntity be = level.getBlockEntity(pos);
                        if (be != null && EidolonReflection.crucibleTileEntityClass.isInstance(be)) {
                            boolean hasWater = false;
                            try {
                                java.lang.reflect.Field f = be.getClass().getDeclaredField("hasWater");
                                f.setAccessible(true);
                                hasWater = f.getBoolean(be);
                            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Eidolon] reflection probe failed", e); }
                            if (!hasWater) {
                                warnings.add(Component.translatable("rsi.eidolon.warn.needs_water_fill").getString());
                            }
                            boolean boiling = false;
                            try {
                                if (boilingField != null) boiling = boilingField.getBoolean(be);
                            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Eidolon] reflection probe failed", e); }
                            if (!boiling) {
                                warnings.add(Component.translatable("rsi.eidolon.warn.needs_heat").getString());
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
                java.lang.reflect.Field pf = EidolonReflection.ritualRecipeClass.getField("pedestalItems");
                @SuppressWarnings("unchecked")
                List<Ingredient> pi = (List<Ingredient>) pf.get(recipe);
                if (pi != null && !pi.isEmpty()) {
                    warnings.add(Component.translatable(
                            "rsi.eidolon.warn.pedestal_items", pi.size()).getString());
                }
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Eidolon] reflection probe failed", e); }

            warnings.add(Component.translatable("rsi.eidolon.warn.needs_focus").getString());

            if (dim != null && pos != null) {
                try {
                    ServerLevel level = CraftPacketUtils.resolveLevel(player.server, dim, player);
                    if (level != null && level.isLoaded(pos)) {
                        BlockEntity be = level.getBlockEntity(pos);
                        if (be != null && EidolonReflection.brazierTileEntityClass != null
                                && EidolonReflection.brazierTileEntityClass.isInstance(be)) {
                            boolean burning = false;
                            try {
                                java.lang.reflect.Field bf = EidolonReflection.brazierTileEntityClass.getDeclaredField("burning");
                                bf.setAccessible(true);
                                burning = bf.getBoolean(be);
                            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Eidolon] reflection probe failed", e); }
                            if (burning) {
                                warnings.add(Component.translatable("rsi.eidolon.error.ritual_busy").getString());
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
            java.lang.reflect.Method m = recipe.getClass().getMethod("getWaterAmount");
            return (int) m.invoke(recipe);
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Eidolon] reflection probe failed", e); }
        try {
            java.lang.reflect.Field f = recipe.getClass().getDeclaredField("waterAmount");
            f.setAccessible(true);
            return f.getInt(recipe);
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Eidolon] reflection probe failed", e); }
        try {
            java.lang.reflect.Method m = recipe.getClass().getMethod("getWater");
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
