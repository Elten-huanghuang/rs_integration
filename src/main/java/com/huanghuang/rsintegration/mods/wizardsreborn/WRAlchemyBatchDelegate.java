package com.huanghuang.rsintegration.mods.wizardsreborn;

import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.mods.common.MachineWaterSupply;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.recipe.WRAlchemyRecipeHandler;
import com.huanghuang.rsintegration.recipe.WRAlchemyRecipeHandler.MachineInput;
import com.huanghuang.rsintegration.storage.StorageOperationStatus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.IItemHandler;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** 只操作炼金材料和产物，能量完全交给炼金机与正上方锅炉处理。 */
public final class WRAlchemyBatchDelegate extends AbstractBatchDelegate {
    private ServerLevel level;
    private BlockPos pos;
    private BlockEntity machine;
    private BlockEntity boiler;
    private Recipe<?> recipe;
    private List<IngredientSpec> required = List.of();
    private List<MachineInput> machineInputs = List.of();
    private ItemStack expectedItem = ItemStack.EMPTY;
    private FluidStack expectedFluid = FluidStack.EMPTY;
    private final List<ItemStack> remaining = new ArrayList<>();
    private final List<PlacedItem> placedItems = new ArrayList<>();
    private final List<PlacedFluid> placedFluids = new ArrayList<>();
    private boolean started;
    private boolean consumed;
    private long lastProgressTick;
    private long lastProgress = -1;
    private Component failure;

    record PlacedItem(int slot, ItemStack stack) {}
    record PlacedFluid(int tank, ItemStack token, boolean refundable) {
        PlacedFluid(int tank, ItemStack token) { this(tank, token, true); }
    }

    @Override
    public PreparationResult prepare(ServerPlayer player, ResourceLocation recipeId,
                                     @Nullable ResourceLocation dim, BlockPos pos) {
        this.level = CraftPacketUtils.resolveLevel(player.server, dim, player);
        if (level == null) return PreparationResult.fatal("炼金机维度不可用");
        if (!level.hasChunkAt(pos) || !level.hasChunkAt(pos.above())) {
            return PreparationResult.retry("炼金结构区块尚未加载");
        }
        BlockEntity candidate = level.getBlockEntity(pos);
        if (!isMachine(candidate)) return PreparationResult.fatal("绑定方块不是炼金机");
        BlockEntity upper = level.getBlockEntity(pos.above());
        if (!isBoiler(upper)) return problem("boiler_missing");
        Recipe<?> found = level.getRecipeManager().byKey(recipeId).orElse(null);
        WRAlchemyRecipeHandler handler = new WRAlchemyRecipeHandler();
        if (found == null || !handler.canHandle(found)) return PreparationResult.fatal("不是炼金机配方");
        this.pos = pos.immutable();
        this.machine = candidate;
        this.boiler = upper;
        this.recipe = found;
        this.machineDim = level.dimension().location();
        this.machineServer = player.server;
        machineInputs = handler.getMachineInputs(found, MachineWaterSupply.isFree(WRAlchemyRecipeHandler.TYPE));
        required = machineInputs.stream().filter(input -> !input.freeWater()).map(MachineInput::material).toList();
        expectedItem = WRAlchemyAccess.itemOutput(found, level.registryAccess());
        expectedFluid = WRAlchemyAccess.fluidOutput(found);
        if (required.isEmpty() || expectedItem.isEmpty() && expectedFluid.isEmpty()) {
            return PreparationResult.fatal("炼金配方缺少材料或产物");
        }
        long fluidCount = machineInputs.stream().filter(input ->
                InkFluidSupport.isToken(input.material().ingredient().getItems()[0])).count();
        if (machineInputs.size() - fluidCount > 6 || fluidCount > 3) return problem("capacity");
        if (machineInputs.size() == fluidCount) return problem("item_input_required");
        if (running() || progress() > 0) return PreparationResult.retry("炼金机正在加工",
                Component.translatable("rsi.wr.alchemy.error.busy"));
        if (!hasEnergy()) return problem("no_energy");
        if (!expectedFluid.isEmpty() && outputTank().getTankCapacity(0) < expectedFluid.getAmount()) {
            return problem("output_full");
        }
        if (storageEndpoint() == null) network = CraftPacketUtils.resolveNetworkForCraft(player, level.dimension(), pos);
        if ((!expectedFluid.isEmpty() || fluidCount > 0 || hasStoredFluid()) && !usesFluidStorage()) {
            return problem("fluid_storage");
        }
        if (!canStoreFluid(player)) return problem("fluid_storage");
        if (!canRecover(player)) return problem("recovery_storage");
        return PreparationResult.ready();
    }

