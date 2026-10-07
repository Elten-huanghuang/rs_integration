package com.huanghuang.rsintegration.mods.ironsspellbooks;

import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.IngredientMatcher;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities;
import com.huanghuang.rsintegration.storage.StorageOperationStatus;
import com.huanghuang.rsintegration.storage.StorageOperationResult;
import com.huanghuang.rsintegration.mods.common.MachineWaterSupply;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
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
import java.util.function.Function;

/** 在真实炼金锅内执行回收和酿造，装瓶消耗账本实际提取的流体和玻璃瓶。 */
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
    private List<ItemStack> secondaryResults = List.of();

    @Override
    public boolean validateAndInit(@Nonnull ServerPlayer player, @Nonnull ResourceLocation recipeId,
                                   @Nullable ResourceLocation dim, @Nonnull BlockPos pos) {
        ServerLevel resolved = CraftPacketUtils.resolveLevel(player.server, dim, player);
        if (resolved != null) IronAlchemistRecipeCatalog.allRecipes(resolved);
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
        consumed = false;
        recycleFailure = "";
        result = ItemStack.EMPTY;
        secondaryResults = List.of();
        heldInputs = List.of();
        actualProduction = null;
        outputStoragePreflightDone = false;
        outputStoragePreflightPassed = false;
        outputStorageWarningSent = false;
        this.machineDim = resolved.dimension().location();
        this.machineServer = player.server;
        return true;
    }

    private boolean recycles() { return recipe != null && recipe.isScrollRecycling(); }
    private boolean producesFluid() { return recycles() || (recipe != null && recipe.isBrewing()); }

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
        if (!producesFluid()) return true;
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
            for (ItemStack extra : recipe.secondaryOutputs()) {
                outputStoragePreflightPassed &= canStoreOutput(storageEndpoint(), player, extra);
            }
            if (!outputStoragePreflightPassed) {
                player.sendSystemMessage(Component.translatable("rsi.alchemist.error.fluid_storage_required"));
            }
        }
        if (!outputStoragePreflightPassed) return false;
        return canEmptyTank(tank, storageEndpoint(), player, InkFluidSupport::token);
    }

    @Override
    public Component validateOutputStorage(@Nonnull ServerPlayer player) {
        if (!producesFluid() || storageEndpoint() == null) return null;
        if (!outputStoragePreflightDone) {
            outputStoragePreflightDone = true;
            outputStoragePreflightPassed = canStoreOutput(storageEndpoint(), player,
                    recipe.getResultItem(level.registryAccess()));
            for (ItemStack extra : recipe.secondaryOutputs()) {
                outputStoragePreflightPassed &= canStoreOutput(storageEndpoint(), player, extra);
            }
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
        if (producesFluid()) {
            IFluidHandler tank = level.getBlockEntity(pos).getCapability(ForgeCapabilities.FLUID_HANDLER).resolve().orElse(null);
            if (!emptyTank(tank, storageEndpoint(), player, InkFluidSupport::token)) return false;
        }
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
        privateLedger.retainCommittedRefunds(heldInputs);
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
        // 链已提交的材料从进入启动方法起由本委托保管；预检失败也必须可退款。
        heldInputs = materials.stream().map(ItemStack::copy).toList();
        if (!validateExecutionContext(player)) return false;
        List<IngredientSpec> specs = getRequiredMaterials();
        if (materials.size() != specs.size()) return false;
        for (int i = 0; i < specs.size(); i++) {
            if (materials.get(i).getCount() != specs.get(i).count()
                    || !IngredientMatcher.test(specs.get(i).ingredient(), materials.get(i))) return false;
        }
        if (recipe.isBrewing()) return startBrew(player, materials);
        if (!recycles()) {
            consumed = true;
            result = recipe.getResultItem(level.registryAccess());
            markCraftStarted();
            return true;
        }
        BlockEntity tile = level.getBlockEntity(pos);
        IFluidHandler tank = tile.getCapability(ForgeCapabilities.FLUID_HANDLER).resolve().orElseThrow();
        if (!emptyTank(tank, storageEndpoint(), player, InkFluidSupport::token)) return false;
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

    static boolean canEmptyTank(IFluidHandler tank, CraftStorageEndpoint endpoint, ServerPlayer player,
                                Function<FluidStack, ItemStack> tokens) {
        if (tank == null || endpoint == null || player == null) return false;
        for (int slot = 0; slot < tank.getTanks(); slot++) {
            FluidStack stored = tank.getFluidInTank(slot).copy();
            if (!stored.isEmpty() && !canStoreOutput(endpoint, player, tokens.apply(stored))) return false;
        }
        return true;
    }

    static boolean emptyTank(IFluidHandler tank, CraftStorageEndpoint endpoint, ServerPlayer player,
                             Function<FluidStack, ItemStack> tokens) {
        if (!canEmptyTank(tank, endpoint, player, tokens)) return false;
        List<FluidStack> contents = new ArrayList<>();
        for (int slot = 0; slot < tank.getTanks(); slot++) {
            FluidStack stored = tank.getFluidInTank(slot).copy();
            if (!stored.isEmpty()) contents.add(stored);
        }
        // 原生锅排空后会整理槽位，先复制全部内容，避免跳过向前移动的流体。
        for (FluidStack stored : contents) {
            FluidStack drained = tank.drain(stored.copy(), IFluidHandler.FluidAction.EXECUTE);
            if (drained.isEmpty()) return false;
            StorageOperationResult inserted = endpoint.insert(player, tokens.apply(drained), false);
            ItemStack remainder = inserted.remainder().orElseThrow(
                    () -> new IllegalStateException("炼金锅旧流体入库数量不确定"));
            if (!remainder.isEmpty()) {
                FluidStack returned = InkFluidSupport.fluid(remainder);
                if (tank.fill(returned, IFluidHandler.FluidAction.EXECUTE) != returned.getAmount())
                    throw new IllegalStateException("无法归还炼金锅旧流体");
            }
            if (!drained.isFluidStackIdentical(stored) || inserted.status() != StorageOperationStatus.SUCCESS) return false;
        }
        return true;
    }

    static boolean canPrepareBrew(IFluidHandler tank, IronSpellBooksRecipe recipe) {
        if (tank == null || tank.getTanks() == 0 || !recipe.isBrewing()) return false;
        for (int slot = 0; slot < tank.getTanks(); slot++) {
            if (!tank.getFluidInTank(slot).isEmpty()) return false;
        }
        FluidStack input = InkFluidSupport.fluid(recipe.inputs().get(0));
        long produced = InkFluidSupport.fluid(recipe.getResultItem(RegistryAccess.EMPTY)).getAmount();
        for (ItemStack extra : recipe.secondaryOutputs()) produced += InkFluidSupport.fluid(extra).getAmount();
        return input.getAmount() <= tank.getTankCapacity(0) && produced <= tank.getTankCapacity(0)
                && tank.fill(input.copy(), IFluidHandler.FluidAction.SIMULATE) == input.getAmount();
    }

    private boolean startBrew(ServerPlayer player, List<ItemStack> materials) {
        BlockEntity tile = level.getBlockEntity(pos);
        IFluidHandler tank = tile.getCapability(ForgeCapabilities.FLUID_HANDLER).resolve().orElse(null);
        if (!emptyTank(tank, storageEndpoint(), player, InkFluidSupport::token) || !canPrepareBrew(tank, recipe)) return false;
        FluidStack input = InkFluidSupport.fluid(materials.get(0));
        BrewInputPreparation prepared = fillBrewInput(tank, input);
        if (!prepared.ready()) {
            List<ItemStack> recovered = new ArrayList<>();
            if (!prepared.refundable().isEmpty()) {
                recovered.add(InkFluidSupport.token(materials.get(0).getItem(), prepared.refundable()));
            }
            recovered.add(materials.get(1).copy());
            heldInputs = List.copyOf(recovered);
            return false;
        }
        ItemStack reagent = materials.get(1).copy();
        try {
            meltInput.invoke(tile, reagent);
        } catch (ReflectiveOperationException failure) {
            retainReagent((Container) tile, reagent);
            consumed = true;
            recycleFailure = "调用炼金锅酿造方法失败，材料仍由机器保管";
            markCraftStarted();
            return true;
        }
        if (!reagent.isEmpty()) {
            FluidStack returned = tank.drain(input.copy(), IFluidHandler.FluidAction.EXECUTE);
            if (returned.isFluidStackIdentical(input)) return false;
            if (!returned.isEmpty()) tank.fill(returned, IFluidHandler.FluidAction.EXECUTE);
            retainReagent((Container) tile, reagent);
            consumed = true;
            recycleFailure = "炼金锅未消耗酿造材料且流体发生变化";
        } else {
            consumed = true;
            result = recipe.getResultItem(level.registryAccess());
            secondaryResults = recipe.secondaryOutputs();
            List<ItemStack> outputs = new ArrayList<>(secondaryResults);
            outputs.add(result);
            if (!matchesBrewOutputs(tank, (Container) tile, outputs)) recycleFailure = "炼金锅实际酿造产物与配方不符";
        }
        markCraftStarted();
        return true;
    }

    record BrewInputPreparation(boolean ready, FluidStack refundable) { }

    static BrewInputPreparation fillBrewInput(IFluidHandler tank, FluidStack input) {
        int filled = tank.fill(input.copy(), IFluidHandler.FluidAction.EXECUTE);
        if (filled == input.getAmount()) return new BrewInputPreparation(true, FluidStack.EMPTY);
        if (filled < 0 || filled > input.getAmount()) throw new IllegalStateException("炼金锅返回了无效的注入数量");
        FluidStack refundable = input.copy();
        refundable.setAmount(input.getAmount() - filled);
        if (filled > 0) {
            FluidStack injected = input.copy();
            injected.setAmount(filled);
            FluidStack returned = tank.drain(injected, IFluidHandler.FluidAction.EXECUTE);
            if (returned.isFluidEqual(input) && returned.getAmount() <= filled) {
                refundable.grow(returned.getAmount());
            } else if (!returned.isEmpty()
                    && tank.fill(returned, IFluidHandler.FluidAction.EXECUTE) != returned.getAmount()) {
                throw new IllegalStateException("无法归还炼金锅注入失败时的异常流体");
            }
        }
        // 尚留在锅内的输入不退款，防止容量变化时复制流体。
        return new BrewInputPreparation(false, refundable);
    }

    private static void retainReagent(Container container, ItemStack reagent) {
        if (reagent.isEmpty()) return;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (container.getItem(slot).isEmpty()) {
                container.setItem(slot, reagent.copy());
                container.setChanged();
                return;
            }
        }
        throw new IllegalStateException("炼金锅无法保留未消耗的酿造材料");
    }

    static boolean matchesBrewOutputs(IFluidHandler tank, Container container, List<ItemStack> outputs) {
        List<FluidStack> fluids = new ArrayList<>();
        for (ItemStack output : outputs) {
            if (InkFluidSupport.isToken(output)) {
                FluidStack fluid = InkFluidSupport.fluid(output);
                FluidStack same = fluids.stream().filter(fluid::isFluidEqual).findFirst().orElse(null);
                if (same == null) fluids.add(fluid); else same.grow(fluid.getAmount());
            } else {
                int count = 0;
                for (int slot = 0; slot < container.getContainerSize(); slot++) {
                    ItemStack stored = container.getItem(slot);
                    if (ItemStack.isSameItemSameTags(stored, output)) count += stored.getCount();
                }
                if (count < output.getCount()) return false;
            }
        }
        int actualAmount = 0;
        for (int slot = 0; slot < tank.getTanks(); slot++) actualAmount += tank.getFluidInTank(slot).getAmount();
        if (actualAmount != fluids.stream().mapToInt(FluidStack::getAmount).sum()) return false;
        for (FluidStack fluid : fluids) {
            if (!tank.drain(fluid.copy(), IFluidHandler.FluidAction.SIMULATE).isFluidStackIdentical(fluid)) return false;
        }
        return true;
    }

    @Override
    protected CraftObservation observeMachineCraft(@Nonnull ServerLevel level, @Nonnull BlockEntity be) {
        if (!recycleFailure.isEmpty()) return failObservation(recycleFailure);
        if (producesFluid() && !result.isEmpty()) {
            IFluidHandler tank = be.getCapability(ForgeCapabilities.FLUID_HANDLER).resolve().orElse(null);
            FluidStack expected = InkFluidSupport.fluid(result);
            if (tank == null || tank.drain(expected, IFluidHandler.FluidAction.SIMULATE).getAmount() != expected.getAmount()) {
                return failObservation("炼金锅中的流体已被外部提取或改变");
            }
            if (recipe.isBrewing()) {
                List<ItemStack> outputs = new ArrayList<>(secondaryResults);
                outputs.add(result);
                if (!matchesBrewOutputs(tank, (Container) be, outputs)) return failObservation("炼金锅酿造产物已被外部改变");
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
        if (producesFluid()) {
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

    @Override public boolean collectsPhysicalSecondaryOutputs() { return recipe != null && recipe.isBrewing(); }

    @Override public List<ItemStack> collectAllResults(@Nonnull ServerPlayer player) {
        if (recipe == null || !recipe.isBrewing()) return super.collectAllResults(player);
        if (result.isEmpty() || !level.hasChunkAt(pos) || !isCauldron(level.getBlockEntity(pos))) return List.of();
        BlockEntity tile = level.getBlockEntity(pos);
        IFluidHandler tank = tile.getCapability(ForgeCapabilities.FLUID_HANDLER).resolve().orElse(null);
        List<ItemStack> outputs = new ArrayList<>();
        outputs.add(result);
        outputs.addAll(secondaryResults);
        List<ItemStack> collected = collectBrewOutputs(tank, (Container) tile, storageEndpoint(), player, outputs);
        if (collected.isEmpty()) return List.of();
        result = ItemStack.EMPTY;
        secondaryResults = List.of();
        return collected;
    }

    static List<ItemStack> collectBrewOutputs(IFluidHandler tank, Container container,
                                             CraftStorageEndpoint endpoint, ServerPlayer player,
                                             List<ItemStack> outputs) {
        if (tank == null || !matchesBrewOutputs(tank, container, outputs)
                || outputs.stream().anyMatch(output -> !canStoreOutput(endpoint, player, output))) return List.of();
        List<ItemStack> collected = new ArrayList<>();
        // 流体整组取出成功后再拿副产物，失败则归还已取出的流体。
        for (ItemStack output : outputs) {
            if (InkFluidSupport.isToken(output)) {
                ItemStack fluid = collectFluid(tank, endpoint, player, output);
                if (fluid.isEmpty()) {
                    for (ItemStack previous : collected) {
                        FluidStack returned = InkFluidSupport.fluid(previous);
                        if (tank.fill(returned, IFluidHandler.FluidAction.EXECUTE) != returned.getAmount()) {
                            throw new IllegalStateException("无法归还炼金锅未收齐的产物");
                        }
                    }
                    return List.of();
                }
                collected.add(fluid);
            }
        }
        for (ItemStack output : outputs) {
            if (!InkFluidSupport.isToken(output)) {
                int missing = output.getCount();
                for (int slot = 0; slot < container.getContainerSize() && missing > 0; slot++) {
                    if (ItemStack.isSameItemSameTags(container.getItem(slot), output)) {
                        ItemStack removed = container.removeItem(slot, missing);
                        collected.add(removed);
                        missing -= removed.getCount();
                    }
                }
            }
        }
        return List.copyOf(collected);
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
        if (!usingSharedLedger && !consumed && ledger != null) {
            ledger.retainCommittedRefunds(heldInputs);
            ledger.refundCommitted(network, player);
        }
        result = ItemStack.EMPTY;
        actualProduction = null;
        secondaryResults = List.of();
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
        secondaryResults = List.of();
        outputStoragePreflightDone = false;
        outputStoragePreflightPassed = false;
        outputStorageWarningSent = false;
        owner = null;
        resetState();
    }
    @Override public BlockPos getMachinePos() { return pos; }
}
