package com.huanghuang.rsintegration.mods.forbidden;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.mixin.forbidden.ClibanoMainBlockEntityAccessor;
import com.huanghuang.rsintegration.util.InsertedStackDelta;
import com.huanghuang.rsintegration.util.PlayerUtils;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.util.Action;
import com.stal111.forbidden_arcanus.common.block.entity.clibano.ClibanoFireType;
import com.stal111.forbidden_arcanus.common.block.entity.clibano.ClibanoMainBlockEntity;
import com.stal111.forbidden_arcanus.common.recipe.ClibanoRecipe;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
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
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.world.ForgeChunkManager;
import net.minecraftforge.items.IItemHandler;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** Serial, machine-slot delegate for the two-lane Forbidden & Arcanus Clibano. */
public final class ClibanoBatchDelegate extends AbstractBatchDelegate {

    private static final int OUTPUT_GRACE_TICKS = 10;

    private ServerPlayer player;
    private ServerLevel level;
    private ResourceKey<Level> dimension;
    private BlockPos pos;
    private ClibanoRecipe recipe;
    private ItemStack expectedOutput = ItemStack.EMPTY;
    private int assignedInputSlot = -1;
    private ItemStack placedInput = ItemStack.EMPTY;
    private ItemStack fuelBaseline = ItemStack.EMPTY;
    private ItemStack fuelAdded = ItemStack.EMPTY;
    private ItemStack soulBaseline = ItemStack.EMPTY;
    private ItemStack soulAdded = ItemStack.EMPTY;
    private boolean inputConsumed;
    private boolean resultsCollected;
    private boolean cleanupDone;
    private boolean chunkForced;
    private int outputGraceTicks;

    @Override
    public boolean validateAndInit(@Nonnull ServerPlayer player, @Nonnull ResourceLocation recipeId,
                                   @Nullable ResourceLocation dim, @Nonnull BlockPos pos) {
        resetState();
        resetOperationState();
        ServerLevel resolved = CraftPacketUtils.resolveLevel(player.server, dim, player);
        if (resolved == null) return false;
        resolved.getChunk(pos);

        Recipe<?> found = resolved.getRecipeManager().byKey(recipeId).orElse(null);
        BlockEntity be = resolved.getBlockEntity(pos);
        if (!(found instanceof ClibanoRecipe clibanoRecipe)
                || !(be instanceof ClibanoMainBlockEntity)) {
            return false;
        }

        this.player = player;
        this.level = resolved;
        this.dimension = resolved.dimension();
        this.pos = pos;
        this.recipe = clibanoRecipe;
        this.expectedOutput = clibanoRecipe.getResultItem(resolved.registryAccess()).copy();
        this.machineDim = resolved.dimension().location();
        this.machineServer = player.server;
        this.network = CraftPacketUtils.resolveNetworkForCraft(player, dimension, pos);
        if (network == null || expectedOutput.isEmpty()) {
            resetState();
            resetOperationState();
            return false;
        }

        IItemHandler inventory = fullInventory(be);
        if (inventory == null || inventory.getSlots() < 7) {
            resetState();
            resetOperationState();
            return false;
        }
        MachineData data = readData(be);
        if (ClibanoInventoryLogic.chooseInputSlot(
                inventory.getStackInSlot(ClibanoInventoryLogic.FIRST_INPUT_SLOT),
                inventory.getStackInSlot(ClibanoInventoryLogic.SECOND_INPUT_SLOT),
                data.firstProgress(), data.secondProgress()) < 0 || !canEvacuateOutputs(inventory)) {
            resetState();
            resetOperationState();
            return false;
        }
        return true;
    }

    @Override
    public PreparationResult prepare(@Nonnull ServerPlayer player, @Nonnull ResourceLocation recipeId,
                                     @Nullable ResourceLocation dim, @Nonnull BlockPos pos) {
        try {
            return validateAndInit(player, recipeId, dim, pos)
                    ? PreparationResult.ready()
                    : PreparationResult.retry("Clibano is busy or cannot accept this operation");
        } catch (LinkageError e) {
            return PreparationResult.fatal("Clibano integration contract is unavailable");
        }
    }

