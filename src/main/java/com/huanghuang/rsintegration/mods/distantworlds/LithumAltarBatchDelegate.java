package com.huanghuang.rsintegration.mods.distantworlds;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.reflection.probes.DistantWorldsReflection;
import com.huanghuang.rsintegration.util.ChunkUtils;
import com.huanghuang.rsintegration.util.Reflect;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** First-stage Firon Lithum Altar executor. */
public final class LithumAltarBatchDelegate extends AbstractBatchDelegate {
    private ServerPlayer player;
    private ServerLevel level;
    private ResourceKey<Level> dimension;
    private BlockPos pos;
    private LithumAltarRecipeDefinition definition;
    private final List<OwnedSlot> ownedSlots = new ArrayList<>();
    private boolean started;
    private ItemStack staff = ItemStack.EMPTY;
    private int staffInventorySlot = -1;
    private boolean staffFromOffhand;
    private boolean staffFromNetwork;
    private ItemStack networkStaff = ItemStack.EMPTY;
    private ItemStack pendingResult = ItemStack.EMPTY;
    private final LithumAltarFuelHelper fuelHelper = new LithumAltarFuelHelper();

    @Override
    public boolean validateAndInit(ServerPlayer player, ResourceLocation recipeId,
                                   @Nullable ResourceLocation dim, BlockPos pos) {
        this.player = player;
        this.pos = pos;
        this.started = false;
        this.pendingResult = ItemStack.EMPTY;
        this.ownedSlots.clear();
        this.definition = LithumAltarRecipeResolver.resolve(recipeId);
        if (!LithumAltarRecipeResolver.isComplete(definition)) return false;
        this.definition = definition;
        this.level = CraftPacketUtils.resolveLevel(player.server, dim, player);
        if (level == null || !level.isLoaded(pos)) {
            if (level == null) return false;
            ChunkUtils.loadChunk(level, pos);
        }
        this.machineDim = level.dimension().location();
        this.dimension = level.dimension();
        BlockState state = level.getBlockState(pos);
        BlockEntity be = level.getBlockEntity(pos);
        if (DistantWorldsReflection.lithumCoreBlockClass == null
                || !DistantWorldsReflection.lithumCoreBlockClass.isInstance(state.getBlock())
                || DistantWorldsReflection.lithumCoreBEClass == null
                || be == null || !DistantWorldsReflection.lithumCoreBEClass.isInstance(be)) return false;
        if (!be.getPersistentData().getString("CurrentRecipe").isEmpty()) return false;
        if (!isStructureValid() || !hasAllPedestalsEmpty()) return false;
        return hasResearch(player);
    }

    @Override
    public List<IngredientSpec> getRequiredMaterials() {
        return definition == null ? null : definition.allMaterials();
    }

    @Override
    public boolean tryStartSingleCraft(ServerPlayer player) {
        this.player = player;
        this.network = CraftPacketUtils.resolveNetworkForCraft(player, dimension, pos);
        if (!acquireStaff(player)) return false;
        if (RSIntegrationConfig.ALLOW_DISTANT_WORLDS_FUEL_AUTOMATION.get()
                && !fuelHelper.findAndLock(level, pos)) {
            releaseNetworkStaff();
            return false;
        }
        this.ledger = new ExtractionLedger();
        List<ItemStack> materials = new ArrayList<>();
        for (IngredientSpec spec : definition.allMaterials()) {
            ItemStack stack = CraftPacketUtils.ensureMaterialAvailable(player, dimension, pos,
                    spec.ingredient(), spec.count(), ledger);
            if (stack.isEmpty()) {
                releaseNetworkStaff();
                return false;
            }
            materials.add(stack);
        }
        if (!ledger.commit(network, player)) {
            releaseNetworkStaff();
            return false;
        }
        if (!placeMaterials(materials)) {
            clearPlacedMaterials();
            ledger.refundCommitted(network, player);
            releaseNetworkStaff();
            return false;
        }
        if (!invokeNativeStart()) {
            clearPlacedMaterials();
            ledger.refundCommitted(network, player);
            releaseNetworkStaff();
            return false;
        }
        return started;
    }

