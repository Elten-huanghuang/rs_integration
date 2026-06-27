package com.huanghuang.rsintegration.mods.embers;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.network.RSIntegration;
import com.rekindled.embers.blockentity.AlchemyPedestalBlockEntity;
import com.rekindled.embers.blockentity.AlchemyPedestalTopBlockEntity;
import com.rekindled.embers.blockentity.AlchemyTabletBlockEntity;
import com.rekindled.embers.recipe.AlchemyRecipe;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.util.Action;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.entity.BlockEntity;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Real block-interaction delegate for Embers Alchemy.
 *
 * <p>Places aspect + input items on real alchemy pedestals, places the
 * tablet item in the alchemy tablet, then sparks the tablet to start the
 * vanilla alchemy animation and processing. The tablet's own
 * {@code serverTick} handles progress, recipe matching, and output
 * production — this delegate only orchestrates item placement and result
 * collection.
 */
public final class EreAlchemyBatchDelegate implements IBatchDelegate {

    private ServerLevel level;
    private BlockPos machinePos;
    private AlchemyRecipe recipe;
    private List<Ingredient> code;
    private AlchemyTabletBlockEntity tablet;
    private List<AlchemyPedestalTopBlockEntity> pedestals;
    private boolean craftStarted;
    @Nullable private INetwork network;

    // ── IBatchDelegate impl ───────────────────────────────────────

    @Override
    public boolean validateAndInit(ServerPlayer player, ResourceLocation recipeId,
                                   @Nullable ResourceLocation dim, BlockPos pos) {
        ServerLevel lvl = CraftPacketUtils.resolveLevel(player.server, dim, player);
        if (lvl == null) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.dim_not_found"));
            return false;
        }
        this.level = lvl;
        this.machinePos = pos;

