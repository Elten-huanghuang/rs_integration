package com.huanghuang.rsintegration.mods.ironfurnaces;

import com.huanghuang.rsintegration.network.RSIntegrationNetwork;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.network.RSIntegrationNetwork;
import com.huanghuang.rsintegration.mods.vanilla.VanillaFurnaceFuelPolicy;
import com.refinedmods.refinedstorage.api.util.Action;
import ironfurnaces.tileentity.furnaces.BlockIronFurnaceTileBase;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.BlastingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.item.crafting.SmokingRecipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.items.ItemHandlerHelper;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Batch delegate for Iron Furnaces ordinary furnace mode. */
public final class IronFurnacesBatchDelegate extends AbstractBatchDelegate {

    private static final int INPUT = 0;
    private static final int FUEL = 1;
    private static final int OUTPUT = 2;
    private static final int[] FACTORY_INPUT = {7, 8, 9, 10, 11, 12};
    private static final Map<String, boolean[]> FACTORY_LEASES = new ConcurrentHashMap<>();

    private ServerPlayer player;
    private ServerLevel level;
    private ResourceKey<Level> dimension;
    private BlockPos pos;
    private AbstractCookingRecipe recipe;
    private BlockIronFurnaceTileBase furnace;
    private boolean observedWorking;
    private int initialInputCount;
    private ItemStack initialFuel = ItemStack.EMPTY;
    private int suppliedFuelCount;
    private boolean inputPlaced;
    private int factorySlot = -1;
    private boolean factoryMode;
    private String factoryLeaseKey;
    private final boolean[] ownedFactoryLanes = new boolean[FACTORY_INPUT.length];

    @Override
    public int prepareFlatBatch(int remainingOperations) {
        // One physical factory can run exactly six independent lanes.
        return factoryMode ? Math.min(FACTORY_INPUT.length, Math.max(0, remainingOperations))
                : super.prepareFlatBatch(remainingOperations);
    }

    @Override
    public PreparationResult prepare(@NotNull ServerPlayer player, @NotNull ResourceLocation recipeId,
                                     @Nullable ResourceLocation dim, @NotNull BlockPos pos) {
        ServerLevel target = CraftPacketUtils.resolveLevel(player.server, dim, player);
        if (target == null) return PreparationResult.fatal("machine dimension unavailable");
        if (!target.isLoaded(pos)) target.getChunk(pos);

        Recipe<?> found = target.getRecipeManager().byKey(recipeId).orElse(null);
        if (!(found instanceof AbstractCookingRecipe cooking)) {
            return PreparationResult.fatal("recipe is not a cooking recipe");
        }
        BlockEntity be = target.getBlockEntity(pos);
        if (!(be instanceof BlockIronFurnaceTileBase ironFurnace)) {
            return PreparationResult.retry("Iron Furnaces block entity unavailable");
        }
        PreparationResult state = validateMachine(ironFurnace, cooking);
        if (state.state() != PreparationState.READY) return state;
        this.network = CraftPacketUtils.resolveNetworkForCraft(player, target.dimension(), pos);
        this.factoryMode = ironFurnace.isFactory();
        if (factoryMode) {
            factoryLeaseKey = target.dimension().location() + ":" + pos.asLong();
            factorySlot = reserveFactorySlot(factoryLeaseKey, ironFurnace);
            if (factorySlot < 0) return PreparationResult.retry("Iron Furnace factory has no free slot");
        } else if (!ironFurnace.getItem(INPUT).isEmpty() || !ironFurnace.getItem(OUTPUT).isEmpty()) {
            return PreparationResult.retry("Iron Furnace input or output is occupied");
        }

        this.player = player;
        this.level = target;
        this.dimension = target.dimension();
        this.pos = pos;
        this.recipe = cooking;
        this.furnace = ironFurnace;
        this.machineDim = target.dimension().location();
        this.machineServer = player.server;
        this.observedWorking = false;
        this.initialInputCount = 0;
        this.initialFuel = ironFurnace.getItem(FUEL).copy();
        this.suppliedFuelCount = 0;
        this.inputPlaced = false;
        markCraftStarted();
        return PreparationResult.ready();
    }

