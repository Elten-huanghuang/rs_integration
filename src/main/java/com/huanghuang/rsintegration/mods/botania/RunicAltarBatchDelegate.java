package com.huanghuang.rsintegration.mods.botania;

import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.util.PlayerUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import vazkii.botania.api.recipe.RunicAltarRecipe;
import vazkii.botania.common.block.block_entity.RunicAltarBlockEntity;
import vazkii.botania.common.item.material.RuneItem;
import vazkii.botania.xplat.XplatAbstractions;
import net.minecraft.core.registries.Registries;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Executes one real Botania runic-altar operation and owns every physical item it places. */
public final class RunicAltarBatchDelegate extends AbstractBatchDelegate {
    private ServerLevel level;
    private BlockPos pos;
    private RunicAltarRecipe recipe;
    private ItemStack expected = ItemStack.EMPTY;
    private UUID ownerId;
    private boolean started;
    private boolean completionTriggered;
    private long startTick;
    private Set<UUID> entitiesBefore = Set.of();
    private final Set<UUID> inputEntityIds = new HashSet<>();
    private final List<ItemStack> reusableOutputs = new ArrayList<>();

    @Override
    public boolean validateAndInit(@Nonnull ServerPlayer player, @Nonnull ResourceLocation recipeId,
                                   @Nullable ResourceLocation dim, @Nonnull BlockPos machinePos) {
        return prepareInternal(player, recipeId, dim, machinePos).state() == PreparationState.READY;
    }

    @Override
    public PreparationResult prepare(@Nonnull ServerPlayer player, @Nonnull ResourceLocation recipeId,
                                     @Nullable ResourceLocation dim, @Nonnull BlockPos machinePos) {
        return prepareInternal(player, recipeId, dim, machinePos);
    }

    private PreparationResult prepareInternal(ServerPlayer player, ResourceLocation recipeId,
                                              @Nullable ResourceLocation dim, BlockPos machinePos) {
        this.pos = machinePos.immutable();
        this.machineDim = dim;
        this.machineServer = player.getServer();
        this.level = dim == null ? player.serverLevel() : player.getServer().getLevel(
                ResourceKey.create(Registries.DIMENSION, dim));
        this.recipe = null;
        this.expected = ItemStack.EMPTY;
        this.network = null;

        if (level == null) return PreparationResult.retry("Runic Altar dimension is unavailable");
        if (!(level.getBlockEntity(pos) instanceof RunicAltarBlockEntity altar)) {
            return PreparationResult.retry("Bound block is not a Runic Altar");
        }
        var found = level.getRecipeManager().byKey(recipeId).orElse(null);
        if (!(found instanceof RunicAltarRecipe runicRecipe)) {
            return PreparationResult.fatal("Recipe is not a Botania runic-altar recipe: " + recipeId);
        }
        ItemStack output = runicRecipe.getResultItem(level.registryAccess());
        if (output == null || output.isEmpty()) {
            return PreparationResult.fatal("Runic Altar recipe has no output: " + recipeId);
        }
        if (!isIdle(altar)) {
            return PreparationResult.retry("Runic Altar still contains items");
        }

        this.recipe = runicRecipe;
        this.expected = output.copy();
        if (!hasStorageAccess()) {
            this.network = CraftPacketUtils.resolveNetworkForCraft(player, level.dimension(), pos);
        }
        return !hasStorageAccess()
                ? PreparationResult.retry("Runic Altar is not connected to a storage backend")
                : PreparationResult.ready();
    }

