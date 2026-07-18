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

/** Batch delegate for Iron Furnaces ordinary furnace mode. */
public final class IronFurnacesBatchDelegate extends AbstractBatchDelegate {

    private static final int INPUT = 0;
    private static final int FUEL = 1;
    private static final int OUTPUT = 2;

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
        if (!ironFurnace.getItem(INPUT).isEmpty() || !ironFurnace.getItem(OUTPUT).isEmpty()) {
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
        if (furnace.isFactory() || furnace.isGenerator() || !furnace.isFurnace()) {
            return PreparationResult.fatal("Iron Furnace Factory/Generator mode is not supported");
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
        return !materials.isEmpty() && startWithMaterial(materials.get(0));
    }

    private boolean startWithMaterial(ItemStack material) {
        if (material.isEmpty() || !refreshMachine()) return false;
        PreparationResult state = validateMachine(furnace, recipe);
        if (state.state() != PreparationState.READY
                || !furnace.getItem(INPUT).isEmpty()
                || !furnace.getItem(OUTPUT).isEmpty()) return false;

        ItemStack placed = material.copy();
        furnace.setItem(INPUT, placed);
        inputPlaced = true;
        initialInputCount = placed.getCount();
        if (!ensureFuel()) {
            furnace.setItem(INPUT, ItemStack.EMPTY);
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
        ItemStack output = current.getItem(OUTPUT);
        ItemStack input = current.getItem(INPUT);
        boolean inputConsumed = inputPlaced && initialInputCount > 0 && input.getCount() < initialInputCount;
        if (inputConsumed) inputPlaced = false;
        if (current.isBurning() || current.cookTime > 0 || inputConsumed) observedWorking = true;
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
        ItemStack result = furnace.getItem(OUTPUT).copy();
        if (!result.isEmpty()) {
            furnace.setItem(OUTPUT, ItemStack.EMPTY);
            furnace.setChanged();
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
            resetState();
            return;
        }
        boolean refundPhysical = player == null;
        if (inputPlaced) {
            ItemStack input = current.getItem(INPUT);
            if (!input.isEmpty()) {
                current.setItem(INPUT, ItemStack.EMPTY);
                if (refundPhysical) refund(input);
            }
            inputPlaced = false;
        }
        ItemStack output = current.getItem(OUTPUT);
        if (!output.isEmpty() && observedWorking) {
            current.setItem(OUTPUT, ItemStack.EMPTY);
            if (refundPhysical) refund(output);
        }
        refundFuel(current);
        current.setChanged();
        resetState();
    }

    @Override
    public void onBatchFinished(@NotNull ServerPlayer player) {
        if (refreshMachine()) refundFuel(furnace);
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
}