    private PreparationResult problem(String key) {
        return PreparationResult.fatal("炼金结构检查失败: " + key,
                Component.translatable("rsi.wr.alchemy.error." + key));
    }

    @Override public boolean validateAndInit(ServerPlayer player, ResourceLocation recipeId,
                                            @Nullable ResourceLocation dim, BlockPos pos) {
        return prepare(player, recipeId, dim, pos).state() == PreparationState.READY;
    }

    private static boolean isMachine(BlockEntity be) {
        return be != null && !be.isRemoved() && be.getClass().getName().equals(
                "mod.maxbogomol.wizards_reborn.common.block.alchemy_machine.AlchemyMachineBlockEntity");
    }

    private static boolean isBoiler(BlockEntity be) {
        return be != null && !be.isRemoved() && be.getClass().getName().equals(
                "mod.maxbogomol.wizards_reborn.common.block.alchemy_boiler.AlchemyBoilerBlockEntity");
    }

    private boolean structureValid() {
        return level != null && level.hasChunkAt(pos) && level.hasChunkAt(pos.above())
                && level.getBlockEntity(pos) == machine && !machine.isRemoved()
                && level.getBlockEntity(pos.above()) == boiler && !boiler.isRemoved();
    }

    private IFluidHandler outputTank() { return (IFluidHandler) WRAlchemyAccess.invoke(boiler, "getTank"); }
    private List<IItemHandler> inventories() {
        return List.of(WRAlchemyAccess.items(machine, false), WRAlchemyAccess.items(machine, true));
    }
    private List<IFluidHandler> tanks() {
        return List.of(WRAlchemyAccess.tank(machine, 0), WRAlchemyAccess.tank(machine, 1),
                WRAlchemyAccess.tank(machine, 2), outputTank());
    }
    private boolean hasStoredFluid() {
        return tanks().stream().anyMatch(tank -> !tank.getFluidInTank(0).isEmpty());
    }
    private boolean canRecover(ServerPlayer player) {
        return WRAlchemyRecovery.canRecover(inventories(), tanks(), storageEndpoint(), player, InkFluidSupport::token);
    }
    private boolean recoverOldContents(ServerPlayer player) {
        if (!structureValid() || running() || progress() > 0) return false;
        try {
            return WRAlchemyRecovery.recover(inventories(), tanks(), storageEndpoint(), player, InkFluidSupport::token);
        } finally {
            // 部分入库也会改变机器内容，需要同步尚未被 RS 接收的剩余内容。
            WRContainerHelper.syncBlockEntity(machine);
            WRContainerHelper.syncBlockEntity(boiler);
        }
    }
    private boolean running() { return (boolean) WRAlchemyAccess.field(machine, "startCraft"); }
    private long progress() {
        return ((Number) WRAlchemyAccess.field(machine, "wissenIsCraft")).longValue()
                + ((Number) WRAlchemyAccess.field(machine, "steamIsCraft")).longValue();
    }

    private boolean hasEnergy() {
        return (WRAlchemyAccess.number(recipe, "getWissen") <= 0 || WRAlchemyAccess.number(boiler, "getWissen") > 0)
                && (WRAlchemyAccess.number(recipe, "getSteam") <= 0 || WRAlchemyAccess.number(boiler, "getSteam") > 0);
    }

    private boolean idle() {
        if (running() || progress() > 0) return false;
        for (boolean output : new boolean[]{false, true}) {
            IItemHandler items = WRAlchemyAccess.items(machine, output);
            for (int slot = 0; slot < items.getSlots(); slot++) {
                if (!items.getStackInSlot(slot).isEmpty()) return false;
            }
        }
        for (int index = 0; index < 3; index++) {
            if (!WRAlchemyAccess.tank(machine, index).getFluidInTank(0).isEmpty()) return false;
        }
        return expectedFluid.isEmpty() || outputTank().getFluidInTank(0).isEmpty();
    }

    private boolean usesFluidStorage() {
        return storageEndpoint() != null && "refinedstorage".equals(
                storageEndpoint().session().reference().backendId().value());
    }