    @Override
    public boolean validateAndInit(@NotNull ServerPlayer player, @NotNull ResourceLocation recipeId,
                                   @Nullable ResourceLocation dim, @NotNull BlockPos pos) {
        return prepare(player, recipeId, dim, pos).state() == PreparationState.READY;
    }

    private static PreparationResult validateMachine(BlockIronFurnaceTileBase furnace,
                                                       AbstractCookingRecipe recipe) {
        if (furnace.isGenerator() || (!furnace.isFactory() && !furnace.isFurnace())) {
            return PreparationResult.fatal("Iron Furnace mode is not supported");
        }
        RecipeType<?> expected = recipeType(recipe);
        if (expected == null || furnace.recipeType != expected) {
            return PreparationResult.fatal("Iron Furnace recipe mode does not match its augment");
        }
        return PreparationResult.ready();
    }

    @Nullable
    public static RecipeType<?> recipeType(Recipe<?> recipe) {
        if (recipe instanceof BlastingRecipe) return RecipeType.BLASTING;
        if (recipe instanceof SmokingRecipe) return RecipeType.SMOKING;
        if (recipe instanceof SmeltingRecipe) return RecipeType.SMELTING;
        return null;
    }

    @Nullable
    @Override
    public List<IngredientSpec> getRequiredMaterials() {
        return recipe == null ? null : CraftPacketUtils.extractIngredientSpecs(recipe);
    }

    @Override
    public boolean tryStartSingleCraft(@NotNull ServerPlayer player) {
        this.ledger = new ExtractionLedger();
        this.usingSharedLedger = false;
        return reserveAndStart(player, ledger, true);
    }

    @Override
    public boolean tryStartSingleCraft(@NotNull ServerPlayer player,
                                       @NotNull ExtractionLedger sharedLedger) {
        this.sharedLedger = sharedLedger;
        this.usingSharedLedger = true;
        return reserveAndStart(player, sharedLedger, false);
    }

