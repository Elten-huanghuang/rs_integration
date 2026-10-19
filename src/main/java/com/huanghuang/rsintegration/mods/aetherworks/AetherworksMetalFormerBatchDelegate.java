package com.huanghuang.rsintegration.mods.aetherworks;

import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.mods.common.MachineWaterSupply;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.recipe.AetherworksMetalFormerRecipeHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler.FluidAction;
import net.sirplop.aetherworks.blockentity.IForge;
import net.sirplop.aetherworks.blockentity.MetalFormerBlockEntity;
import net.sirplop.aetherworks.recipe.IMetalFormerRecipe;
import net.sirplop.aetherworks.recipe.MetalFormerContext;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** 由原生锻造炉推进加工；只管理本次投料及共用槽位中的实际成品。 */
public final class AetherworksMetalFormerBatchDelegate extends AbstractBatchDelegate {
    private ServerLevel level;
    private BlockPos pos;
    private MetalFormerBlockEntity machine;
    private IForge forge;
    private IMetalFormerRecipe recipe;
    private List<IngredientSpec> required = List.of();
    private ItemStack expected = ItemStack.EMPTY;
    private final List<ItemStack> remaining = new ArrayList<>();
    private ItemStack placedItem = ItemStack.EMPTY;
    private FluidStack placedFluid = FluidStack.EMPTY;
    private boolean started;
    private boolean sawProgress;
    private long startTick;
    private long unpoweredTicks;
    private Component failureMessage;

    @Override public PreparationResult prepare(ServerPlayer player, ResourceLocation recipeId,
                                               @Nullable ResourceLocation dim, BlockPos target) {
        level = CraftPacketUtils.resolveLevel(player.server, dim, player);
        if (level == null || !level.hasChunkAt(target)) {
            return PreparationResult.retry("金属成型台区块尚未加载");
        }
        Recipe<?> found = level.getRecipeManager().byKey(recipeId).orElse(null);
        if (!(found instanceof IMetalFormerRecipe forming)) {
            return PreparationResult.fatal("不是金属成型配方",
                    Component.translatable("rsi.generic.error.wrong_recipe_type"));
        }
        if (!(level.getBlockEntity(target) instanceof MetalFormerBlockEntity former)) {
            return PreparationResult.fatal("绑定位置不是金属成型台",
                    Component.translatable("rsi.aetherworks.error.not_metal_former"));
        }
        BlockEntity attached = AetherworksMachineSafety.findAttachedForge(level, target, former);
        if (!(attached instanceof IForge attachedForge)) {
            return PreparationResult.fatal("金属成型台未连接完整锻造炉",
                    Component.translatable("rsi.aetherworks.error.no_attached_forge"));
        }
        var handler = new AetherworksMetalFormerRecipeHandler();
        List<IngredientSpec> specs = handler.getIngredients(found);
        ItemStack output = handler.getResultItem(found, level.registryAccess());
        if (specs == null || specs.size() != 2 || output.isEmpty()) {
            return PreparationResult.fatal("无法解析金属成型材料或产物",
                    Component.translatable("rsi.generic.error.wrong_recipe_type"));
        }
        pos = target.immutable();
        machine = former;
        forge = attachedForge;
        recipe = forming;
        required = List.copyOf(specs);
        expected = output.copy();
        machineDim = level.dimension().location();
        machineServer = player.server;
        if (storageEndpoint() == null) setStorageEndpoint(CraftPacketUtils.resolveEndpointForCraft(
                player, level.dimension(), target).orElse(null));
        if (!MachineWaterSupply.supportsFluidStorage(storageEndpoint())) {
            return PreparationResult.fatal("金属成型流体材料需要 RS 或 BD 流体存储",
                    Component.translatable("rsi.embers_machine.error.fluid_storage"));
        }
        if (required.get(1).count() > machine.getTank().getCapacity()) {
            return PreparationResult.fatal("配方流体量超过金属成型台容量",
                    Component.translatable("rsi.aetherworks.error.fluid_capacity"));
        }
        if (!idle()) return PreparationResult.retry("金属成型台物品槽或流体罐非空",
                Component.translatable("rsi.aetherworks.error.metal_former_occupied"));
        if (!hasPower()) return PreparationResult.retry("锻造炉缺少余烬",
                Component.translatable("rsi.aetherworks.error.forge_unpowered"));
        return PreparationResult.ready();
    }

    @Override public boolean validateAndInit(ServerPlayer player, ResourceLocation recipeId,
                                            @Nullable ResourceLocation dim, BlockPos pos) {
        return prepare(player, recipeId, dim, pos).state() == PreparationState.READY;
    }

