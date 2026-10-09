package com.huanghuang.rsintegration.mods.embers;

import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.util.PlayerUtils;
import com.rekindled.embers.api.tile.IBin;
import com.rekindled.embers.api.upgrades.UpgradeUtil;
import com.rekindled.embers.blockentity.StampBaseBlockEntity;
import com.rekindled.embers.blockentity.StamperBlockEntity;
import com.rekindled.embers.recipe.IStampingRecipe;
import com.rekindled.embers.recipe.StampingContext;
import com.rekindled.embers.util.Misc;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.ItemStackHandler;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 自动复用或更换印模；底座供料，产物落地时由合成链直接捕获。 */
public final class EmbersStamperBatchDelegate extends AbstractBatchDelegate {
    private ServerLevel level;
    private BlockPos pos;
    private StamperBlockEntity stamper;
    private StampBaseBlockEntity base;
    private IStampingRecipe recipe;
    private ItemStack expected = ItemStack.EMPTY;
    private List<IngredientSpec> required = List.of();
    private final List<ItemStack> remaining = new ArrayList<>();
    private ItemStack placedItem = ItemStack.EMPTY;
    private ItemStack initialStamp = ItemStack.EMPTY;
    private ItemStack placedStamp = ItemStack.EMPTY;
    private ItemStack replacedStamp = ItemStack.EMPTY;
    private FluidStack placedFluid = FluidStack.EMPTY;
    private boolean needsStamp;
    private boolean started;
    private boolean consumed;
    private long startTick;
    private long unpoweredTicks;
    private long inputMissingTick = -1;
    private Component failureMessage;
    private UUID ownerId;

    @Override public PreparationResult prepare(ServerPlayer player, ResourceLocation recipeId,
                                               @Nullable ResourceLocation dim, BlockPos target) {
        level = CraftPacketUtils.resolveLevel(player.server, dim, player);
        if (level == null || !level.hasChunkAt(target) || !level.hasChunkAt(target.below(3))) {
            return PreparationResult.retry("压印结构区块尚未加载");
        }
        Recipe<?> found = level.getRecipeManager().byKey(recipeId).orElse(null);
        if (!(found instanceof IStampingRecipe stamping)) {
            return PreparationResult.fatal("不是余烬压印配方",
                    Component.translatable("rsi.embers_machine.error.recipe"));
        }
        BlockEntity hammer = level.getBlockEntity(target);
        BlockEntity lower = level.getBlockEntity(target.below(2));
        if (!(hammer instanceof StamperBlockEntity machine)
                || !(lower instanceof StampBaseBlockEntity stampBase)) {
            return PreparationResult.fatal("压印结构不完整",
                    Component.translatable("rsi.embers_machine.error.stamper_structure"));
        }
        if (level.getBlockEntity(target.below(3)) instanceof IBin) {
            return PreparationResult.fatal("压印底座下方不能放置收集箱",
                    Component.translatable("rsi.embers_machine.error.stamper_collector"));
        }
        var handler = ModRecipeHandlers.handlerFor(found);
        List<IngredientSpec> specs = handler == null ? null : handler.getIngredients(found);
        ItemStack result = handler == null ? ItemStack.EMPTY
                : handler.getResultItem(found, level.registryAccess());
        if (specs == null || specs.isEmpty() || result.isEmpty()) {
            return PreparationResult.fatal("压印配方材料或产物无效",
                    Component.translatable("rsi.embers_machine.error.recipe"));
        }
        pos = target.immutable();
        stamper = machine;
        base = stampBase;
        recipe = stamping;
        expected = result.copy();
        initialStamp = machine.stamp.getStackInSlot(0).copy();
        needsStamp = !installedStampMatches();
        if (needsStamp && EmbersStampingRecipeHandler.hasMatchingInstalledStamp(player, stamping)) {
            return PreparationResult.retry("另一台已绑定压印锤装有正确印模");
        }
        required = needsStamp ? List.copyOf(specs) : specs.stream()
                .filter(spec -> spec.role() != DemandRole.CATALYST).toList();
        machineDim = level.dimension().location();
        machineServer = player.server;
        ownerId = player.getUUID();
        if (storageEndpoint() == null) network = CraftPacketUtils.resolveNetworkForCraft(
                player, level.dimension(), target);
        if (!stamping.getDisplayInputFluid().getFluids().isEmpty()
                && (storageEndpoint() == null || !"refinedstorage".equals(
                storageEndpoint().session().reference().backendId().value()))) {
            return PreparationResult.fatal("压印流体材料需要 RS 流体存储",
                    Component.translatable("rsi.embers_machine.error.fluid_storage"));
        }
        if (!idle()) return PreparationResult.retry("印模底座仍有材料或压印锤尚未复位",
                Component.translatable("rsi.embers_machine.error.stamper_occupied"));
        if (!hasPower()) return PreparationResult.retry("压印锤缺少 Ember 能量",
                Component.translatable("rsi.embers_machine.error.no_ember"));
        return PreparationResult.ready();
    }