    private boolean reserveAndStart(ServerPlayer player, ExtractionLedger activeLedger,
                                    boolean commit) {
        if (recipe == null || recipe.getIngredients().isEmpty()) return false;
        resolveNetwork(player);
        Ingredient input = recipe.getIngredients().get(0);
        ItemStack material = CraftPacketUtils.ensureMaterialAvailable(
                player, dimension, pos, input, 1, activeLedger);
        if (material.isEmpty()) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.missing_materials",
                    CraftPacketUtils.describeIngredient(input)));
            return false;
        }
        if (commit && (network == null || !activeLedger.commit(network, player))) return false;
        return startWithMaterial(material);
    }

    @Override
    public boolean tryStartWithMaterials(@NotNull ServerPlayer player,
                                         @NotNull List<ItemStack> materials,
                                         @NotNull ExtractionLedger sharedLedger) {
        this.player = player;
        this.sharedLedger = sharedLedger;
        this.usingSharedLedger = true;
        resolveNetwork(player);
        if (factoryMode && !materials.isEmpty()) {
            if (!refreshMachine() || furnace.getEnergy() <= 0) return false;
            List<ItemStack> laneInputs = splitFactoryMaterials(materials);
            if (laneInputs.isEmpty()) return false;
            int placed = 0;
            for (ItemStack laneInput : laneInputs) {
                int slot = placed == 0 && factorySlot >= 0 && ownedFactoryLanes[factorySlot]
                        ? factorySlot : reserveFactorySlot(factoryLeaseKey, furnace);
                if (slot < 0) {
                    rollbackFactoryPlacement(furnace);
                    return false;
                }
                furnace.setItem(FACTORY_INPUT[slot], laneInput);
                if (placed++ == 0) {
                    factorySlot = slot;
                    inputPlaced = true;
                    initialInputCount = 1;
                }
            }
            furnace.setChanged();
            observedWorking = false;
            markCraftStarted();
            return true;
        }
        return !materials.isEmpty() && startWithMaterial(materials.get(0));
    }

    static List<ItemStack> splitFactoryMaterials(List<ItemStack> materials) {
        List<ItemStack> lanes = new ArrayList<>(FACTORY_INPUT.length);
        for (ItemStack material : materials) {
            if (material.isEmpty()) return List.of();
            for (int remaining = material.getCount(); remaining > 0; remaining--) {
                if (lanes.size() == FACTORY_INPUT.length) return List.of();
                lanes.add(material.copyWithCount(1));
            }
        }
        return lanes;
    }

    private boolean startWithMaterial(ItemStack material) {
        if (material.isEmpty() || !refreshMachine()) return false;
        PreparationResult state = validateMachine(furnace, recipe);
        int in = factoryMode ? FACTORY_INPUT[factorySlot] : INPUT;
        int out = factoryMode ? in + 6 : OUTPUT;
        if (state.state() != PreparationState.READY || !furnace.getItem(in).isEmpty() || !furnace.getItem(out).isEmpty()) return false;
        if (factoryMode && furnace.getEnergy() <= 0) return false;

        ItemStack placed = material.copy();
        furnace.setItem(in, placed);
        inputPlaced = true;
        initialInputCount = placed.getCount();
        if (!factoryMode && !ensureFuel()) {
            furnace.setItem(in, ItemStack.EMPTY);
            inputPlaced = false;
            furnace.setChanged();
            player.sendSystemMessage(Component.translatable("rsi.ironfurnaces.error.no_fuel"));
            return false;
        }
        furnace.setChanged();
        level.sendBlockUpdated(pos, level.getBlockState(pos), level.getBlockState(pos), 3);
        observedWorking = false;
        markCraftStarted();
        return true;
    }

    private boolean refreshMachine() {
        if (level == null || pos == null) return false;
        if (!level.isLoaded(pos)) level.getChunk(pos);
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof BlockIronFurnaceTileBase current)) return false;
        furnace = current;
        return true;
    }

    private void resolveNetwork(ServerPlayer player) {
        this.player = player;
        this.network = CraftPacketUtils.resolveNetworkForCraft(player, dimension, pos);
        if (network == null) network = RSIntegrationNetwork.resolveNetworkFromPlayer(player);
    }

    private boolean ensureFuel() {
        int remaining = Math.max(0, recipe.getCookingTime() - Math.max(0, furnace.furnaceBurnTime));
        if (remaining == 0) return true;

        ItemStack existing = furnace.getItem(FUEL);
        if (!existing.isEmpty()) {
            int burn = BlockIronFurnaceTileBase.getBurnTime(existing, furnace.recipeType);
            if (burn <= 0) return false;
            int needed = VanillaFurnaceFuelPolicy.requiredAmount(remaining, burn);
            int limit = Math.min(existing.getMaxStackSize(), furnace.getMaxStackSize());
            if (needed > limit) return false;
            if (existing.getCount() >= needed) return true;
            ItemStack extra = extractExact(existing, needed - existing.getCount());
            if (extra.isEmpty()) return false;
            ItemStack merged = existing.copy();
            merged.grow(extra.getCount());
            furnace.setItem(FUEL, merged);
            suppliedFuelCount += extra.getCount();
            return true;
        }

        if (network == null) return false;
        List<ItemStack> candidates = new ArrayList<>();
        for (var entry : network.getItemStorageCache().getList().getStacks()) {
            candidates.add(entry.getStack());
        }
        VanillaFurnaceFuelPolicy.Selection selection = VanillaFurnaceFuelPolicy.select(
                candidates, RSIntegrationConfig.VANILLA_FURNACE_FUEL_PRIORITY.get(),
                furnace.recipeType, remaining);
        if (selection == null) return false;
        ItemStack extracted = extractExact(selection.fuel(), selection.amount());
        if (extracted.isEmpty()) return false;
        furnace.setItem(FUEL, extracted);
        suppliedFuelCount += extracted.getCount();
        player.displayClientMessage(Component.translatable(
                "rsi.ironfurnaces.info.fuel_supplied", extracted.getCount()), true);
        return true;
    }

    private ItemStack extractExact(ItemStack template, int amount) {
        if (network == null || amount <= 0) return ItemStack.EMPTY;
        ItemStack extracted = network.extractItem(template.copyWithCount(1), amount, Action.PERFORM);
        if (extracted.getCount() == amount) return extracted;
        if (!extracted.isEmpty()) network.insertItem(extracted, extracted.getCount(), Action.PERFORM);
        return ItemStack.EMPTY;
    }

    @NotNull
    @Override
    protected CraftObservation observeMachineCraft(@NotNull ServerLevel level,
                                                    @NotNull BlockEntity be) {
        if (!(be instanceof BlockIronFurnaceTileBase current)) {
            return failObservation("Iron Furnace block entity replaced");
        }
        if (validateMachine(current, recipe).state() != PreparationState.READY) {
            return failObservation("Iron Furnace mode changed during crafting");
        }
        furnace = current;
        int inSlot = factoryMode ? FACTORY_INPUT[factorySlot] : INPUT;
        int outSlot = factoryMode ? inSlot + 6 : OUTPUT;
        ItemStack output = current.getItem(outSlot);
        ItemStack input = current.getItem(inSlot);
        boolean inputConsumed = inputPlaced && initialInputCount > 0 && input.getCount() < initialInputCount;
        if (inputConsumed) inputPlaced = false;
        if (current.isBurning() || (factoryMode && current.factoryCookTime[factorySlot] > 0) || inputConsumed) observedWorking = true;
        if (!observedWorking) return workingObservation();
        if (!output.isEmpty() || (inputConsumed && input.isEmpty() && current.cookTime == 0)) {
            return doneObservation();
        }
        return workingObservation();
    }

    @Override
    protected boolean isMachineCraftFinished(@NotNull ServerLevel level, @NotNull BlockEntity be) {
        return observeMachineCraft(level, be).phase() == CraftPhase.DONE;
    }

    @NotNull
    @Override
    public ItemStack collectResult(@NotNull ServerPlayer player) {
        if (!refreshMachine()) return ItemStack.EMPTY;
        int outSlot = factoryMode ? FACTORY_INPUT[factorySlot] + 6 : OUTPUT;
        ItemStack result = furnace.getItem(outSlot).copy();
        if (!result.isEmpty()) {
            furnace.setItem(outSlot, ItemStack.EMPTY);
            furnace.setChanged();
        }
        if (factoryMode) {
            int next = findNextFactoryLane(furnace, factorySlot);
            if (next >= 0) {
                factorySlot = next;
                ItemStack nextInput = furnace.getItem(FACTORY_INPUT[next]);
                inputPlaced = !nextInput.isEmpty();
                initialInputCount = nextInput.getCount();
                observedWorking = !furnace.getItem(FACTORY_INPUT[next] + 6).isEmpty()
                        || furnace.factoryCookTime[next] > 0;
            }
        }
        return result;
    }

    @Nullable
    @Override
    public ExpectedProduction getExpectedProduction() {
        if (recipe == null || level == null) return null;
        ItemStack result = recipe.getResultItem(level.registryAccess()).copy();
        return result.isEmpty() ? null : new ExpectedProduction(result, result.getCount());
    }

    @Override
    protected void clearMachineState(BlockEntity be, @Nullable ServerPlayer player) {
        if (!(be instanceof BlockIronFurnaceTileBase current)) {
            releaseFactoryLease();
            resetState();
            return;
        }
        boolean refundPhysical = player == null;
        if (factoryMode) {
            for (int lane = 0; lane < FACTORY_INPUT.length; lane++) {
                if (!ownedFactoryLanes[lane]) continue;
                int inSlot = FACTORY_INPUT[lane];
                ItemStack input = current.getItem(inSlot);
                if (!input.isEmpty()) {
                    current.setItem(inSlot, ItemStack.EMPTY);
                    if (refundPhysical) refund(input);
                }
                ItemStack output = current.getItem(inSlot + 6);
                if (!output.isEmpty() && observedWorking) {
                    current.setItem(inSlot + 6, ItemStack.EMPTY);
                    if (refundPhysical) refund(output);
                }
            }
            inputPlaced = false;
        } else if (inputPlaced) {
            int inSlot = INPUT;
            ItemStack input = current.getItem(inSlot);
            if (!input.isEmpty()) {
                current.setItem(inSlot, ItemStack.EMPTY);
                if (refundPhysical) refund(input);
            }
            inputPlaced = false;
        }
        if (!factoryMode) {
            ItemStack output = current.getItem(OUTPUT);
            if (!output.isEmpty() && observedWorking) {
                current.setItem(OUTPUT, ItemStack.EMPTY);
                if (refundPhysical) refund(output);
            }
        }
        refundFuel(current);
        current.setChanged();
        releaseFactoryLease();
        resetState();
    }

    @Override
    protected void clearMissingMachineState(@Nullable ServerPlayer player) {
        releaseFactoryLease();
        resetState();
    }

    @Override
    public void onBatchFinished(@NotNull ServerPlayer player) {
        if (refreshMachine()) refundFuel(furnace);
        releaseFactoryLease();
        resetState();
    }

    private void refundFuel(BlockIronFurnaceTileBase current) {
        ItemStack fuel = current.getItem(FUEL);
        if (fuel.isEmpty() || BlockIronFurnaceTileBase.getBurnTime(fuel, current.recipeType) <= 0) return;
        if (!initialFuel.isEmpty() && !ItemStack.isSameItemSameTags(initialFuel, fuel)) return;
        int owned = Math.min(fuel.getCount(), suppliedFuelCount);
        if (owned <= 0) return;
        ItemStack refund = fuel.copyWithCount(owned);
        ItemStack retained = fuel.copy();
        retained.shrink(owned);
        current.setItem(FUEL, retained.isEmpty() ? ItemStack.EMPTY : retained);
        refund(refund);
    }

    private void refund(ItemStack stack) {
        if (stack.isEmpty()) return;
        if (network != null) {
            ItemStack leftover = network.insertItem(stack.copy(), stack.getCount(), Action.PERFORM);
            if (!leftover.isEmpty() && player != null) ItemHandlerHelper.giveItemToPlayer(player, leftover);
        } else if (player != null) {
            ItemHandlerHelper.giveItemToPlayer(player, stack.copy());
        } else if (level != null && pos != null) {
            Block.popResource(level, pos, stack.copy());
        }
    }

    @NotNull
    @Override
    public BlockPos getMachinePos() {
        return pos;
    }

    private int findFactorySlot(BlockIronFurnaceTileBase f) {
        for (int i = 0; i < FACTORY_INPUT.length; i++)
            if (f.getItem(FACTORY_INPUT[i]).isEmpty() && f.getItem(FACTORY_INPUT[i] + 6).isEmpty()) return i;
        return -1;
    }

    private int reserveFactorySlot(String key, BlockIronFurnaceTileBase f) {
        boolean[] leases = FACTORY_LEASES.computeIfAbsent(key, ignored -> new boolean[FACTORY_INPUT.length]);
        synchronized (leases) {
            for (int i = 0; i < FACTORY_INPUT.length; i++) {
                if (!leases[i] && f.getItem(FACTORY_INPUT[i]).isEmpty()
                        && f.getItem(FACTORY_INPUT[i] + 6).isEmpty()) {
                    leases[i] = true;
                    ownedFactoryLanes[i] = true;
                    return i;
                }
            }
        }
        return -1;
    }

    private void releaseFactoryLease() {
        if (factoryLeaseKey == null) return;
        boolean[] leases = FACTORY_LEASES.get(factoryLeaseKey);
        if (leases != null) synchronized (leases) {
            for (int i = 0; i < ownedFactoryLanes.length; i++) {
                if (ownedFactoryLanes[i]) leases[i] = false;
                ownedFactoryLanes[i] = false;
            }
            boolean empty = true;
            for (boolean lease : leases) empty &= !lease;
            if (empty) FACTORY_LEASES.remove(factoryLeaseKey, leases);
        }
        factoryLeaseKey = null;
        factorySlot = -1;
    }

    private void rollbackFactoryPlacement(BlockIronFurnaceTileBase f) {
        for (int i = 0; i < ownedFactoryLanes.length; i++) {
            if (!ownedFactoryLanes[i]) continue;
            f.setItem(FACTORY_INPUT[i], ItemStack.EMPTY);
        }
        releaseFactoryLease();
        inputPlaced = false;
        f.setChanged();
    }

    private int findNextFactoryLane(BlockIronFurnaceTileBase f, int current) {
        for (int offset = 1; offset < FACTORY_INPUT.length; offset++) {
            int lane = (current + offset) % FACTORY_INPUT.length;
            int input = FACTORY_INPUT[lane];
            if (!f.getItem(input).isEmpty() || !f.getItem(input + 6).isEmpty()
                    || f.factoryCookTime[lane] > 0) return lane;
        }
        return -1;
    }

}
