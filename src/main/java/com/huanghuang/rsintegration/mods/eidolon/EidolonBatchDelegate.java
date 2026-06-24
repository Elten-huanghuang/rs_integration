package com.huanghuang.rsintegration.mods.eidolon;

import com.huanghuang.rsintegration.RSIntegrationMod;
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


public final class EidolonBatchDelegate implements IBatchDelegate {

    // ── Shared class refs ────────────────────────────────────────
    private static volatile boolean classesLoaded;
    private static volatile Class<?> crucibleRecipeClass;
    private static volatile Class<?> crucibleRecipeStepClass;
    private static volatile Class<?> crucibleTileEntityClass;
    private static volatile Class<?> crucibleStepInnerClass;
    private static volatile java.lang.reflect.Field boilingField;
    private static volatile java.lang.reflect.Field stepsField;

    private static void ensureClasses() {
        if (classesLoaded) return;
        classesLoaded = true;
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
    }

    // ── Instance state ───────────────────────────────────────────
    private ServerPlayer player;
    private ResourceKey<Level> myDim;
    private BlockPos myPos;
    private Object crucible;             // CrucibleTileEntity
    private Recipe<?> recipe;            // CrucibleRecipe
    private ExtractionLedger ledger;
    private ExtractionLedger sharedLedger;
    private INetwork network;
    private ItemStack pendingResult;      // Stored result for collectResult()
    private boolean craftCompleted;       // Flag set after instant craft
    private boolean usingSharedLedger;

    // ── IBatchDelegate impl ───────────────────────────────────────

    @Override
    public boolean validateAndInit(ServerPlayer player, ResourceLocation recipeId,
                                   @Nullable ResourceLocation dim, BlockPos pos) {
        ensureClasses();
        if (crucibleTileEntityClass == null || crucibleRecipeClass == null) {
            player.sendSystemMessage(Component.translatable("rsi.batch.error.mod_missing", "Eidolon"));
            return false;
        }

        ServerLevel level = CraftPacketUtils.resolveLevel(player.server, dim, player);
        if (level == null) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.dim_not_found"));
            return false;
        }
        this.myDim = level.dimension();
        this.myPos = pos;
        this.player = player;

        Recipe<?> foundRecipe = level.getRecipeManager().byKey(recipeId).orElse(null);
        if (foundRecipe == null) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.recipe_not_found", recipeId.toString()));
            return false;
        }
        if (!crucibleRecipeClass.isInstance(foundRecipe)) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.wrong_recipe_type"));
            return false;
        }
        this.recipe = foundRecipe;

        if (!level.isLoaded(pos)) level.getChunk(pos);
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null || !crucibleTileEntityClass.isInstance(be)) {
            player.sendSystemMessage(Component.translatable("rsi.eidolon.error.crucible_not_found"));
            return false;
        }
        this.crucible = be;

        // Validate state
        boolean hasWater;
        try { hasWater = be.getClass().getField("hasWater").getBoolean(be); } catch (Exception e) { hasWater = false; }

        boolean boiling = false;
        try {
            if (boilingField != null) boiling = boilingField.getBoolean(be);
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Batch-Eidolon] Reflection probe failed", e); }

        boolean stepsEmpty = true;
        try {
            if (stepsField != null) {
                List<?> steps = (List<?>) stepsField.get(be);
                stepsEmpty = steps == null || steps.isEmpty();
            }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Batch-Eidolon] Reflection probe failed", e); }

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

        // Re-validate crucible state before each iteration
        boolean hasWater;
        try { hasWater = crucible.getClass().getField("hasWater").getBoolean(crucible); } catch (Exception e) { hasWater = false; }

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

        // Phase 2: commit all extractions atomically
        if (!ledger.commit(network, player)) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Eidolon] Ledger commit failed");
            return false;
        }

        // Drain water, stop boiling, clear steps (consume resources)
        try {
            Object tank = crucible.getClass().getField("tank").get(crucible);
            tank.getClass()
                    .getMethod("drain", int.class, IFluidHandler.FluidAction.class)
                    .invoke(tank, 1000, IFluidHandler.FluidAction.EXECUTE);
            crucible.getClass().getField("hasWater").set(crucible, false);
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Batch-Eidolon] Reflection probe failed", e); }

        try {
            if (stepsField != null) stepsField.set(crucible, new ArrayList<>());
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Batch-Eidolon] Reflection probe failed", e); }

        try {
            crucible.getClass().getMethod("setChanged").invoke(crucible);
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Batch-Eidolon] Reflection probe failed", e); }

        // Get result and store
        try {
            this.pendingResult = ((ItemStack) recipe.getClass().getMethod("getResult")
                    .invoke(recipe)).copy();
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Eidolon] Failed to get result:", e);
            refundAll();
            ledger = null;
            return false;
        }

        this.craftCompleted = true;
        return true;
    }

    // ── Chain support: pre-reserved materials ────────────────────

    @Override
    @Nullable
    public List<IngredientSpec> getRequiredMaterials() {
        if (recipe == null) return null;
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

        // Re-validate crucible state
        boolean hasWater;
        try { hasWater = crucible.getClass().getField("hasWater").getBoolean(crucible); } catch (Exception e) { hasWater = false; }
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

        // Drain water, stop boiling, clear steps
        try {
            Object tank = crucible.getClass().getField("tank").get(crucible);
            tank.getClass()
                    .getMethod("drain", int.class, IFluidHandler.FluidAction.class)
                    .invoke(tank, 1000, IFluidHandler.FluidAction.EXECUTE);
            crucible.getClass().getField("hasWater").set(crucible, false);
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Batch-Eidolon] Reflection probe failed", e); }

        try {
            if (stepsField != null) stepsField.set(crucible, new ArrayList<>());
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Batch-Eidolon] Reflection probe failed", e); }

        try {
            crucible.getClass().getMethod("setChanged").invoke(crucible);
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Batch-Eidolon] Reflection probe failed", e); }

        // Get result and store
        try {
            this.pendingResult = ((ItemStack) recipe.getClass().getMethod("getResult")
                    .invoke(recipe)).copy();
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Eidolon] Failed to get result:", e);
            return false;
        }

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
        ledger = null;
        sharedLedger = null;
        network = null;
        usingSharedLedger = false;
    }

    @Override
    public void onBatchFinished(ServerPlayer player) {
        pendingResult = ItemStack.EMPTY;
        craftCompleted = false;
        ledger = null;
        sharedLedger = null;
        network = null;
        usingSharedLedger = false;
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
                    int stirs = step.getClass().getField("stirs").getInt(step);
                    @SuppressWarnings("unchecked")
                    List<Ingredient> matches = (List<Ingredient>) step.getClass().getField("matches").get(step);
                    List<Ingredient> ingredients = new ArrayList<>(matches);
                    result.add(new StepInput(stirs, ingredients));
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Eidolon] Failed to collect steps", e);
        }
        return result;
    }

    // ── Refund ───────────────────────────────────────────────────

    private void refundAll() {
        if (ledger == null || !ledger.isCommitted()) return;
        List<StepInput> stepInputs = collectSteps(recipe);
        for (StepInput si : stepInputs) {
            for (Ingredient ing : si.ingredients) {
                if (ing.isEmpty()) continue;
                ItemStack[] opts = ing.getItems();
                if (opts.length > 0 && !opts[0].isEmpty()) {
                    ItemStack refund = opts[0].copyWithCount(1);
                    if (network != null) {
                        network.insertItem(refund, 1, com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                    } else if (player != null) {
                        ItemHandlerHelper.giveItemToPlayer(player, refund);
                    }
                }
            }
        }
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