    @Override public boolean validateAndInit(ServerPlayer player, ResourceLocation recipeId,
                                            @Nullable ResourceLocation dim, BlockPos pos) {
        return prepare(player, recipeId, dim, pos).state() == PreparationState.READY;
    }

    private boolean structureValid() {
        return stamper != null && base != null
                && !stamper.isRemoved() && !base.isRemoved()
                && level.hasChunkAt(pos) && level.hasChunkAt(pos.below(3))
                && level.getBlockEntity(pos) == stamper
                && level.getBlockEntity(pos.below(2)) == base;
    }

    private boolean outputPathClear() {
        return !(level.getBlockEntity(pos.below(3)) instanceof IBin);
    }

    private boolean installedStampMatches() {
        Ingredient stamp = recipe.getDisplayStamp();
        return stamp == null || stamp.isEmpty() || stamp.test(stamper.stamp.getStackInSlot(0));
    }

    private boolean stampSlotUnchanged() {
        return sameStack(stamper.stamp.getStackInSlot(0), initialStamp);
    }

    private static boolean sameStack(ItemStack current, ItemStack snapshot) {
        return current.getCount() == snapshot.getCount()
                && (current.isEmpty() && snapshot.isEmpty()
                || ItemStack.isSameItemSameTags(current, snapshot));
    }

    /** 只有印模槽未被其他操作改动且新印模正确时，才执行替换。 */
    static boolean replaceStamp(ItemStackHandler slot, ItemStack original,
                                ItemStack replacement, Ingredient requiredStamp) {
        if (!sameStack(slot.getStackInSlot(0), original)
                || replacement.isEmpty() || replacement.getCount() != 1
                || !requiredStamp.test(replacement)) return false;
        slot.setStackInSlot(0, replacement.copy());
        if (requiredStamp.test(slot.getStackInSlot(0))) return true;
        slot.setStackInSlot(0, original.copy());
        return false;
    }

    private boolean idle() {
        return base.inventory.getStackInSlot(0).isEmpty()
                && base.getTank().getFluidInTank(0).isEmpty()
                && !stamper.powered;
    }

    private boolean hasPower() {
        double cost = UpgradeUtil.getTotalEmberConsumption(stamper, 80.0D,
                UpgradeUtil.getUpgrades(level, pos, Misc.horizontals));
        return stamper.capability.getEmber() >= cost;
    }

    @Override public boolean validateExecutionContext(@Nullable ServerPlayer player) {
        return player != null && structureValid() && outputPathClear()
                && (started || !needsStamp ? installedStampMatches() : stampSlotUnchanged())
                && (started || idle() && hasPower());
    }

    @Override public List<IngredientSpec> getRequiredMaterials() { return required; }
    @Override public ItemStack getExpectedOutput() { return expected.copy(); }
    @Override public ExpectedProduction getExpectedProduction() {
        return expected.isEmpty() ? null : new ExpectedProduction(expected.copy(), expected.getCount());
    }
    @Override public AABB getOutputCaptureRegion() {
        return pos == null ? null : new AABB(pos.below()).inflate(0.25);
    }
    @Override public Component materialReservationFailureMessage(ServerPlayer player) {
        return needsStamp ? Component.translatable("rsi.embers_machine.error.stamp_unavailable") : null;
    }

    @Override public boolean tryStartSingleCraft(ServerPlayer player) {
        if (!validateExecutionContext(player)) return false;
        ledger = new ExtractionLedger();
        ledger.setStorageEndpoint(storageEndpoint());
        List<ItemStack> materials = new ArrayList<>();
        for (IngredientSpec spec : required) {
            ItemStack material = CraftPacketUtils.ensureMaterialAvailable(player, level.dimension(), pos,
                    spec.ingredient(), spec.count(), ledger);
            if (material.isEmpty()) { ledger.rollback(player); return false; }
            materials.add(material.copy());
        }
        if (!ledger.commit(network, player)) return false;
        return start(player, materials);
    }

    @Override public boolean tryStartWithMaterials(ServerPlayer player, List<ItemStack> materials,
                                                   ExtractionLedger sharedLedger) {
        useSharedLedger(sharedLedger);
        return start(player, materials);
    }