    private boolean machinePresent() {
        return machine != null && !machine.isRemoved() && level != null && pos != null
                && level.hasChunkAt(pos) && level.getBlockEntity(pos) == machine;
    }

    private boolean structureValid() {
        if (!machinePresent() || forge == null) return false;
        BlockEntity owner = forge.getOwner();
        return owner != null && !owner.isRemoved() && level.hasChunkAt(owner.getBlockPos())
                && level.getBlockEntity(owner.getBlockPos()) == owner
                && AetherworksMachineSafety.containsIdentity(forge.getParts(), machine);
    }

    private boolean idle() {
        return machine.inventory.getStackInSlot(0).isEmpty() && machine.getTank().isEmpty()
                && machine.progress == 0;
    }

    private boolean hasPower() {
        // 1.1.2 的锻造炉 canFunction() 固定返回 false；原生成型台只检查可消耗的余烬。
        return forge != null && forge.getEmberCapability().getEmber() >= 1.0;
    }

    @Override public boolean validateExecutionContext(@Nullable ServerPlayer player) {
        return player != null && structureValid() && (started || idle() && hasPower());
    }

    @Override public List<IngredientSpec> getRequiredMaterials() { return required; }
    @Override public ItemStack getExpectedOutput() { return expected.copy(); }
    @Override public ExpectedProduction getExpectedProduction() {
        return expected.isEmpty() ? null : new ExpectedProduction(expected.copy(), expected.getCount());
    }

    @Override public boolean startSingleOperation(ServerPlayer player) {
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
        if (!ledger.commitToEndpoint(storageEndpoint(), player)) return false;
        return start(player, materials);
    }

    @Override public boolean startPreparedOperation(ServerPlayer player, List<ItemStack> materials,
                                                   ExtractionLedger sharedLedger) {
        useSharedLedger(sharedLedger);
        return start(player, materials);
    }

    private boolean start(ServerPlayer player, List<ItemStack> materials) {
        if (started || required.size() != 2 || materials.size() != required.size()) return false;
        remaining.clear();
        materials.forEach(stack -> remaining.add(stack.copy()));
        if (!validateExecutionContext(player)) return false;
        for (int i = 0; i < materials.size(); i++) {
            ItemStack material = materials.get(i);
            IngredientSpec spec = required.get(i);
            if (material.isEmpty() || material.getCount() != spec.count()
                    || !spec.ingredient().test(material)) return false;
        }
        ItemStack item = materials.get(0);
        FluidStack fluid = InkFluidSupport.fluid(materials.get(1));
        if (fluid.isEmpty() || !machine.inventory.insertItem(0, item.copy(), true).isEmpty()
                || machine.getTank().fill(fluid.copy(), FluidAction.SIMULATE) != fluid.getAmount()) return false;
        // 沿用天华砧和工具站的自动温控；加工和余烬消耗仍由原生锻造炉负责。
        regulateTemperature();
        ItemStack rest = machine.inventory.insertItem(0, item.copy(), false);
        int inserted = item.getCount() - rest.getCount();
        if (inserted > 0) placedItem = item.copyWithCount(inserted);
        remaining.set(0, rest.copy());
        if (!rest.isEmpty()) return false;
        int filled = machine.getTank().fill(fluid.copy(), FluidAction.EXECUTE);
        if (filled > 0) {
            placedFluid = fluid.copy();
            placedFluid.setAmount(filled);
        }
        remaining.set(1, materials.get(1).copyWithCount(fluid.getAmount() - filled));
        if (filled != fluid.getAmount() || !recipe.matches(new MetalFormerContext(
                machine.inventory, machine.getTank(), (int) forge.getHeatCapability().getHeat()), level)) return false;
        machine.cachedRecipe = recipe;
        machine.progress = 0;
        machine.setChanged();
        started = true;
        startTick = level.getGameTime();
        markCraftStarted();
        forceMachineChunk(level, pos, true);
        return true;
    }

    private void regulateTemperature() {
        if (forge.getHeatCapability().getHeat() < recipe.getTemperature()) {
            forge.getHeatCapability().setHeat(recipe.getTemperature());
        }
    }

    /** 输入与成品共用槽位，必须比较完整 NBT；相同物品还需要加工证据。 */
    static boolean outputReady(ItemStack visible, ItemStack expected, ItemStack input,
                               int progress, boolean sawProgress, boolean fluidConsumed) {
        return !visible.isEmpty() && visible.getCount() >= expected.getCount()
                && ItemStack.isSameItemSameTags(visible, expected) && progress == 0
                && (!ItemStack.isSameItemSameTags(visible, input) || sawProgress || fluidConsumed);
    }

