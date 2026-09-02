package com.huanghuang.rsintegration.mods.wishingfountain;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.util.PlayerUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.items.IItemHandlerModifiable;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Executes one wish through a formed Wishing Fountain multiblock. */
public final class WishingFountainBatchDelegate extends AbstractBatchDelegate {
    private ServerLevel level;
    private BlockPos pos;
    private Recipe<?> recipe;
    private WishingFountainStructure.Resolved structure;
    private ItemStack expected = ItemStack.EMPTY;
    private boolean started;
    private boolean actionInvoked;
    private final Set<UUID> entitiesBefore = new HashSet<>();
    private final List<Integer> placedSlots = new ArrayList<>();

    @Override
    public boolean validateAndInit(@Nonnull ServerPlayer player,
                                   @Nonnull ResourceLocation recipeId,
                                   @Nullable ResourceLocation dim,
                                   @Nonnull BlockPos machinePos) {
        return prepareInternal(player, recipeId, dim, machinePos).state()
                == PreparationState.READY;
    }

    @Override
    public PreparationResult prepare(@Nonnull ServerPlayer player,
                                     @Nonnull ResourceLocation recipeId,
                                     @Nullable ResourceLocation dim,
                                     @Nonnull BlockPos machinePos) {
        return prepareInternal(player, recipeId, dim, machinePos);
    }

    private PreparationResult prepareInternal(ServerPlayer player, ResourceLocation recipeId,
                                              @Nullable ResourceLocation dim, BlockPos machinePos) {
        clearLocalState();
        this.machineDim = dim;
        this.machineServer = player.getServer();
        this.level = dim == null ? player.serverLevel() : player.getServer().getLevel(
                ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dim));
        if (level == null) return PreparationResult.retry("Wishing Fountain dimension is unavailable");
        if (!level.hasChunkAt(machinePos)) {
            return PreparationResult.retry("Wishing Fountain chunk is unloaded");
        }

        this.structure = WishingFountainStructure.resolve(level, machinePos);
        if (structure == null) {
            return PreparationResult.retry("Wishing Fountain multiblock is incomplete");
        }
        this.pos = structure.corePos();
        if (!allInputsEmpty()) {
            return PreparationResult.retry("Wishing Fountain still contains materials");
        }

        Recipe<?> found = level.getRecipeManager().byKey(recipeId).orElse(null);
        if (found == null || !WishingFountainRecipeHandler.RECIPE_CLASS.equals(
                found.getClass().getName())) {
            return PreparationResult.fatal(
                    "Recipe is not a Wishing Fountain recipe: " + recipeId);
        }
        List<IngredientSpec> specs = specifications(found);
        if (specs.isEmpty() || specs.size() > structure.inputEntities().size()) {
            return PreparationResult.fatal(
                    "Wishing Fountain recipe requires an invalid number of materials: " + recipeId);
        }
        this.recipe = found;
        ItemStack result = found.getResultItem(level.registryAccess());
        this.expected = result == null ? ItemStack.EMPTY : result.copy();
        if (expected.isEmpty()) {
            return PreparationResult.fatal(
                    "Wishing Fountain recipe does not produce an item: " + recipeId);
        }