    @Override
    public List<IngredientSpec> getRequiredMaterials() {
        if (recipe == null) return null;
        List<IngredientSpec> specs = new ArrayList<>();
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (!ingredient.isEmpty()) specs.add(new IngredientSpec(ingredient, 1));
        }
        if (!recipe.getReagent().isEmpty()) {
            specs.add(new IngredientSpec(recipe.getReagent(), 1));
        }
        return specs;
    }

    @Override
    public boolean tryStartSingleCraft(@Nonnull ServerPlayer player) {
        if (!canStartNow()) return false;
        List<ItemStack> extracted = storageEndpoint() != null
                ? BotaniaDelegateSupport.extractAtomically(
                        storageEndpoint(), player, getRequiredMaterials())
                : BotaniaDelegateSupport.extractAtomically(
                        network, getRequiredMaterials());
        if (extracted.isEmpty()) return false;
        if (start(player, extracted)) return true;
        for (ItemStack stack : extracted) refundStandalone(player, stack);
        return false;
    }

    @Override
    public boolean tryStartSingleCraft(@Nonnull ServerPlayer player,
                                       @Nonnull ExtractionLedger sharedLedger) {
        return false;
    }

    @Override
    public boolean tryStartWithMaterials(@Nonnull ServerPlayer player,
                                         @Nonnull List<ItemStack> materials,
                                         @Nonnull ExtractionLedger sharedLedger) {
        useSharedLedger(sharedLedger);
        return start(player, materials);
    }

    private boolean start(ServerPlayer player, List<ItemStack> materials) {
        List<IngredientSpec> specs = getRequiredMaterials();
        if (specs == null || materials.size() != specs.size() || !canStartNow()) return false;
        for (int i = 0; i < specs.size(); i++) {
            ItemStack material = materials.get(i);
            IngredientSpec spec = specs.get(i);
            if (material == null || material.isEmpty() || material.getCount() != spec.count()
                    || !spec.ingredient().test(material)) {
                return false;
            }
        }

        ownerId = player.getUUID();
        reusableOutputs.clear();
        inputEntityIds.clear();
        AABB outputRegion = getOutputCaptureRegion();
        entitiesBefore = BotaniaDelegateSupport.snapshot(level, outputRegion);
        startTick = level.getGameTime();

        for (ItemStack material : materials) {
            ItemStack placed = material.copy();
            if (placed.getItem() instanceof RuneItem) {
                reusableOutputs.add(placed.copy());
            }
            ItemEntity entity = new ItemEntity(level,
                    pos.getX() + 0.5D, pos.getY() + 1.1D, pos.getZ() + 0.5D, placed);
            entity.setDeltaMovement(0.0D, 0.0D, 0.0D);
            BotaniaDelegateSupport.protectOperationInput(entity);
            if (!level.addFreshEntity(entity)) {
                discardOwnedInputEntities(false, null);
                reusableOutputs.clear();
                return false;
            }
            inputEntityIds.add(entity.getUUID());
        }

        started = true;
        completionTriggered = false;
        markCraftStarted();
        return true;
    }

    private boolean canStartNow() {
        if (level == null || pos == null || recipe == null || !hasStorageAccess()) return false;
        return level.getBlockEntity(pos) instanceof RunicAltarBlockEntity altar && isIdle(altar);
    }

    private boolean isIdle(RunicAltarBlockEntity altar) {
        if (!altar.isEmpty()) return false;
        return level.getEntitiesOfClass(ItemEntity.class, new AABB(pos), entity ->
                entity.isAlive() && !entity.getItem().isEmpty()).isEmpty();
    }

    @Override
    protected boolean isMachineCraftFinished(@Nonnull ServerLevel currentLevel,
                                             @Nonnull BlockEntity blockEntity) {
        if (!started) return false;
        if (hasPhysicalPrimaryOutput()) return true;
        if (!completionTriggered && blockEntity instanceof RunicAltarBlockEntity altar
                && altar.manaToGet > 0 && altar.getCurrentMana() >= altar.manaToGet
                && hasOwnedReagent()) {
            ServerPlayer owner = PlayerUtils.getOnlinePlayer(machineServer, ownerId);
            if (owner != null && !owner.hasDisconnected()) {
                boolean used = altar.onUsedByWand(owner, ItemStack.EMPTY, Direction.UP);
                completionTriggered = used && altar.isEmpty();
            }
        }
        return completionTriggered || hasPhysicalPrimaryOutput();
    }

    private boolean hasOwnedReagent() {
        if (recipe == null) return false;
        for (UUID entityId : inputEntityIds) {
            Entity raw = level.getEntity(entityId);
            if (raw instanceof ItemEntity item && item.isAlive()
                    && recipe.getReagent().test(item.getItem())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasPhysicalPrimaryOutput() {
        return !level.getEntitiesOfClass(ItemEntity.class, getOutputCaptureRegion(), entity ->
                isNewOperationEntity(entity)
                        && ItemStack.isSameItemSameTags(entity.getItem(), expected)).isEmpty();
    }

    @Override
    public ItemStack collectResult(@Nonnull ServerPlayer player) {
        List<ItemStack> results = collectAllResults(player);
        return results.isEmpty() ? ItemStack.EMPTY : results.get(0);
    }

    @Override
    public List<ItemStack> collectAllResults(@Nonnull ServerPlayer player) {
        if (level == null || pos == null) return List.of();
        List<ItemStack> results = new ArrayList<>();
        for (ItemEntity entity : level.getEntitiesOfClass(
                ItemEntity.class, getOutputCaptureRegion(), this::isOwnedPhysicalOutput)) {
            results.add(entity.getItem().copy());
            entity.discard();
        }
        return results;
    }

    private boolean isOwnedPhysicalOutput(ItemEntity entity) {
        return isNewOperationEntity(entity)
                && XplatAbstractions.INSTANCE.itemFlagsComponent(entity).runicAltarSpawned
                && RunicAltarOutputRules.isOwnedOutput(
                        entity.getItem(), expected, reusableOutputs);
    }

    private boolean isNewOperationEntity(ItemEntity entity) {
        return entity.isAlive()
                && !inputEntityIds.contains(entity.getUUID())
                && BotaniaDelegateSupport.isNew(entity, entitiesBefore)
                && level.getGameTime() >= startTick;
    }

    @Override
    public boolean collectsPhysicalSecondaryOutputs() {
        // Botania returns RuneItem inputs as fresh world entities after a successful craft.
        return true;
    }

    @Override
    protected void clearMachineState(BlockEntity blockEntity, @Nullable ServerPlayer player) {
        boolean refund = !usingSharedLedger;
        List<ItemStack> recoveredInputs = discardOwnedInputEntities(refund, player);
        if (blockEntity instanceof RunicAltarBlockEntity altar) {
            Container inventory = altar.getItemHandler();
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (stack.isEmpty()) continue;
                ItemStack removed = stack.copy();
                inventory.setItem(slot, ItemStack.EMPTY);
                recoveredInputs.add(removed.copy());
                if (refund) refundStandalone(player, removed);
            }
            altar.setChanged();
            level.sendBlockUpdated(pos, altar.getBlockState(), altar.getBlockState(), 3);
        }
        recordFailureRecoveredInputs(recoveredInputs);
        clearLocalState();
        resetState();
    }

    @Override
    protected void clearMissingMachineState(@Nullable ServerPlayer player) {
        recordFailureRecoveredInputs(
                discardOwnedInputEntities(!usingSharedLedger, player));
        clearLocalState();
        resetState();
    }

    private List<ItemStack> discardOwnedInputEntities(boolean refund, @Nullable ServerPlayer player) {
        List<ItemStack> recovered = new ArrayList<>();
        if (level == null) return recovered;
        for (UUID entityId : inputEntityIds) {
            Entity raw = level.getEntity(entityId);
            if (!(raw instanceof ItemEntity item) || !item.isAlive()) continue;
            ItemStack removed = item.getItem().copy();
            item.discard();
            recovered.add(removed.copy());
            if (refund) refundStandalone(player, removed);
        }
        inputEntityIds.clear();
        return recovered;
    }

    private void refundStandalone(@Nullable ServerPlayer player, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        ItemStack leftover = insertIntoStorage(player, stack, false);
        if (leftover.isEmpty()) return;
        if (player != null) {
            PlayerUtils.safeGiveToPlayer(player, leftover, network);
        } else if (level != null && pos != null) {
            ItemEntity drop = new ItemEntity(level,
                    pos.getX() + 0.5D, pos.getY() + 1.1D, pos.getZ() + 0.5D,
                    leftover.copy());
            drop.setDeltaMovement(0.0D, 0.2D, 0.0D);
            level.addFreshEntity(drop);
        }
    }

    private void clearLocalState() {
        started = false;
        completionTriggered = false;
        ownerId = null;
        entitiesBefore = Set.of();
        inputEntityIds.clear();
        reusableOutputs.clear();
    }

    @Override
    public ItemStack getExpectedOutput() {
        return expected.isEmpty() ? null : expected;
    }

    @Override
    public AABB getOutputCaptureRegion() {
        return pos == null ? null : new AABB(pos).inflate(1.5D);
    }

    @Override
    public BlockPos getMachinePos() {
        return pos;
    }

    @Override
    public void onBatchFinished(@Nullable ServerPlayer player) {
        if (!markTerminalCleanup()) return;
        clearLocalState();
        resetState();
    }
}