    @Nullable
    @Override
    public List<IngredientSpec> getRequiredMaterials() {
        if (recipe == null || recipe.getIngredients().isEmpty()) return null;
        Ingredient ingredient = recipe.getIngredients().get(0);
        return ingredient.isEmpty() ? null : List.of(new IngredientSpec(ingredient, 1));
    }

    @Override
    public boolean tryStartSingleCraft(@Nonnull ServerPlayer player) {
        return false;
    }

    @Override
    public boolean tryStartWithMaterials(@Nonnull ServerPlayer player, @Nonnull List<ItemStack> materials,
                                         @Nonnull ExtractionLedger sharedLedger) {
        this.player = player;
        this.sharedLedger = sharedLedger;
        this.usingSharedLedger = true;
        this.network = CraftPacketUtils.resolveNetworkForCraft(player, dimension, pos);
        if (network == null || materials.size() != 1 || recipe == null) return false;

        ItemStack material = materials.get(0);
        if (material.isEmpty() || !recipe.getIngredients().get(0).test(material)) return false;

        level.getChunk(pos);
        BlockEntity raw = level.getBlockEntity(pos);
        if (!(raw instanceof ClibanoMainBlockEntity be)) return false;
        IItemHandler inventory = fullInventory(be);
        if (inventory == null || inventory.getSlots() < 7) return false;

        MachineData data = readData(be);
        assignedInputSlot = ClibanoInventoryLogic.chooseInputSlot(
                inventory.getStackInSlot(ClibanoInventoryLogic.FIRST_INPUT_SLOT),
                inventory.getStackInSlot(ClibanoInventoryLogic.SECOND_INPUT_SLOT),
                data.firstProgress(), data.secondProgress());
        if (assignedInputSlot < 0) return false;

        fuelBaseline = inventory.getStackInSlot(ClibanoInventoryLogic.FUEL_SLOT).copy();
        soulBaseline = inventory.getStackInSlot(ClibanoInventoryLogic.SOUL_SLOT).copy();

        if (!evacuateOutputs(be, inventory)) return false;
        if (!ensureSoul(be, inventory, data.fireOrdinal())) {
            cleanupRuntimeResources(be, inventory);
            return false;
        }
        if (!ensureFuel(be, inventory, data.burnTime())) {
            cleanupRuntimeResources(be, inventory);
            return false;
        }

        IItemHandler top = be.getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.UP).orElse(null);
        if (top == null) {
            cleanupRuntimeResources(be, inventory);
            return false;
        }
        placedInput = material.copyWithCount(1);
        ItemStack remainder = top.insertItem(assignedInputSlot, placedInput.copy(), false);
        ItemStack inserted = InsertedStackDelta.between(placedInput, remainder);
        if (inserted.getCount() != placedInput.getCount()) {
            if (!inserted.isEmpty()) inventory.extractItem(assignedInputSlot, inserted.getCount(), false);
            placedInput = ItemStack.EMPTY;
            cleanupRuntimeResources(be, inventory);
            return false;
        }

