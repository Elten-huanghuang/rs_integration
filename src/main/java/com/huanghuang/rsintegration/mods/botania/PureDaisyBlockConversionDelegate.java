package com.huanghuang.rsintegration.mods.botania;

import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities;
import com.huanghuang.rsintegration.crafting.batch.ParallelBatchSizing;

import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.Block;
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

public final class PureDaisyBlockConversionDelegate extends AbstractBatchDelegate {
    private static final BlockPos[] OFFSETS = {
            new BlockPos(-1, 0, -1), new BlockPos(-1, 0, 0), new BlockPos(-1, 0, 1),
            new BlockPos(0, 0, 1), new BlockPos(1, 0, 1), new BlockPos(1, 0, 0),
            new BlockPos(1, 0, -1), new BlockPos(0, 0, -1)
    };

    private ServerLevel level;
    private BlockPos pos;
    private PureDaisyRecipe recipe;

    private ItemStack input = ItemStack.EMPTY;
    private ItemStack expected = ItemStack.EMPTY;
    private final List<BlockPos> targets = new ArrayList<>();
    private final Set<BlockPos> placed = new LinkedHashSet<>();
    /** 已转化完成、但合成链还没领取的产物。 */
    private final List<ItemStack> harvestedOutputs = new ArrayList<>();
    private boolean harvested;
    private int requestedBatch = 1;
    private long startTick;

    @Override
    public boolean validateAndInit(@Nonnull ServerPlayer player, @Nonnull ResourceLocation recipeId,
                                   ResourceLocation dim, @Nonnull BlockPos at) {
        pos = at;
        machineDim = dim;
        machineServer = player.getServer();
        level = dim == null ? player.serverLevel() : player.getServer().getLevel(
                ResourceKey.create(
                        Registries.DIMENSION, dim));
        if (level == null || !(level.getBlockEntity(pos) instanceof PureDaisyBlockEntity)) return false;
        var found = level.getRecipeManager().byKey(recipeId).orElse(null);
        if (!(found instanceof PureDaisyRecipe pureDaisyRecipe)) return false;
        recipe = pureDaisyRecipe;
        expected = new ItemStack(recipe.getOutputState().getBlock());
        // The chain may already have selected a non-RS backend.
        if (storageEndpoint() == null) {
            setStorageEndpoint(CraftPacketUtils.resolveEndpointForCraft(player, level.dimension(), pos).orElse(null));
        }
        targets.clear();
        Arrays.stream(OFFSETS).map(pos::offset).filter(level::isEmptyBlock).forEach(targets::add);
        return hasStorageAccess() && !targets.isEmpty() && !expected.isEmpty();
    }

    @Override
    public List<IngredientSpec> getRequiredMaterials() {
        ItemStack[] displayed = recipe.getInput().getDisplayedStacks().toArray(ItemStack[]::new);
        return displayed.length == 0 ? null
                : List.of(new IngredientSpec(Ingredient.of(Arrays.stream(displayed)), 1));
    }

    @Override
    public int prepareFlatBatch(int remainingOperations) {
        requestedBatch = graphBatchSize(remainingOperations, availableBatchCapacity());
        return requestedBatch;
    }

    @Override
    public void prepareGraphBatch(int executions) {
        requestedBatch = graphBatchSize(executions, availableBatchCapacity());
    }

    @Override
    public int preferredParallelBatchSize(int totalOperations, int workerCount) {
        return parallelWorkerBatchSize(totalOperations, workerCount, availableBatchCapacity());
    }

    private int availableBatchCapacity() {
        return Math.min(OFFSETS.length, targets.size());
    }

    static int graphBatchSize(int executions, int capacity) {
        return ParallelBatchSizing.boundedBatch(executions, capacity);
    }

    static int parallelWorkerBatchSize(int totalOperations, int workerCount, int capacity) {
        return ParallelBatchSizing.boundedEvenShare(totalOperations, workerCount, capacity);
    }

    @Override
    public boolean startSingleOperation(@Nonnull ServerPlayer player) {
        if (storageEndpoint() == null) return false;
        List<ItemStack> materials = BotaniaDelegateSupport.extractAtomically(
                storageEndpoint(), player, getRequiredMaterials());
        return !materials.isEmpty() && start(materials.get(0));
    }

    @Override
    public boolean startSingleOperation(@Nonnull ServerPlayer player, @Nonnull ExtractionLedger ledger) {
        return false;
    }

    @Override
    public boolean startPreparedOperation(@Nonnull ServerPlayer player, @Nonnull List<ItemStack> materials,
                                         @Nonnull ExtractionLedger ledger) {
        useSharedLedger(ledger);
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
        harvestedOutputs.clear();
        startTick = level.getGameTime();
        markCraftStarted();
        return true;
    }

