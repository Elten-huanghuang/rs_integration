package com.huanghuang.rsintegration.mods.farmersdelight;

import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.reflection.probes.FarmersDelightReflection;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.util.Action;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.world.ForgeChunkManager;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Batch delegate for Farmer's Delight Cooking Pot. */
public final class CookingPotBatchDelegate extends AbstractBatchDelegate {

    // Slot layout matching CookingPotBlockEntity
    private static final int INPUT_SLOTS = 6;   // 0..5
    private static final int MEAL_DISPLAY_SLOT = 6;
    private static final int CONTAINER_SLOT = 7;
    private static final int OUTPUT_SLOT = 8;

    private ServerPlayer player;
    private ServerLevel myLevel;
    private ResourceKey<Level> myDim;
    private BlockPos myPos;
    private Recipe<?> recipe;
    private boolean craftDone;

    private static volatile Field inventoryField;
    private static volatile boolean reflectionProbed;

    @Override
    public boolean validateAndInit(ServerPlayer player, ResourceLocation recipeId,
                                   @Nullable ResourceLocation dim, BlockPos pos) {
        ServerLevel level = CraftPacketUtils.resolveLevel(player.server, dim, player);
        if (level == null) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.dim_not_found"));
            return false;
        }
        this.myLevel = level;
        this.myDim = level.dimension();
        this.myPos = pos;
        this.player = player;