    private boolean canStoreFluid(ServerPlayer player) {
        return expectedFluid.isEmpty() || usesFluidStorage() && storageEndpoint().insert(player,
                InkFluidSupport.token(expectedFluid), true).status() == StorageOperationStatus.SUCCESS;
    }

    @Override public List<IngredientSpec> getRequiredMaterials() { return required; }

    @Override public void configureMaterialReservation(ExtractionLedger ledger, ServerPlayer player) {
        if (ledger.storageEndpoint() != null) setStorageEndpoint(ledger.storageEndpoint());
        recoverOldContents(player);
    }

    @Override public boolean validateExecutionContext(@Nullable ServerPlayer player) {
        if (!structureValid() || player == null) return false;
        return started || !running() && progress() == 0 && hasEnergy() && canStoreFluid(player) && canRecover(player);
    }

    @Override public Component validateOutputStorage(ServerPlayer player) {
        return canStoreFluid(player) && canRecover(player) ? null
                : Component.translatable("rsi.wr.alchemy.error.recovery_storage");
    }

    @Override public boolean tryStartSingleCraft(ServerPlayer player) {
        if (started || !validateExecutionContext(player) || !recoverOldContents(player)) return false;
        this.ledger = new ExtractionLedger();
        ledger.setStorageEndpoint(storageEndpoint());
        List<ItemStack> materials = new ArrayList<>();
        for (IngredientSpec spec : required) {
            ItemStack stack = CraftPacketUtils.ensureMaterialAvailable(player, level.dimension(), pos,
                    spec.ingredient(), spec.count(), ledger);
            if (stack.isEmpty()) { ledger.rollback(player); return false; }
            materials.add(stack.copy());
        }
        if (!ledger.commit(network, player)) return false;
        return place(player, materials);
    }

    @Override public boolean tryStartWithMaterials(ServerPlayer player, List<ItemStack> materials,
                                                   ExtractionLedger sharedLedger) {
        useSharedLedger(sharedLedger);
        return place(player, materials);
    }

    private boolean place(ServerPlayer player, List<ItemStack> materials) {
        if (started) return false;
        remaining.clear();
        materials.forEach(stack -> remaining.add(stack.copy()));
        if (!validateExecutionContext(player) || materials.size() != required.size()) return false;
        if (!recoverOldContents(player)) {
            player.sendSystemMessage(Component.translatable("rsi.wr.alchemy.error.recovery_storage"));
            return false;
        }
        if (!idle() || !canStoreFluid(player)) return false;
        IItemHandler items = WRAlchemyAccess.items(machine, false);
        List<ItemStack> inputs = new ArrayList<>();
        int materialIndex = 0;
        for (MachineInput input : machineInputs) {
            inputs.add(input.freeWater()
                    ? input.material().ingredient().getItems()[0].copyWithCount(input.material().count())
                    : materials.get(materialIndex++));
        }
        int itemSlot = 0, fluidSlot = 0;
        // 先模拟全部槽位，防止部分投入后发现容量不足。
        for (int index = 0; index < inputs.size(); index++) {
            ItemStack stack = inputs.get(index);
            IngredientSpec spec = machineInputs.get(index).material();
            if (stack.getCount() != spec.count() || !spec.ingredient().test(stack)) return false;
            if (InkFluidSupport.isToken(stack)) {
                FluidStack fluid = InkFluidSupport.fluid(stack);
                if (!canFillEmptyTank(WRAlchemyAccess.tank(machine, fluidSlot++), fluid)) return false;
            } else if (!items.insertItem(itemSlot++, stack.copy(), true).isEmpty()) return false;
        }
        itemSlot = 0;
        fluidSlot = 0;
        materialIndex = 0;
        for (int index = 0; index < inputs.size(); index++) {
            ItemStack stack = inputs.get(index);
            boolean refundable = !machineInputs.get(index).freeWater();
            if (InkFluidSupport.isToken(stack)) {
                int filled = WRAlchemyAccess.tank(machine, fluidSlot).fill(InkFluidSupport.fluid(stack),
                        IFluidHandler.FluidAction.EXECUTE);
                if (filled > 0) placedFluids.add(new PlacedFluid(fluidSlot, stack.copyWithCount(filled), refundable));
                if (refundable) remaining.set(materialIndex++, stack.copyWithCount(stack.getCount() - filled));
                fluidSlot++;
                if (filled != stack.getCount()) return false;
            } else {
                ItemStack rest = items.insertItem(itemSlot, stack.copy(), false);
                int inserted = stack.getCount() - rest.getCount();
                if (inserted > 0) placedItems.add(new PlacedItem(itemSlot, stack.copyWithCount(inserted)));
                remaining.set(materialIndex++, rest.copy());
                itemSlot++;
                if (!rest.isEmpty()) return false;
            }
        }
        if (!nativeRecipeMatches()) return false;
        if (WRAlchemyAccess.hasPotionOutput(recipe)) {
            ItemStack bottle = (ItemStack) WRAlchemyAccess.invoke(machine, "getAlchemyBottle");
            expectedItem = WRAlchemyAccess.potionOutput(recipe, bottle);
        }
        if (!WRContainerHelper.invokeWissenWandFunction(machine)) return false;
        started = true;
        lastProgressTick = level.getGameTime();
        lastProgress = progress();
        markCraftStarted();
        WRContainerHelper.syncBlockEntity(machine);
        forceMachineChunk(level, pos, true);
        return true;
    }

