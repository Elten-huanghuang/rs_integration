package com.huanghuang.rsintegration.mods.vanilla;

import com.huanghuang.rsintegration.crafting.CraftPacketUtils;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;

/** Selects the physical delegate for cooking recipes without eagerly loading optional mods. */
public final class CookingMachineBatchDelegate extends AbstractBatchDelegate {

    private static final String IRON_FURNACE_BE =
            "ironfurnaces.tileentity.furnaces.BlockIronFurnaceTileBase";
    private static final String IRON_DELEGATE =
            "com.huanghuang.rsintegration.mods.ironfurnaces.IronFurnacesBatchDelegate";

    private IBatchDelegate delegate;

    @Override
    public PreparationResult prepare(@Nonnull ServerPlayer player, @Nonnull ResourceLocation recipeId,
                                     @Nullable ResourceLocation dim, @Nonnull BlockPos pos) {
        delegate = selectDelegate(player, dim, pos);
        if (delegate == null) return PreparationResult.retry("unsupported cooking machine");
        PreparationResult result = delegate.prepare(player, recipeId, dim, pos);
        if (result.state() == PreparationState.READY) markCraftStarted();
        return result;
    }

    @Override
    public boolean validateAndInit(@Nonnull ServerPlayer player, @Nonnull ResourceLocation recipeId,
                                   @Nullable ResourceLocation dim, @Nonnull BlockPos pos) {
        return prepare(player, recipeId, dim, pos).state() == PreparationState.READY;
    }

    @Nullable
    private static IBatchDelegate selectDelegate(ServerPlayer player, @Nullable ResourceLocation dim,
                                                  BlockPos pos) {
        ServerLevel level = CraftPacketUtils.resolveLevel(
                player.server, dim, player);
        if (level == null) return null;
        if (!level.isLoaded(pos)) level.getChunk(pos);
        BlockEntity be = level.getBlockEntity(pos);
        if (be != null && isIronFurnace(be.getClass())) {
            try {
                return (IBatchDelegate) Class.forName(IRON_DELEGATE).getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException | LinkageError exception) {
                RSIntegrationMod.LOGGER.error("[RSI-IronFurnaces] Failed to create cooking delegate", exception);
                return null;
            }
        }
        return new VanillaMachineBatchDelegate();
    }

    static boolean isIronFurnace(Class<?> type) {
        Class<?> current = type;
        while (current != null) {
            if (IRON_FURNACE_BE.equals(current.getName())) return true;
            current = current.getSuperclass();
        }
        return false;
    }

    private IBatchDelegate active() {
        if (delegate == null) throw new IllegalStateException("Cooking delegate has not been prepared");
        return delegate;
    }

    private void configureChild() {
        if (!(delegate instanceof AbstractBatchDelegate child)) return;
        if (machineDim != null) child.setMachineDim(machineDim);
        if (machineServer != null) child.setMachineServer(machineServer);
        if (targetOutput != null) child.setTargetOutput(targetOutput);
    }

    @Override
    public void setMachineDim(@Nonnull ResourceLocation dim) {
        super.setMachineDim(dim);
        configureChild();
    }

    @Override
    public void setMachineServer(@Nonnull net.minecraft.server.MinecraftServer server) {
        super.setMachineServer(server);
        configureChild();
    }

    @Override
    public void setTargetOutput(@Nullable ItemStack target) {
        super.setTargetOutput(target);
        configureChild();
    }

    @Override
    public boolean acceptsMachineWithoutBlockEntity(@Nonnull ServerLevel level, @Nonnull BlockPos pos) {
        return delegate != null && delegate.acceptsMachineWithoutBlockEntity(level, pos);
    }

    @Override
    public boolean tryStartSingleCraft(@Nonnull ServerPlayer player) {
        configureChild();
        return active().tryStartSingleCraft(player);
    }

    @Override
    public boolean tryStartSingleCraft(@Nonnull ServerPlayer player,
                                       @Nonnull ExtractionLedger sharedLedger) {
        configureChild();
        return active().tryStartSingleCraft(player, sharedLedger);
    }

    @Nullable
    @Override
    public List<IngredientSpec> getRequiredMaterials() {
        return active().getRequiredMaterials();
    }

    @Nonnull
    @Override
    public List<IngredientSpec> getGraphSpecs() {
        return active().getGraphSpecs();
    }

    @Nonnull
    @Override
    public List<IngredientSpec> getSupplementalSpecs() {
        return active().getSupplementalSpecs();
    }

    @Nonnull
    @Override
    public List<ItemStack> mergeSupplementalMaterials(@Nonnull List<ItemStack> graph,
                                                       @Nonnull List<ItemStack> supplemental) {
        return active().mergeSupplementalMaterials(graph, supplemental);
    }

    @Nullable
    @Override
    public BatchConcurrencyCapabilities concurrencyCapabilities() {
        return delegate != null ? delegate.concurrencyCapabilities() : null;
    }

    @Override
    public boolean supportsConcurrentNodeExecution() {
        return delegate != null && delegate.supportsConcurrentNodeExecution();
    }

    @Override
    public boolean tryStartWithMaterials(@Nonnull ServerPlayer player,
                                         @Nonnull List<ItemStack> materials,
                                         @Nonnull ExtractionLedger sharedLedger) {
        configureChild();
        return active().tryStartWithMaterials(player, materials, sharedLedger);
    }

    @Nonnull
    @Override
    protected CraftObservation observeMachineCraft(@Nonnull ServerLevel level,
                                                    @Nonnull BlockEntity be) {
        CraftObservation observation = active().observeCraft(level);
        this.phase = observation.phase();
        return observation;
    }

    @Override
    protected boolean isMachineCraftFinished(@Nonnull ServerLevel level, @Nonnull BlockEntity be) {
        return active().isCraftComplete(level);
    }

    @Nonnull
    @Override
    public ItemStack collectResult(@Nonnull ServerPlayer player) {
        return active().collectResult(player);
    }

    @Nonnull
    @Override
    public List<ItemStack> collectAllResults(@Nonnull ServerPlayer player) {
        return active().collectAllResults(player);
    }

    @Override
    public boolean collectsPhysicalSecondaryOutputs() {
        return active().collectsPhysicalSecondaryOutputs();
    }

    @Nullable
    @Override
    public ExpectedProduction getExpectedProduction() {
        return active().getExpectedProduction();
    }

    @Override
    public void releasePreparationResources() {
        if (delegate != null) delegate.releasePreparationResources();
    }

    @Override
    protected void clearMachineState(BlockEntity be, ServerPlayer player) {
        if (delegate != null) delegate.onBatchFailed(player, "cooking delegate aborted");
        delegate = null;
        resetState();
    }

    @Override
    public void onBatchFinished(@Nonnull ServerPlayer player) {
        if (delegate != null) delegate.onBatchFinished(player);
        delegate = null;
        resetState();
    }

    @Nonnull
    @Override
    public BlockPos getMachinePos() {
        return active().getMachinePos();
    }

    @Nullable
    @Override
    public ItemStack getExpectedOutput() {
        return active().getExpectedOutput();
    }

    @Nullable
    @Override
    public net.minecraft.world.phys.AABB getOutputCaptureRegion() {
        return active().getOutputCaptureRegion();
    }
}