    @Override protected boolean isMachineCraftFinished(ServerLevel current, BlockEntity be) {
        if (!started || be != machine || !machinePresent()) return false;
        boolean fluidConsumed = machine.getTank().getFluidAmount() < placedFluid.getAmount();
        return outputReady(machine.inventory.getStackInSlot(0), expected, placedItem,
                machine.progress, sawProgress, fluidConsumed);
    }

    @Override protected CraftObservation observeMachineCraft(ServerLevel current, BlockEntity be) {
        if (!structureValid()) {
            failureMessage = Component.translatable("rsi.aetherworks.error.no_attached_forge");
            return failObservation("金属成型台或锻造炉结构已改变");
        }
        if (isMachineCraftFinished(current, be)) return doneObservation();
        sawProgress |= machine.progress > 0;
        ItemStack visible = machine.inventory.getStackInSlot(0);
        if (started && (visible.isEmpty() || !ItemStack.isSameItemSameTags(visible, placedItem))) {
            failureMessage = Component.translatable("rsi.aetherworks.error.metal_former_output_removed");
            return failObservation("成型材料已消失或被替换，未找到预期产物");
        }
        if (!hasPower()) unpoweredTicks++;
        else regulateTemperature();
        if (level.getGameTime() - startTick - unpoweredTicks > Math.max(2400L, recipe.getCraftTime() * 2L)) {
            failureMessage = Component.translatable("rsi.aetherworks.error.metal_former_stalled");
            return failObservation("金属成型加工超时");
        }
        return workingObservation();
    }

    @Override public Component craftFailureMessage(CraftObservation observation) { return failureMessage; }

    @Override public ItemStack collectResult(ServerPlayer player) {
        if (!isMachineCraftFinished(level, machine)) return ItemStack.EMPTY;
        ItemStack result = machine.inventory.extractItem(0, expected.getCount(), false);
        if (!result.isEmpty()) {
            machine.cachedRecipe = null;
            machine.setChanged();
            started = false;
            placedItem = ItemStack.EMPTY;
            placedFluid = FluidStack.EMPTY;
        }
        return result;
    }

    @Override protected void clearMachineState(BlockEntity be, @Nullable ServerPlayer player) {
        List<ItemStack> recovered = new ArrayList<>();
        remaining.stream().filter(stack -> !stack.isEmpty()).forEach(stack -> recovered.add(stack.copy()));
        // 锻造炉断开仍可回收原机器里的剩余材料；已完成的产物不能充当输入退款。
        if (be == machine && machinePresent() && (!placedItem.isEmpty() || !placedFluid.isEmpty())
                && !isMachineCraftFinished(level, be)) {
            ItemStack visible = machine.inventory.getStackInSlot(0);
            if (!placedItem.isEmpty() && ItemStack.isSameItemSameTags(visible, placedItem)) {
                ItemStack removed = machine.inventory.extractItem(0, placedItem.getCount(), false);
                if (!removed.isEmpty()) recovered.add(removed);
            }
            if (!placedFluid.isEmpty()) {
                FluidStack drained = machine.getTank().drain(placedFluid.copy(), FluidAction.EXECUTE);
                if (!drained.isEmpty()) recovered.add(InkFluidSupport.token(drained));
            }
            if (machine.cachedRecipe == recipe) {
                machine.cachedRecipe = null;
                machine.progress = 0;
            }
            machine.setChanged();
        }
        settleRecovery(recovered, player);
    }

    @Override protected void clearMissingMachineState(@Nullable ServerPlayer player) {
        settleRecovery(remaining.stream().filter(stack -> !stack.isEmpty()).map(ItemStack::copy).toList(), player);
    }

    private void settleRecovery(List<ItemStack> recovered, @Nullable ServerPlayer player) {
        recordFailureRecoveredInputs(recovered);
        if (!usingSharedLedger && ledger != null && ledger.isCommitted()) {
            ledger.retainCommittedRefunds(recovered);
            ledger.refundCommittedToEndpoint(storageEndpoint(), player);
        }
        remaining.clear();
        placedItem = ItemStack.EMPTY;
        placedFluid = FluidStack.EMPTY;
        started = false;
        resetState();
    }

    @Override public void onBatchFinished(@Nullable ServerPlayer player) {
        if (!markTerminalCleanup()) return;
        resetState();
    }

    @Override public BlockPos getMachinePos() { return pos; }

    public static List<Component> getPlanWarnings(Recipe<?> recipe) {
        if (!(recipe instanceof IMetalFormerRecipe forming)) return List.of();
        return List.of(Component.translatable("rsi.aetherworks.warn.temp_min", forming.getTemperature()),
                Component.translatable("rsi.aetherworks.info.craft_time", forming.getCraftTime()));
    }
}
