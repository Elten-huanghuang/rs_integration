package com.huanghuang.rsintegration.mods.embers;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.mods.embers.EreAlchemyLock;
import com.huanghuang.rsintegration.mods.embers.KnownCodeSavedData;
import com.huanghuang.rsintegration.network.RSIntegrationNetwork;
import com.huanghuang.rsintegration.reflection.probes.EmbersReflection;
import com.huanghuang.rsintegration.util.ChunkUtils;
import com.huanghuang.rsintegration.util.Reflect;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.storage.tracker.IStorageTracker;
import com.refinedmods.refinedstorage.api.util.Action;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.items.ItemHandlerHelper;
import org.jetbrains.annotations.NotNull;

/** Batch delegate for Embers Alchemy Tablet (deterministic mode). */
public final class EreAlchemyBatchDelegate
extends AbstractBatchDelegate {
    private ServerLevel level;
    private BlockPos machinePos;
    private Object recipe;
    private List<Ingredient> code;
    private EreAlchemyMaterials materials;
    private Object tablet;
    private List<PedestalInfo> pedestals;
    private boolean craftStarted;
    @Nullable
    private EreAlchemyLock.Lease lockLease;
    @Nullable
    private ServerPlayer player;

    public boolean validateAndInit(ServerPlayer player, ResourceLocation recipeId, @Nullable ResourceLocation dim, BlockPos pos) {
        if (!EmbersReflection.isAvailable()) {
            player.sendSystemMessage(Component.translatable("rsi.batch.error.mod_missing", "Embers"));
            return false;
        }
        ServerLevel lvl = CraftPacketUtils.resolveLevel((MinecraftServer)player.server, (ResourceLocation)dim, (ServerPlayer)player);
        if (lvl == null) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.dim_not_found"));
            return false;
        }
        this.level = lvl;
        this.machinePos = pos;
        this.player = player;
        if (!lvl.isLoaded(pos)) {
            lvl.getChunk(pos);
        }
        int minCX = pos.getX() - 3 >> 4;
        int maxCX = pos.getX() + 3 >> 4;
        int minCZ = pos.getZ() - 3 >> 4;
        int maxCZ = pos.getZ() + 3 >> 4;
        for (int cx = minCX; cx <= maxCX; ++cx) {
            for (int cz = minCZ; cz <= maxCZ; ++cz) {
                lvl.getChunk(cx, cz);
            }
        }
        BlockEntity be = lvl.getBlockEntity(pos);
        if (be == null || !EmbersReflection.alchemyTabletBEClass.isInstance(be)) {
            RSIntegrationMod.LOGGER.warn("[RSI-Embers] No AlchemyTabletBlockEntity at {}", (Object)pos);
            player.sendSystemMessage(Component.translatable("rsi.embers.error.not_tablet"));
            return false;
        }
        this.tablet = be;
        Recipe r = lvl.getRecipeManager().byKey(recipeId).orElse(null);
        if (r == null || !EmbersReflection.alchemyRecipeClass.isInstance(r)) {
            RSIntegrationMod.LOGGER.warn("[RSI-Embers] Recipe {} is not an AlchemyRecipe", (Object)recipeId);
            player.sendSystemMessage(Component.translatable("rsi.embers.error.not_alchemy_recipe", recipeId.toString()));
            return false;
        }
        this.recipe = r;
        long seed = lvl.getSeed();
        @SuppressWarnings("unchecked")
        List<Ingredient> codeList = (List<Ingredient>) (Object) Reflect.invoke((Object)this.recipe, "getCode", seed).map(o -> (List<?>)o).orElse(Collections.emptyList());
        this.code = codeList;
        @SuppressWarnings("unchecked")
        List<Ingredient> inputs = (List<Ingredient>) (Object) Reflect.getField((Object)this.recipe, "inputs")
                .map(o -> (List<?>)o).orElse(Collections.emptyList());
        Ingredient tabletIngredient = Reflect.getField((Object)this.recipe, "tablet")
                .filter(Ingredient.class::isInstance).map(Ingredient.class::cast).orElse(null);
        if (inputs.size() != this.code.size()) {
            RSIntegrationMod.LOGGER.warn("[RSI-Embers] Code/input count mismatch: {} code entries, {} inputs",
                    this.code.size(), inputs.size());
            return false;
        }
        this.materials = EreAlchemyMaterials.create(tabletIngredient, this.code, inputs);
        this.pedestals = EreAlchemyBatchDelegate.scanPedestals((Level)lvl, pos);
        if (this.pedestals.size() < this.code.size()) {
            RSIntegrationMod.LOGGER.warn("[RSI-Embers] Pedestal count mismatch: {} available, {} needed", (Object)this.pedestals.size(), (Object)this.code.size());
            player.sendSystemMessage(Component.translatable("rsi.embers.error.pedestals_insufficient", this.code.size(), this.pedestals.size()));
            return false;
        }
        this.network = RSIntegrationNetwork.resolveNetworkFromPlayer((ServerPlayer)player);
        this.lockLease = EreAlchemyLock.tryAcquire(lvl.dimension(), (BlockPos)pos, player.getUUID());
        if (this.lockLease == null) {
            // Preparation is retried by the chain; do not mutate the machine or spam chat here.
            return false;
        }
        EreAlchemyBatchDelegate.recycleBlockingItems(lvl, pos, this.pedestals, player);
        for (PedestalInfo p : this.pedestals) {
            Object bottomInv;
            Object topInv = Reflect.getField((Object)p.be(), "inventory").orElse(null);
            if (topInv != null) {
                ItemStack topStack = readInventoryStack(topInv);
                if (!topStack.isEmpty()) {
                    RSIntegrationMod.LOGGER.warn("[RSI-Embers] Pedestal top at {} still occupied after recycle: {}", (Object)p.pos(), (Object)topStack);
                    player.sendSystemMessage(Component.translatable("rsi.embers.error.pedestal_top_occupied", p.pos().toShortString(), topStack.getHoverName().getString()));
                    return false;
                }
            }
            BlockEntity bottomBE = lvl.getBlockEntity(p.pos().below());
            if (bottomBE == null || !EmbersReflection.alchemyPedestalBEClass.isInstance(bottomBE)) {
                String reason = bottomBE == null ? "\u65e0BE" : "\u7c7b\u578b=" + bottomBE.getClass().getSimpleName();
                RSIntegrationMod.LOGGER.warn("[RSI-Embers] Pedestal bottom at {} missing or invalid: {}", (Object)p.pos().below(), (Object)reason);
                player.sendSystemMessage(Component.translatable("rsi.embers.error.pedestal_bottom_invalid", p.pos().below().toShortString(), reason));
                return false;
            }
            if (bottomBE.isRemoved()) {
                RSIntegrationMod.LOGGER.debug("[RSI-Embers] Pedestal bottom at {} isRemoved=true but BE type valid \u2014 ignoring spurious flag", (Object)p.pos().below());
            }
            bottomInv = Reflect.getField((Object)bottomBE, "inventory").orElse(null);
            if (bottomInv == null) continue;
            ItemStack bottomStack = readInventoryStack(bottomInv);
            if (bottomStack.isEmpty()) continue;
            RSIntegrationMod.LOGGER.warn("[RSI-Embers] Pedestal bottom at {} still occupied after recycle: {}", (Object)p.pos().below(), (Object)bottomStack);
            player.sendSystemMessage(Component.translatable("rsi.embers.error.pedestal_bottom_occupied", p.pos().below().toShortString(), bottomStack.getHoverName().getString()));
            return false;
        }
        @SuppressWarnings("unchecked")
        List<?> aspectsRaw = (List<?>) Reflect.getField((Object)this.recipe, "aspects").orElse(Collections.emptyList());
        int[] codeIndices = new int[this.code.size()];
        for (int i = 0; i < this.code.size(); ++i) {
            Ingredient aspectIng = this.code.get(i);
            int idx = 0;
            for (int j = 0; j < aspectsRaw.size(); ++j) {
                Ingredient recipeAspect;
                Object raw = aspectsRaw.get(j);
                if (!(raw instanceof Ingredient) || !(recipeAspect = (Ingredient)raw).toJson().toString().equals(aspectIng.toJson().toString())) continue;
                idx = j;
                break;
            }
            codeIndices[i] = idx;
        }
        KnownCodeSavedData.get((ServerLevel)this.level).putCode(recipeId.toString(), codeIndices);
        RSIntegrationMod.LOGGER.debug("[RSI-Embers] validateAndInit OK: recipe={} pedestals={}", (Object)recipeId, (Object)this.pedestals.size());
        return true;
    }

    @Nullable
    public List<IngredientSpec> getRequiredMaterials() {
        return this.materials != null ? this.materials.allSpecs() : null;
    }

    @Override
    public List<IngredientSpec> getGraphSpecs() {
        return this.materials != null ? this.materials.graphSpecs() : List.of();
    }

    @Override
    public List<IngredientSpec> getSupplementalSpecs() {
        return this.materials != null ? this.materials.supplementalSpecs() : List.of();
    }

    @Override
    public List<ItemStack> mergeSupplementalMaterials(List<ItemStack> graphMaterials,
                                                       List<ItemStack> supplementalMaterials) {
        if (this.materials == null) return graphMaterials;
        return this.materials.mergeStacks(graphMaterials, supplementalMaterials);
    }

    public boolean tryStartSingleCraft(ServerPlayer player) {
        return false;
    }

    public boolean tryStartWithMaterials(ServerPlayer player, List<ItemStack> materials, ExtractionLedger sharedLedger) {
        ItemStack outputStack;
        BlockEntity current;
        this.sharedLedger = sharedLedger;
        this.usingSharedLedger = true;
        if (this.tablet == null || this.pedestals == null || this.code == null) {
            return false;
        }
        if (this.machinePos != null && this.level != null && this.level.isLoaded(this.machinePos) && ((current = this.level.getBlockEntity(this.machinePos)) == null || current.isRemoved())) {
            player.sendSystemMessage(Component.translatable("rsi.error.machine_missing"));
            if (this.ledger != null && this.ledger.isCommitted()) {
                this.ledger.refundCommitted(this.network, player);
            }
            return false;
        }
        Object outHandler = Reflect.getField((Object)this.tablet, "outputHandler").orElse(null);
        if (outHandler != null && !(outputStack = Reflect.invoke(outHandler, "getStackInSlot", 0).map(o -> (ItemStack)o).orElse(ItemStack.EMPTY)).isEmpty()) {
            RSIntegrationMod.LOGGER.warn("[RSI-Embers] Tablet output slot not empty \u2014 previous craft?");
            player.sendSystemMessage(Component.translatable("rsi.embers.error.tablet_output_occupied"));
            return false;
        }
        int expectedMaterialCount = this.materials != null
                ? this.materials.allSpecs().size() : 1 + 2 * this.code.size();
        if (materials.size() < expectedMaterialCount) {
            RSIntegrationMod.LOGGER.error("[RSI-Embers] Material count mismatch: expected >= {}, got {}", (Object)expectedMaterialCount, (Object)materials.size());
            return false;
        }
        int matIdx = 0;
        try {
            Object tabletInv = Reflect.getField((Object)this.tablet, "inventory").orElse(null);
            if (tabletInv != null) {
                Reflect.invoke(tabletInv, "setStackInSlot", 0, materials.get(matIdx++));
            }
            ((BlockEntity) this.tablet).setChanged();
            for (int i = 0; i < this.code.size(); ++i) {
                PedestalInfo pi = this.pedestals.get(i);
                BlockPos bottomPos = pi.pos().below();
                if (!this.level.isLoaded(bottomPos)) {
                    RSIntegrationMod.LOGGER.warn("[RSI-Embers] Bottom pedestal chunk unloaded at {} \u2014 force-loading", (Object)bottomPos);
                    ChunkUtils.loadChunk((ServerLevel) this.level, bottomPos);
                }
                BlockEntity bottomBE = this.level.getBlockEntity(bottomPos);
                if (bottomBE != null && EmbersReflection.alchemyPedestalBEClass.isInstance(bottomBE)) {
                    Object bottomInv = Reflect.getField((Object)bottomBE, "inventory").orElse(null);
                    if (bottomInv != null) {
                        Reflect.invoke(bottomInv, "setStackInSlot", 0, materials.get(matIdx++));
                    }
                } else {
                    RSIntegrationMod.LOGGER.error("[RSI-Embers] No bottom pedestal at {}", (Object)bottomPos);
                    player.sendSystemMessage(Component.translatable("rsi.embers.error.bottom_pedestal_missing"));
                    this.clearAllSlots();
                    return false;
                }
                bottomBE.setChanged();
                Object topInv = Reflect.getField((Object)pi.be(), "inventory").orElse(null);
                if (topInv != null) {
                    Reflect.invoke(topInv, "setStackInSlot", 0, materials.get(matIdx++));
                }
                ((BlockEntity) pi.be()).setChanged();
            }
            int progressBefore = Reflect.getIntField((Object)this.tablet, "progress").orElse(-999);
            Reflect.invoke((Object)this.tablet, "sparkProgress", this.tablet, 1000.0);
            int progressAfter = Reflect.getIntField((Object)this.tablet, "progress").orElse(-999);
            if (progressAfter == 0) {
                RSIntegrationMod.LOGGER.warn("[RSI-Embers] sparkProgress FAILED: progress stayed 0 — recipe mismatch or items wrong");
                player.sendSystemMessage(Component.translatable("rsi.embers.error.placement_failed"));
                this.clearAllSlots();
                releaseAlchemyLease();
                return false;
            }
            this.craftStarted = true;
            RSIntegrationMod.LOGGER.debug("[RSI-Embers] sparkProgress OK: progress {} -> {}",
                    (Object)progressBefore, (Object)progressAfter);
            return true;
        }
        catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Embers] Material placement failed:", (Throwable)e);
            player.sendSystemMessage(Component.translatable("rsi.embers.error.placement_failed"));
            this.clearAllSlots();
            releaseAlchemyLease();
            return false;
        }
    }

    @Override
    protected boolean isMachineCraftFinished(ServerLevel level, BlockEntity be) {
        if (this.tablet == null) {
            RSIntegrationMod.debug("[RSI-Embers] isCraftComplete: tablet is null");
            return true;
        }
        int progress = Reflect.getIntField(be, "progress").orElse(-1);
        boolean complete = this.craftStarted && progress == 0;
        if (complete) {
            RSIntegrationMod.debug("[RSI-Embers] isCraftComplete=TRUE: craftStarted={} progress={}",
                    (Object)this.craftStarted, (Object)progress);
        }
        return complete;
    }

    @Override
    public void releasePreparationResources() {
        releaseAlchemyLease();
    }

    private void releaseAlchemyLease() {
        if (this.lockLease == null) return;
        EreAlchemyLock.release(this.lockLease);
        this.lockLease = null;
    }

    public ItemStack collectResult(ServerPlayer player) {
        releaseAlchemyLease();
        this.craftStarted = false;
        if (this.tablet == null) {
            RSIntegrationMod.LOGGER.debug("[RSI-Embers] collectResult: tablet is null");
            return ItemStack.EMPTY;
        }

        // serverTick tries IBin below first; if that succeeds, outputMode
        // stays false and outputHandler returns EMPTY.
        // If no IBin, serverTick sets outputMode=true and places the result
        // in the tablet's outputHandler.
        ItemStack result = ItemStack.EMPTY;

        // Primary: check outputHandler (where result goes if no IBin)
        Object outHandler = Reflect.getField((Object)this.tablet, "outputHandler").orElse(null);
        RSIntegrationMod.LOGGER.debug("[RSI-Embers] collectResult: outputHandler found={}", (Object)(outHandler != null));
        if (outHandler != null) {
            result = Reflect.invoke(outHandler, "extractItem", 0, 64, false)
                    .map(o -> (ItemStack)o).orElse(ItemStack.EMPTY);
            RSIntegrationMod.LOGGER.debug("[RSI-Embers] collectResult: outputHandler extractItem isEmpty={}",
                    (Object)result.isEmpty());
            if (!result.isEmpty()) {
                ((BlockEntity) this.tablet).setChanged();
                this.clearAllSlots();
                RSIntegrationMod.LOGGER.debug("[RSI-Embers] Collected result from outputHandler: {} x{}",
                        (Object)result.getHoverName().getString(), (Object)result.getCount());
                return result;
            }
        }

        // Fallback: result may be in the IBin below the tablet
        BlockPos below = this.machinePos.below();
        ChunkUtils.loadChunk((ServerLevel)this.level, (BlockPos)below);
        BlockEntity be = this.level.getBlockEntity(below);
        RSIntegrationMod.LOGGER.debug("[RSI-Embers] collectResult: IBin below check — EmbersReflection.ibinClass={} be={} isInstance={}",
                (Object)(EmbersReflection.ibinClass != null), (Object)(be != null),
                (Object)(EmbersReflection.ibinClass != null && be != null && EmbersReflection.ibinClass.isInstance(be)));
        if (EmbersReflection.ibinClass != null && be != null && EmbersReflection.ibinClass.isInstance(be)) {
            Object binInv = Reflect.invoke((Object)be, "getInventory").orElse(null);
            RSIntegrationMod.LOGGER.debug("[RSI-Embers] collectResult: binInv found={}", (Object)(binInv != null));
            if (binInv != null) {
                result = Reflect.invoke(binInv, "extractItem", 0, 64, false)
                        .map(o -> (ItemStack)o).orElse(ItemStack.EMPTY);
                RSIntegrationMod.LOGGER.debug("[RSI-Embers] collectResult: IBin extractItem isEmpty={}",
                        (Object)result.isEmpty());
                if (!result.isEmpty()) {
                    be.setChanged();
                    this.clearAllSlots();
                    RSIntegrationMod.LOGGER.debug("[RSI-Embers] Collected result from IBin: {} x{}",
                            (Object)result.getHoverName().getString(), (Object)result.getCount());
                    return result;
                }
            }
        }

        RSIntegrationMod.LOGGER.warn("[RSI-Embers] collectResult: NO RESULT FOUND in outputHandler or IBin");
        return ItemStack.EMPTY;
    }

    @Override
    protected void clearMachineState(BlockEntity be, ServerPlayer player) {
        releaseAlchemyLease();
        this.craftStarted = false;
        this.clearAllSlots();
    }

    public void onBatchFinished(@NotNull ServerPlayer player) {
    }

    public BlockPos getMachinePos() {
        return this.machinePos != null ? this.machinePos : BlockPos.ZERO;
    }

    public static List<String> getPlanWarnings(ServerPlayer player, Recipe<?> recipe, @Nullable ResourceLocation dim, @Nullable BlockPos pos) {
        ArrayList<String> warnings = new ArrayList<String>();
        if (dim == null || pos == null) {
            warnings.add(Component.translatable("rsi.embers.warn.no_tablet_bound").getString());
            return warnings;
        }
        ServerLevel lvl = CraftPacketUtils.resolveLevel((MinecraftServer)player.server, (ResourceLocation)dim, (ServerPlayer)player);
        if (lvl != null) {
            if (!lvl.isLoaded(pos)) {
                lvl.getChunk(pos);
            }
            int minCX = pos.getX() - 3 >> 4;
            int maxCX = pos.getX() + 3 >> 4;
            int minCZ = pos.getZ() - 3 >> 4;
            int maxCZ = pos.getZ() + 3 >> 4;
            for (int cx = minCX; cx <= maxCX; ++cx) {
                for (int cz = minCZ; cz <= maxCZ; ++cz) {
                    lvl.getChunk(cx, cz);
                }
            }
            BlockEntity be = lvl.getBlockEntity(pos);
            if (EmbersReflection.alchemyTabletBEClass == null || be == null || !EmbersReflection.alchemyTabletBEClass.isInstance(be)) {
                warnings.add(Component.translatable("rsi.embers.warn.tablet_missing").getString());
            } else if (EmbersReflection.alchemyRecipeClass != null && EmbersReflection.alchemyRecipeClass.isInstance(recipe)) {
                long seed = lvl.getSeed();
                @SuppressWarnings("unchecked")
                List<?> code = Reflect.invoke(recipe, "getCode", seed).map(o -> (List<?>)o).orElse(Collections.emptyList());
                int needed = code.size();
                List<PedestalInfo> nearby = EreAlchemyBatchDelegate.scanPedestals((Level)lvl, pos);
                if (nearby.size() < needed) {
                    warnings.add(Component.translatable("rsi.embers.warn.pedestals_insufficient", needed, nearby.size()).getString());
                }
                for (PedestalInfo p : nearby) {
                    BlockEntity bottomBE = lvl.getBlockEntity(p.pos().below());
                    if (EmbersReflection.alchemyPedestalBEClass != null && bottomBE != null && EmbersReflection.alchemyPedestalBEClass.isInstance(bottomBE)) continue;
                    warnings.add(Component.translatable("rsi.embers.warn.pedestal_invalid").getString());
                    break;
                }
            }
        }
        if (!((Boolean)RSIntegrationConfig.ENABLE_EMBERS_ALCHEMY_CALC.get()).booleanValue()) {
            KnownCodeSavedData savedData = KnownCodeSavedData.get((ServerLevel)player.serverLevel());
            if (savedData.getCode(recipe.getId().toString()) != null) {
                warnings.add(Component.translatable("rsi.embers.info.code_cached").getString());
            } else {
                warnings.add(Component.translatable("rsi.embers.warn.infer_mode_only").getString());
            }
        }
        return warnings;
    }

    static List<PedestalInfo> scanPedestals(Level level, BlockPos tabletPos) {
        ArrayList<PedestalInfo> result = new ArrayList<PedestalInfo>();
        BlockPos.MutableBlockPos mpos = tabletPos.mutable();
        for (int dx = -3; dx <= 3; ++dx) {
            for (int dz = -3; dz <= 3; ++dz) {
                mpos.set(tabletPos.getX() + dx, tabletPos.getY() + 1, tabletPos.getZ() + dz);
                BlockEntity be = level.getBlockEntity((BlockPos)mpos);
                if (be == null || EmbersReflection.alchemyPedestalTopBEClass == null || !EmbersReflection.alchemyPedestalTopBEClass.isInstance(be)) continue;
                result.add(new PedestalInfo(mpos.immutable(), be));
            }
        }
        return result;
    }

    static void recycleBlockingItems(ServerLevel level, BlockPos tabletPos, List<PedestalInfo> pedestals, ServerPlayer player) {
        INetwork network = RSIntegrationNetwork.resolveNetworkFromPlayer((ServerPlayer)player);
        if (network == null) {
            RSIntegrationMod.LOGGER.warn("[RSI-Embers] No RS network for recycling, clearing pedestals anyway");
        }
        ArrayList<ItemStack> recycled = new ArrayList<ItemStack>();
        BlockEntity tabletBE = level.getBlockEntity(tabletPos);
        if (tabletBE != null && EmbersReflection.alchemyTabletBEClass != null && EmbersReflection.alchemyTabletBEClass.isInstance(tabletBE)) {
            ItemStack itemStack;
            Object tabletInv = Reflect.getField((Object)tabletBE, "inventory").orElse(null);
            if (tabletInv != null && !(itemStack = Reflect.invokeInt(tabletInv, "getStackInSlot", 0).map(o -> (ItemStack)o).orElse(ItemStack.EMPTY)).isEmpty()) {
                recycled.add(itemStack.copy());
                Reflect.invoke(tabletInv, "setStackInSlot", 0, ItemStack.EMPTY);
                tabletBE.setChanged();
            }
            Object outH = Reflect.getField((Object)tabletBE, "outputHandler").orElse(null);
            ItemStack os;
            if (outH != null && !(os = Reflect.invoke(outH, "getStackInSlot", 0).map(o -> (ItemStack)o).orElse(ItemStack.EMPTY)).isEmpty()) {
                recycled.add(os.copy());
                Reflect.invoke(outH, "setStackInSlot", 0, ItemStack.EMPTY);
                tabletBE.setChanged();
            }
        }
        if (pedestals != null) {
            for (PedestalInfo pedestalInfo : pedestals) {
                Object bottomInv;
                BlockEntity bottomBE;
                BlockPos bottomPos;
                Object topInv = Reflect.getField((Object)pedestalInfo.be(), "inventory").orElse(null);
                if (topInv != null) {
                    ItemStack s2 = readInventoryStack(topInv);
                    if (!s2.isEmpty()) {
                        recycled.add(s2.copy());
                        writeInventoryStack(topInv, ItemStack.EMPTY);
                        ((BlockEntity) pedestalInfo.be()).setChanged();
                    }
                }
                if (!level.isLoaded(bottomPos = pedestalInfo.pos().below()) || (bottomBE = level.getBlockEntity(bottomPos)) == null || EmbersReflection.alchemyPedestalBEClass == null || !EmbersReflection.alchemyPedestalBEClass.isInstance(bottomBE) || (bottomInv = Reflect.getField((Object)bottomBE, "inventory").orElse(null)) == null) continue;
                ItemStack s = readInventoryStack(bottomInv);
                if (s.isEmpty()) continue;
                recycled.add(s.copy());
                writeInventoryStack(bottomInv, ItemStack.EMPTY);
                bottomBE.setChanged();
            }
        }
        if (!recycled.isEmpty()) {
            if (network != null) {
                int totalRecycled = 0;
                for (ItemStack s : recycled) {
                    ItemStack leftover;
                    if (s.isEmpty()) continue;
                    IStorageTracker tracker = network.getItemStorageTracker();
                    if (tracker != null) {
                        tracker.changed(player, s.copy());
                    }
                    if (!(leftover = network.insertItem(s, s.getCount(), Action.PERFORM)).isEmpty()) {
                        RSIntegrationMod.LOGGER.warn("[RSI-Embers] Recycle partial: {} x{} \u2192 leftover {}", (Object)s.getHoverName().getString(), (Object)s.getCount(), (Object)leftover.getCount());
                        ItemHandlerHelper.giveItemToPlayer(player, (ItemStack)leftover);
                    }
                    ++totalRecycled;
                }
                if (totalRecycled > 0 && player != null) {
                    player.displayClientMessage(Component.translatable("rsi.embers.info.recycled", totalRecycled), true);
                }
            } else {
                for (ItemStack itemStack : recycled) {
                    if (itemStack.isEmpty()) continue;
                    ItemHandlerHelper.giveItemToPlayer(player, (ItemStack)itemStack);
                }
            }
            RSIntegrationMod.LOGGER.debug("[RSI-Embers] Recycled {} blocking items from previous craft", (Object)recycled.size());
        }
    }

    private static ItemStack readInventoryStack(Object inventory) {
        if (inventory instanceof net.minecraftforge.items.IItemHandler handler) {
            return handler.getStackInSlot(0);
        }
        return Reflect.invoke(inventory, "getStackInSlot", 0)
                .filter(ItemStack.class::isInstance)
                .map(ItemStack.class::cast)
                .orElse(ItemStack.EMPTY);
    }

    private static void writeInventoryStack(Object inventory, ItemStack stack) {
        if (inventory instanceof net.minecraftforge.items.IItemHandlerModifiable handler) {
            handler.setStackInSlot(0, stack);
            return;
        }
        Reflect.invoke(inventory, "setStackInSlot", 0, stack);
    }

    private void clearAllSlots() {
        ItemStack s2;
        Object tabletInv;
        ArrayList<ItemStack> toRefund = new ArrayList<ItemStack>();
        if (this.tablet != null && (tabletInv = Reflect.getField((Object)this.tablet, "inventory").orElse(null)) != null && !(s2 = Reflect.invokeInt(tabletInv, "getStackInSlot", 0).map(o -> (ItemStack)o).orElse(ItemStack.EMPTY)).isEmpty()) {
            toRefund.add(s2.copy());
            Reflect.invoke(tabletInv, "setStackInSlot", 0, ItemStack.EMPTY);
            ((BlockEntity) this.tablet).setChanged();
        }
        if (this.pedestals != null && this.level != null) {
            for (PedestalInfo top : this.pedestals) {
                Object bottomInv;
                BlockEntity bottomBE;
                BlockPos bottomPos;
                Object topInv = Reflect.getField((Object)top.be(), "inventory").orElse(null);
                if (topInv != null) {
                    ItemStack as = readInventoryStack(topInv);
                    if (!as.isEmpty()) {
                        toRefund.add(as.copy());
                        writeInventoryStack(topInv, ItemStack.EMPTY);
                        ((BlockEntity) top.be()).setChanged();
                    }
                }
                if (!this.level.isLoaded(bottomPos = top.pos().below()) || (bottomBE = this.level.getBlockEntity(bottomPos)) == null || EmbersReflection.alchemyPedestalBEClass == null || !EmbersReflection.alchemyPedestalBEClass.isInstance(bottomBE) || (bottomInv = Reflect.getField((Object)bottomBE, "inventory").orElse(null)) == null) continue;
                ItemStack is = readInventoryStack(bottomInv);
                if (is.isEmpty()) continue;
                toRefund.add(is.copy());
                writeInventoryStack(bottomInv, ItemStack.EMPTY);
                bottomBE.setChanged();
            }
        }
        if (this.network != null && !toRefund.isEmpty()) {
            for (ItemStack stack : toRefund) {
                ItemStack leftover;
                if (stack.isEmpty()) continue;
                IStorageTracker tracker = this.network.getItemStorageTracker();
                if (tracker != null && this.player != null) {
                    tracker.changed(this.player, (Object)stack.copy());
                }
                if ((leftover = this.network.insertItem(stack, stack.getCount(), Action.PERFORM)).isEmpty()) continue;
                RSIntegrationMod.LOGGER.warn("[RSI-Embers] Refund partial: {} x{}", (Object)leftover.getHoverName().getString(), (Object)leftover.getCount());
                if (this.player == null) continue;
                ItemHandlerHelper.giveItemToPlayer(this.player, (ItemStack)leftover);
            }
        }
    }

    record PedestalInfo(BlockPos pos, Object be) {
    }
}