        if (!hasStorageAccess()) {
            this.network = CraftPacketUtils.resolveNetworkForCraft(
                    player, level.dimension(), pos);
        }
        if (!hasStorageAccess()) {
            return PreparationResult.retry("Wishing Fountain is not connected to a storage backend");
        }
        RSIntegrationMod.LOGGER.info(
                "[RSI-WishingFountain] Ready recipe={} core={} inputs={} backend={}",
                recipeId, pos, structure.inputEntities().size(),
                storageEndpoint().session().reference());
        return PreparationResult.ready();
    }

    @Nullable
    @Override
    public List<IngredientSpec> getRequiredMaterials() {
        return recipe == null ? null : specifications(recipe);
    }

    private static List<IngredientSpec> specifications(Recipe<?> recipe) {
        List<IngredientSpec> result = new ArrayList<>();
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (!ingredient.isEmpty()) result.add(new IngredientSpec(ingredient, 1));
        }
        return result;
    }

    @Override
    public boolean tryStartSingleCraft(@Nonnull ServerPlayer player) {
        if (!canStartNow()) return false;
        this.ledger = new ExtractionLedger();
        this.ledger.setStorageEndpoint(storageEndpoint());
        this.usingSharedLedger = false;
        List<ItemStack> materials = reserveMaterials(player, ledger);
        if (materials.isEmpty()) return false;
        if (!ledger.commit(network, player)) return false;
        if (start(player, materials)) return true;
        ledger.refundCommitted(network, player);
        return false;
    }

    private List<ItemStack> reserveMaterials(ServerPlayer player, ExtractionLedger targetLedger) {
        List<IngredientSpec> specs = getRequiredMaterials();
        if (specs == null || specs.isEmpty()) return List.of();
        List<ItemStack> materials = new ArrayList<>(specs.size());
        for (IngredientSpec spec : specs) {
            ItemStack reserved = CraftPacketUtils.ensureMaterialAvailable(
                    player, level.dimension(), pos, spec.ingredient(), spec.count(), targetLedger);
            if (reserved.isEmpty()) return List.of();
            materials.add(reserved);
        }
        return materials;
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
            if (material == null || material.isEmpty()
                    || material.getCount() != spec.count()
                    || !spec.ingredient().test(material)) return false;
        }

        entitiesBefore.clear();
        for (ItemEntity entity : level.getEntitiesOfClass(
                ItemEntity.class, getOutputCaptureRegion())) {
            entitiesBefore.add(entity.getUUID());
        }

        placedSlots.clear();
        try {
            for (int i = 0; i < materials.size(); i++) {
                BlockEntity input = structure.inputEntities().get(i);
                IItemHandlerModifiable handler = WishingFountainStructure.itemHandler(input);
                if (handler == null || !handler.getStackInSlot(0).isEmpty()) {
                    discardPlacedMaterials();
                    return false;
                }
                handler.setStackInSlot(0, materials.get(i).copy());
                placedSlots.add(i);
                WishingFountainStructure.refresh(input);
            }

            invokeWish(recipe, level, pos);
            actionInvoked = true;
            if (!consumePlacedMaterials()) {
                RSIntegrationMod.LOGGER.error(
                        "[RSI-WishingFountain] Wish {} produced its output, but one or more input slots could not be cleared at {}",
                        recipe.getId(), pos);
            }
            RSIntegrationMod.LOGGER.info(
                    "[RSI-WishingFountain] Materials placed and wish invoked recipe={} core={} slots={}",
                    recipe.getId(), pos, materials.size());
            started = true;
            markCraftStarted();
            forceMachineChunk(level, pos, true);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            RSIntegrationMod.LOGGER.error(
                    "[RSI-WishingFountain] Failed to execute recipe {} at {}",
                    recipe == null ? "unknown" : recipe.getId(), pos, failure);
            discardPlacedMaterials();
            return false;
        }
    }

    private static void invokeWish(Recipe<?> recipe, Level level, BlockPos pos)
            throws ReflectiveOperationException {
        Method method = recipe.getClass().getMethod(
                "spawnOutputEntity", Level.class, BlockPos.class);
        method.invoke(recipe, level, pos);
    }

    private boolean canStartNow() {
        if (level == null || pos == null || recipe == null || !hasStorageAccess()) return false;
        WishingFountainStructure.Resolved current = WishingFountainStructure.resolve(level, pos);
        if (current == null || !current.corePos().equals(pos)) return false;
        this.structure = current;
        return allInputsEmpty();
    }

    private boolean allInputsEmpty() {
        if (structure == null || structure.inputEntities().size() != 8) return false;
        for (BlockEntity input : structure.inputEntities()) {
            IItemHandlerModifiable handler = WishingFountainStructure.itemHandler(input);
            if (handler == null || !handler.getStackInSlot(0).isEmpty()) return false;
        }
        return true;
    }

    private boolean consumePlacedMaterials() {
        if (structure == null) return false;
        boolean consumedAll = true;
        for (int index : placedSlots) {
            BlockEntity input = structure.inputEntities().get(index);
            IItemHandlerModifiable handler = WishingFountainStructure.itemHandler(input);
            if (handler == null) {
                consumedAll = false;
                continue;
            }
            try {
                ItemStack removed = handler.extractItem(0, 1, false);
                if (removed.isEmpty()) consumedAll = false;
            } catch (RuntimeException | LinkageError failure) {
                consumedAll = false;
                try {
                    handler.setStackInSlot(0, ItemStack.EMPTY);
                } catch (RuntimeException | LinkageError fallbackFailure) {
                    failure.addSuppressed(fallbackFailure);
                    RSIntegrationMod.LOGGER.error(
                            "[RSI-WishingFountain] Could not clear consumed material at {}",
                            input.getBlockPos(), failure);
                }
            }
            WishingFountainStructure.refresh(input);
        }
        placedSlots.clear();
        return consumedAll;
    }

    private List<ItemStack> discardPlacedMaterials() {
        List<ItemStack> removed = new ArrayList<>();
        if (structure != null) {
            for (int index : placedSlots) {
                BlockEntity input = structure.inputEntities().get(index);
                IItemHandlerModifiable handler = WishingFountainStructure.itemHandler(input);
                if (handler != null) {
                    ItemStack stack = handler.extractItem(0, Integer.MAX_VALUE, false);
                    if (!stack.isEmpty()) removed.add(stack.copy());
                }
                WishingFountainStructure.refresh(input);
            }
        }
        placedSlots.clear();
        return removed;
    }

    @Override
    protected boolean isMachineCraftFinished(@Nonnull ServerLevel currentLevel,
                                             @Nonnull BlockEntity blockEntity) {
        return started && actionInvoked;
    }

    @Override
    public ItemStack collectResult(@Nonnull ServerPlayer player) {
        if (expected.isEmpty() || level == null) return ItemStack.EMPTY;
        for (ItemEntity entity : level.getEntitiesOfClass(
                ItemEntity.class, getOutputCaptureRegion(), this::isOwnedOutput)) {
            ItemStack result = entity.getItem().copy();
            entity.discard();
            return result;
        }
        return ItemStack.EMPTY;
    }

    private boolean isOwnedOutput(ItemEntity entity) {
        return entity.isAlive() && !entitiesBefore.contains(entity.getUUID())
                && ItemStack.isSameItemSameTags(entity.getItem(), expected);
    }

    @Nullable
    @Override
    public ExpectedProduction getExpectedProduction() {
        return expected.isEmpty() ? null : new ExpectedProduction(expected, expected.getCount());
    }

    @Nullable
    @Override
    public ItemStack getExpectedOutput() {
        return expected.isEmpty() ? null : expected;
    }

    @Override
    public boolean canCollectResultWithoutWorldCapture() {
        return true;
    }

    @Override
    public boolean publishesDeclaredGraphOutputs() {
        return !expected.isEmpty();
    }

    @Nullable
    @Override
    public AABB getOutputCaptureRegion() {
        return pos == null ? null : new AABB(pos.above(2)).inflate(1.5D);
    }

    @Nonnull
    @Override
    public BlockPos getMachinePos() {
        return pos;
    }

    @Override
    protected void clearMachineState(BlockEntity blockEntity, @Nullable ServerPlayer player) {
        List<ItemStack> recovered = discardPlacedMaterials();
        recordFailureRecoveredInputs(recovered);
        if (!usingSharedLedger) {
            for (ItemStack stack : recovered) refundStandalone(player, stack);
        }
        clearLocalState();
        resetState();
    }

    private void refundStandalone(@Nullable ServerPlayer player, ItemStack stack) {
        ItemStack remainder = insertIntoStorage(player, stack, false);
        if (remainder.isEmpty()) return;
        if (player != null) {
            PlayerUtils.safeGiveToPlayer(player, remainder, network);
        } else if (level != null && pos != null) {
            level.addFreshEntity(new ItemEntity(level, pos.getX() + 0.5D,
                    pos.getY() + 1.0D, pos.getZ() + 0.5D, remainder.copy()));
        }
    }

    @Override
    public void onBatchFinished(@Nullable ServerPlayer player) {
        if (!markTerminalCleanup()) return;
        clearLocalState();
        resetState();
    }

    private void clearLocalState() {
        started = false;
        actionInvoked = false;
        entitiesBefore.clear();
        placedSlots.clear();
    }
}
