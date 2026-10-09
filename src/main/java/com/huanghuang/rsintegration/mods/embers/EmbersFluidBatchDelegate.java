package com.huanghuang.rsintegration.mods.embers;

import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.storage.StorageOperationStatus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** 熔炼炉和离心器共享的流体产物事务；只接管本次投入的材料。 */
abstract class EmbersFluidBatchDelegate extends AbstractBatchDelegate {
    protected ServerLevel level;
    protected BlockPos pos;
    protected Recipe<?> recipe;
    protected List<IngredientSpec> required = List.of();
    protected FluidStack expected = FluidStack.EMPTY;
    protected final List<ItemStack> remaining = new ArrayList<>();
    protected boolean started;
    protected boolean consumed;
    private long startTick;
    private long unpoweredTicks;
    private Component failureMessage;

    protected abstract boolean isRecipe(Recipe<?> candidate);
    protected abstract boolean bindMachine(BlockEntity root);
    protected abstract boolean structureValid();
    protected abstract IFluidHandler outputTank();
    protected abstract boolean inputsIdle();
    protected abstract boolean hasPower();
    protected abstract boolean placeInputs(List<ItemStack> materials);
    protected abstract boolean nativeRecipeMatches();
    protected abstract List<ItemStack> recoverInputs();

    @Override
    public PreparationResult prepare(ServerPlayer player, ResourceLocation recipeId,
                                     @Nullable ResourceLocation dim, BlockPos target) {
        level = CraftPacketUtils.resolveLevel(player.server, dim, player);
        if (level == null || !level.hasChunkAt(target) || !level.hasChunkAt(target.above())) {
            return PreparationResult.retry("余烬机器区块尚未加载");
        }
        Recipe<?> found = level.getRecipeManager().byKey(recipeId).orElse(null);
        if (found == null || !isRecipe(found)) return PreparationResult.fatal(
                "余烬配方类型不匹配", Component.translatable("rsi.embers_machine.error.recipe"));
        BlockEntity root = level.getBlockEntity(target);
        if (!bindMachine(root) || !structureValid()) {
            return PreparationResult.fatal("余烬机器结构不完整或绑定位置不正确",
                    Component.translatable("rsi.embers_machine.error.structure"));
        }
        var handler = ModRecipeHandlers.handlerFor(found);
        if (handler == null) return PreparationResult.fatal("余烬配方处理器未注册",
                Component.translatable("rsi.embers_machine.error.recipe"));
        List<IngredientSpec> specs = handler.getIngredients(found);
        ItemStack result = handler.getResultItem(found, level.registryAccess());
        FluidStack output = InkFluidSupport.fluid(result);
        if (specs == null || specs.isEmpty() || output.isEmpty()) {
            return PreparationResult.fatal("余烬配方材料或流体产物无效",
                    Component.translatable("rsi.embers_machine.error.recipe"));
        }
        pos = target.immutable();
        recipe = found;
        required = List.copyOf(specs);
        expected = output.copy();
        machineDim = level.dimension().location();
        machineServer = player.server;
        if (storageEndpoint() == null) network = CraftPacketUtils.resolveNetworkForCraft(
                player, level.dimension(), target);
        if (!canStoreOutput(player)) return PreparationResult.fatal("RS 流体存储不可用",
                Component.translatable("rsi.embers_machine.error.fluid_storage"));
        if (!inputsIdle()) return PreparationResult.retry("余烬机器输入槽非空",
                Component.translatable("rsi.embers_machine.error.inputs_occupied"));
        if (!outputTank().getFluidInTank(0).isEmpty()) return PreparationResult.fatal(
                "余烬机器产物罐非空",
                Component.translatable("rsi.embers_machine.error.output_occupied"));
        if (outputTank().fill(expected.copy(), IFluidHandler.FluidAction.SIMULATE)
                < expected.getAmount()) return PreparationResult.fatal("余烬机器产物罐容量不足",
                Component.translatable("rsi.embers_machine.error.output_capacity"));
        if (!hasPower()) return PreparationResult.retry("余烬机器缺少 Ember 能量",
                Component.translatable("rsi.embers_machine.error.no_ember"));
        return PreparationResult.ready();
    }

