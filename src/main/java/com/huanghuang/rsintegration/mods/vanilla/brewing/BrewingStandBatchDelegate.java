package com.huanghuang.rsintegration.mods.vanilla.brewing;

import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.IngredientMatcher;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities;
import com.huanghuang.rsintegration.crafting.batch.PhysicalInputRecovery;
import com.huanghuang.rsintegration.util.ChunkUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** Dedicated physical executor for a single vanilla brewing-stand conversion. */
public final class BrewingStandBatchDelegate extends AbstractBatchDelegate {
    private BlockPos pos;
    private ServerLevel level;
    private BrewingStandBlockEntity stand;
    private VanillaBrewingRecipeDefinition recipe;
    private boolean placed;
    private final List<ItemStack> placedInputs = new ArrayList<>();

    /** Each stand owns its three bottle slots and can run independently. */
    @Override
    public BatchConcurrencyCapabilities concurrencyCapabilities() {
        return BatchConcurrencyCapabilities.machineSlot();
    }

    @Override
    public boolean validateAndInit(ServerPlayer player, ResourceLocation recipeId,
                                   @Nullable ResourceLocation dim, BlockPos pos) {
        this.pos = pos;
        this.level = CraftPacketUtils.resolveLevel(player.server, dim, player);
        this.recipe = VanillaBrewingCatalog.byId(recipeId);
        this.placed = false;
        this.placedInputs.clear();
        if (level == null || recipe == null) return false;
        if (!level.hasChunkAt(pos)) return false;
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof BrewingStandBlockEntity brewingStand)) return false;
        if (!isEmpty(brewingStand)) return false;
        this.stand = brewingStand;
        setMachineDim(level.dimension().location());
        setMachineServer(player.server);
        return true;
    }

    @Override
    public List<IngredientSpec> getRequiredMaterials() {
        return recipe == null ? null : List.of(
                new IngredientSpec(recipe.getIngredients().get(0), 3),
                new IngredientSpec(recipe.getIngredients().get(1), 1),
                new IngredientSpec(recipe.getIngredients().get(2), 1));
    }

    @Override
    public boolean tryStartSingleCraft(ServerPlayer player) {
        if (recipe == null || stand == null) return false;
        this.ledger = new ExtractionLedger();
        if (storageEndpoint() == null) {
            this.network = CraftPacketUtils.resolveNetworkForCraft(player, level.dimension(), pos);
        }
        if (network == null && !hasStorageAccess()) return false;
        ledger.setStorageEndpoint(storageEndpoint());
        ItemStack input = ledger.reserve(recipe.getIngredients().get(0), 3, network, player,
                level.dimension(), pos);
        ItemStack reagent = ledger.reserveExact(recipe.reagent(), 1, network, player, level.dimension(), pos);
        ItemStack fuel = ledger.reserveExact(new ItemStack(net.minecraft.world.item.Items.BLAZE_POWDER),
                1, network, player, level.dimension(), pos);
        if (input.isEmpty() || reagent.isEmpty() || fuel.isEmpty() || !ledger.commit(network, player)) return false;
        return place(List.of(input, reagent, fuel));
    }

    @Override
    public boolean tryStartWithMaterials(ServerPlayer player, List<ItemStack> materials,
                                         ExtractionLedger sharedLedger) {
        this.sharedLedger = sharedLedger;
        this.usingSharedLedger = true;
        return place(materials);
    }

    private boolean place(List<ItemStack> materials) {
        if (materials.size() < 3 || stand == null) return false;
        ItemStack input = materials.get(0).copy();
        ItemStack reagent = materials.get(1).copyWithCount(1);
        ItemStack fuel = materials.get(2).copyWithCount(1);
        if (input.getCount() < 3 || !matchesInput(input)
                || !ItemStack.isSameItemSameTags(reagent, recipe.reagent())
                || !fuel.is(net.minecraft.world.item.Items.BLAZE_POWDER)) return false;
        if (!isEmpty(stand)) return false;
        stand.setItem(0, input.copyWithCount(1));
        stand.setItem(1, input.copyWithCount(1));
        stand.setItem(2, input.copyWithCount(1));
        stand.setItem(3, reagent);
        stand.setItem(4, fuel);
        placedInputs.clear();
        placedInputs.add(input.copyWithCount(1));
        placedInputs.add(input.copyWithCount(1));
        placedInputs.add(input.copyWithCount(1));
        placedInputs.add(reagent.copy());
        placedInputs.add(fuel.copy());
        stand.setChanged();
        level.sendBlockUpdated(pos, level.getBlockState(pos), level.getBlockState(pos), 3);
        placed = true;
        markCraftStarted();
        return true;
    }

    private boolean matchesInput(ItemStack input) {
        return ItemStack.isSameItemSameTags(input, recipe.input())
                || IngredientMatcher.matchesWaterBottleIgnoringPurity(recipe.input(), input);
    }

    private boolean matchesOutput(ItemStack output) {
        return ItemStack.isSameItemSameTags(output, recipe.outputUnit())
                || IngredientMatcher.matchesPotionIgnoringPurity(recipe.outputUnit(), output);
    }

    private static boolean isEmpty(BrewingStandBlockEntity stand) {
        for (int slot = 0; slot <= 4; slot++) {
            if (!stand.getItem(slot).isEmpty()) return false;
        }
        return true;
    }

    @Override
    protected CraftObservation observeMachineCraft(ServerLevel level, BlockEntity be) {
        if (!(be instanceof BrewingStandBlockEntity current) || !placed) {
            return failObservation("brewing stand state is unavailable");
        }
        boolean allOutput = true;
        for (int slot = 0; slot < 3; slot++) {
            ItemStack bottle = current.getItem(slot);
            allOutput &= matchesOutput(bottle);
            if (bottle.isEmpty() || (!matchesInput(bottle) && !matchesOutput(bottle))) {
                return failObservation("brewing bottle was externally changed");
            }
        }
        if (allOutput) return doneObservation();
        return workingObservation();
    }

    @Override
    protected boolean isMachineCraftFinished(ServerLevel level, BlockEntity be) {
        if (!(be instanceof BrewingStandBlockEntity current)) return false;
        for (int slot = 0; slot < 3; slot++) {
            if (!matchesOutput(current.getItem(slot))) return false;
        }
        return true;
    }

    @Override
    public List<ItemStack> collectAllResults(ServerPlayer player) {
        if (stand == null || !isMachineCraftFinished(level, stand)) return List.of();
        List<ItemStack> results = new ArrayList<>();
        ItemStack brewed = recipe.output();
        for (int slot = 0; slot < 3; slot++) stand.removeItemNoUpdate(slot);
        results.add(brewed);
        ItemStack remainder = stand.removeItemNoUpdate(3);
        if (!remainder.isEmpty()) results.add(remainder);
        ItemStack unusedFuel = stand.removeItemNoUpdate(4);
        if (!unusedFuel.isEmpty()) results.add(unusedFuel);
        stand.setChanged();
        placed = false;
        placedInputs.clear();
        return results;
    }

    @Override public ItemStack collectResult(ServerPlayer player) {
        List<ItemStack> results = collectAllResults(player);
        return results.isEmpty() ? ItemStack.EMPTY : results.get(0);
    }

    @Override public boolean collectsPhysicalSecondaryOutputs() { return true; }

    @Override
    protected void clearMachineState(BlockEntity be, ServerPlayer player) {
        List<ItemStack> recovered = new ArrayList<>();
        if (be instanceof BrewingStandBlockEntity current && placed) {
            for (int slot = 0; slot < placedInputs.size(); slot++) {
                ItemStack expected = placedInputs.get(slot);
                ItemStack visible = current.getItem(slot);
                if (!PhysicalInputRecovery.recoveredExpected(visible, expected)) continue;
                ItemStack owned = visible.copyWithCount(expected.getCount());
                ItemStack retained = visible.copy();
                retained.shrink(expected.getCount());
                current.setItem(slot, retained.isEmpty() ? ItemStack.EMPTY : retained);
                recovered.add(owned);
            }
            current.setChanged();
        }
        recordFailureRecoveredInputs(recovered);
        placed = false;
        placedInputs.clear();
        resetState();
    }

    @Override public void onBatchFinished(@NotNull ServerPlayer player) { resetState(); }
    @Override public BlockPos getMachinePos() { return pos; }

    @Override
    public ExpectedProduction getExpectedProduction() {
        return recipe == null ? null : new ExpectedProduction(recipe.outputUnit(), 3);
    }
}
