package com.huanghuang.rsintegration.mods.botania;

import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.util.Action;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import vazkii.botania.api.recipe.PureDaisyRecipe;
import vazkii.botania.common.block.flower.PureDaisyBlockEntity;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class PureDaisyBlockConversionDelegate extends AbstractBatchDelegate {
    private static final BlockPos[] OFFSETS = {
            new BlockPos(-1, 0, -1), new BlockPos(-1, 0, 0), new BlockPos(-1, 0, 1),
            new BlockPos(0, 0, 1), new BlockPos(1, 0, 1), new BlockPos(1, 0, 0),
            new BlockPos(1, 0, -1), new BlockPos(0, 0, -1)
    };

    private ServerLevel level;
    private BlockPos pos;
    private PureDaisyRecipe recipe;
    private INetwork network;
    private ItemStack input = ItemStack.EMPTY;
    private ItemStack expected = ItemStack.EMPTY;
    private final List<BlockPos> targets = new ArrayList<>();
    private final Set<BlockPos> placed = new LinkedHashSet<>();
    private boolean harvested;
    private int requestedBatch = 1;
    private Set<UUID> entitiesBefore = Set.of();

    @Override
    public boolean validateAndInit(@Nonnull ServerPlayer player, @Nonnull ResourceLocation recipeId,
                                   ResourceLocation dim, @Nonnull BlockPos at) {
        pos = at;
        machineDim = dim;
        machineServer = player.getServer();
        level = dim == null ? player.serverLevel() : player.getServer().getLevel(
                net.minecraft.resources.ResourceKey.create(
                        net.minecraft.core.registries.Registries.DIMENSION, dim));
        if (level == null || !(level.getBlockEntity(pos) instanceof PureDaisyBlockEntity)) return false;
        var found = level.getRecipeManager().byKey(recipeId).orElse(null);
        if (!(found instanceof PureDaisyRecipe pureDaisyRecipe)) return false;
        recipe = pureDaisyRecipe;
        expected = new ItemStack(recipe.getOutputState().getBlock());
        network = CraftPacketUtils.resolveNetworkForCraft(player, level.dimension(), pos);
        targets.clear();
        Arrays.stream(OFFSETS).map(pos::offset).filter(level::isEmptyBlock).forEach(targets::add);
        return network != null && !targets.isEmpty() && !expected.isEmpty();
    }

    @Override
    public List<IngredientSpec> getRequiredMaterials() {
        ItemStack[] displayed = recipe.getInput().getDisplayedStacks().toArray(ItemStack[]::new);
        return displayed.length == 0 ? null
                : List.of(new IngredientSpec(Ingredient.of(Arrays.stream(displayed)), 1));
    }

    @Override
    public int prepareFlatBatch(int remainingOperations) {
        requestedBatch = Math.min(Math.min(OFFSETS.length, targets.size()),
                Math.max(1, remainingOperations));
        return requestedBatch;
    }

    @Override
    public boolean tryStartSingleCraft(@Nonnull ServerPlayer player) {
        List<ItemStack> materials = BotaniaDelegateSupport.extractAtomically(network, getRequiredMaterials());
        return !materials.isEmpty() && start(materials.get(0));
    }

    @Override
    public boolean tryStartSingleCraft(@Nonnull ServerPlayer player, @Nonnull ExtractionLedger ledger) {
        return false;
    }

    @Override
    public boolean tryStartWithMaterials(@Nonnull ServerPlayer player, @Nonnull List<ItemStack> materials,
                                         @Nonnull ExtractionLedger ledger) {
        return materials.size() == 1 && start(materials.get(0));
    }

    private boolean start(ItemStack stack) {
        if (!(stack.getItem() instanceof BlockItem blockItem) || stack.isEmpty()) return false;
        int count = stack.getCount();
        if (count != requestedBatch) return false;
        List<BlockPos> available = targets.stream().filter(level::isEmptyBlock).limit(count).toList();
        if (available.size() != count) return false;
        BlockState state = blockItem.getBlock().defaultBlockState();
        if (!(level.getBlockEntity(pos) instanceof PureDaisyBlockEntity flower)) return false;
        for (BlockPos target : available) {
            if (!recipe.matches(level, target, flower, state)) return false;
        }

        input = stack.copy();
        entitiesBefore = BotaniaDelegateSupport.snapshot(level, captureRegion());
        placed.clear();
        for (BlockPos target : available) {
            if (!level.setBlock(target, state, 3)) {
                for (BlockPos rollback : placed) level.destroyBlock(rollback, false);
                placed.clear();
                return false;
            }
            placed.add(target);
        }
        harvested = false;
        markCraftStarted();
        return true;
    }

    @Override
    protected boolean isMachineCraftFinished(@Nonnull ServerLevel current, @Nonnull BlockEntity blockEntity) {
        if (placed.isEmpty()) return false;
        if (!harvested && placed.stream().allMatch(target ->
                current.getBlockState(target).is(recipe.getOutputState().getBlock()))) {
            harvested = true;
            for (BlockPos target : placed) current.destroyBlock(target, true);
        }
        if (!harvested) return false;
        int found = current.getEntitiesOfClass(ItemEntity.class, captureRegion(), this::isExpectedDrop)
                .stream().mapToInt(entity -> entity.getItem().getCount()).sum();
        return found >= placed.size();
    }

    private boolean isExpectedDrop(ItemEntity entity) {
        return BotaniaDelegateSupport.isNew(entity, entitiesBefore)
                && entity.isAlive() && ItemStack.isSameItem(entity.getItem(), expected);
    }

    @Override
    public ItemStack collectResult(@Nonnull ServerPlayer player) {
        List<ItemStack> all = collectAllResults(player);
        return all.isEmpty() ? ItemStack.EMPTY : all.get(0);
    }

    @Override
    public List<ItemStack> collectAllResults(@Nonnull ServerPlayer player) {
        List<ItemStack> results = new ArrayList<>();
        for (ItemEntity entity : level.getEntitiesOfClass(ItemEntity.class, captureRegion(), this::isExpectedDrop)) {
            results.add(entity.getItem().copy());
            entity.discard();
        }
        return results;
    }

    @Override
    protected void clearMachineState(BlockEntity blockEntity, ServerPlayer player) {
        if (harvested || placed.isEmpty()) return;
        for (BlockPos target : placed) {
            if (!level.isEmptyBlock(target)) level.destroyBlock(target, false);
        }
        if (!usingSharedLedger && !input.isEmpty()) {
            network.insertItem(input.copy(), input.getCount(), Action.PERFORM);
        }
    }

    private AABB captureRegion() {
        return new AABB(pos).inflate(1.75, 1.25, 1.75);
    }

    @Override
    public BatchConcurrencyCapabilities concurrencyCapabilities() {
        return new BatchConcurrencyCapabilities(
                BatchConcurrencyCapabilities.MaterialOwnership.CHAIN_RESERVED,
                BatchConcurrencyCapabilities.OutputOwnership.OWNED_WORLD_CAPTURE,
                BatchConcurrencyCapabilities.CleanupContract.SEPARABLE_OFFLINE,
                BatchConcurrencyCapabilities.SideEffects.LOCAL_WORLD_ITEMS,
                BatchConcurrencyCapabilities.PreparationContract.RETRY_SAFE,
                List.of());
    }

    @Override public ItemStack getExpectedOutput() {
        return expected.isEmpty() ? null : expected.copyWithCount(requestedBatch);
    }
    @Override public AABB getOutputCaptureRegion() { return pos == null ? null : captureRegion(); }
    @Override public BlockPos getMachinePos() { return pos; }
    @Override public void onBatchFinished(@Nonnull ServerPlayer player) { resetState(); }
}