    @Override public boolean validateAndInit(ServerPlayer player, ResourceLocation recipeId,
                                            @Nullable ResourceLocation dim, BlockPos pos) {
        return prepare(player, recipeId, dim, pos).state() == PreparationState.READY;
    }

    protected final boolean canStoreOutput(ServerPlayer player) {
        if (storageEndpoint() == null || !"refinedstorage".equals(
                storageEndpoint().session().reference().backendId().value())) return false;
        return storageEndpoint().insert(player, InkFluidSupport.token(expected), true).status()
                == StorageOperationStatus.SUCCESS;
    }

    @Override public Component validateOutputStorage(ServerPlayer player) {
        return canStoreOutput(player) ? null
                : Component.translatable("rsi.embers_machine.error.fluid_storage");
    }

    @Override public boolean validateExecutionContext(@Nullable ServerPlayer player) {
        return player != null && structureValid() && (started || inputsIdle()
                && outputTank().getFluidInTank(0).isEmpty() && hasPower() && canStoreOutput(player));
    }

    @Override public List<IngredientSpec> getRequiredMaterials() { return required; }

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
        if (!placeInputs(materials) || !nativeRecipeMatches()) return false;
        started = true;
        startTick = level.getGameTime();
        unpoweredTicks = 0;
        markCraftStarted();
        forceMachineChunk(level, pos, true);
        return true;
    }

    protected final void placed(int index) { remaining.set(index, ItemStack.EMPTY); }

    @Override protected boolean isMachineCraftFinished(ServerLevel current, BlockEntity be) {
        if (!started || !structureValid()) return false;
        FluidStack stored = outputTank().getFluidInTank(0);
        boolean done = stored.isFluidEqual(expected) && stored.getAmount() >= expected.getAmount();
        if (done) consumed = true;
        return done;
    }

    @Override protected CraftObservation observeMachineCraft(ServerLevel current, BlockEntity be) {
        if (!structureValid()) {
            failureMessage = Component.translatable("rsi.embers_machine.error.structure_changed");
            return failObservation("余烬机器结构已改变");
        }
        if (isMachineCraftFinished(current, be)) return doneObservation();
        if (started && inputsIdle()) {
            consumed = true;
            failureMessage = Component.translatable("rsi.embers_machine.error.output_removed");
            return failObservation("投入材料已消失，但成品液体不足或已被外部管道抽走");
        }
        // 能量不足时原版机器会保留输入并等待，暂停加工卡死计时。
        if (!hasPower()) unpoweredTicks++;
        if (level.getGameTime() - startTick - unpoweredTicks > 2400) {
            failureMessage = Component.translatable("rsi.embers_machine.error.stalled");
            return failObservation("余烬机器加工超时，请检查 Ember 供应和产物流向");
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
        if (!started || !structureValid()
                || !consumed && !isMachineCraftFinished(level, level.getBlockEntity(pos))) {
            return List.of();
        }
        FluidStack drained = EmbersOutputRecovery.drainFluid(outputTank(), expected);
        return drained.isEmpty() ? List.of() : List.of(InkFluidSupport.token(drained));
    }

    @Override public boolean collectsPhysicalSecondaryOutputs() { return true; }

    @Override protected void clearMachineState(BlockEntity be, @Nullable ServerPlayer player) {
        List<ItemStack> recovered = new ArrayList<>();
        remaining.stream().filter(stack -> !stack.isEmpty()).forEach(stack -> recovered.add(stack.copy()));
        if (structureValid() && !consumed && !isMachineCraftFinished(level, be)) {
            recovered.addAll(recoverInputs());
        }
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
        recordFailureRecoveredInputs(recovered);
        if (!usingSharedLedger && ledger != null && ledger.isCommitted()) {
            ledger.retainCommittedRefunds(recovered);
            ledger.refundCommitted(network, player);
        }
        resetState();
    }

    @Override public void onBatchFinished(@Nullable ServerPlayer player) {
        if (!markTerminalCleanup()) return;
        resetState();
    }

    @Override public BlockPos getMachinePos() { return pos; }
}