        if (!lvl.isLoaded(pos)) lvl.getChunk(pos);
        // scanPedestals scans a 7×7 area (pos.x±3, pos.z±3) at y+1.
        // Force-load all chunks in that area — otherwise getBlockEntity()
        // returns null for any position in an unloaded chunk, even though
        // the pedestal blocks physically exist.
        int minCX = (pos.getX() - 3) >> 4;
        int maxCX = (pos.getX() + 3) >> 4;
        int minCZ = (pos.getZ() - 3) >> 4;
        int maxCZ = (pos.getZ() + 3) >> 4;
        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                lvl.getChunk(cx, cz);
            }
        }
        BlockEntity be = lvl.getBlockEntity(pos);
        if (!(be instanceof AlchemyTabletBlockEntity tbe)) {
            RSIntegrationMod.LOGGER.warn("[RSI-Embers] No AlchemyTabletBlockEntity at {}", pos);
            player.sendSystemMessage(Component.translatable("rsi.embers.error.not_tablet"));
            return false;
        }
        this.tablet = tbe;

        Recipe<?> r = lvl.getRecipeManager().byKey(recipeId).orElse(null);
        if (!(r instanceof AlchemyRecipe ar)) {
            RSIntegrationMod.LOGGER.warn("[RSI-Embers] Recipe {} is not an AlchemyRecipe", recipeId);
            player.sendSystemMessage(Component.translatable("rsi.embers.error.not_alchemy_recipe", recipeId.toString()));
            return false;
        }
        this.recipe = ar;

        long seed = lvl.getSeed();
        this.code = recipe.getCode(seed);

        // Don't use AlchemyTabletBlockEntity.getNearbyPedestals() — it calls
        // AlchemyPedestalTopBlockEntity.isValid() which requires items in BOTH
        // top and bottom slots. Empty pedestals (normal before craft) return 0.
        this.pedestals = scanPedestals(lvl, pos);
        if (pedestals.size() < code.size()) {
            RSIntegrationMod.LOGGER.warn("[RSI-Embers] Pedestal count mismatch: {} available, {} needed",
                    pedestals.size(), code.size());
            player.sendSystemMessage(Component.translatable("rsi.embers.error.pedestals_insufficient",
                    code.size(), pedestals.size()));
            return false;
        }

        // Pedestals must be empty (not in use by another craft)
        for (AlchemyPedestalTopBlockEntity p : pedestals) {
            if (!p.inventory.getStackInSlot(0).isEmpty()) {
                RSIntegrationMod.LOGGER.warn("[RSI-Embers] Pedestal top at {} is occupied", p.getBlockPos());
                player.sendSystemMessage(Component.translatable("rsi.embers.error.pedestal_invalid"));
                return false;
            }
            BlockEntity bottomBE = lvl.getBlockEntity(p.getBlockPos().below());
            if (!(bottomBE instanceof AlchemyPedestalBlockEntity bottom) || bottom.isRemoved()
                    || !bottom.inventory.getStackInSlot(0).isEmpty()) {
                RSIntegrationMod.LOGGER.warn("[RSI-Embers] Pedestal bottom at {} missing or occupied", p.getBlockPos().below());
                player.sendSystemMessage(Component.translatable("rsi.embers.error.pedestal_invalid"));
                return false;
            }
        }

        // Lock LAST, after all validation, to prevent permanent lock leaks
        // when any earlier check returns false.
        if (!EreAlchemyLock.tryLock(lvl.dimension(), pos, player.getUUID())) {
            player.sendSystemMessage(Component.translatable("rsi.embers.error.tablet_locked"));
            return false;
        }

        this.network = RSIntegration.resolveNetworkFromPlayer(player);

        int[] codeIndices = new int[code.size()];
        for (int i = 0; i < code.size(); i++) {
            Ingredient aspectIng = code.get(i);
            int idx = 0;
            for (int j = 0; j < recipe.aspects.size(); j++) {
                if (recipe.aspects.get(j).toJson().toString().equals(aspectIng.toJson().toString())) {
                    idx = j;
                    break;
                }
            }
            codeIndices[i] = idx;
        }
        KnownCodeSavedData.get(level).putCode(recipeId.toString(), codeIndices);
        RSIntegrationMod.LOGGER.debug("[RSI-Embers] validateAndInit OK: recipe={} pedestals={}",
                recipeId, pedestals.size());
        return true;
    }

    @Override
    @Nullable
    public List<IngredientSpec> getRequiredMaterials() {
        if (recipe == null || code == null) return null;
        List<IngredientSpec> specs = new ArrayList<>();
        specs.add(new IngredientSpec(recipe.tablet, 1));
        for (int i = 0; i < code.size(); i++) {
            specs.add(new IngredientSpec(code.get(i), 1));
            specs.add(new IngredientSpec(recipe.inputs.get(i), 1));
        }
        return specs;
    }

    @Override
    public boolean tryStartSingleCraft(ServerPlayer player) {
        return false; // Embers always uses chain path (getRequiredMaterials + tryStartWithMaterials)
    }

    @Override
    public boolean tryStartWithMaterials(ServerPlayer player, List<ItemStack> materials,
                                         ExtractionLedger sharedLedger) {
        if (tablet == null || pedestals == null || code == null) return false;
        if (!tablet.outputHandler.getStackInSlot(0).isEmpty()) {
            RSIntegrationMod.LOGGER.warn("[RSI-Embers] Tablet output slot not empty — previous craft?");
            player.sendSystemMessage(Component.translatable("rsi.embers.error.tablet_output_occupied"));
            return false;
        }

        int matIdx = 0;
        try {
            tablet.inventory.setStackInSlot(0, materials.get(matIdx++));
            tablet.setChanged();

            for (int i = 0; i < code.size(); i++) {
                AlchemyPedestalTopBlockEntity top = pedestals.get(i);

                // Place aspect (code) item on BOTTOM pedestal, input item on TOP.
                // Embers' PedestalContents(bottomStack, topStack) treats bottom as the
                // aspect "symbol" and top as the material ingredient.
                BlockPos bottomPos = top.getBlockPos().below();
                if (!level.isLoaded(bottomPos)) level.getChunk(bottomPos);
                BlockEntity bottomBE = level.getBlockEntity(bottomPos);
                if (bottomBE instanceof AlchemyPedestalBlockEntity bottom) {
                    bottom.inventory.setStackInSlot(0, materials.get(matIdx++));
                    bottom.setChanged();
                } else {
                    RSIntegrationMod.LOGGER.error("[RSI-Embers] No bottom pedestal at {}", bottomPos);
                    player.sendSystemMessage(Component.translatable("rsi.embers.error.bottom_pedestal_missing"));
                    clearAllSlots();
                    return false;
                }

                top.inventory.setStackInSlot(0, materials.get(matIdx++));
                top.setChanged();
            }

            // sparkStrength must be >= 1000.0 otherwise sparkProgress returns immediately.
            tablet.sparkProgress(tablet, 1000.0);
            craftStarted = true;
            RSIntegrationMod.LOGGER.debug("[RSI-Embers] Craft started: {} pedestals filled + sparked, progress={}",
                    code.size(), tablet.progress);
            return true;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Embers] Material placement failed:", e);
            player.sendSystemMessage(Component.translatable("rsi.embers.error.placement_failed"));
            clearAllSlots();
            return false;
        }
    }

    @Override
    public boolean isCraftComplete(ServerLevel level) {
        if (tablet == null || tablet.isRemoved()) return true; // safety: abort on missing BE
        // serverTick increments progress (0→1→...→40→0), then places
        // result in IBin below (outputMode=false) or in tablet inventory
        // (outputMode=true). Polling outputHandler alone misses the IBin
        // path; progress==0 after craftStarted is the reliable signal.
        return craftStarted && tablet.progress == 0;
    }

    @Override
    public ItemStack collectResult(ServerPlayer player) {
        EreAlchemyLock.unlock(level.dimension(), machinePos);
        craftStarted = false;
        if (tablet == null) return ItemStack.EMPTY;

        // serverTick tries IBin below first; if that succeeds, outputMode
        // stays false and outputHandler.extractItem returns EMPTY.
        // If no IBin, serverTick sets outputMode=true and places the result
        // in the tablet inventory (accessible via outputHandler).
        ItemStack result = tablet.outputHandler.extractItem(0, 64, false);
        if (!result.isEmpty()) {
            tablet.setChanged();
            // Clear any surviving pedestal items (aspects/inputs). Embers should
            // have consumed them on success, but if anything survived the next
            // craft attempt will fail because validateAndInit finds occupied slots.
            clearAllSlots();
            RSIntegrationMod.LOGGER.debug("[RSI-Embers] Collected result from tablet: {} x{}",
                    result.getHoverName().getString(), result.getCount());
            return result;
        }

        // Result may be in the IBin below the tablet
        BlockPos below = machinePos.below();
        if (!level.isLoaded(below)) level.getChunk(below);
        BlockEntity be = level.getBlockEntity(below);
        if (be instanceof com.rekindled.embers.api.tile.IBin bin) {
            result = bin.getInventory().extractItem(0, 64, false);
            if (!result.isEmpty()) {
                clearAllSlots();
                RSIntegrationMod.LOGGER.debug("[RSI-Embers] Collected result from IBin: {} x{}",
                        result.getHoverName().getString(), result.getCount());
                return result;
            }
        }

        RSIntegrationMod.LOGGER.warn("[RSI-Embers] collectResult: no result found in tablet or IBin");
        return ItemStack.EMPTY;
    }

    @Override
    public void onBatchFailed(ServerPlayer player, String reason) {
        EreAlchemyLock.unlock(level.dimension(), machinePos);
        craftStarted = false;
        clearAllSlots();
        if (reason != null && !reason.isEmpty()) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.craft_failed", reason));
        }
    }

    @Override
    public void onBatchFinished(ServerPlayer player) {}

    @Override
    public BlockPos getMachinePos() {
        return machinePos != null ? machinePos : BlockPos.ZERO;
    }

    // ── plan-time warnings ─────────────────────────────────────────

    /** Provide plan-time warnings visible in the crafting plan UI before execution. */
    public static List<String> getPlanWarnings(ServerPlayer player, Recipe<?> recipe,
                                                @Nullable ResourceLocation dim,
                                                @Nullable BlockPos pos) {
        List<String> warnings = new ArrayList<>();
        if (dim == null || pos == null) {
            warnings.add(Component.translatable("rsi.embers.warn.no_tablet_bound").getString());
            return warnings;
        }

        // Machine validation at plan time — detect missing tablet, insufficient
        // pedestals, etc. before the user clicks Confirm, so the plan screen
        // shows a warning instead of silently showing "1 step" that fails immediately.
        ServerLevel lvl = CraftPacketUtils.resolveLevel(player.server, dim, player);
        if (lvl != null) {
            // Pre-load chunks in the 7×7 scan area so getBlockEntity() works
            if (!lvl.isLoaded(pos)) lvl.getChunk(pos);
            int minCX = (pos.getX() - 3) >> 4;
            int maxCX = (pos.getX() + 3) >> 4;
            int minCZ = (pos.getZ() - 3) >> 4;
            int maxCZ = (pos.getZ() + 3) >> 4;
            for (int cx = minCX; cx <= maxCX; cx++) {
                for (int cz = minCZ; cz <= maxCZ; cz++) {
                    lvl.getChunk(cx, cz);
                }
            }

            BlockEntity be = lvl.getBlockEntity(pos);
            if (!(be instanceof AlchemyTabletBlockEntity)) {
                warnings.add(Component.translatable("rsi.embers.warn.tablet_missing").getString());
            } else if (recipe instanceof AlchemyRecipe ar) {
                long seed = lvl.getSeed();
                List<net.minecraft.world.item.crafting.Ingredient> code = ar.getCode(seed);
                int needed = code.size();

                java.util.List<AlchemyPedestalTopBlockEntity> nearby =
                        scanPedestals(lvl, pos);

                if (nearby.size() < needed) {
                    warnings.add(Component.translatable("rsi.embers.warn.pedestals_insufficient",
                            needed, nearby.size()).getString());
                }
                for (AlchemyPedestalTopBlockEntity p : nearby) {
                    BlockEntity bottomBE = lvl.getBlockEntity(p.getBlockPos().below());
                    if (!(bottomBE instanceof AlchemyPedestalBlockEntity)) {
                        warnings.add(Component.translatable("rsi.embers.warn.pedestal_invalid").getString());
                        break;
                    }
                }
            }
        }

        if (!RSIntegrationConfig.ENABLE_EMBERS_ALCHEMY_CALC.get()) {
            var savedData = com.huanghuang.rsintegration.mods.embers.KnownCodeSavedData
                    .get(player.serverLevel());
            if (savedData.getCode(recipe.getId().toString()) != null) {
                // Code was previously inferred — pedestal layout will be shown from cache
                warnings.add(Component.translatable("rsi.embers.info.code_cached").getString());
            } else {
                warnings.add(Component.translatable("rsi.embers.warn.infer_mode_only").getString());
            }
        }
        return warnings;
    }

    // ── internal ─────────────────────────────────────────────────

    /**
     * Scan for AlchemyPedestalTopBlockEntity in a 7×7 area at y+1.
     * Does NOT use getNearbyPedestals() because that method calls isValid()
     * which requires items in both top and bottom slots — empty pedestals
     * (the normal state before a craft) would be excluded.
     */
    static List<AlchemyPedestalTopBlockEntity> scanPedestals(net.minecraft.world.level.Level level,
                                                              BlockPos tabletPos) {
        List<AlchemyPedestalTopBlockEntity> result = new ArrayList<>();
        BlockPos.MutableBlockPos mpos = tabletPos.mutable();
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                mpos.set(tabletPos.getX() + dx, tabletPos.getY() + 1, tabletPos.getZ() + dz);
                BlockEntity be = level.getBlockEntity(mpos);
                if (be instanceof AlchemyPedestalTopBlockEntity top) {
                    result.add(top);
                }
            }
        }
        return result;
    }

    /** Clear surviving items from all slots and refund them to RS. */
    private void clearAllSlots() {
        List<ItemStack> toRefund = new ArrayList<>();

        if (tablet != null && !tablet.isRemoved()) {
            ItemStack s = tablet.inventory.getStackInSlot(0);
            if (!s.isEmpty()) { toRefund.add(s.copy()); tablet.inventory.setStackInSlot(0, ItemStack.EMPTY); tablet.setChanged(); }
        }
        if (pedestals != null && level != null) {
            for (AlchemyPedestalTopBlockEntity top : pedestals) {
                if (top.isRemoved()) continue;
                ItemStack as = top.inventory.getStackInSlot(0);
                if (!as.isEmpty()) { toRefund.add(as.copy()); top.inventory.setStackInSlot(0, ItemStack.EMPTY); top.setChanged(); }

                BlockPos bottomPos = top.getBlockPos().below();
                if (!level.isLoaded(bottomPos)) continue;
                BlockEntity bottomBE = level.getBlockEntity(bottomPos);
                if (bottomBE instanceof AlchemyPedestalBlockEntity bottom && !bottom.isRemoved()) {
                    ItemStack is = bottom.inventory.getStackInSlot(0);
                    if (!is.isEmpty()) { toRefund.add(is.copy()); bottom.inventory.setStackInSlot(0, ItemStack.EMPTY); bottom.setChanged(); }
                }
            }
        }

        if (network != null && !toRefund.isEmpty()) {
            for (ItemStack s : toRefund) {
                if (s.isEmpty()) continue;
                var tracker = network.getItemStorageTracker();
                if (tracker != null) tracker.changed(null, s.copy());
                ItemStack leftover = network.insertItem(s, s.getCount(), Action.PERFORM);
                if (!leftover.isEmpty()) {
                    RSIntegrationMod.LOGGER.debug("[RSI-Embers] Refund partial: {} x{}",
                            leftover.getHoverName().getString(), leftover.getCount());
                }
            }
        }
    }
}