    private boolean nativeRecipeMatches() {
        try {
            Class<?> contextType = Class.forName("mod.maxbogomol.wizards_reborn.common.recipe.AlchemyMachineContext");
            IItemHandler input = WRAlchemyAccess.items(machine, false);
            SimpleContainer container = new SimpleContainer(7);
            for (int slot = 0; slot < input.getSlots(); slot++) container.setItem(slot, input.getStackInSlot(slot).copy());
            Object context = contextType.getConstructor(Container.class, IFluidHandler[].class)
                    .newInstance(container, new IFluidHandler[]{WRAlchemyAccess.tank(machine, 0),
                            WRAlchemyAccess.tank(machine, 1), WRAlchemyAccess.tank(machine, 2)});
            @SuppressWarnings("unchecked")
            Recipe<Container> typed = (Recipe<Container>) recipe;
            @SuppressWarnings("unchecked")
            RecipeType<Recipe<Container>> type = (RecipeType<Recipe<Container>>) recipe.getType();
            return typed.matches((Container) context, level)
                    && level.getRecipeManager().getRecipeFor(type, (Container) context, level)
                    .map(selected -> selected.getId().equals(recipe.getId())).orElse(false);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("无法校验炼金机材料", failure);
        }
    }

    static boolean canFillEmptyTank(IFluidHandler tank, FluidStack fluid) {
        return !fluid.isEmpty() && tank.getFluidInTank(0).isEmpty()
                && tank.fill(fluid.copy(), IFluidHandler.FluidAction.SIMULATE) == fluid.getAmount();
    }

    static boolean hasOutput(IItemHandler items, IFluidHandler tank, ItemStack item, FluidStack fluid) {
        ItemStack stored = items.getStackInSlot(0);
        return (item.isEmpty() || ItemStack.isSameItemSameTags(stored, item) && stored.getCount() >= item.getCount())
                && (fluid.isEmpty() || tank.drain(fluid.copy(), IFluidHandler.FluidAction.SIMULATE)
                .isFluidStackIdentical(fluid));
    }

    @Override protected boolean isMachineCraftFinished(ServerLevel level, BlockEntity be) {
        if (!started || !structureValid() || be != machine) return false;
        boolean complete = !running() && hasOutput(WRAlchemyAccess.items(machine, true), outputTank(), expectedItem, expectedFluid);
        if (complete) consumed = true;
        return complete;
    }

    @Override protected CraftObservation observeMachineCraft(ServerLevel level, BlockEntity be) {
        if (!structureValid() || be != machine) {
            failure = Component.translatable("rsi.wr.alchemy.error.structure_changed");
            return failObservation("炼金结构已改变");
        }
        if (isMachineCraftFinished(level, be)) return doneObservation();
        long progress = progress();
        if (lastProgress != progress) { lastProgress = progress; lastProgressTick = level.getGameTime(); }
        if (level.getGameTime() - lastProgressTick >= 200) {
            failure = Component.translatable("rsi.wr.alchemy.error.stalled");
            return failObservation("炼金加工停滞，请检查蒸汽和秘蕴供应");
        }
        return workingObservation();
    }

    @Override public Component craftFailureMessage(CraftObservation observation) { return failure; }
    @Override public boolean failureConsumesInputs(CraftObservation observation) { return consumed; }
    @Override public ItemStack collectResult(ServerPlayer player) {
        List<ItemStack> outputs = collectAllResults(player);
        return outputs.isEmpty() ? ItemStack.EMPTY : outputs.get(0);
    }