    private boolean start(ServerPlayer player, List<ItemStack> materials) {
        if (started || materials.size() != required.size() || !validateExecutionContext(player)) return false;
        remaining.clear();
        for (int i = 0; i < materials.size(); i++) {
            ItemStack material = materials.get(i);
            IngredientSpec spec = required.get(i);
            if (material.isEmpty() || material.getCount() != spec.count()
                    || !spec.ingredient().test(material)) return false;
            remaining.add(material.copy());
        }
        int index = 0;
        boolean needsItem = recipe.getDisplayInput() != null && !recipe.getDisplayInput().isEmpty();
        if (needsItem) {
            ItemStack item = materials.get(index);
            if (!base.inventory.insertItem(0, item.copy(), true).isEmpty()) return false;
            index++;
        }
        boolean needsFluid = !recipe.getDisplayInputFluid().getFluids().isEmpty();
        if (needsFluid) {
            FluidStack fluid = InkFluidSupport.fluid(materials.get(index));
            if (fluid.isEmpty() || base.getTank().fill(fluid.copy(), IFluidHandler.FluidAction.SIMULATE)
                    != fluid.getAmount()) return false;
        }
        if (needsStamp) {
            int stampIndex = required.size() - 1;
            ItemStack replacement = materials.get(stampIndex);
            if (!replaceStamp(stamper.stamp, initialStamp, replacement,
                    recipe.getDisplayStamp())) return false;
            replacedStamp = initialStamp.copy();
            placedStamp = replacement.copy();
            remaining.set(stampIndex, ItemStack.EMPTY);
        }
        index = 0;
        if (needsItem) {
            ItemStack item = materials.get(index);
            ItemStack rest = base.inventory.insertItem(0, item.copy(), false);
            int added = item.getCount() - rest.getCount();
            if (added > 0) {
                placedItem = item.copyWithCount(added);
                remaining.set(index, rest.copy());
            }
            if (!rest.isEmpty()) return false;
            index++;
        }
        if (needsFluid) {
            FluidStack fluid = InkFluidSupport.fluid(materials.get(index));
            int filled = base.getTank().fill(fluid.copy(), IFluidHandler.FluidAction.EXECUTE);
            if (filled > 0) {
                placedFluid = fluid.copy();
                placedFluid.setAmount(filled);
                remaining.set(index, materials.get(index).copyWithCount(fluid.getAmount() - filled));
            }
            if (filled != fluid.getAmount()) return false;
        }
        if (!recipe.matches(new StampingContext(base.inventory, base.getTank(),
                stamper.stamp.getStackInSlot(0)), level)) return false;
        started = true;
        startTick = level.getGameTime();
        unpoweredTicks = 0;
        markCraftStarted();
        forceMachineChunk(level, pos, true);
        return true;
    }

    @Override protected CraftObservation observeMachineCraft(ServerLevel current, BlockEntity be) {
        if (!structureValid()) {
            failureMessage = Component.translatable("rsi.embers_machine.error.structure_changed");
            return failObservation("压印结构已改变");
        }
        boolean inputMissing = !placedItem.isEmpty() && base.inventory.getStackInSlot(0).isEmpty()
                || !placedFluid.isEmpty() && base.getTank().getFluidInTank(0).isEmpty();
        if (inputMissing) consumed = true;
        if (!outputPathClear()) {
            failureMessage = Component.translatable("rsi.embers_machine.error.stamper_collector");
            return failObservation("压印期间放入了收集箱，无法直接捕获产物");
        }
        // 原版在同一游戏刻消耗底座材料并生成掉落物，先让合成链检查捕获缓存。
        if (started && inputMissing) {
            if (inputMissingTick < 0) inputMissingTick = level.getGameTime();
            if (level.getGameTime() - inputMissingTick <= 2) return workingObservation();
            consumed = true;
            failureMessage = Component.translatable("rsi.embers_machine.error.stamper_output_removed");
            return failObservation("压印材料已消耗，但未捕获到掉落产物");
        }
        // 原版在能量不足时保留底座材料等待供能，不把这段等待算作加工卡死。
        if (!hasPower()) unpoweredTicks++;
        if (level.getGameTime() - startTick - unpoweredTicks > 600) {
            failureMessage = Component.translatable("rsi.embers_machine.error.stamper_stalled");
            return failObservation("压印加工超时，请检查余烬能量供应和产物掉落位置");
        }
        return workingObservation();
    }

    @Override public Component craftFailureMessage(CraftObservation observation) {
        return failureMessage;
    }

    @Override public boolean failureConsumesInputs(CraftObservation observation) { return consumed; }
    @Override public ItemStack collectResult(ServerPlayer player) {
        List<ItemStack> results = collectAllResults(player);
        return results.isEmpty() ? ItemStack.EMPTY : results.get(0);
    }
    @Override public List<ItemStack> collectAllResults(ServerPlayer player) {
        if (!started) return List.of();
        List<ItemStack> collected = new ArrayList<>();
        remaining.stream().filter(stack -> !stack.isEmpty()).forEach(stack -> collected.add(stack.copy()));
        if (structureValid()) collected.addAll(recoverPlacedInputs());
        if (!replacedStamp.isEmpty()) {
            collected.add(replacedStamp.copy());
            replacedStamp = ItemStack.EMPTY;
        }
        return collected;
    }

