package com.huanghuang.rsintegration.mods.eidolon;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.network.AltarBindingRegistry;
import com.huanghuang.rsintegration.network.RSIntegration;
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


public final class EidolonBatchDelegate extends AbstractBatchDelegate {

    // ── Shared class refs ────────────────────────────────────────
    private static volatile Class<?> crucibleRecipeClass;
    private static volatile Class<?> crucibleRecipeStepClass;
    private static volatile Class<?> crucibleTileEntityClass;
    private static volatile Class<?> crucibleStepInnerClass;
    private static volatile Class<?> worktableBlockClass;
    private static volatile Class<?> worktableRecipeClass;
    private static volatile java.lang.reflect.Field boilingField;
    private static volatile java.lang.reflect.Field stepsField;

    private static void ensureClasses() {
        if (!com.huanghuang.rsintegration.util.ModClassLoader.ensureClasses("eidolon",
                "elucent.eidolon.recipe.CrucibleRecipe",
                "elucent.eidolon.recipe.CrucibleRecipe$Step",
                "elucent.eidolon.common.tile.CrucibleTileEntity",
                "elucent.eidolon.common.tile.CrucibleTileEntity$CrucibleStep",
                "elucent.eidolon.common.block.WorktableBlock",
                "elucent.eidolon.recipe.WorktableRecipe")) return;
        try {
            crucibleRecipeClass = Class.forName("elucent.eidolon.recipe.CrucibleRecipe");
            crucibleRecipeStepClass = Class.forName("elucent.eidolon.recipe.CrucibleRecipe$Step");
            crucibleTileEntityClass = Class.forName("elucent.eidolon.common.tile.CrucibleTileEntity");
            crucibleStepInnerClass = Class.forName(
                    "elucent.eidolon.common.tile.CrucibleTileEntity$CrucibleStep");

            try {
                boilingField = crucibleTileEntityClass.getDeclaredField("boiling");
                boilingField.setAccessible(true);
            } catch (NoSuchFieldException e) {
                RSIntegrationMod.LOGGER.warn("[RSI-Batch-Eidolon] boiling field not found");
            }

            try {
                stepsField = crucibleTileEntityClass.getDeclaredField("steps");
                stepsField.setAccessible(true);
            } catch (NoSuchFieldException e) {
                RSIntegrationMod.LOGGER.warn("[RSI-Batch-Eidolon] steps field not found");
            }
        } catch (ClassNotFoundException e) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Eidolon] Failed to load Eidolon classes", e);
        }
        try {
            worktableBlockClass = Class.forName("elucent.eidolon.common.block.WorktableBlock");
        } catch (ClassNotFoundException ignored) {}
        try {
            worktableRecipeClass = Class.forName("elucent.eidolon.recipe.WorktableRecipe");
        } catch (ClassNotFoundException ignored) {}
    }

    // ── Instance state ───────────────────────────────────────────
    private ServerPlayer player;
    private ResourceKey<Level> myDim;
    private BlockPos myPos;
    private Object crucible;             // CrucibleTileEntity (null for worktable)
    private Recipe<?> recipe;            // CrucibleRecipe or WorktableRecipe
    private boolean isWorktable;         // true = worktable mode, no BE interaction
    private ItemStack pendingResult;      // Stored result for collectResult()
    private boolean craftCompleted;       // Flag set after instant craft

    // ── IBatchDelegate impl ───────────────────────────────────────

    @Override
    public boolean validateAndInit(ServerPlayer player, ResourceLocation recipeId,
                                   @Nullable ResourceLocation dim, BlockPos pos) {
        ensureClasses();

        ServerLevel level = CraftPacketUtils.resolveLevel(player.server, dim, player);
        if (level == null) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.dim_not_found"));
            return false;
        }
        this.myDim = level.dimension();
        this.myPos = pos;
        this.player = player;

        com.huanghuang.rsintegration.util.ChunkUtils.loadChunk(level, pos);
        BlockEntity be = level.getBlockEntity(pos);
        var blockState = level.getBlockState(pos);

        Recipe<?> foundRecipe = level.getRecipeManager().byKey(recipeId).orElse(null);
        if (foundRecipe == null) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.recipe_not_found", recipeId.toString()));
            return false;
        }
        this.recipe = foundRecipe;

        // Detect worktable mode
        boolean isWtRecipe = worktableRecipeClass != null && worktableRecipeClass.isInstance(foundRecipe);
        boolean isWtBlock = worktableBlockClass != null && worktableBlockClass.isInstance(blockState.getBlock());
        this.isWorktable = isWtRecipe || isWtBlock;

        if (isWorktable) {
            if (!isWtRecipe) {
                player.sendSystemMessage(Component.literal("§c" + Component.translatable("rsi.generic.error.wrong_recipe_type").getString()
                        + " [" + recipeId + " expected=WorktableRecipe got=" + foundRecipe.getClass().getSimpleName() + "]"));
                return false;
            }
            this.crucible = null;
            this.pendingResult = ItemStack.EMPTY;
            this.craftCompleted = false;
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-Eidolon] validateAndInit OK (worktable): recipe={}", recipeId);
            return true;
        }

        // Crucible mode
        if (crucibleTileEntityClass == null || crucibleRecipeClass == null) {
            player.sendSystemMessage(Component.translatable("rsi.batch.error.mod_missing", "Eidolon"));
            return false;
        }
        if (!crucibleRecipeClass.isInstance(foundRecipe)) {
            player.sendSystemMessage(Component.literal("§c" + Component.translatable("rsi.generic.error.wrong_recipe_type").getString()
                    + " [" + recipeId + " expected=CrucibleRecipe got=" + foundRecipe.getClass().getSimpleName() + "]"));
            return false;
        }
        if (be == null || !crucibleTileEntityClass.isInstance(be)) {
            player.sendSystemMessage(Component.translatable("rsi.eidolon.error.crucible_not_found"));
            return false;
        }
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

                Constructor<?> ctor = crucibleStepInnerClass.getConstructor(int.class, List.class);
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
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Batch-Eidolon] Reflection probe failed", e); }

        try {
            if (stepsField != null) stepsField.set(crucible, new ArrayList<>());
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Batch-Eidolon] Reflection probe failed", e); }

        try {
            crucible.getClass().getMethod("setChanged").invoke(crucible);
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Batch-Eidolon] Reflection probe failed", e); }

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

    // ── Worktable ingredient helpers ────────────────────────────

    @Nullable
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

    @Nullable
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

        // Worktable mode: materials (core + extras) were pre-extracted by chain.
        // Apply crafting remainders: items with hasCraftingRemainingItem() leave
        // getCraftingRemainingItem() behind; everything else is consumed.
        if (isWorktable) {
            INetwork net = RSIntegration.resolveNetworkFromPlayer(player);
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
                Constructor<?> ctor = crucibleStepInnerClass.getConstructor(int.class, List.class);
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
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Batch-Eidolon] Reflection probe failed", e); }

        try {
            if (stepsField != null) stepsField.set(crucible, new ArrayList<>());
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Batch-Eidolon] Reflection probe failed", e); }

        try {
            crucible.getClass().getMethod("setChanged").invoke(crucible);
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Batch-Eidolon] Reflection probe failed", e); }

        this.craftCompleted = true;
        return true;
    }

    @Override
    public boolean isCraftComplete(ServerLevel level) {
        // Eidolon crafts are instant
        return craftCompleted;
    }

    @Override
    public ItemStack collectResult(ServerPlayer player) {
        ItemStack result = pendingResult.copy();
        pendingResult = ItemStack.EMPTY;
        craftCompleted = false;
        return result;
    }

    @Override
    public void onBatchFailed(ServerPlayer player, String reason) {
        if (!usingSharedLedger) {
            refundAll();
        }
        pendingResult = ItemStack.EMPTY;
        craftCompleted = false;
        resetState();
    }

    @Override
    public void onBatchFinished(ServerPlayer player) {
        pendingResult = ItemStack.EMPTY;
        craftCompleted = false;
        resetState();
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
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-Eidolon] Failed to read current water amount", e);
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
        ensureClasses();
        if (crucibleRecipeClass == null || !crucibleRecipeClass.isInstance(recipe))
            return warnings;

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
                    if (be != null && crucibleTileEntityClass.isInstance(be)) {
                        boolean hasWater = false;
                        try {
                            java.lang.reflect.Field f = be.getClass().getDeclaredField("hasWater");
                            f.setAccessible(true);
                            hasWater = f.getBoolean(be);
                        } catch (Exception ignored) {}
                        if (!hasWater) {
                            warnings.add(Component.translatable("rsi.eidolon.warn.needs_water_fill").getString());
                        }
                        boolean boiling = false;
                        try {
                            if (boilingField != null) boiling = boilingField.getBoolean(be);
                        } catch (Exception ignored) {}
                        if (!boiling) {
                            warnings.add(Component.translatable("rsi.eidolon.warn.needs_heat").getString());
                        }
                    }
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.debug("[RSI-Batch-Eidolon] Plan warning check failed", e);
            }
        }

        return warnings;
    }

    static int readWaterAmountStatic(Recipe<?> recipe) {
        try {
            java.lang.reflect.Method m = recipe.getClass().getMethod("getWaterAmount");
            return (int) m.invoke(recipe);
        } catch (Exception ignored) {}
        try {
            java.lang.reflect.Field f = recipe.getClass().getDeclaredField("waterAmount");
            f.setAccessible(true);
            return f.getInt(recipe);
        } catch (Exception ignored) {}
        try {
            java.lang.reflect.Method m = recipe.getClass().getMethod("getWater");
            return (int) m.invoke(recipe);
        } catch (Exception ignored) {}
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
