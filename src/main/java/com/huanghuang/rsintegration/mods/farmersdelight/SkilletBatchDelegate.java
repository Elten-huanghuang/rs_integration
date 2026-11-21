package com.huanghuang.rsintegration.mods.farmersdelight;

import com.huanghuang.rsintegration.crafting.batch.OperationOutputSource;
import com.huanghuang.rsintegration.crafting.batch.OperationStartContext;
import com.huanghuang.rsintegration.crafting.batch.OutputContract;
import com.huanghuang.rsintegration.crafting.batch.MaterialPlan;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.PhysicalInputRecovery;
import com.huanghuang.rsintegration.crafting.TaskOutputReception;
import com.huanghuang.rsintegration.mods.vanilla.CampfireCookingOperation;
import java.util.HashSet;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.recipe.CampfireRecipeSupport;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.reflection.probes.FarmersDelightReflection;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CampfireCookingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.items.ItemStackHandler;
import org.jetbrains.annotations.NotNull;
import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Batch delegate for Farmer's Delight Skillet (also handles campfire). */
public final class SkilletBatchDelegate extends AbstractBatchDelegate {

    private ServerPlayer player;
    private ServerLevel myLevel;
    private ResourceKey<Level> myDim;
    private BlockPos myPos;
    private Recipe<?> recipe;
    private boolean craftDone;

    // Skillet-specific
    private boolean isSkillet;
    private boolean isArcaneStove;
    private boolean skilletStarted;
    private int skilletPrevTime = -1;
    private ItemStack placedInput = ItemStack.EMPTY;
    // Campfire-specific
    private CampfireBlockEntity campfireBE;
    private CampfireCookingOperation campfireOperation;
    private TaskOutputReception.Writer production;
    private int arcaneSlot = -1;
    private final Set<UUID> arcaneEntitiesBefore = new HashSet<>();
    private long arcaneStartTick;