    private List<ItemStack> recoverPlacedInputs() {
        List<ItemStack> recovered = new ArrayList<>();
        ItemStack item = base.inventory.getStackInSlot(0);
        if (!placedItem.isEmpty() && ItemStack.isSameItemSameTags(item, placedItem)) {
            ItemStack removed = base.inventory.extractItem(0, placedItem.getCount(), false);
            if (!removed.isEmpty()) recovered.add(removed);
        }
        if (!placedFluid.isEmpty()) {
            FluidStack drained = base.getTank().drain(placedFluid.copy(), IFluidHandler.FluidAction.EXECUTE);
            if (!drained.isEmpty()) recovered.add(InkFluidSupport.token(drained));
        }
        return recovered;
    }

    private void recoverInstalledStamp(List<ItemStack> recovered, @Nullable ServerPlayer player) {
        if (placedStamp.isEmpty()) return;
        if (level.hasChunkAt(pos) && level.getBlockEntity(pos) == stamper
                && stamper.stamp.getStackInSlot(0).getCount() == placedStamp.getCount()
                && ItemStack.isSameItemSameTags(stamper.stamp.getStackInSlot(0), placedStamp)) {
            stamper.stamp.setStackInSlot(0, replacedStamp.copy());
            recovered.add(placedStamp.copy());
            replacedStamp = ItemStack.EMPTY;
        } else {
            returnReplacedStamp(player);
        }
        placedStamp = ItemStack.EMPTY;
    }

    private void returnReplacedStamp(@Nullable ServerPlayer player) {
        if (replacedStamp.isEmpty()) return;
        if (player == null && machineServer != null && ownerId != null) {
            player = machineServer.getPlayerList().getPlayer(ownerId);
        }
        ItemStack leftover = insertIntoStorage(player, replacedStamp.copy(), false);
        replacedStamp = ItemStack.EMPTY;
        if (leftover.isEmpty()) return;
        if (player != null) {
            PlayerUtils.safeGiveToPlayer(player, leftover, network);
        } else {
            ServerLevel dropLevel = level != null && pos != null && level.hasChunkAt(pos)
                    ? level : machineServer == null ? null : machineServer.overworld();
            if (dropLevel != null) {
                BlockPos dropPos = dropLevel == level ? pos : dropLevel.getSharedSpawnPos();
                dropLevel.addFreshEntity(new ItemEntity(dropLevel, dropPos.getX() + 0.5,
                        dropPos.getY() + 0.5, dropPos.getZ() + 0.5, leftover));
            }
        }
    }

    @Override public boolean collectsPhysicalSecondaryOutputs() { return true; }

    @Override public void releasePreparationResources() {
        // 失败时若机器区块已卸载，基类不会调用机器清理钩子；先保全旧印模。
        if (!replacedStamp.isEmpty() && level != null && pos != null
                && !level.hasChunkAt(pos)) returnReplacedStamp(null);
    }

    @Override protected void clearMachineState(BlockEntity be, @Nullable ServerPlayer player) {
        List<ItemStack> recovered = new ArrayList<>();
        remaining.stream().filter(stack -> !stack.isEmpty()).forEach(stack -> recovered.add(stack.copy()));
        if (structureValid()) recovered.addAll(recoverPlacedInputs());
        recoverInstalledStamp(recovered, player);
        recordFailureRecoveredInputs(recovered);
        if (!usingSharedLedger && ledger != null && ledger.isCommitted()) {
            ledger.retainCommittedRefunds(recovered);
            ledger.refundCommitted(network, player);
        }
        resetState();
    }

    @Override protected void clearMissingMachineState(@Nullable ServerPlayer player) {
        List<ItemStack> recovered = remaining.stream().filter(stack -> !stack.isEmpty())
                .map(ItemStack::copy).toList();
        returnReplacedStamp(player);
        recordFailureRecoveredInputs(recovered);
        if (!usingSharedLedger && ledger != null && ledger.isCommitted()) {
            ledger.retainCommittedRefunds(recovered);
            ledger.refundCommitted(network, player);
        }
        resetState();
    }

    @Override public void onBatchFinished(@Nullable ServerPlayer player) {
        if (!markTerminalCleanup()) return;
        returnReplacedStamp(player);
        resetState();
    }
    @Override public BlockPos getMachinePos() { return pos; }
}
