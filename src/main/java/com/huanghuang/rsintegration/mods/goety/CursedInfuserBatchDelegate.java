package com.huanghuang.rsintegration.mods.goety;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class CursedInfuserBatchDelegate extends AbstractBatchDelegate {
    private static final String RECIPE =
            "com.Polarice3.Goety.common.crafting.CursedInfuserRecipes";

    private ServerLevel level;
    private BlockPos pos;
    private Recipe<?> recipe;
    private GoetyInfuserMachineSupport.Kind machineKind;
    private ItemStack expected = ItemStack.EMPTY;
    private ItemStack queuedInput = ItemStack.EMPTY;
    private ItemStack inputTemplate = ItemStack.EMPTY;
    private int cookingTime;
    private int preparedCapacity = 1;
    private int requestedBatch = 1;
    private final Set<Integer> activeOwnedSlots = new HashSet<>();

    @Override
    public PreparationResult prepare(@Nonnull ServerPlayer player,
                                     @Nonnull ResourceLocation recipeId,
                                     @Nullable ResourceLocation dim,
                                     @Nonnull BlockPos pos) {
        ServerLevel resolved = CraftPacketUtils.resolveLevel(player.server, dim, player);
        if (resolved == null) return PreparationResult.fatal("machine dimension unavailable");
        if (!resolved.hasChunkAt(pos)) return PreparationResult.retry("machine chunk unloaded");

        Recipe<?> found = resolved.getRecipeManager().byKey(recipeId).orElse(null);
        if (found == null || !RECIPE.equals(found.getClass().getName())) {
            return PreparationResult.fatal("recipe is not a cursed infuser recipe");
        }
        Boolean grim = GoetyInfuserMachineSupport.isGrimRecipe(found);
        if (grim == null) return PreparationResult.fatal("recipe grim flag is unavailable");

        BlockEntity blockEntity = resolved.getBlockEntity(pos);
        GoetyInfuserMachineSupport.Kind kind = GoetyInfuserMachineSupport.kindOf(blockEntity);
        if (kind == null) return PreparationResult.retry("cursed infuser block entity unavailable");
        if (grim && !kind.acceptsGrimRecipes()) {
            return PreparationResult.fatal("grim recipe requires an upgraded cursed infuser",
                    Component.translatable("rsi.goety.infuser.grim_machine_required"));
        }

        GoetyInfuserMachineSupport.Prerequisite prerequisite =
                GoetyInfuserMachineSupport.prerequisite(resolved, pos, kind);
        if (prerequisite != GoetyInfuserMachineSupport.Prerequisite.READY) {
            return PreparationResult.fatal("cursed infuser prerequisite is missing",
                    GoetyInfuserMachineSupport.prerequisiteMessage(prerequisite));
        }
        List<ItemStack> slots = GoetyInfuserMachineSupport.recipeItems(blockEntity);
        if (slots == null || slots.isEmpty()) {
            return PreparationResult.retry("cursed infuser recipe slots are unavailable");
        }
        int available = countEmptySlots(slots);
        if (available <= 0) return PreparationResult.retry("cursed infuser has no free recipe slot");

        ItemStack result = found.getResultItem(resolved.registryAccess()).copy();
        int duration = GoetyInfuserMachineSupport.cookingTime(found);
        if (result.isEmpty() || duration <= 0) {
            return PreparationResult.fatal("cursed infuser recipe output or duration is invalid");
        }

        this.level = resolved;
        this.pos = pos.immutable();
        this.recipe = found;
        this.machineKind = kind;
        this.expected = result;
        this.cookingTime = duration;
        this.preparedCapacity = Math.min(kind.recipeCapacity(), available);
        this.requestedBatch = 1;
        this.machineDim = resolved.dimension().location();
        this.machineServer = player.server;
        this.activeOwnedSlots.clear();
        this.queuedInput = ItemStack.EMPTY;
        this.inputTemplate = ItemStack.EMPTY;
        markCraftStarted();
        return PreparationResult.ready();
    }

    @Override
    public boolean validateAndInit(ServerPlayer player, ResourceLocation recipeId,
                                   @Nullable ResourceLocation dim, BlockPos pos) {
        return prepare(player, recipeId, dim, pos).state() == PreparationState.READY;
    }

    @Override
    public void prepareGraphBatch(int executions) {
        requestedBatch = Math.max(1, executions);
    }

    @Override
    public int preferredParallelBatchSize(int totalOperations, int workerCount) {
        return GoetyInfuserMachineSupport.parallelBatchSize(
                totalOperations, workerCount, preparedCapacity);
    }

    @Nullable
    @Override
    public List<IngredientSpec> getRequiredMaterials() {
        var handler = ModRecipeHandlers.handlerFor(recipe);
        return handler == null ? null : handler.getIngredients(recipe);
    }

    @Override
    public boolean validateExecutionContext(@Nullable ServerPlayer player) {
        if (!refreshMachine()) return false;
        if (GoetyInfuserMachineSupport.prerequisite(level, pos, machineKind)
                != GoetyInfuserMachineSupport.Prerequisite.READY) return false;
        List<ItemStack> slots = GoetyInfuserMachineSupport.recipeItems(level.getBlockEntity(pos));
        return slots != null && countEmptySlots(slots) > 0;
    }

    @Override
    public boolean tryStartSingleCraft(ServerPlayer player) {
        return false;
    }

    @Override
    public boolean tryStartWithMaterials(ServerPlayer player, List<ItemStack> materials,
                                         ExtractionLedger sharedLedger) {
        if (!validateExecutionContext(player) || materials.size() != 1) return false;
        ItemStack material = materials.get(0);
        if (material == null || material.isEmpty() || material.getCount() != requestedBatch) {
            return false;
        }
        if (recipe.getIngredients().isEmpty()
                || !recipe.getIngredients().get(0).test(material.copyWithCount(1))) {
            return false;
        }

        queuedInput = material.copy();
        inputTemplate = material.copyWithCount(1);
        activeOwnedSlots.clear();
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null || !fillAvailableSlots(blockEntity)
                || activeOwnedSlots.isEmpty()) {
            queuedInput = ItemStack.EMPTY;
            inputTemplate = ItemStack.EMPTY;
            activeOwnedSlots.clear();
            return false;
        }
        usingSharedLedger = true;
        markCraftStarted();
        return true;
    }

    @Override
    protected boolean isMachineCraftFinished(ServerLevel level, BlockEntity blockEntity) {
        if (!queuedInput.isEmpty()) return false;
        List<ItemStack> slots = GoetyInfuserMachineSupport.recipeItems(blockEntity);
        if (slots == null) return false;
        return activeOwnedSlots.stream().allMatch(slot ->
                slot < 0 || slot >= slots.size() || slots.get(slot).isEmpty());
    }

    @Nonnull
    @Override
    protected CraftObservation observeMachineCraft(@Nonnull ServerLevel level,
                                                    @Nonnull BlockEntity blockEntity) {
        if (GoetyInfuserMachineSupport.kindOf(blockEntity) != machineKind) {
            return failObservation("cursed infuser block entity changed");
        }
        if (GoetyInfuserMachineSupport.prerequisite(level, pos, machineKind)
                != GoetyInfuserMachineSupport.Prerequisite.READY) {
            return failObservation("cursed infuser prerequisite was removed");
        }
        if (!fillAvailableSlots(blockEntity)) {
            return failObservation("cursed infuser rejected queued recipe input");
        }
        if (phase == CraftPhase.WAITING_FOR_START) return workingObservation();
        return isMachineCraftFinished(level, blockEntity)
                ? doneObservation() : workingObservation();
    }

    private boolean fillAvailableSlots(BlockEntity blockEntity) {
        List<ItemStack> slots = GoetyInfuserMachineSupport.recipeItems(blockEntity);
        if (slots == null) return false;
        Set<Integer> completedSlots = new HashSet<>();
        for (int slot : activeOwnedSlots) {
            if (slot < 0 || slot >= slots.size() || slots.get(slot).isEmpty()) {
                completedSlots.add(slot);
            }
        }
        activeOwnedSlots.removeAll(completedSlots);

        while (!queuedInput.isEmpty() && countEmptySlots(slots) > 0) {
            boolean[] emptyBefore = new boolean[slots.size()];
            for (int i = 0; i < slots.size(); i++) emptyBefore[i] = slots.get(i).isEmpty();
            int beforeCount = queuedInput.getCount();
            if (!GoetyInfuserMachineSupport.placeRecipeItem(
                    blockEntity, machineKind, queuedInput, cookingTime)) {
                return false;
            }
            if (queuedInput.getCount() != beforeCount - 1) return false;

            List<ItemStack> updated = GoetyInfuserMachineSupport.recipeItems(blockEntity);
            if (updated == null || updated.size() != slots.size()) return false;
            int placedSlot = findNewlyOccupiedSlot(updated, emptyBefore);
            if (placedSlot < 0) return false;
            activeOwnedSlots.add(placedSlot);
            slots = updated;
        }
        return true;
    }

    static int findNewlyOccupiedSlot(List<ItemStack> slots, boolean[] emptyBefore) {
        int limit = Math.min(slots.size(), emptyBefore.length);
        for (int i = 0; i < limit; i++) {
            if (emptyBefore[i] && !slots.get(i).isEmpty()) return i;
        }
        return -1;
    }

    static int countEmptySlots(List<ItemStack> slots) {
        int count = 0;
        for (ItemStack stack : slots) if (stack == null || stack.isEmpty()) count++;
        return count;
    }

    private boolean refreshMachine() {
        if (level == null || pos == null || machineKind == null || !level.hasChunkAt(pos)) {
            return false;
        }
        return GoetyInfuserMachineSupport.kindOf(level.getBlockEntity(pos)) == machineKind;
    }

    @Override
    public ItemStack collectResult(ServerPlayer player) {
        return ItemStack.EMPTY;
    }

    @Override
    protected void clearMachineState(BlockEntity blockEntity, @Nullable ServerPlayer player) {
        List<ItemStack> slots = GoetyInfuserMachineSupport.recipeItems(blockEntity);
        if (slots != null) {
            for (int slot : activeOwnedSlots) {
                if (slot < 0 || slot >= slots.size()) continue;
                ItemStack stack = slots.get(slot);
                if (!stack.isEmpty() && ItemStack.isSameItemSameTags(stack, inputTemplate)) {
                    slots.set(slot, ItemStack.EMPTY);
                }
            }
            blockEntity.setChanged();
            if (level != null && pos != null) {
                level.sendBlockUpdated(pos, level.getBlockState(pos), level.getBlockState(pos), 3);
            }
        }
        clearLocalState();
    }

    @Override
    public void onBatchFinished(@Nullable ServerPlayer player) {
        if (!markTerminalCleanup()) return;
        clearLocalState();
    }

    private void clearLocalState() {
        level = null;
        pos = null;
        recipe = null;
        machineKind = null;
        expected = ItemStack.EMPTY;
        queuedInput = ItemStack.EMPTY;
        inputTemplate = ItemStack.EMPTY;
        cookingTime = 0;
        preparedCapacity = 1;
        requestedBatch = 1;
        activeOwnedSlots.clear();
        resetState();
    }

    @Override
    public BlockPos getMachinePos() {
        return pos;
    }

    @Override
    public ItemStack getExpectedOutput() {
        if (expected.isEmpty()) return null;
        try {
            return expected.copyWithCount(Math.multiplyExact(expected.getCount(), requestedBatch));
        } catch (ArithmeticException exception) {
            RSIntegrationMod.LOGGER.warn("[RSI-CursedInfuser] Expected output count overflow");
            return null;
        }
    }

    @Override
    public AABB getOutputCaptureRegion() {
        return pos == null ? null : outputCaptureRegion(pos);
    }

    static AABB outputCaptureRegion(BlockPos pos) {
        // All three machines spawn recipe results inside their own block volume.
        // Keeping this exact lets adjacent bound infusers run concurrently.
        return new AABB(pos);
    }

    @Override
    public BatchConcurrencyCapabilities concurrencyCapabilities() {
        return new BatchConcurrencyCapabilities(
                BatchConcurrencyCapabilities.MaterialOwnership.CHAIN_RESERVED,
                BatchConcurrencyCapabilities.OutputOwnership.OWNED_WORLD_CAPTURE,
                BatchConcurrencyCapabilities.CleanupContract.SEPARABLE_OFFLINE,
                BatchConcurrencyCapabilities.SideEffects.LOCAL_WORLD_ITEMS,
                BatchConcurrencyCapabilities.PreparationContract.RETRY_SAFE,
                List.of(BlockPos.ZERO.below()));
    }
}