    @Override
    public void bindProduction(TaskOutputReception.Writer writer) {
        if (campfireBE != null) production = writer;
    }

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
        if (!(found instanceof CampfireCookingRecipe)) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.recipe_not_found", recipeId.toString()));
            return false;
        }
        this.recipe = found;
        this.craftDone = false;
        this.skilletStarted = false;
        this.placedInput = ItemStack.EMPTY;
        this.isSkillet = false;
        this.isArcaneStove = false;
        this.campfireBE = null;
        this.arcaneSlot = -1;
        this.arcaneEntitiesBefore.clear();

        BlockEntity be = level.getBlockEntity(pos);
        if (be != null && ArcaneStoveSupport.isArcaneStove(be)) {
            this.isArcaneStove = true;
            return true;
        }
        if (be != null && isSkilletBE(be)) {
            this.isSkillet = true;
        } else if (be instanceof CampfireBlockEntity campfire) {
            if (CampfireCookingOperation.freeSlot(campfire) < 0) return false;
            this.campfireBE = campfire;
        }
        return true;
    }

    @Override
    public MaterialPlan materialPlan() {
        return MaterialPlan.graphSlots(recipeSlots());
    }

    List<IngredientSpec> recipeSlots() {
        var handler = ModRecipeHandlers.claimFor(recipe).winner();
        if (handler != null) {
            return handler.getIngredients(recipe);
        }
        return CraftPacketUtils.extractIngredientSpecs(recipe);
    }

    @Override
    protected boolean startOperationImpl(OperationStartContext context) {
        if (campfireBE != null && !context.inputSlots().isEmpty()
                && context.materialOwnership() == OperationStartContext.MaterialOwnership.CHAIN_RESERVED) {
            useSharedLedger(context.ledger());
        }
        return super.startOperationImpl(context);
    }

    @Override
    public boolean startSingleOperation(ServerPlayer player) {
        List<IngredientSpec> specs = recipeSlots();
        if (specs == null || specs.isEmpty()) return false;

        List<ItemStack> materials = new ArrayList<>();
        try (ExtractionLedger ledger = materialAccess().newLedger()) {
            if (storageEndpoint() == null) setStorageEndpoint(CraftPacketUtils.resolveEndpointForCraft(player, myDim, myPos).orElse(null));
            if (storageEndpoint() == null && !hasStorageAccess()) return false;
            ledger.setStorageEndpoint(storageEndpoint());

            for (IngredientSpec spec : specs) {
                if (spec.isEmpty()) continue;
                ItemStack reserved = CraftPacketUtils.ensureMaterialAvailable(
                        player, myDim, myPos, spec.ingredient(), spec.count(), ledger);
                if (reserved.isEmpty()) {
                    return false;
                }
                materials.add(reserved.copy());
            }

            if (!ledger.commitToEndpoint(storageEndpoint(), player)) return false;

            this.usingSharedLedger = false;
            if (!startPreparedOperation(player, materials, ledger)) {
                for (ItemStack mat : materials) {
                    if (!mat.isEmpty())
                        insertIntoStorage(player, mat, false);
                }
                return false;
            }
            return true;
        }
    }

    @Override
    public boolean startPreparedOperation(ServerPlayer player, List<ItemStack> materials,
                                         ExtractionLedger sharedLedger) {
        this.player = player;
        this.craftDone = false;

        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be == null) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-Skillet] BlockEntity missing at {}", myPos);
            return false;
        }

        if (materials.isEmpty()) return false;
        ItemStack input = materials.get(0).copyWithCount(1);

        if (campfireBE != null) {
            if (be != campfireBE || materials.size() != 1 || materials.get(0).getCount() != 1
                    || !(recipe instanceof CampfireCookingRecipe cooking)) return false;
            campfireOperation = CampfireCookingOperation.start(campfireBE, input, cooking.getCookingTime(), production);
            if (campfireOperation == null) return false;
            campfireOperation.updateCampfire(myLevel);
            forceMachineChunk(myLevel, myPos, true);
            return true;
        }
        if (isArcaneStove) return tryStartArcaneStove(be, input);
        if (isSkilletBE(be)) return tryStartSkillet(be, input);

        RSIntegrationMod.LOGGER.warn("[RSI-Batch-Skillet] Unknown BE type: {}", be.getClass().getName());
        return false;
    }

    private boolean tryStartArcaneStove(BlockEntity be, ItemStack input) {
        ItemStackHandler stoveItems = ArcaneStoveSupport.items(be);
        if (stoveItems == null || stoveItems.getSlots() <= 0) return false;
        boolean[] emptyBefore = new boolean[stoveItems.getSlots()];
        boolean hasEmptySlot = false;
        for (int slot = 0; slot < stoveItems.getSlots(); slot++) {
            if (stoveItems.getStackInSlot(slot).isEmpty()) {
                emptyBefore[slot] = true;
                hasEmptySlot = true;
            }
        }
        if (!hasEmptySlot) {
            player.sendSystemMessage(Component.translatable(
                    "rsi.farmersdelight.arcane_stove.full"));
            return false;
        }
        if (!ArcaneStoveSupport.ensureBurning(player, be,
                recipe instanceof CampfireCookingRecipe ccr ? ccr.getCookingTime() : 200,
                new ArcaneStoveSupport.FuelAccess() {
                    @Override public ItemStack extract(ItemStack template) {
                        return extractExactFromStorage(player, template, 1, false);
                    }
                    @Override public void refund(ItemStack stack) {
                        ItemStack remainder = insertIntoStorage(player, stack, false);
                        if (!remainder.isEmpty()) ItemHandlerHelper.giveItemToPlayer(player, remainder);
                    }
                })) return false;
        arcaneEntitiesBefore.clear();
        for (var entity : myLevel.getEntitiesOfClass(ItemEntity.class,
                new AABB(myPos).inflate(1.5))) {
            arcaneEntitiesBefore.add(entity.getUUID());
        }
        if (!ArcaneStoveSupport.placeFood(be, player, input,
                recipe instanceof CampfireCookingRecipe ccr ? ccr.getCookingTime() : 200)) return false;
        arcaneSlot = findPlacedArcaneSlot(stoveItems, emptyBefore, input);
        if (arcaneSlot < 0) {
            RSIntegrationMod.LOGGER.warn(
                    "[RSI-ArcaneStove] Native placeFood succeeded but its slot could not be identified at {}",
                    myPos);
        }
        placedInput = input.copy();
        arcaneStartTick = myLevel.getGameTime();
        forceMachineChunk(myLevel, myPos, true);
        be.setChanged();
        return true;
    }

    private static int findPlacedArcaneSlot(ItemStackHandler handler, boolean[] emptyBefore,
                                            ItemStack input) {
        for (int slot = 0; slot < handler.getSlots() && slot < emptyBefore.length; slot++) {
            ItemStack stored = handler.getStackInSlot(slot);
            if (emptyBefore[slot] && !stored.isEmpty()
                    && PhysicalInputRecovery.recoveredExpected(stored, input)) {
                return slot;
            }
        }
        return -1;
    }

    private boolean tryStartSkillet(BlockEntity be, ItemStack input) {
        if (!isHeated(be)) {
            player.sendSystemMessage(Component.translatable("rsi.farmersdelight.no_heat"));
            return false;
        }

        try {
            // FD 1.2.8+ uses addItemToCook(ItemStack, Player); older versions
            // use addItemToCook(ItemStack, CampfireCookingRecipe).
            Method addItem;
            if (hasMethod(be.getClass(), "addItemToCook",
                    ItemStack.class, Player.class)) {
                addItem = be.getClass().getMethod("addItemToCook",
                        ItemStack.class, Player.class);
                addItem.invoke(be, input, player);
            } else {
                addItem = be.getClass().getMethod("addItemToCook",
                        ItemStack.class, CampfireCookingRecipe.class);
                addItem.invoke(be, input, (CampfireCookingRecipe) recipe);
            }
            be.setChanged();
            forceMachineChunk(myLevel, myPos, true);
            skilletPrevTime = -1;
            skilletStarted = true;
            placedInput = input.copy();
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-Skillet] Item added to skillet");
            return true;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Skillet] Failed to add item", e);
            return false;
        }
    }

    private static boolean hasMethod(Class<?> clazz, String name, Class<?>... paramTypes) {
        try {
            clazz.getMethod(name, paramTypes);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    @Override
    protected CraftObservation observeMachineCraft(ServerLevel level, BlockEntity be) {
        if (campfireBE == null) return super.observeMachineCraft(level, be);
        if (campfireOperation == null) return failObservation("campfire cooking not started");
        if (campfireOperation.completed()) return doneObservation();
        if (!campfireOperation.ownsInput(be)) return failObservation("campfire input removed or replaced");
        observedCycle = campfireOperation.progress();
        return workingObservation(observedCycle.completedTicks());
    }

    @Override
    protected boolean isMachineCraftFinished(ServerLevel level, BlockEntity be) {
        if (isArcaneStove) {
            ItemStack expected = getExpectedOutput();
            if (expected == null || expected.isEmpty()) return false;
            return level.getEntitiesOfClass(ItemEntity.class,
                    new AABB(myPos).inflate(1.5), entity ->
                            entity.isAlive() && level.getGameTime() >= arcaneStartTick
                                    && !arcaneEntitiesBefore.contains(entity.getUUID())
                                    && ItemStack.isSameItemSameTags(entity.getItem(), expected)).stream()
                    .findAny().isPresent();
        }
        if (isSkilletBE(be)) {
            // 有些旧版本会把烤好的食物留在槽内。
            if (!skilletStarted) return false;
            try {
                if ((Boolean) be.getClass().getMethod("isCooking").invoke(be)) return false;
                Object value = be.getClass().getMethod("getStoredStack").invoke(be);
                if (value instanceof ItemStack stored && !stored.isEmpty()) {
                    ItemStack rawInput = recipe.getIngredients().isEmpty() ? ItemStack.EMPTY
                            : recipe.getIngredients().get(0).getItems().length > 0
                            ? recipe.getIngredients().get(0).getItems()[0] : ItemStack.EMPTY;
                    return rawInput.isEmpty() || !ItemStack.isSameItem(stored, rawInput);
                }
                return true;
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-Batch-Skillet] Unable to inspect skillet state", e);
                return false;
            }
        }

        return false;
    }

    @Override public OperationOutputSource outputSource() {
        return OperationOutputSource.single(this::takeResult, outputContract());
    }

    private ItemStack takeResult(ServerPlayer player) {
        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be == null) return ItemStack.EMPTY;

        if (isArcaneStove || campfireBE != null) {
            return ItemStack.EMPTY;
        }
        if (isSkilletBE(be)) {
            try {
                Method removeItem = be.getClass().getMethod("removeItem");
                Object result = removeItem.invoke(be);
                be.setChanged();
                craftDone = true;
                // Older FD versions may retain a transformed stack. Current FD
                // spawns the cooked result into the world and clears this slot;
                // that output is owned by CraftOutputInterceptor instead.
                if (result instanceof ItemStack s && !s.isEmpty()) return s;
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.error("[RSI-Batch-Skillet] Failed to remove item", e);
            }
            return ItemStack.EMPTY;
        }

        return ItemStack.EMPTY;
    }

    @Override
    protected void clearMachineState(BlockEntity be, ServerPlayer player) {
        List<ItemStack> recoveredInputs = new ArrayList<>();
        if (campfireBE != null) {
            ItemStack recovered = campfireOperation == null ? ItemStack.EMPTY : campfireOperation.cancel(be);
            campfireOperation = null;
            if (!recovered.isEmpty()) {
                recoveredInputs.add(recovered.copy());
                if (!usingSharedLedger) refundToRSNetwork(recovered);
            }
        } else if (isArcaneStove) {
            ItemStack recovered = clearArcaneInput(be);
            if (!recovered.isEmpty()) {
                recoveredInputs.add(recovered.copy());
                if (!usingSharedLedger) refundToRSNetwork(recovered);
            }
        } else if (isSkilletBE(be)) {
            try {
                Method isCooking = be.getClass().getMethod("isCooking");
                if ((Boolean) isCooking.invoke(be)) {
                    ItemStack recovered = takeResult(player);
                    if (PhysicalInputRecovery.recoveredExpected(recovered, placedInput)) {
                        ItemStack owned = recovered.copyWithCount(placedInput.getCount());
                        recoveredInputs.add(owned);
                        if (!usingSharedLedger) refundToRSNetwork(owned);
                    }
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-Skillet] Reflection read failed", e);
            }
        }
        recordFailureRecoveredInputs(recoveredInputs);
        craftDone = false;
        skilletPrevTime = -1;
        placedInput = ItemStack.EMPTY;
        arcaneSlot = -1;
    }

    private ItemStack clearArcaneInput(BlockEntity be) {
        ItemStackHandler handler = ArcaneStoveSupport.items(be);
        if (handler == null || placedInput.isEmpty()
                || arcaneSlot < 0 || arcaneSlot >= handler.getSlots()) return ItemStack.EMPTY;
        ItemStack visible = handler.getStackInSlot(arcaneSlot);
        if (visible.isEmpty()
                || !PhysicalInputRecovery.recoveredExpected(visible, placedInput)) return ItemStack.EMPTY;
        ItemStack recovered = handler.extractItem(arcaneSlot,
                Math.min(visible.getCount(), placedInput.getCount()), false);
        be.setChanged();
        return recovered;
    }

    @Override
    public void cleanup(@NotNull ServerPlayer player) {
        if (!markTerminalCleanup()) return;
        releaseCampfireOperation();
        releaseMachineChunk();
        craftDone = false;
        skilletPrevTime = -1;
        setStorageEndpoint(null);
    }

    @Override
    public BlockPos getMachinePos() { return myPos; }

    @Override
    public ItemStack getExpectedOutput() {
        if (campfireBE != null) return null;
        if (recipe == null || myLevel == null || myPos == null) return null;
        ItemStack expected = CampfireRecipeSupport.resolveOutput(
                recipe, myLevel.registryAccess());
        return expected.isEmpty() ? null : expected;
    }

    @Override
    public OutputContract outputContract() {
        return campfireBE == null ? super.outputContract() : CampfireCookingOperation.outputContract(
                CampfireRecipeSupport.resolveOutput(recipe, myLevel.registryAccess()));
    }

    /**
     * Older Farmer's Delight builds retained the transformed item in the
     * skillet inventory. Newer builds eject it as a world item. Only the former
     * may complete without a captured world output.
     */
    @Override
    public boolean canCollectResultWithoutWorldCapture() {
        if (recipe == null || myLevel == null || myPos == null) return false;
        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be == null || !isSkilletBE(be)) return false;
        try {
            Object value = be.getClass().getMethod("getStoredStack").invoke(be);
            if (!(value instanceof ItemStack stored) || stored.isEmpty()) return false;
            ItemStack rawInput = recipe.getIngredients().isEmpty()
                    ? ItemStack.EMPTY
                    : recipe.getIngredients().get(0).getItems().length > 0
                    ? recipe.getIngredients().get(0).getItems()[0] : ItemStack.EMPTY;
            return rawInput.isEmpty() || !ItemStack.isSameItem(stored, rawInput);
        } catch (Exception ignored) {
            // Builds without getStoredStack use world output capture.
            return false;
        }
    }

    @Override
    public AABB getOutputCaptureRegion() {
        return campfireBE != null || myPos == null ? null : new AABB(myPos).inflate(1.5);
    }

    // ── plan helpers ──

    public static List<Component> getPlanWarnings(ServerPlayer player, Recipe<?> recipe,
                                                @Nullable ResourceLocation dim,
                                                @Nullable BlockPos pos) {
        List<Component> warnings = new ArrayList<>();
        boolean arcaneStove = false;
        if (pos != null) {
            ServerLevel level = CraftPacketUtils.resolveLevel(player.server, dim, player);
            if (level != null && level.hasChunkAt(pos)
                    && ArcaneStoveSupport.isArcaneStove(level.getBlockEntity(pos))) {
                arcaneStove = true;
                warnings.add(Component.translatable("rsi.farmersdelight.arcane_stove.plan_fuel"));
            }
        }
        if (!arcaneStove) {
            warnings.add(Component.translatable("rsi.farmersdelight.heat_warning"));
        }
        return warnings;
    }

    // ── reflection ──

    private static boolean isSkilletBE(BlockEntity be) {
        return FarmersDelightReflection.skilletBEClass != null
                && FarmersDelightReflection.skilletBEClass.isAssignableFrom(be.getClass());
    }

    private static boolean isHeated(BlockEntity be) {
        try {
            Method m = be.getClass().getMethod("isHeated");
            return (boolean) m.invoke(be);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Skillet] Reflection read failed", e);
            return false;
        }
    }

    @Override
    protected void clearMissingMachineState(@Nullable ServerPlayer player) {
        releaseCampfireOperation();
    }

    @Override
    protected void clearUnavailableMachineState(@Nullable ServerPlayer player) {
        releaseCampfireOperation();
    }

    private void releaseCampfireOperation() {
        if (campfireOperation == null) return;
        campfireOperation.release();
        campfireOperation = null;
    }

    private void refundToRSNetwork(ItemStack stack) {
        ItemStack leftover = insertIntoStorage(player, stack, false);
        if (!leftover.isEmpty() && player != null) {
            ItemHandlerHelper.giveItemToPlayer(player, leftover);
        }
    }

}