    @Override public List<ItemStack> collectAllResults(ServerPlayer player) {
        if (!structureValid() || !isMachineCraftFinished(level, machine)) return List.of();
        List<ItemStack> outputs = new ArrayList<>();
        if (!expectedItem.isEmpty()) outputs.add(WRAlchemyAccess.items(machine, true).extractItem(0, expectedItem.getCount(), false));
        if (!expectedFluid.isEmpty()) {
            FluidStack drained = outputTank().drain(expectedFluid.copy(), IFluidHandler.FluidAction.EXECUTE);
            if (!drained.isEmpty()) outputs.add(InkFluidSupport.token(drained));
        }
        // 原模组可能留下容器，跟随真实产物回收，不作为额外合成材料返还。
        IItemHandler input = WRAlchemyAccess.items(machine, false);
        for (int slot = 0; slot < input.getSlots(); slot++) {
            ItemStack remainder = input.extractItem(slot, input.getStackInSlot(slot).getCount(), false);
            if (!remainder.isEmpty()) outputs.add(remainder);
        }
        WRContainerHelper.syncBlockEntity(machine);
        WRContainerHelper.syncBlockEntity(boiler);
        return outputs;
    }

    @Override public boolean collectsPhysicalSecondaryOutputs() { return true; }

    @Override protected void clearMachineState(BlockEntity be, @Nullable ServerPlayer player) {
        List<ItemStack> recovered = new ArrayList<>();
        remaining.stream().filter(stack -> !stack.isEmpty()).forEach(stack -> recovered.add(stack.copy()));
        if (be == machine && !machine.isRemoved()) {
            // 原模组在产物出现时才消耗材料；取消时清除本次投入并停止启动标志。
            if (started && structureValid() && !running()
                    && hasOutput(WRAlchemyAccess.items(machine, true), outputTank(), expectedItem, expectedFluid)) {
                consumed = true;
            }
            if (!consumed) {
                IItemHandler items = WRAlchemyAccess.items(machine, false);
                for (PlacedItem placed : placedItems) {
                    ItemStack current = items.getStackInSlot(placed.slot());
                    if (ItemStack.isSameItemSameTags(current, placed.stack())) {
                        recovered.add(items.extractItem(placed.slot(), placed.stack().getCount(), false));
                    }
                }
                for (PlacedFluid placed : placedFluids) {
                    FluidStack drained = WRAlchemyAccess.tank(machine, placed.tank()).drain(
                            InkFluidSupport.fluid(placed.token()), IFluidHandler.FluidAction.EXECUTE);
                    if (placed.refundable() && !drained.isEmpty()) {
                        recovered.add(InkFluidSupport.token(placed.token().getItem(), drained));
                    }
                }
                if (started || !placedItems.isEmpty() || !placedFluids.isEmpty()) {
                    WRAlchemyAccess.setField(machine, "startCraft", false);
                    WRAlchemyAccess.setField(machine, "wissenIsCraft", 0);
                    WRAlchemyAccess.setField(machine, "steamIsCraft", 0);
                }
            }
            WRContainerHelper.syncBlockEntity(machine);
        }
        recordFailureRecoveredInputs(recovered);
        if (!usingSharedLedger && ledger != null && ledger.isCommitted()) {
            ledger.retainCommittedRefunds(recovered);
            ledger.refundCommitted(network, player);
        }
        remaining.clear();
        placedItems.clear();
        placedFluids.clear();
    }

    @Override protected void clearMissingMachineState(@Nullable ServerPlayer player) {
        List<ItemStack> recovered = remaining.stream().filter(stack -> !stack.isEmpty())
                .map(ItemStack::copy).toList();
        recordFailureRecoveredInputs(recovered);
        if (!usingSharedLedger && ledger != null && ledger.isCommitted()) {
            ledger.retainCommittedRefunds(recovered);
            ledger.refundCommitted(network, player);
        }
        remaining.clear();
        placedItems.clear();
        placedFluids.clear();
    }

    @Override public void onBatchFinished(@Nullable ServerPlayer player) {
        if (!markTerminalCleanup()) return;
        releaseMachineChunk();
        remaining.clear();
        placedItems.clear();
        placedFluids.clear();
        resetState();
    }

    @Override public BlockPos getMachinePos() { return pos; }
}