    @Override
    public boolean tryStartWithMaterials(ServerPlayer player, List<ItemStack> materials,
                                         ExtractionLedger sharedLedger) {
        this.player = player;
        useSharedLedger(sharedLedger);
        this.network = CraftPacketUtils.resolveNetworkForCraft(player, dimension, pos);
        if (!acquireStaff(player)) return false;
        if (RSIntegrationConfig.ALLOW_DISTANT_WORLDS_FUEL_AUTOMATION.get()
                && !fuelHelper.findAndLock(level, pos)) {
            releaseNetworkStaff();
            return false;
        }
        if (!placeMaterials(materials) || !invokeNativeStart()) {
            clearPlacedMaterials();
            releaseNetworkStaff();
            return false;
        }
        return true;
    }

    private boolean placeMaterials(List<ItemStack> materials) {
        if (materials.isEmpty()) return false;
        BlockEntity core = level.getBlockEntity(pos);
        if (core == null) return false;
        ItemStack coreStack = materials.get(0).copyWithCount(1);
        if (!getSlot(core, 0).isEmpty() || !setSlot(core, 0, coreStack)) return false;
        ownedSlots.add(new OwnedSlot(pos, coreStack.copy()));
        int materialIndex = 1;
        for (BlockPos pedestalPos : pedestalPositions()) {
            if (materialIndex >= materials.size()) break;
            BlockEntity pedestal = level.getBlockEntity(pedestalPos);
            if (pedestal == null || DistantWorldsReflection.lithumPedestalBEClass == null
                    || !DistantWorldsReflection.lithumPedestalBEClass.isInstance(pedestal)) continue;
            if (!getSlot(pedestal, 0).isEmpty()) continue;
            ItemStack placed = materials.get(materialIndex).copyWithCount(1);
            if (setSlot(pedestal, 0, placed)) {
                ownedSlots.add(new OwnedSlot(pedestalPos, placed.copy()));
                materialIndex++;
            }
        }
        return materialIndex == materials.size();
    }