        be.setChanged();
        level.sendBlockUpdated(pos, level.getBlockState(pos), level.getBlockState(pos), 3);
        forceChunkLoad(true);
        markCraftStarted();
        return true;
    }

    @Nonnull
    @Override
    protected CraftObservation observeMachineCraft(@Nonnull ServerLevel level, @Nonnull BlockEntity raw) {
        if (!(raw instanceof ClibanoMainBlockEntity be) || assignedInputSlot < 0) {
            return failObservation("Clibano main block entity missing");
        }
        IItemHandler inventory = fullInventory(be);
        if (inventory == null) return failObservation("Clibano inventory unavailable");

        MachineData data = readData(be);
        int progress = assignedInputSlot == ClibanoInventoryLogic.FIRST_INPUT_SLOT
                ? data.firstProgress() : data.secondProgress();
        ItemStack currentInput = inventory.getStackInSlot(assignedInputSlot);
        boolean matchingInput = !currentInput.isEmpty()
                && ItemStack.isSameItemSameTags(currentInput, placedInput);
        if ((!currentInput.isEmpty() && !matchingInput)
                || currentInput.isEmpty()
                || currentInput.getCount() < placedInput.getCount()) {
            inputConsumed = true;
        }
        int outputCount = matchingOutputCount(inventory);

        if (inputConsumed && outputCount >= expectedOutput.getCount()) return doneObservation();
        if (inputConsumed) {
            if (++outputGraceTicks > OUTPUT_GRACE_TICKS) {
                // Let chain production audit terminate without refunding consumed input.
                return doneObservation();
            }
            return workingObservation();
        }
        if (progress > 0 || matchingInput) return workingObservation();
        return new CraftObservation(CraftPhase.WAITING_FOR_START);
    }

    @Nonnull
    @Override
    public List<ItemStack> collectAllResults(@Nonnull ServerPlayer player) {
        if (resultsCollected || level == null || pos == null) return List.of();
        resultsCollected = true;
        BlockEntity raw = level.getBlockEntity(pos);
        if (!(raw instanceof ClibanoMainBlockEntity be)) return List.of();
        IItemHandler output = be.getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.DOWN).orElse(null);
        if (output == null) return List.of();

        int remaining = expectedOutput.getCount();
        List<ItemStack> results = new ArrayList<>(2);
        for (int slot : new int[]{ClibanoInventoryLogic.FIRST_OUTPUT_SLOT,
                ClibanoInventoryLogic.SECOND_OUTPUT_SLOT}) {
            ItemStack stack = output.getStackInSlot(slot);
            if (remaining <= 0 || stack.isEmpty()
                    || !ItemStack.isSameItemSameTags(stack, expectedOutput)) continue;
            ItemStack extracted = output.extractItem(slot, Math.min(remaining, stack.getCount()), false);
            if (!extracted.isEmpty()) {
                results.add(extracted);
                remaining -= extracted.getCount();
            }
        }
        be.setChanged();
        return List.copyOf(results);
    }

    @Nonnull
    @Override
    public ItemStack collectResult(@Nonnull ServerPlayer player) {
        List<ItemStack> results = collectAllResults(player);
        return results.isEmpty() ? ItemStack.EMPTY : results.get(0);
    }

    @Override
    public boolean collectsPhysicalSecondaryOutputs() {
        return true;
    }

    @Nullable
    @Override
    public ExpectedProduction getExpectedProduction() {
        return expectedOutput.isEmpty() ? null
                : new ExpectedProduction(expectedOutput, expectedOutput.getCount());
    }

    @Override
    protected void clearMachineState(BlockEntity raw, ServerPlayer player) {
        if (cleanupDone) return;
        cleanupDone = true;
        if (raw instanceof ClibanoMainBlockEntity be) {
            IItemHandler inventory = fullInventory(be);
            if (inventory != null) {
                if (!inputConsumed && assignedInputSlot >= 0 && !placedInput.isEmpty()) {
                    ItemStack current = inventory.getStackInSlot(assignedInputSlot);
                    if (ItemStack.isSameItemSameTags(current, placedInput)) {
                        inventory.extractItem(assignedInputSlot,
                                Math.min(placedInput.getCount(), current.getCount()), false);
                    }
                }
                cleanupRuntimeResources(be, inventory);
                be.setChanged();
            }
        }
        forceChunkLoad(false);
        resetState();
    }

    @Override
    public void onBatchFinished(@NotNull ServerPlayer player) {
        if (level != null && pos != null) {
            BlockEntity raw = level.getBlockEntity(pos);
            if (raw instanceof ClibanoMainBlockEntity be) {
                IItemHandler inventory = fullInventory(be);
                if (inventory != null) cleanupRuntimeResources(be, inventory);
            }
        }
        forceChunkLoad(false);
        resetState();
    }

    @Override
    public BlockPos getMachinePos() {
        return pos;
    }

    public static List<String> getPlanWarnings(ServerPlayer player, Recipe<?> recipe,
                                               @Nullable ResourceLocation dim, @Nullable BlockPos pos) {
        List<String> warnings = new ArrayList<>();
        warnings.add(Component.translatable("rsi.clibano.warn.fuel_required").getString());
        if (recipe instanceof ClibanoRecipe clibano && clibano.getRequiredFireType().ordinal() > 0) {
            warnings.add(Component.translatable("rsi.clibano.warn.soul_required").getString());
        }
        return warnings;
    }

    private boolean canEvacuateOutputs(IItemHandler inventory) {
        for (int slot : new int[]{ClibanoInventoryLogic.FIRST_OUTPUT_SLOT,
                ClibanoInventoryLogic.SECOND_OUTPUT_SLOT}) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (stack.isEmpty()) continue;
            ItemStack remainder = network.insertItem(stack.copy(), stack.getCount(), Action.SIMULATE);
            if (!remainder.isEmpty()) return false;
        }
        return true;
    }

    private boolean evacuateOutputs(ClibanoMainBlockEntity be, IItemHandler inventory) {
        if (!canEvacuateOutputs(inventory)) return false;
        IItemHandler output = be.getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.DOWN).orElse(null);
        if (output == null) return false;

        for (int slot : new int[]{ClibanoInventoryLogic.FIRST_OUTPUT_SLOT,
                ClibanoInventoryLogic.SECOND_OUTPUT_SLOT}) {
            ItemStack snapshot = output.getStackInSlot(slot).copy();
            if (snapshot.isEmpty()) continue;
            ItemStack extracted = output.extractItem(slot, snapshot.getCount(), false);
            if (!sameStackAndCount(snapshot, extracted)) {
                safeRestoreOutput(inventory, slot, extracted);
                return false;
            }
            ItemStack remainder = network.insertItem(extracted.copy(), extracted.getCount(), Action.PERFORM);
            if (!remainder.isEmpty()) {
                safeRestoreOutput(inventory, slot, remainder);
                return false;
            }
        }
        be.setChanged();
        return inventory.getStackInSlot(ClibanoInventoryLogic.FIRST_OUTPUT_SLOT).isEmpty()
                && inventory.getStackInSlot(ClibanoInventoryLogic.SECOND_OUTPUT_SLOT).isEmpty();
    }

    private void safeRestoreOutput(IItemHandler inventory, int preferredSlot, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        ItemStack remainder = restoreToSlot(inventory, preferredSlot, stack);
        if (!remainder.isEmpty()) {
            int other = preferredSlot == ClibanoInventoryLogic.FIRST_OUTPUT_SLOT
                    ? ClibanoInventoryLogic.SECOND_OUTPUT_SLOT
                    : ClibanoInventoryLogic.FIRST_OUTPUT_SLOT;
            remainder = restoreToSlot(inventory, other, remainder);
        }
        if (!remainder.isEmpty()) safeDeliver(remainder);
    }

    private static ItemStack restoreToSlot(IItemHandler inventory, int slot, ItemStack stack) {
        ItemStack current = inventory.getStackInSlot(slot);
        if (current.isEmpty()) {
            if (inventory instanceof net.minecraftforge.items.ItemStackHandler handler) {
                handler.setStackInSlot(slot, stack.copy());
                return ItemStack.EMPTY;
            }
            return inventory.insertItem(slot, stack.copy(), false);
        }
        if (!ItemStack.isSameItemSameTags(current, stack)) return stack;
        int room = Math.min(inventory.getSlotLimit(slot), current.getMaxStackSize()) - current.getCount();
        if (room <= 0) return stack;
        int moved = Math.min(room, stack.getCount());
        if (inventory instanceof net.minecraftforge.items.ItemStackHandler handler) {
            ItemStack merged = current.copy();
            merged.grow(moved);
            handler.setStackInSlot(slot, merged);
            return moved == stack.getCount() ? ItemStack.EMPTY
                    : stack.copyWithCount(stack.getCount() - moved);
        }
        return inventory.insertItem(slot, stack.copy(), false);
    }

    private boolean ensureSoul(ClibanoMainBlockEntity be, IItemHandler inventory, int currentFire) {
        int required = recipe.getRequiredFireType().ordinal();
        if (ClibanoInventoryLogic.fireSatisfies(currentFire, required)) return true;
        ItemStack current = inventory.getStackInSlot(ClibanoInventoryLogic.SOUL_SLOT);
        if (!current.isEmpty()) {
            return ClibanoFireType.fromItem(current).ordinal() >= required;
        }

        ItemStack candidate = findNetworkItem(stack -> ClibanoFireType.fromItem(stack).ordinal() >= required
                && ClibanoFireType.fromItem(stack) != ClibanoFireType.FIRE);
        if (candidate.isEmpty()) return false;
        ItemStack extracted = network.extractItem(candidate.copyWithCount(1), 1, Action.PERFORM);
        if (extracted.getCount() != 1) {
            safeDeliver(extracted);
            return false;
        }
        IItemHandler side = be.getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.NORTH).orElse(null);
        if (side == null) {
            safeDeliver(extracted);
            return false;
        }
        ItemStack remainder = side.insertItem(ClibanoInventoryLogic.SOUL_SLOT, extracted, false);
        soulAdded = InsertedStackDelta.between(extracted, remainder);
        if (!remainder.isEmpty()) safeDeliver(remainder);
        return soulAdded.getCount() == 1;
    }

    private boolean ensureFuel(ClibanoMainBlockEntity be, IItemHandler inventory, int bankedBurnTime) {
        ItemStack current = inventory.getStackInSlot(ClibanoInventoryLogic.FUEL_SLOT);
        int neededTicks = Math.max(0,
                recipe.getCookingTime(recipe.getRequiredFireType()) - Math.max(0, bankedBurnTime));
        if (neededTicks == 0) return true;

        ItemStack fuelType = current.isEmpty()
                ? findNetworkItem(stack -> burnDuration(be, stack) > 0)
                : current.copyWithCount(1);
        int duration = burnDuration(be, fuelType);
        if (fuelType.isEmpty() || duration <= 0) return false;
        int count = Math.max(1, (neededTicks + duration - 1) / duration);
        int room = Math.min(inventory.getSlotLimit(ClibanoInventoryLogic.FUEL_SLOT),
                fuelType.getMaxStackSize()) - current.getCount();
        count = Math.min(count, room);
        if (count <= 0) return true;

        ItemStack extracted = network.extractItem(fuelType.copyWithCount(1), count, Action.SIMULATE);
        if (extracted.getCount() != count) return false;
        extracted = network.extractItem(fuelType.copyWithCount(1), count, Action.PERFORM);
        if (extracted.getCount() != count) {
            safeDeliver(extracted);
            return false;
        }
        IItemHandler side = be.getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.NORTH).orElse(null);
        if (side == null) {
            safeDeliver(extracted);
            return false;
        }
        ItemStack remainder = side.insertItem(ClibanoInventoryLogic.FUEL_SLOT, extracted, false);
        fuelAdded = InsertedStackDelta.between(extracted, remainder);
        if (!remainder.isEmpty()) safeDeliver(remainder);
        return !fuelAdded.isEmpty();
    }

    private void cleanupRuntimeResources(ClibanoMainBlockEntity be, IItemHandler inventory) {
        IItemHandler side = be.getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.NORTH).orElse(null);
        if (side == null) return;
        refundAdded(side, ClibanoInventoryLogic.FUEL_SLOT, fuelBaseline, fuelAdded);
        refundAdded(side, ClibanoInventoryLogic.SOUL_SLOT, soulBaseline, soulAdded);
        fuelAdded = ItemStack.EMPTY;
        soulAdded = ItemStack.EMPTY;
    }

    private void refundAdded(IItemHandler handler, int slot, ItemStack baseline, ItemStack added) {
        if (added.isEmpty()) return;
        ItemStack current = handler.getStackInSlot(slot);
        int refundable = ClibanoInventoryLogic.refundableAddedCount(baseline, added.getCount(), current);
        if (refundable <= 0) return;
        ItemStack extracted = handler.extractItem(slot, refundable, false);
        if (!extracted.isEmpty()) safeDeliver(extracted);
    }

    private int matchingOutputCount(IItemHandler inventory) {
        return ClibanoInventoryLogic.countMatching(List.of(
                inventory.getStackInSlot(ClibanoInventoryLogic.FIRST_OUTPUT_SLOT),
                inventory.getStackInSlot(ClibanoInventoryLogic.SECOND_OUTPUT_SLOT)), expectedOutput);
    }

    private ItemStack findNetworkItem(java.util.function.Predicate<ItemStack> predicate) {
        for (var entry : new ArrayList<>(network.getItemStorageCache().getList().getStacks())) {
            ItemStack stack = entry.getStack();
            if (!stack.isEmpty() && predicate.test(stack)) return stack.copyWithCount(1);
        }
        return ItemStack.EMPTY;
    }

    private static int burnDuration(ClibanoMainBlockEntity be, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0;
        try {
            return ((ClibanoMainBlockEntityAccessor) be).rsi$callGetBurnDuration(stack);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    @Nullable
    private static IItemHandler fullInventory(BlockEntity be) {
        if (be instanceof ClibanoMainBlockEntity clibano) {
            return clibano.getItemStackHandler();
        }
        return null;
    }

    private static MachineData readData(BlockEntity be) {
        CompoundTag tag = be.saveWithoutMetadata();
        return new MachineData(
                tag.getInt("BurnTime"),
                tag.getInt("CookingProgressFirst"),
                tag.getInt("CookingProgressSecond"),
                parseFireOrdinal(tag.getString("FireType")));
    }

    private static int parseFireOrdinal(String name) {
        if ("enchanted_fire".equals(name)) return ClibanoFireType.ENCHANTED_FIRE.ordinal();
        if ("soul_fire".equals(name)) return ClibanoFireType.SOUL_FIRE.ordinal();
        return ClibanoFireType.FIRE.ordinal();
    }

    private void safeDeliver(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        ItemStack remainder = stack.copy();
        if (network != null) {
            remainder = network.insertItem(remainder, remainder.getCount(), Action.PERFORM);
            if (remainder.isEmpty()) return;
        }
        if (player != null && !player.hasDisconnected()) {
            PlayerUtils.safeGiveToPlayer(player, remainder, null);
            return;
        }
        if (level != null && pos != null) {
            level.addFreshEntity(new ItemEntity(level,
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, remainder.copy()));
        }
    }

    private void forceChunkLoad(boolean load) {
        if (level == null || pos == null || chunkForced == load) return;
        try {
            ForgeChunkManager.forceChunk(level, RSIntegrationMod.MOD_ID, pos,
                    pos.getX() >> 4, pos.getZ() >> 4, load, true);
            chunkForced = load;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Clibano] Failed to update chunk ticket at {}", pos, e);
        }
    }

    private void resetOperationState() {
        assignedInputSlot = -1;
        placedInput = ItemStack.EMPTY;
        fuelBaseline = ItemStack.EMPTY;
        fuelAdded = ItemStack.EMPTY;
        soulBaseline = ItemStack.EMPTY;
        soulAdded = ItemStack.EMPTY;
        inputConsumed = false;
        resultsCollected = false;
        cleanupDone = false;
        outputGraceTicks = 0;
    }

    private static boolean sameStackAndCount(ItemStack expected, ItemStack actual) {
        return expected.getCount() == actual.getCount()
                && ItemStack.isSameItemSameTags(expected, actual);
    }

    private record MachineData(int burnTime, int firstProgress, int secondProgress, int fireOrdinal) {}
}