    @Override
    protected boolean isMachineCraftFinished(@Nonnull ServerLevel current, @Nonnull BlockEntity blockEntity) {
        if (placed.isEmpty()) return false;
        // Botania's block state can be observed before the recipe's configured
        // duration has elapsed (for example during a same-tick block update).
        // Keep the RS batch pending for at least the native Pure Daisy time.
        if (current.getGameTime() - startTick < recipe.getTime()) return false;
        if (harvested) return true;
        if (!placed.stream().allMatch(target ->
                current.getBlockState(target).is(recipe.getOutputState().getBlock()))) {
            return false;
        }
        harvest(current);
        return true;
    }

    /**
     * 收走已经转化好的方块。
     * <p>
     * 这里刻意不让方块产生掉落实体：白雏菊的转化产物就是该方块本身，直接记账并交给
     * 合成链结算即可。旧的实现用 {@code destroyBlock(target, true)} 生成掉落物再回到世界里
     * 扫描 ItemEntity，一旦掉落被捕获器、拾取或区块卸载吞掉，就会表现为「方块被破坏但产物没了」。
     */
    private void harvest(ServerLevel current) {
        harvested = true;
        BlockState outputState = recipe.getOutputState();
        int converted = 0;
        for (BlockPos target : placed) {
            if (current.getBlockState(target).is(outputState.getBlock())
                    && current.destroyBlock(target, false)) {
                converted++;
            }
        }
        if (converted > 0) {
            harvestedOutputs.add(expected.copyWithCount(converted));
        }
    }

    @Override
    public ItemStack collectResult(@Nonnull ServerPlayer player) {
        List<ItemStack> all = collectAllResults(player);
        return all.isEmpty() ? ItemStack.EMPTY : all.get(0);
    }

    @Override
    public List<ItemStack> collectAllResults(@Nonnull ServerPlayer player) {
        if (harvestedOutputs.isEmpty()) return List.of();
        List<ItemStack> results = new ArrayList<>(harvestedOutputs.size());
        for (ItemStack stack : harvestedOutputs) {
            results.add(stack.copy());
        }
        harvestedOutputs.clear();
        return results;
    }

    /**
     * 中止时回收现场：把已放入世界的方块（无论还没转化还是已经转化完）都清掉，
     * 并按「输入物品」的数量退回，保证退款账目与登记的原材料一致。
     * <p>
     * 退货一律折成输入物品而不是产物：账本里登记的原材料是输入，
     * 上报产物无法抵消账本退款，会造成一次失败同时拿到产物和退款。
     */
    @Override
    protected void clearMachineState(BlockEntity blockEntity, ServerPlayer player) {
        int recovered = 0;
        // 已收获但没来得及交给合成链的产物也折回输入。
        for (ItemStack stack : harvestedOutputs) recovered += stack.getCount();
        harvestedOutputs.clear();
        if (!harvested) {
            BlockItem inputBlock = input.getItem() instanceof BlockItem blockItem ? blockItem : null;
            BlockState outputState = recipe == null ? null : recipe.getOutputState();
            for (BlockPos target : placed) {
                BlockState currentState = level.getBlockState(target);
                boolean restorable = inputBlock != null && currentState.is(inputBlock.getBlock())
                        || outputState != null && currentState.is(outputState.getBlock());
                if (restorable && level.destroyBlock(target, false)) recovered++;
            }
        }
        placed.clear();
        List<ItemStack> recoveredInputs = new ArrayList<>();
        if (recovered > 0 && !input.isEmpty()) recoveredInputs.add(input.copyWithCount(recovered));
        recordFailureRecoveredInputs(recoveredInputs);
        if (!usingSharedLedger) {
            for (ItemStack stack : recoveredInputs) deliver(stack, player);
        }
    }

    private void deliver(ItemStack stack, ServerPlayer player) {
        if (stack.isEmpty()) return;
        ItemStack leftover = insertIntoStorage(player, stack.copy(), false);
        if (leftover.isEmpty()) return;
        if (player != null && !player.hasDisconnected()) {
            ItemHandlerHelper.giveItemToPlayer(player, leftover);
        } else if (level != null && pos != null) {
            // 玩家已离线：把回收到的物品留在机器旁，之后可以捡回，避免直接蒸发。
            Block.popResource(level, pos, leftover);
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
    @Override public void onBatchFinished(@Nonnull ServerPlayer player) {
        harvestedOutputs.clear();
        placed.clear();
        harvested = false;
        resetState();
    }
}
