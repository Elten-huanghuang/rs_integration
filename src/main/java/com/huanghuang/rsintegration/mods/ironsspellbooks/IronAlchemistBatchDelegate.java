package com.huanghuang.rsintegration.mods.ironsspellbooks;

import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.IngredientMatcher;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities;
import com.huanghuang.rsintegration.storage.StorageOperationStatus;
import com.huanghuang.rsintegration.mods.common.MachineWaterSupply;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.lang.reflect.Method;

/** 在真实炼金锅内执行卷轴回收，装瓶消耗账本实际提取的墨水和玻璃瓶。 */
public final class IronAlchemistBatchDelegate extends AbstractBatchDelegate {
    private ServerLevel level;
    private BlockPos pos;
    private IronSpellBooksRecipe recipe;
    private ItemStack result = ItemStack.EMPTY;
    private ExpectedProduction actualProduction;
    private boolean consumed;
    private String recycleFailure = "";
    private boolean outputStoragePreflightDone;
    private boolean outputStoragePreflightPassed;
    private boolean outputStorageWarningSent;
    private Method meltInput;
    private List<ItemStack> heldInputs = List.of();
    private ServerPlayer owner;

    @Override
    public boolean validateAndInit(@Nonnull ServerPlayer player, @Nonnull ResourceLocation recipeId,
                                   @Nullable ResourceLocation dim, @Nonnull BlockPos pos) {
        ServerLevel resolved = CraftPacketUtils.resolveLevel(player.server, dim, player);
        IronSpellBooksRecipe found = IronSpellBooksRecipeCatalog.byId(recipeId);
        if (resolved == null || !resolved.hasChunkAt(pos) || found == null
                || found.machine() != IronSpellBooksRecipe.Machine.ALCHEMIST_CAULDRON
                || !isCauldron(resolved.getBlockEntity(pos))) return false;
        BlockEntity tile = resolved.getBlockEntity(pos);
        if (tile.getCapability(ForgeCapabilities.FLUID_HANDLER).resolve().isEmpty()) return false;
        try {
            meltInput = tile.getClass().getMethod("tryMeltInput", ItemStack.class);
        } catch (ReflectiveOperationException failure) {
            return false;
        }
        this.level = resolved;
        this.pos = pos.immutable();
        this.recipe = found;
        this.owner = player;
        outputStoragePreflightDone = false;
        outputStoragePreflightPassed = false;
        outputStorageWarningSent = false;
        this.machineDim = resolved.dimension().location();
        this.machineServer = player.server;
        return true;
    }

    private boolean recycles() { return recipe != null && InkFluidSupport.isToken(recipe.getResultItem(level.registryAccess())); }

    private static boolean isCauldron(BlockEntity tile) {
        return tile instanceof Container && !tile.isRemoved()
                && new ResourceLocation("irons_spellbooks", "alchemist_cauldron").equals(
                ForgeRegistries.BLOCKS.getKey(tile.getBlockState().getBlock()));
    }

    @Override public List<IngredientSpec> getRequiredMaterials() {
        return recipe == null ? null : new IronSpellBooksRecipeHandler().getIngredients(recipe);
    }
    @Override public BatchConcurrencyCapabilities concurrencyCapabilities() { return BatchConcurrencyCapabilities.delegateResult(); }