        Recipe<?> found = level.getRecipeManager().byKey(recipeId).orElse(null);
        if (found == null) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.recipe_not_found", recipeId.toString()));
            return false;
        }
        if (FarmersDelightReflection.cookingPotRecipeClass == null || !FarmersDelightReflection.cookingPotRecipeClass.isInstance(found)) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.recipe_not_found", recipeId.toString()));
            return false;
        }
        this.recipe = found;
        this.craftDone = false;
        return true;
    }

    @Nullable
    @Override
    public List<IngredientSpec> getRequiredMaterials() {
        List<IngredientSpec> specs = getRecipeIngredientSpecs();
        if (specs == null) specs = new ArrayList<>();
        else specs = new ArrayList<>(specs);

        ItemStack container = getContainerItem(recipe, myLevel != null ? myLevel.registryAccess() : null);
        if (!container.isEmpty()) {
            specs.add(new IngredientSpec(Ingredient.of(container), 1));
        }
        return specs.isEmpty() ? null : specs;
    }

    @Override
    public List<IngredientSpec> getGraphSpecs() {
        List<IngredientSpec> specs = getRecipeIngredientSpecs();
        return specs != null ? specs : List.of();
    }

    @Override
    public List<IngredientSpec> getSupplementalSpecs() {
        ItemStack container = getContainerItem(recipe, myLevel != null ? myLevel.registryAccess() : null);
        return container.isEmpty()
                ? List.of()
                : List.of(new IngredientSpec(Ingredient.of(container), 1));
    }

    @Nullable
    private List<IngredientSpec> getRecipeIngredientSpecs() {
        var handler = ModRecipeHandlers.handlerFor(recipe);
        return handler != null
                ? handler.getIngredients(recipe)
                : CraftPacketUtils.extractIngredientSpecs(recipe);
    }

    @Override
    public BatchConcurrencyCapabilities concurrencyCapabilities() {
        return BatchConcurrencyCapabilities.machineSlotWithLocalWorldItems();
    }

    @Override
    public boolean tryStartSingleCraft(ServerPlayer player) {
        List<IngredientSpec> specs = getRequiredMaterials();
        if (specs == null || specs.isEmpty()) return false;

        List<ItemStack> materials = new ArrayList<>();
        try (ExtractionLedger ledger = new ExtractionLedger()) {
            this.network = CraftPacketUtils.resolveNetworkForCraft(player, myDim, myPos);
            if (this.network == null) return false;

            for (IngredientSpec spec : specs) {
                if (spec.isEmpty()) continue;
                ItemStack reserved = CraftPacketUtils.ensureMaterialAvailable(
                        player, myDim, myPos, spec.ingredient(), spec.count(), ledger);
                if (reserved.isEmpty()) {
                    return false;
                }
                materials.add(reserved.copy());
            }

            if (!ledger.commit(network, player)) return false;

            this.usingSharedLedger = false;
            if (!tryStartWithMaterials(player, materials, ledger)) {
                for (ItemStack mat : materials) {
                    if (!mat.isEmpty())
                        network.insertItem(mat.copy(), mat.getCount(), Action.PERFORM);
                }
                return false;
            }
            return true;
        }
    }

    @Override
    public boolean tryStartWithMaterials(ServerPlayer player, List<ItemStack> materials,
                                         ExtractionLedger sharedLedger) {
        this.player = player;
        this.usingSharedLedger = true;
        this.craftDone = false;

        myLevel.getChunk(myPos);

        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be == null) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-CookingPot] BlockEntity missing at {}", myPos);
            return false;
        }
        if (!FarmersDelightReflection.cookingPotBEClass.isInstance(be)) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-CookingPot] Wrong BE type: {}", be.getClass().getName());
            return false;
        }

        IItemHandler itemHandler = getInventory(be);
        if (itemHandler == null || itemHandler.getSlots() < 9) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-CookingPot] Cannot access item handler");
            return false;
        }
        if (!itemHandler.getStackInSlot(OUTPUT_SLOT).isEmpty()) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-CookingPot] Output slot occupied at {}", myPos);
            return false;
        }
        if (!itemHandler.getStackInSlot(MEAL_DISPLAY_SLOT).isEmpty()) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-CookingPot] Meal display slot occupied at {}", myPos);
            return false;
        }

        forceChunkLoad(true);

        // Check heat source — Cooking Pot requires a heat source below
        if (!isHeated(be)) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-CookingPot] No heat source under cooking pot at {}", myPos);
            player.sendSystemMessage(Component.translatable("rsi.farmersdelight.no_heat"));
            return false;
        }

        ItemStack requiredContainer = getContainerItem(recipe, myLevel.registryAccess());
        List<ItemStack> inputMaterials = new ArrayList<>();
        ItemStack containerMaterial = ItemStack.EMPTY;
        for (ItemStack material : materials) {
            if (material.isEmpty()) continue;
            if (containerMaterial.isEmpty() && !requiredContainer.isEmpty()
                    && ItemStack.isSameItemSameTags(material, requiredContainer)) {
                containerMaterial = material.copyWithCount(1);
                if (material.getCount() > 1) {
                    inputMaterials.add(material.copyWithCount(material.getCount() - 1));
                }
            } else {
                inputMaterials.add(material);
            }
        }
        if (!requiredContainer.isEmpty() && containerMaterial.isEmpty()) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-CookingPot] Planned container missing for recipe {}: {}",
                    recipe.getId(), requiredContainer);
            return false;
        }

        long materialCount = inputMaterials.stream().filter(s -> !s.isEmpty()).count();
        if (materialCount > INPUT_SLOTS) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-CookingPot] Recipe {} has {} ingredients but only {} input slots",
                    recipe.getId(), materialCount, INPUT_SLOTS);
            forceChunkLoad(false);
            return false;
        }

        // Insert ingredients into input slots 0..5
        int slot = 0;
        for (ItemStack mat : inputMaterials) {
            if (mat.isEmpty()) continue;
            ItemStack single = mat.copyWithCount(1);
            ItemStack remainder = itemHandler.insertItem(slot, single, false);
            if (!remainder.isEmpty()) {
                RSIntegrationMod.LOGGER.warn("[RSI-Batch-CookingPot] Failed to insert into slot {}: {}",
                        slot, remainder.getHoverName().getString());
                for (int back = 0; back < slot; back++) {
                    ItemStack refund = itemHandler.extractItem(back, 64, false);
                    if (!refund.isEmpty() && !usingSharedLedger && network != null)
                        network.insertItem(refund, refund.getCount(), Action.PERFORM);
                }
                be.setChanged();
                return false;
            }
            slot++;
        }
        be.setChanged();

        // Insert the container that was reserved by the same shared ledger as the
        // ingredients. Never extract it from RS out-of-band after commit.
        if (!requiredContainer.isEmpty()) {
            ItemStack existingContainer = itemHandler.getStackInSlot(CONTAINER_SLOT);
            if (!existingContainer.isEmpty()) {
                RSIntegrationMod.LOGGER.warn("[RSI-Batch-CookingPot] Container slot occupied at {}", myPos);
                rollbackInputs(itemHandler, slot);
                be.setChanged();
                return false;
            }
            ItemStack simulated = itemHandler.insertItem(CONTAINER_SLOT, containerMaterial, true);
            if (!simulated.isEmpty()) {
                rollbackInputs(itemHandler, slot);
                be.setChanged();
                return false;
            }
            ItemStack remainder = itemHandler.insertItem(CONTAINER_SLOT, containerMaterial, false);
            if (!remainder.isEmpty()) {
                rollbackInputs(itemHandler, slot);
                be.setChanged();
                return false;
            }
            be.setChanged();
        }

        RSIntegrationMod.LOGGER.debug("[RSI-Batch-CookingPot] Materials inserted, cooking should start next tick");
        return true;
    }

    @Override
    protected boolean isMachineCraftFinished(ServerLevel level, BlockEntity be) {
        if (!FarmersDelightReflection.cookingPotBEClass.isInstance(be)) return false;

        IItemHandler itemHandler = getInventory(be);
        if (itemHandler == null) return false;

        ItemStack output = itemHandler.getStackInSlot(OUTPUT_SLOT);
        if (!output.isEmpty()) return matchesRecipeOutput(output);

        ItemStack declared = getDeclaredContainerItem(recipe);
        if (!declared.isEmpty()) return false;
        ItemStack inferred = getContainerItem(recipe, level.registryAccess());
        ItemStack storedContainer = itemHandler.getStackInSlot(CONTAINER_SLOT);
        ItemStack meal = itemHandler.getStackInSlot(MEAL_DISPLAY_SLOT);
        return !inferred.isEmpty()
                && ItemStack.isSameItemSameTags(inferred, storedContainer)
                && matchesRecipeOutput(meal);
    }

    @Override
    public ItemStack collectResult(ServerPlayer player) {
        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be == null) return ItemStack.EMPTY;

        IItemHandler itemHandler = getInventory(be);
        if (itemHandler == null) return ItemStack.EMPTY;

        ItemStack result = itemHandler.extractItem(OUTPUT_SLOT, 64, false);
        if (result.isEmpty()) {
            ItemStack meal = itemHandler.getStackInSlot(MEAL_DISPLAY_SLOT);
            ItemStack declared = getDeclaredContainerItem(recipe);
            ItemStack inferred = getContainerItem(recipe, myLevel.registryAccess());
            ItemStack storedContainer = itemHandler.getStackInSlot(CONTAINER_SLOT);
            if (declared.isEmpty() && !inferred.isEmpty()
                    && ItemStack.isSameItemSameTags(inferred, storedContainer)
                    && matchesRecipeOutput(meal)) {
                result = itemHandler.extractItem(MEAL_DISPLAY_SLOT, meal.getCount(), false);
                itemHandler.extractItem(CONTAINER_SLOT, 1, false);
            }
        }
        be.setChanged();
        craftDone = true;
        return result;
    }

    @Override
    protected void clearMachineState(BlockEntity be, ServerPlayer player) {
        clearMachineSlotsAndRefund();
        forceChunkLoad(false);
        craftDone = false;
    }

    @Override
    public void onBatchFinished(@NotNull ServerPlayer player) {
        clearMachineSlotsAndRefund();
        forceChunkLoad(false);
        craftDone = false;
        network = null;
    }

    @Override
    public BlockPos getMachinePos() { return myPos; }

    @Nullable
    @Override
    public ExpectedProduction getExpectedProduction() {
        ItemStack result = getRecipeResult(recipe, myLevel != null ? myLevel.registryAccess() : null);
        return result.isEmpty() ? null : new ExpectedProduction(result, result.getCount());
    }

    // ── plan helpers ──

    public static void addFuelIfNeeded(@Nullable String recipeModTypeId,
                                       Map<Item, Integer> itemAvailable,
                                       Map<Item, Ingredient> itemSource,
                                       Map<Item, Integer> neededCounts,
                                       int repeatCount) {
        if (!"farmersdelight_cooking_pot".equals(recipeModTypeId)) return;
        int containerNeeded = repeatCount;
        // Container items are added per-craft, accounted via getContainerItem
    }

    public static List<String> getPlanWarnings(ServerPlayer player, Recipe<?> recipe,
                                                @Nullable ResourceLocation dim,
                                                @Nullable BlockPos pos) {
        List<String> warnings = new ArrayList<>();
        ItemStack container = getContainerItem(recipe, player.level().registryAccess());
        if (!container.isEmpty()) {
            warnings.add(Component.translatable("rsi.farmersdelight.container_needed",
                    container.getHoverName().getString()).getString());
        }
        warnings.add(Component.translatable("rsi.farmersdelight.heat_warning").getString());
        return warnings;
    }

    // ── reflection ──

    private static void probeReflection() {
        if (reflectionProbed) return;
        reflectionProbed = true;
        try {
            inventoryField = FarmersDelightReflection.cookingPotBEClass.getDeclaredField("inventory");
            inventoryField.setAccessible(true);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-CookingPot] Reflection probe failed", e);
        }
    }

    private static IItemHandler getInventory(BlockEntity be) {
        probeReflection();
        if (inventoryField != null) {
            try {
                return (IItemHandler) inventoryField.get(be);
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Batch-CookingPot] reflection probe failed", e); }
        }
        return be.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER)
                .resolve().orElse(null);
    }

    private static boolean isHeated(BlockEntity be) {
        try {
            Method m = be.getClass().getMethod("isHeated");
            return (boolean) m.invoke(be);
        } catch (Exception e) {
            return false;
        }
    }

    private static ItemStack getDeclaredContainerItem(Recipe<?> recipe) {
        try {
            Method m = recipe.getClass().getMethod("getOutputContainer");
            Object result = m.invoke(recipe);
            if (result instanceof ItemStack s && !s.isEmpty()) return s;
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Batch-CookingPot] getOutputContainer failed", e); }
        try {
            Field f = recipe.getClass().getDeclaredField("container");
            f.setAccessible(true);
            Object v = f.get(recipe);
            if (v instanceof ItemStack s && !s.isEmpty()) return s;
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Batch-CookingPot] container field access failed", e); }
        // Fallback: the recipe declares no explicit container, but the meal item
        // may itself be a bowl/cup food (getCraftingRemainingItem() = the empty
        // bowl). FD's moveMealToOutput() refuses to move the meal to the output
        // slot when doesMealHaveContainer() is true (which includes
        // meal.hasCraftingRemainingItem()), so without supplying that container
        // the meal stays stuck in the display slot and never reaches OUTPUT_SLOT
        // — the product is silently never recovered into RS. Derive the required
        // container from the result item's crafting remainder.
        return ItemStack.EMPTY;
    }

    public static ItemStack getContainerItem(Recipe<?> recipe, @Nullable RegistryAccess access) {
        ItemStack declared = getDeclaredContainerItem(recipe);
        if (!declared.isEmpty()) return declared;
        try {
            ItemStack result = getRecipeResult(recipe, access);
            if (!result.isEmpty() && result.hasCraftingRemainingItem()) {
                ItemStack rem = result.getCraftingRemainingItem();
                if (!rem.isEmpty()) return rem;
            }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Batch-CookingPot] meal-container fallback failed", e); }
        return ItemStack.EMPTY;
    }

    private boolean matchesRecipeOutput(ItemStack stack) {
        ItemStack expected = getRecipeResult(recipe, myLevel != null ? myLevel.registryAccess() : null);
        return !stack.isEmpty() && !expected.isEmpty()
                && ItemStack.isSameItemSameTags(stack, expected)
                && stack.getCount() >= expected.getCount();
    }

    /** SRG-safe recipe result using the active level's registry access. */
    private static ItemStack getRecipeResult(Recipe<?> recipe, @Nullable RegistryAccess access) {
        if (recipe instanceof net.minecraft.world.item.crafting.CraftingRecipe) return ItemStack.EMPTY;
        try {
            return ModRecipeHandlers.tryGetResultItem(recipe, access);
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
    }

    // ── cleanup ──

    private void rollbackInputs(IItemHandler handler, int insertedSlots) {
        for (int back = 0; back < insertedSlots; back++) {
            ItemStack refund = handler.extractItem(back, 64, false);
            if (!refund.isEmpty() && !usingSharedLedger) refundToRSNetwork(refund);
        }
    }

    private void clearMachineSlotsAndRefund() {
        myLevel.getChunk(myPos);
        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be == null) return;
        if (!FarmersDelightReflection.cookingPotBEClass.isInstance(be)) return;

        IItemHandler handler = getInventory(be);
        if (handler == null || handler.getSlots() < 9) return;

        for (int slot = 0; slot < INPUT_SLOTS; slot++) {
            ItemStack s = handler.extractItem(slot, 64, false);
            if (!s.isEmpty() && !usingSharedLedger) refundToRSNetwork(s);
        }
        ItemStack meal = handler.extractItem(MEAL_DISPLAY_SLOT, 64, false);
        if (!meal.isEmpty()) refundToRSNetwork(meal);
        // Container was reserved via the shared ledger (separated from materials in
        // tryStartWithMaterials L204-216). When usingSharedLedger=true, the ledger's
        // refundCommitted() will restore it; delegate must not double-refund.
        ItemStack container = handler.extractItem(CONTAINER_SLOT, 64, false);
        if (!container.isEmpty() && !usingSharedLedger) refundToRSNetwork(container);
        ItemStack out = handler.extractItem(OUTPUT_SLOT, 64, false);
        // Output is not part of the shared input ledger. If collection races with
        // cleanup, never discard it merely because this delegate used that ledger.
        if (!out.isEmpty()) refundToRSNetwork(out);
        be.setChanged();
    }

    private void refundToRSNetwork(ItemStack stack) {
        if (network != null) {
            ItemStack leftover = network.insertItem(stack.copy(), stack.getCount(), Action.PERFORM);
            if (!leftover.isEmpty() && player != null) {
                ItemHandlerHelper.giveItemToPlayer(player, leftover);
            }
        } else if (player != null) {
            ItemHandlerHelper.giveItemToPlayer(player, stack.copy());
        }
    }

    private void forceChunkLoad(boolean load) {
        try {
            int cx = myPos.getX() >> 4;
            int cz = myPos.getZ() >> 4;
            ForgeChunkManager.forceChunk(myLevel, RSIntegrationMod.MOD_ID, myPos, cx, cz, load, true);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-CookingPot] Chunk load failed", e);
        }
    }
}