    private boolean invokeNativeStart() {
        if (DistantWorldsReflection.recipePickerProcedureClass == null || staff.isEmpty()) return false;
        try (DistantWorldsResearchBypass.Grant grant = researchGrant()) {
            if (!grant.available()) return false;
            Method execute = DistantWorldsReflection.recipePickerProcedureClass.getMethod(
                    "execute", net.minecraft.world.level.LevelAccessor.class, double.class, double.class,
                    double.class, net.minecraft.world.entity.Entity.class);
            execute.invoke(null, level, (double) pos.getX(), (double) pos.getY(),
                    (double) pos.getZ(), player);
            BlockEntity core = level.getBlockEntity(pos);
            String current = core == null ? "" : core.getPersistentData().getString("CurrentRecipe");
            started = definition.currentRecipe().equals(current);
            if (started) damageStaff();
            phase = started ? CraftPhase.WORKING : CraftPhase.FAILED;
            return started;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean acquireStaff(ServerPlayer player) {
        if (hasStaff(player)) return true;
        if (network == null) return false;
        for (var entry : network.getItemStorageCache().getList().getStacks()) {
            ItemStack candidate = entry.getStack();
            if (!isStaff(candidate)) continue;
            ItemStack extracted = com.huanghuang.rsintegration.network.RSIntegrationNetwork
                    .extractExactFromNetwork(network, candidate, 1, player);
            if (extracted.getCount() != 1) {
                if (!extracted.isEmpty()) deliver(extracted);
                return false;
            }
            staff = extracted.copy();
            networkStaff = extracted.copy();
            staffFromNetwork = true;
            return true;
        }
        return false;
    }

    private boolean hasStaff(ServerPlayer player) {
        staffFromNetwork = false;
        networkStaff = ItemStack.EMPTY;
        staffInventorySlot = -1;
        staffFromOffhand = false;
        for (int i = 0; i < player.getInventory().items.size(); i++) {
            ItemStack stack = player.getInventory().items.get(i);
            if (isStaff(stack)) {
                staff = stack.copyWithCount(1);
                staffInventorySlot = i;
                return true;
            }
        }
        ItemStack offhand = player.getOffhandItem();
        if (isStaff(offhand)) {
            staff = offhand.copyWithCount(1);
            staffFromOffhand = true;
            return true;
        }
        return false;
    }

    private void damageStaff() {
        if (staffFromNetwork) {
            networkStaff.hurtAndBreak(1, player, ignored -> {});
            staff = networkStaff.isEmpty() ? ItemStack.EMPTY : networkStaff.copy();
            releaseNetworkStaff();
            return;
        }
        ItemStack source;
        if (staffFromOffhand) {
            source = player.getOffhandItem();
        } else if (staffInventorySlot >= 0 && staffInventorySlot < player.getInventory().items.size()) {
            source = player.getInventory().items.get(staffInventorySlot);
        } else {
            return;
        }
        if (!isStaff(source)) return;
        source.hurtAndBreak(1, player, ignored -> {});
        staff = source.isEmpty() ? ItemStack.EMPTY : source.copyWithCount(1);
        player.getInventory().setChanged();
    }

    private void releaseNetworkStaff() {
        if (!staffFromNetwork || networkStaff.isEmpty()) return;
        ItemStack remainder = network == null ? networkStaff.copy()
                : network.insertItem(networkStaff, networkStaff.getCount(),
                com.refinedmods.refinedstorage.api.util.Action.PERFORM);
        if (!remainder.isEmpty()) deliver(remainder);
        networkStaff = ItemStack.EMPTY;
        staffFromNetwork = false;
    }

    private void deliver(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        if (player != null) {
            ItemHandlerHelper.giveItemToPlayer(player, stack);
        } else if (level != null && pos != null) {
            net.minecraft.world.Containers.dropItemStack(level, pos.getX() + 0.5,
                    pos.getY() + 1, pos.getZ() + 0.5, stack);
        }
    }

    private DistantWorldsResearchBypass.Grant researchGrant() {
        if (hasAdvancement(player)) return DistantWorldsResearchBypass.noChange();
        if (!RSIntegrationConfig.ALLOW_DISTANT_WORLDS_RESEARCH_BYPASS.get()) {
            return DistantWorldsResearchBypass.unavailable();
        }
        return DistantWorldsResearchBypass.temporarilyGrant(player);
    }

    private boolean hasResearch(ServerPlayer player) {
        return RSIntegrationConfig.ALLOW_DISTANT_WORLDS_RESEARCH_BYPASS.get() || hasAdvancement(player);
    }

    private boolean hasAdvancement(ServerPlayer player) {
        var manager = player.server.getAdvancements();
        var advancement = manager.getAdvancement(ResourceLocation.fromNamespaceAndPath(
                "distant_worlds", "incandescent_forever"));
        return advancement != null && player.getAdvancements().getOrStartProgress(advancement).isDone();
    }

    private boolean isStructureValid() {
        if (DistantWorldsReflection.structureIntegrityProcedureClass == null) return false;
        try {
            Method execute = DistantWorldsReflection.structureIntegrityProcedureClass.getMethod(
                    "execute", net.minecraft.world.level.LevelAccessor.class,
                    double.class, double.class, double.class);
            Object result = execute.invoke(null, level, (double) pos.getX(), (double) pos.getY(), (double) pos.getZ());
            return "Successful".equals(result);
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    private boolean hasAllPedestalsEmpty() {
        int found = 0;
        for (BlockPos pedestalPos : pedestalPositions()) {
            BlockEntity pedestal = level.getBlockEntity(pedestalPos);
            if (pedestal == null || DistantWorldsReflection.lithumPedestalBEClass == null
                    || !DistantWorldsReflection.lithumPedestalBEClass.isInstance(pedestal)
                    || !getSlot(pedestal, 0).isEmpty()) return false;
            found++;
        }
        return found == 8;
    }

    private boolean isStaff(ItemStack stack) {
        return !stack.isEmpty() && "distant_worlds:dalite_staff".equals(
                net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem()).toString());
    }

    private List<BlockPos> pedestalPositions() {
        return LithumAltarStructureHelper.pedestalPositions(pos);
    }

    private static ItemStack getSlot(BlockEntity be, int slot) {
        return LithumAltarStructureHelper.getSlot(be, slot);
    }

    private static boolean setSlot(BlockEntity be, int slot, ItemStack stack) {
        return LithumAltarStructureHelper.setSlot(be, slot, stack);
    }

    private void clearPlacedMaterials() {
        if (level == null) return;
        for (OwnedSlot owned : ownedSlots) {
            BlockEntity be = level.getBlockEntity(owned.pos());
            if (be == null) continue;
            ItemStack current = getSlot(be, 0);
            if (ItemStack.isSameItemSameTags(current, owned.stack())
                    && current.getCount() == owned.stack().getCount()) {
                setSlot(be, 0, ItemStack.EMPTY);
            }
        }
        ownedSlots.clear();
    }

    @Override
    protected CraftObservation observeMachineCraft(ServerLevel level, BlockEntity be) {
        String current = be.getPersistentData().getString("CurrentRecipe");
        if (!started) return new CraftObservation(CraftPhase.WAITING_FOR_START);
        if (!current.isEmpty()) {
            if (!definition.currentRecipe().equals(current)) {
                return failObservation("Lithum Altar recipe ownership changed");
            }
            if (RSIntegrationConfig.ALLOW_DISTANT_WORLDS_FUEL_AUTOMATION.get()) {
                LithumAltarStateReader.Snapshot snapshot = LithumAltarStateReader.read(level, pos);
                if (snapshot != null && snapshot.maxEnergy() > 0
                        && snapshot.currentEnergy() < snapshot.maxEnergy()
                        && !fuelHelper.ensureFuel(level, network)) {
                    warnOnce("fuel", "[RSI-DW] No Lithum Furnace fuel available for altar at {}", pos);
                }
            }
            phase = CraftPhase.WORKING;
            return new CraftObservation(phase);
        }
        ItemStack output = getSlot(be, 0);
        if (IBatchDelegate.matchesProducedItem(output, definition.output())) {
            pendingResult = output.copy();
            setSlot(be, 0, ItemStack.EMPTY);
            phase = CraftPhase.DONE;
            return new CraftObservation(phase);
        }
        return failObservation("Lithum Altar cleared CurrentRecipe without the expected output");
    }

    @Override
    public ItemStack getExpectedOutput() {
        return definition == null ? null : definition.output().copy();
    }

    @Override
    protected boolean isMachineCraftFinished(ServerLevel level, BlockEntity be) {
        if (!started) return false;
        if (IBatchDelegate.matchesProducedItem(pendingResult, definition.output())) return true;
        String current = be.getPersistentData().getString("CurrentRecipe");
        return current.isEmpty() && IBatchDelegate.matchesProducedItem(getSlot(be, 0), definition.output());
    }

    @Override
    public ItemStack collectResult(ServerPlayer player) {
        if (IBatchDelegate.matchesProducedItem(pendingResult, definition.output())) {
            ItemStack result = pendingResult.copy();
            pendingResult = ItemStack.EMPTY;
            return result;
        }
        BlockEntity be = level == null ? null : level.getBlockEntity(pos);
        if (be == null) return ItemStack.EMPTY;
        ItemStack result = getSlot(be, 0).copy();
        if (!IBatchDelegate.matchesProducedItem(result, definition.output())) return ItemStack.EMPTY;
        setSlot(be, 0, ItemStack.EMPTY);
        return result;
    }

    @Override
    protected void clearMachineState(BlockEntity be, ServerPlayer player) {
        clearPlacedMaterials();
        fuelHelper.refundUnused(level, network, player);
        releaseNetworkStaff();
    }

    @Override
    public void onBatchFinished(ServerPlayer player) {
        fuelHelper.refundUnused(level, network, player);
        releaseNetworkStaff();
        pendingResult = ItemStack.EMPTY;
        ownedSlots.clear();
        resetState();
    }
    @Override public BlockPos getMachinePos() { return pos; }
    @Override public boolean supportsConcurrentNodeExecution() { return false; }

    private record OwnedSlot(BlockPos pos, ItemStack stack) {}
}