    @Override
    public boolean validateExecutionContext(@Nullable ServerPlayer player) {
        if (player == null || recipe == null || storageEndpoint() == null
                || !"refinedstorage".equals(storageEndpoint().session().reference().backendId().value())
                || !level.hasChunkAt(pos) || !isCauldron(level.getBlockEntity(pos))) return false;
        if (consumed) return recycleFailure.isEmpty();
        if (!recycles()) return true;
        BlockEntity tile = level.getBlockEntity(pos);
        Container container = (Container) tile;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (!container.getItem(slot).isEmpty()) return false;
        }
        IFluidHandler tank = tile.getCapability(ForgeCapabilities.FLUID_HANDLER).resolve().orElse(null);
        if (tank == null) return false;
        if (!outputStoragePreflightDone) {
            outputStoragePreflightDone = true;
            outputStoragePreflightPassed = canStoreOutput(storageEndpoint(), player,
                    recipe.getResultItem(level.registryAccess()));
            if (!outputStoragePreflightPassed) {
                player.sendSystemMessage(Component.translatable("rsi.alchemist.error.fluid_storage_required"));
            }
        }
        if (!outputStoragePreflightPassed) return false;
        int missingWater = missingWater(tank);
        if (missingWater > 0 && !MachineWaterSupply.canFill(IronSpellBooksRSModule.ALCHEMIST_CAULDRON_TYPE,
                tank, missingWater, storageEndpoint(), player)) return false;
        return canFitRecycledInk(tank, InkFluidSupport.fluid(recipe.getResultItem(level.registryAccess())));
    }

    @Override
    public Component validateOutputStorage(@Nonnull ServerPlayer player) {
        if (!recycles() || storageEndpoint() == null) return null;
        if (!outputStoragePreflightDone) {
            outputStoragePreflightDone = true;
            outputStoragePreflightPassed = canStoreOutput(storageEndpoint(), player,
                    recipe.getResultItem(level.registryAccess()));
        }
        return outputStoragePreflightPassed ? null
                : Component.translatable("rsi.alchemist.error.fluid_storage_required");
    }

    static boolean canStoreOutput(CraftStorageEndpoint endpoint, ServerPlayer player, ItemStack output) {
        return endpoint != null && player != null && !output.isEmpty()
                && "refinedstorage".equals(endpoint.session().reference().backendId().value())
                && endpoint.insert(player, output.copy(), true).status() == StorageOperationStatus.SUCCESS;
    }

    private static int missingWater(IFluidHandler tank) {
        return InkFluidSupport.BOTTLE_AMOUNT - tank.drain(new FluidStack(Fluids.WATER, InkFluidSupport.BOTTLE_AMOUNT),
                IFluidHandler.FluidAction.SIMULATE).getAmount();
    }

    static boolean canFitRecycledInk(IFluidHandler tank, FluidStack output) {
        if (output.isEmpty()) return false;
        for (int slot = 0; slot < tank.getTanks(); slot++) {
            FluidStack stored = tank.getFluidInTank(slot);
            if ((stored.isEmpty() || (stored.getFluid() == Fluids.WATER && stored.getAmount() <= InkFluidSupport.BOTTLE_AMOUNT))
                    && tank.getTankCapacity(slot) >= output.getAmount()) return true;
            if (stored.isFluidEqual(output) && tank.getTankCapacity(slot) - stored.getAmount() >= output.getAmount()) return true;
        }
        return false;
    }

    @Override
    public boolean tryStartSingleCraft(@Nonnull ServerPlayer player) {
        if (storageEndpoint() == null) network = CraftPacketUtils.resolveNetworkForCraft(player, level.dimension(), pos);
        if (!validateExecutionContext(player)) return false;
        ExtractionLedger privateLedger = new ExtractionLedger();
        ledger = privateLedger;
        usingSharedLedger = false;
        privateLedger.setStorageEndpoint(storageEndpoint());
        List<ItemStack> materials = new ArrayList<>();
        for (IngredientSpec spec : getRequiredMaterials()) {
            ItemStack material = CraftPacketUtils.ensureMaterialAvailable(player, level.dimension(), pos,
                    spec.ingredient(), spec.count(), privateLedger);
            if (material.isEmpty()) { privateLedger.close(); return false; }
            materials.add(material);
        }
        if (!privateLedger.commit(network, player)) return false;
        if (start(player, materials)) return true;
        privateLedger.refundCommitted(network, player);
        return false;
    }

    @Override
    public boolean tryStartWithMaterials(@Nonnull ServerPlayer player, @Nonnull List<ItemStack> materials,
                                         @Nonnull ExtractionLedger sharedLedger) {
        ledger = sharedLedger;
        this.sharedLedger = sharedLedger;
        usingSharedLedger = true;
        if (sharedLedger.storageEndpoint() != null) setStorageEndpoint(sharedLedger.storageEndpoint());
        return start(player, materials);
    }

    private boolean start(ServerPlayer player, List<ItemStack> materials) {
        if (!validateExecutionContext(player)) return false;
        List<IngredientSpec> specs = getRequiredMaterials();
        if (materials.size() != specs.size()) return false;
        for (int i = 0; i < specs.size(); i++) {
            if (materials.get(i).getCount() != specs.get(i).count()
                    || !IngredientMatcher.test(specs.get(i).ingredient(), materials.get(i))) return false;
        }
        if (!recycles()) {
            heldInputs = materials.stream().map(ItemStack::copy).toList();
            consumed = true;
            result = recipe.getResultItem(level.registryAccess());
            markCraftStarted();
            return true;
        }
        BlockEntity tile = level.getBlockEntity(pos);
        IFluidHandler tank = tile.getCapability(ForgeCapabilities.FLUID_HANDLER).resolve().orElseThrow();
        int missingWater = missingWater(tank);
        if (missingWater > 0 && MachineWaterSupply.fill(IronSpellBooksRSModule.ALCHEMIST_CAULDRON_TYPE,
                tank, missingWater, storageEndpoint(), player) != missingWater) return false;
        if (missingWater(tank) != 0 || !canFitRecycledInk(tank,
                InkFluidSupport.fluid(recipe.getResultItem(level.registryAccess())))) return false;
        FluidStack output = InkFluidSupport.fluid(recipe.getResultItem(level.registryAccess()));
        FluidStack probe = output.copy();
        probe.setAmount(Integer.MAX_VALUE);
        int before = tank.drain(probe.copy(), IFluidHandler.FluidAction.SIMULATE).getAmount();
        ItemStack scroll = materials.get(0).copy();
        heldInputs = materials.stream().map(ItemStack::copy).toList();
        try {
            meltInput.invoke(tile, scroll);
        } catch (ReflectiveOperationException failure) {
            consumed = true;
            recycleFailure = "调用炼金锅卷轴回收方法失败";
            markCraftStarted();
            return true;
        }
        if (!scroll.isEmpty()) return false;
        consumed = true;
        int after = tank.drain(probe, IFluidHandler.FluidAction.SIMULATE).getAmount();
        int produced = after - before;
        recycleFailure = recyclingFailure(produced, output.getAmount());
        result = produced == output.getAmount() ? recipe.getResultItem(level.registryAccess()) : ItemStack.EMPTY;
        actualProduction = result.isEmpty() ? null : new ExpectedProduction(result, result.getCount());
        markCraftStarted();
        return true;
    }

    @Override
    protected CraftObservation observeMachineCraft(@Nonnull ServerLevel level, @Nonnull BlockEntity be) {
        if (!recycleFailure.isEmpty()) return failObservation(recycleFailure);
        if (recycles() && !result.isEmpty()) {
            IFluidHandler tank = be.getCapability(ForgeCapabilities.FLUID_HANDLER).resolve().orElse(null);
            FluidStack expected = InkFluidSupport.fluid(result);
            if (tank == null || tank.drain(expected, IFluidHandler.FluidAction.SIMULATE).getAmount() != expected.getAmount()) {
                return failObservation("炼金锅中的墨水已被外部提取或改变");
            }
        }
        // 原模组正常消耗卷轴但未中奖，也是一轮已完成的操作。
        return consumed ? doneObservation() : workingObservation();
    }

    static String recyclingFailure(int produced, int expected) {
        return produced == 0 || produced == expected ? "" : "炼金锅墨水数量变化与本次回收不符";
    }

    @Override protected boolean isMachineCraftFinished(@Nonnull ServerLevel level, @Nonnull BlockEntity be) {
        return consumed && recycleFailure.isEmpty();
    }

    @Override
    public ItemStack collectResult(@Nonnull ServerPlayer player) {
        if (result.isEmpty()) return ItemStack.EMPTY;
        if (recycles()) {
            if (!level.hasChunkAt(pos) || !isCauldron(level.getBlockEntity(pos))) return ItemStack.EMPTY;
            IFluidHandler tank = level.getBlockEntity(pos).getCapability(ForgeCapabilities.FLUID_HANDLER).resolve().orElse(null);
            ItemStack collected = collectFluid(tank, storageEndpoint(), player, result);
            if (collected.isEmpty()) {
                if (!outputStorageWarningSent) {
                    player.sendSystemMessage(Component.translatable("rsi.alchemist.error.fluid_storage_missing_output_kept"));
                    outputStorageWarningSent = true;
                }
                return ItemStack.EMPTY;
            }
            result = ItemStack.EMPTY;
            return collected;
        }
        ItemStack collected = result;
        result = ItemStack.EMPTY;
        return collected;
    }

    @Override
    public ExpectedProduction getExpectedProduction() {
        return actualProduction;
    }

    @Override public boolean failureConsumesInputs(@Nonnull CraftObservation observation) {
        return consumed;
    }

    static ItemStack collectFluid(IFluidHandler tank, CraftStorageEndpoint endpoint, ServerPlayer player, ItemStack output) {
        if (tank == null || !canStoreOutput(endpoint, player, output)) return ItemStack.EMPTY;
        FluidStack requested = InkFluidSupport.fluid(output);
        FluidStack simulated = tank.drain(requested.copy(), IFluidHandler.FluidAction.SIMULATE);
        if (requested.isEmpty() || !requested.isFluidEqual(simulated) || simulated.getAmount() != requested.getAmount()) return ItemStack.EMPTY;
        FluidStack drained = tank.drain(requested, IFluidHandler.FluidAction.EXECUTE);
        if (!requested.isFluidEqual(drained) || drained.getAmount() != requested.getAmount()) {
            if (!drained.isEmpty()) tank.fill(drained, IFluidHandler.FluidAction.EXECUTE);
            return ItemStack.EMPTY;
        }
        return InkFluidSupport.token(output.getItem(), drained);
    }

    @Override
    protected void clearMachineState(BlockEntity be, ServerPlayer player) {
        recordFailureRecoveredInputs(consumed ? List.of() : heldInputs);
        if (!usingSharedLedger && !consumed && ledger != null) ledger.refundCommitted(network, player);
        result = ItemStack.EMPTY;
        actualProduction = null;
        outputStoragePreflightDone = false;
        outputStoragePreflightPassed = false;
        outputStorageWarningSent = false;
        owner = null;
        resetState();
    }
    @Override public void onBatchFinished(@Nullable ServerPlayer player) {
        markTerminalCleanup();
        result = ItemStack.EMPTY;
        actualProduction = null;
        outputStoragePreflightDone = false;
        outputStoragePreflightPassed = false;
        outputStorageWarningSent = false;
        owner = null;
        resetState();
    }
    @Override public BlockPos getMachinePos() { return pos; }
}
