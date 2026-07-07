package com.huanghuang.rsintegration.mods.embers;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.mods.embers.EreAlchemyBatchDelegate;
import com.huanghuang.rsintegration.mods.embers.EreAlchemyLock;
import com.huanghuang.rsintegration.mods.embers.KnownCodeSavedData;
import com.huanghuang.rsintegration.network.RSIntegrationNetwork;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.reflection.probes.EmbersReflection;
import com.huanghuang.rsintegration.util.ChunkUtils;
import com.huanghuang.rsintegration.util.Reflect;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.storage.tracker.IStorageTracker;
import com.refinedmods.refinedstorage.api.util.Action;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
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

public final class EreAlchemyInferDelegate
extends AbstractBatchDelegate {
    private static final int MINIMAX_THRESHOLD = 1024;
    private static final int MAX_COMBINATIONS = 50000;
    private static final long PROGRESS_TTL_MS = 1800000L;
    private static final Map<String, InferenceProgress> PROGRESS_CACHE;
    private ServerLevel level;
    private BlockPos machinePos;
    private Object recipe;
    private List<Ingredient> aspects;
    private List<Ingredient> inputs;
    private int aspectsSize;
    private int inputsSize;
    private Object tablet;
    private List<EreAlchemyBatchDelegate.PedestalInfo> pedestals;
    @Nullable
    private ServerPlayer player;
    private Phase phase = Phase.INIT;
    private List<int[]> allCombinations;
    private List<int[]> candidates;
    private int[] currentGuess;
    private int attemptCount;
    private int consecutiveZeroBlackPins;
    private int waitStartTick;
    private ItemStack successResult = ItemStack.EMPTY;
    private boolean succeeded;
    private List<IngredientSpec> firstAttemptSpecs;
    private int[] aspectEquivClasses;

    private static String progressKey(UUID playerId, String recipeId) {
        return playerId + "|" + recipeId;
    }

    private void saveProgress(UUID playerId) {
        if (this.recipe == null || playerId == null) {
            return;
        }
        ResourceLocation rid = ((Recipe<?>) this.recipe).getId();
        if (rid == null) {
            return;
        }
        EreAlchemyInferDelegate.cleanExpiredProgress(1800000L);
        PROGRESS_CACHE.put(EreAlchemyInferDelegate.progressKey(playerId, rid.toString()), new InferenceProgress(this.allCombinations, new ArrayList<int[]>(this.candidates), this.currentGuess != null ? (int[])this.currentGuess.clone() : null, this.attemptCount, this.consecutiveZeroBlackPins, this.aspectsSize, this.inputsSize, System.currentTimeMillis()));
        RSIntegrationMod.LOGGER.debug("[RSI-Embers-Infer] Saved progress for {}: {} candidates, {} attempts", (Object)rid, (Object)this.candidates.size(), (Object)this.attemptCount);
    }

    @Nullable
    private static InferenceProgress takeProgress(UUID playerId, String recipeId) {
        if (playerId == null) {
            return null;
        }
        return PROGRESS_CACHE.remove(EreAlchemyInferDelegate.progressKey(playerId, recipeId));
    }

    private static void clearProgress(UUID playerId, String recipeId) {
        if (playerId == null) {
            return;
        }
        PROGRESS_CACHE.remove(EreAlchemyInferDelegate.progressKey(playerId, recipeId));
    }

    private static void cleanExpiredProgress(long maxAgeMs) {
        long cutoff = System.currentTimeMillis() - maxAgeMs;
        Iterator<Map.Entry<String, InferenceProgress>> it = PROGRESS_CACHE.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, InferenceProgress> entry = it.next();
            if (entry.getValue().timestamp() >= cutoff) continue;
            it.remove();
        }
    }

    public boolean validateAndInit(ServerPlayer player, ResourceLocation recipeId, @Nullable ResourceLocation dim, BlockPos pos) {
        if (!EmbersReflection.isAvailable()) {
            player.sendSystemMessage(Component.translatable("rsi.batch.error.mod_missing", "Embers"));
            return false;
        }
        this.player = player;
        ServerLevel lvl = CraftPacketUtils.resolveLevel((MinecraftServer)player.server, (ResourceLocation)dim, (ServerPlayer)player);
        if (lvl == null) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.dim_not_found"));
            return false;
        }
        this.level = lvl;
        this.machinePos = pos;
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
            RSIntegrationMod.LOGGER.warn("[RSI-Embers-Infer] No AlchemyTabletBlockEntity at {}", (Object)pos);
            player.sendSystemMessage(Component.translatable("rsi.embers.error.not_tablet"));
            return false;
        }
        this.tablet = be;
        Recipe r = lvl.getRecipeManager().byKey(recipeId).orElse(null);
        if (r == null || !EmbersReflection.alchemyRecipeClass.isInstance(r)) {
            RSIntegrationMod.LOGGER.warn("[RSI-Embers-Infer] Recipe {} is not an AlchemyRecipe", (Object)recipeId);
            player.sendSystemMessage(Component.translatable("rsi.embers.error.not_alchemy_recipe", recipeId.toString()));
            return false;
        }
        this.recipe = r;
        this.aspects = EreAlchemyInferDelegate.readIngredientList(this.recipe, "aspects");
        this.inputs = EreAlchemyInferDelegate.readIngredientList(this.recipe, "inputs");
        if (this.aspects == null || this.inputs == null || this.aspects.isEmpty() || this.inputs.isEmpty()) {
            RSIntegrationMod.LOGGER.warn("[RSI-Embers-Infer] Cannot read aspects/inputs for {}", (Object)recipeId);
            player.sendSystemMessage(Component.translatable("rsi.embers.error.cannot_read_fields"));
            return false;
        }
        this.aspectsSize = this.aspects.size();
        this.inputsSize = this.inputs.size();
        this.aspectEquivClasses = computeAspectEquivClasses(this.aspects);
        this.pedestals = EreAlchemyBatchDelegate.scanPedestals((Level)lvl, pos);
        if (this.pedestals.size() < this.inputsSize) {
            RSIntegrationMod.LOGGER.warn("[RSI-Embers-Infer] Need {} pedestals, found {}", (Object)this.inputsSize, (Object)this.pedestals.size());
            player.sendSystemMessage(Component.translatable("rsi.embers.error.pedestals_insufficient", this.inputsSize, this.pedestals.size()));
            return false;
        }
        this.network = RSIntegrationNetwork.resolveNetworkFromPlayer((ServerPlayer)player);
        EreAlchemyBatchDelegate.recycleBlockingItems(lvl, pos, this.pedestals, player);
        for (EreAlchemyBatchDelegate.PedestalInfo p : this.pedestals) {
            ItemStack bottomStack;
            Object bottomInv;
            ItemStack topStack;
            Object topInv = Reflect.getField((Object)p.be(), "inventory").orElse(null);
            if (topInv != null && !(topStack = Reflect.invoke(topInv, "getStackInSlot", 0).map(o -> (ItemStack)o).orElse(ItemStack.EMPTY)).isEmpty()) {
                RSIntegrationMod.LOGGER.warn("[RSI-Embers-Infer] Pedestal top at {} still occupied after recycle: {}", (Object)p.pos(), (Object)topStack.getHoverName().getString());
                player.sendSystemMessage(Component.literal("\u00a7c\u57fa\u5ea7\u9876\u90e8\u4ecd\u6709\u7269\u54c1: " + p.pos().toShortString() + " (" + topStack.getHoverName().getString() + ")"));
                return false;
            }
            BlockEntity bottomBE = lvl.getBlockEntity(p.pos().below());
            if (bottomBE == null || !EmbersReflection.alchemyPedestalBEClass.isInstance(bottomBE)) {
                String reason = bottomBE == null ? "\u65e0BE" : "\u7c7b\u578b=" + bottomBE.getClass().getSimpleName();
                RSIntegrationMod.LOGGER.warn("[RSI-Embers-Infer] Pedestal bottom at {} missing or invalid ({})", (Object)p.pos().below(), (Object)reason);
                player.sendSystemMessage(Component.literal("\u00a7c\u57fa\u5ea7\u5e95\u90e8\u5f02\u5e38: " + p.pos().below().toShortString() + " (" + reason + ")"));
                return false;
            }
            if (bottomBE.isRemoved()) {
                RSIntegrationMod.LOGGER.debug("[RSI-Embers-Infer] Pedestal bottom at {} isRemoved=true but BE type valid \u2014 ignoring spurious flag", (Object)p.pos().below());
            }
            if ((bottomInv = Reflect.getField((Object)bottomBE, "inventory").orElse(null)) == null || (bottomStack = Reflect.invoke(bottomInv, "getStackInSlot", 0).map(o -> (ItemStack)o).orElse(ItemStack.EMPTY)).isEmpty()) continue;
            RSIntegrationMod.LOGGER.warn("[RSI-Embers-Infer] Pedestal bottom at {} still occupied after recycle: {}", (Object)p.pos().below(), (Object)bottomStack.getHoverName().getString());
            player.sendSystemMessage(Component.literal("\u00a7c\u57fa\u5ea7\u5e95\u90e8\u4ecd\u6709\u7269\u54c1: " + p.pos().below().toShortString() + " (" + bottomStack.getHoverName().getString() + ")"));
            return false;
        }
        InferenceProgress saved = EreAlchemyInferDelegate.takeProgress(player.getUUID(), recipeId.toString());
        if (saved != null && saved.aspectsSize() == this.aspectsSize && saved.inputsSize() == this.inputsSize) {
            this.allCombinations = saved.allCombinations();
            this.candidates = new ArrayList<int[]>(saved.candidates());
            this.currentGuess = saved.currentGuess() != null ? (int[])saved.currentGuess().clone() : null;
            this.attemptCount = saved.attemptCount();
            this.consecutiveZeroBlackPins = saved.consecutiveZeroBlackPins();
            RSIntegrationMod.LOGGER.debug("[RSI-Embers-Infer] Restored progress for {}: {} candidates remain, {} attempts so far", (Object)recipeId, (Object)this.candidates.size(), (Object)this.attemptCount);
        } else {
            long total;
            if (saved != null) {
                RSIntegrationMod.LOGGER.debug("[RSI-Embers-Infer] Saved progress mismatch (aspects/inputs size changed), discarding");
            }
            if ((total = EreAlchemyInferDelegate.pow(this.aspectsSize, this.inputsSize)) > 50000L) {
                RSIntegrationMod.LOGGER.warn("[RSI-Embers-Infer] {} combinations exceeds limit {} \u2014 use Mode 2", (Object)total, (Object)50000);
                player.sendSystemMessage(Component.translatable("rsi.embers.error.too_many_combos", total, 50000));
                return false;
            }
            this.generateAllCombinations();
            this.candidates = new ArrayList<int[]>(this.allCombinations);
            this.currentGuess = this.selectInitialGuess();
            this.attemptCount = 0;
            this.consecutiveZeroBlackPins = 0;
        }
        this.succeeded = false;
        this.phase = Phase.INIT;
        this.buildFirstAttemptSpecs();
        if (!EreAlchemyLock.tryLock(lvl.dimension(), (BlockPos)pos, player.getUUID())) {
            player.sendSystemMessage(Component.translatable("rsi.embers.error.tablet_locked"));
            return false;
        }
        RSIntegrationMod.LOGGER.debug("[RSI-Embers-Infer] Init OK: recipe={} aspects={} inputs={} combos={} firstGuess={}", (Object)recipeId, (Object)this.aspectsSize, (Object)this.inputsSize, (Object)this.allCombinations.size(), (Object)Arrays.toString(this.currentGuess));
        if (player != null) {
            player.displayClientMessage(Component.translatable("rsi.embers.infer.started", this.allCombinations.size(), RSIntegrationConfig.EMBERS_INFER_MAX_ATTEMPTS.get()), true);
        }
        return true;
    }

    @Nullable
    public List<IngredientSpec> getRequiredMaterials() {
        return this.firstAttemptSpecs;
    }

    public boolean tryStartSingleCraft(ServerPlayer player) {
        return false;
    }

    public boolean tryStartWithMaterials(ServerPlayer player, List<ItemStack> materials, ExtractionLedger sharedLedger) {
        ItemStack outputStack;
        BlockEntity current;
        this.sharedLedger = sharedLedger;
        this.usingSharedLedger = true;
        if (this.tablet == null || this.pedestals == null || this.currentGuess == null) {
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
            RSIntegrationMod.LOGGER.warn("[RSI-Embers-Infer] Tablet output slot not empty");
            player.sendSystemMessage(Component.translatable("rsi.embers.error.tablet_output_occupied"));
            return false;
        }
        if (!this.placeMaterialsAndSpark(materials)) {
            return false;
        }
        ++this.attemptCount;
        this.waitStartTick = this.level.getServer().getTickCount();
        this.phase = Phase.PLACED;
        // Stop using sharedLedger after first placement — the chain has already
        // committed it. Retries extract directly from the network instead.
        this.usingSharedLedger = false;
        RSIntegrationMod.LOGGER.debug("[RSI-Embers-Infer] Attempt {} started: guess={}", (Object)this.attemptCount, (Object)Arrays.toString(this.currentGuess));
        return true;
    }

    @Override
    protected boolean isMachineCraftFinished(ServerLevel level, BlockEntity be) {
        if (this.phase == Phase.DONE_SUCCESS || this.phase == Phase.DONE_FAILED) {
            warnOnce("already_done", "[RSI-Embers-Infer] isMachineCraftFinished: already done, phase={}", this.phase);
            return true;
        }
        if (this.phase == Phase.INIT) {
            return false;
        }
        if (this.phase == Phase.PLACED || this.phase == Phase.WAITING) {
            var progressOpt = Reflect.getIntField(this.tablet, "progress");
            int progress = progressOpt.orElse(-999);
            int elapsed = level.getServer().getTickCount() - this.waitStartTick;
            warnOnce("progress_poll", "[RSI-Embers-Infer] isMachineCraftFinished: progressOpt={} progress={} elapsed={} waitStart={} tickNow={} phase={}",
                    progressOpt, progress, elapsed,
                    this.waitStartTick, level.getServer().getTickCount(),
                    this.phase);
            if (progress > 0) {
                this.phase = Phase.WAITING;
                return false;
            }
            if (elapsed < 10) {
                return false;
            }
            ItemStack result = this.findResultAnywhere();
            if (result.isEmpty()) {
                if (elapsed > (Integer)RSIntegrationConfig.EMBERS_PROGRESS_TIMEOUT_TICKS.get()) {
                    warnOnce("progress_timeout", "[RSI-Embers-Infer] Timeout waiting for alchemy result");
                    this.phase = Phase.DONE_FAILED;
                    return true;
                }
                return false;
            }
            warnOnce("result_found", "[RSI-Embers-Infer] isMachineCraftFinished: found result={} calling processResult",
                    result.getHoverName().getString());
            return this.processResult(result);
        }
        warnOnce("unexpected_phase", "[RSI-Embers-Infer] isMachineCraftFinished: unexpected phase={}", this.phase);
        return false;
    }

    private ItemStack findResultAnywhere() {
        ItemStack r;
        Object binInv;
        ItemStack r2;
        Object outHandler = Reflect.getField((Object)this.tablet, "outputHandler").orElse(null);
        if (outHandler != null && !(r2 = Reflect.invoke(outHandler, "getStackInSlot", 0).map(o -> (ItemStack)o).orElse(ItemStack.EMPTY)).isEmpty()) {
            return r2;
        }
        BlockPos below = this.machinePos.below();
        ChunkUtils.loadChunk((ServerLevel)this.level, (BlockPos)below);
        BlockEntity be = this.level.getBlockEntity(below);
        if (EmbersReflection.ibinClass != null && be != null && EmbersReflection.ibinClass.isInstance(be) && (binInv = Reflect.invoke((Object)be, "getInventory").orElse(null)) != null && !(r = Reflect.invoke(binInv, "getStackInSlot", 0).map(o -> (ItemStack)o).orElse(ItemStack.EMPTY)).isEmpty()) {
            return r;
        }
        return ItemStack.EMPTY;
    }

    private boolean processResult(ItemStack result) {
        int maxAttempts;
        boolean isSuccess;
        ItemStack expectedOutput = this.getRecipeOutput();
        boolean bl = isSuccess = !expectedOutput.isEmpty() && ItemStack.isSameItem((ItemStack)result, (ItemStack)expectedOutput);
        if (isSuccess) {
            this.successResult = result.copy();
            this.succeeded = true;
            this.phase = Phase.DONE_SUCCESS;
            int[] codeIndices = (int[])this.currentGuess.clone();
            ResourceLocation rid = ((Recipe<?>) this.recipe).getId();
            if (rid != null) {
                KnownCodeSavedData.get((ServerLevel)this.level).putCode(rid.toString(), codeIndices);
                if (this.player != null) {
                    EreAlchemyInferDelegate.clearProgress(this.player.getUUID(), rid.toString());
                }
            }
            RSIntegrationMod.LOGGER.debug("[RSI-Embers-Infer] SUCCESS after {} attempts: code={}", (Object)this.attemptCount, (Object)Arrays.toString(codeIndices));
            if (this.player != null) {
                this.player.displayClientMessage(Component.translatable("rsi.embers.infer.success", this.attemptCount), true);
            }
            return true;
        }
        int blackPins = 0;
        int whitePins = 0;
        CompoundTag nbt = result.getTag();
        if (nbt != null) {
            blackPins = nbt.getInt("blackPins");
            whitePins = nbt.getInt("whitePins");
        }
        RSIntegrationMod.LOGGER.debug("[RSI-Embers-Infer] Attempt {} FAIL: blackPins={} whitePins={} guess={}", (Object)this.attemptCount, (Object)blackPins, (Object)whitePins, (Object)Arrays.toString(this.currentGuess));
        if (this.player != null) {
            this.player.displayClientMessage(Component.translatable("rsi.embers.infer.progress", this.attemptCount, blackPins, whitePins, this.candidates.size()), true);
        }
        // Remove failure item from output slot, matching HEAD version
        Object outHandler = Reflect.getField((Object)this.tablet, "outputHandler").orElse(null);
        if (outHandler != null) {
            Reflect.invoke(outHandler, "extractItem", 0, 64, false);
        }
        ((BlockEntity) this.tablet).setChanged();
        if (blackPins == 0) {
            ++this.consecutiveZeroBlackPins;
            int zeroLimit = (Integer)RSIntegrationConfig.EMBERS_INFER_ZERO_BLACK_LIMIT.get();
            if (this.consecutiveZeroBlackPins >= zeroLimit) {
                warnOnce("zero_black_pin_limit", "[RSI-Embers-Infer] {} consecutive 0-blackPin, aborting", (Object)this.consecutiveZeroBlackPins);
                if (this.player != null) {
                    this.player.displayClientMessage(Component.translatable("rsi.embers.infer.failed_zero_black", zeroLimit), true);
                }
                this.clearAndRefundSurvivors();
                this.phase = Phase.DONE_FAILED;
                return true;
            }
        } else {
            this.consecutiveZeroBlackPins = 0;
        }
        if (this.attemptCount >= (maxAttempts = ((Integer)RSIntegrationConfig.EMBERS_INFER_MAX_ATTEMPTS.get()).intValue())) {
            warnOnce("max_attempts_reached", "[RSI-Embers-Infer] Max attempts {} reached", (Object)maxAttempts);
            if (this.player != null) {
                this.player.displayClientMessage(Component.translatable("rsi.embers.infer.failed_max", maxAttempts), true);
            }
            this.clearAndRefundSurvivors();
            this.phase = Phase.DONE_FAILED;
            return true;
        }
        this.clearAndRefundSurvivors();
        this.filterCandidates(blackPins, whitePins);
        if (this.candidates.isEmpty()) {
            warnOnce("no_candidates_remain", "[RSI-Embers-Infer] No candidates remain after filtering (black={} white={} guess={})",
                    blackPins, whitePins, Arrays.toString(this.currentGuess));
            if (this.player != null) {
                this.player.displayClientMessage(Component.translatable("rsi.embers.infer.failed_no_candidates"), true);
            }
            this.clearAndRefundSurvivors();
            this.phase = Phase.DONE_FAILED;
            return true;
        }
        this.currentGuess = this.candidates.size() == 1 ? this.candidates.get(0) : this.selectNextGuess();
        if (this.currentGuess == null) {
            this.clearAndRefundSurvivors();
            this.phase = Phase.DONE_FAILED;
            return true;
        }
        // Save progress BEFORE extractAndSparkNext so attemptCount reflects the
        // number of completed attempts, not the one about to start.  When this
        // state is later restored, tryStartWithMaterials will ++attemptCount
        // exactly once for the replayed attempt (no double-count).
        if (this.player != null) {
            this.saveProgress(this.player.getUUID());
        }
        if (!this.extractAndSparkNext()) {
            this.phase = Phase.DONE_FAILED;
            return true;
        }
        return false;
    }

    public ItemStack collectResult(ServerPlayer player) {
        EreAlchemyLock.unlock(this.level.dimension(), (BlockPos)this.machinePos);
        if (this.succeeded && !this.successResult.isEmpty()) {
            ItemStack out = this.successResult.copy();
            this.successResult = ItemStack.EMPTY;

            // Clear result from wherever it ended up (outputHandler or IBin),
            // matching the HEAD version's extraction order.
            Object outHandler = Reflect.getField((Object)this.tablet, "outputHandler").orElse(null);
            ItemStack cleared = ItemStack.EMPTY;
            if (outHandler != null) {
                cleared = Reflect.invoke(outHandler, "extractItem", 0, 64, false)
                        .map(o -> (ItemStack)o).orElse(ItemStack.EMPTY);
            }
            if (cleared.isEmpty()) {
                // Result may be in IBin below
                BlockPos below = this.machinePos.below();
                ChunkUtils.loadChunk((ServerLevel)this.level, (BlockPos)below);
                BlockEntity be = this.level.getBlockEntity(below);
                if (EmbersReflection.ibinClass != null && be != null && EmbersReflection.ibinClass.isInstance(be)) {
                    Object binInv = Reflect.invoke((Object)be, "getInventory").orElse(null);
                    if (binInv != null) {
                        Reflect.invoke(binInv, "extractItem", 0, 64, false);
                    }
                }
            }
            ((BlockEntity) this.tablet).setChanged();
            this.clearAndRefundSurvivors();
            return out;
        }
        return ItemStack.EMPTY;
    }

    @Override
    protected void clearMachineState(BlockEntity be, ServerPlayer player) {
        EreAlchemyLock.unlock(this.level.dimension(), be.getBlockPos());
        this.clearAndRefundSurvivors();
    }

    public void onBatchFinished(ServerPlayer player) {
    }

    public BlockPos getMachinePos() {
        return this.machinePos != null ? this.machinePos : BlockPos.ZERO;
    }

    private void generateAllCombinations() {
        this.allCombinations = new ArrayList<int[]>();
        int[] buf = new int[this.inputsSize];
        this.generateRecursive(0, buf);
    }

    private void generateRecursive(int pos, int[] buf) {
        if (pos == this.inputsSize) {
            this.allCombinations.add((int[])buf.clone());
            return;
        }
        int a = 0;
        while (a < this.aspectsSize) {
            buf[pos] = a++;
            this.generateRecursive(pos + 1, buf);
        }
    }

    private int[] selectInitialGuess() {
        int[] guess = new int[this.inputsSize];
        for (int i = 0; i < this.inputsSize; ++i) {
            guess[i] = i % this.aspectsSize;
        }
        EreAlchemyInferDelegate.shuffle(guess);
        return guess;
    }

    @Nullable
    private int[] selectNextGuess() {
        if (this.candidates.size() > 1024) {
            return this.candidates.get(new Random().nextInt(this.candidates.size()));
        }
        return this.selectMinimax();
    }

    @Nullable
    private int[] selectMinimax() {
        int bestScore = Integer.MAX_VALUE;
        int[] bestCandidate = null;
        for (int[] c : this.candidates) {
            HashMap<Long, Integer> partitions = new HashMap<Long, Integer>();
            for (int[] r : this.candidates) {
                MatchStats s = matchStats(c, r);
                long key = (long)s.correct << 32 | (long)s.valueOnly & 0xFFFFFFFFL;
                partitions.merge(key, 1, Integer::sum);
            }
            int worst = 0;
            Iterator iterator = partitions.values().iterator();
            while (iterator.hasNext()) {
                int v = (Integer)iterator.next();
                if (v <= worst) continue;
                worst = v;
            }
            if (worst >= bestScore) continue;
            bestScore = worst;
            bestCandidate = c;
            if (bestScore != 1) continue;
            break;
        }
        return bestCandidate;
    }

    private MatchStats matchStats(int[] guess, int[] target) {
        int correct = 0;
        int valueOnly = 0;
        boolean[] gu = new boolean[guess.length];
        boolean[] tu = new boolean[target.length];
        for (int i = 0; i < guess.length; i++) {
            if (aspectEquivClasses[guess[i]] == aspectEquivClasses[target[i]]) {
                correct++;
                tu[i] = true;
                gu[i] = true;
            }
        }
        outer: for (int i = 0; i < guess.length; i++) {
            if (gu[i]) continue;
            for (int j = 0; j < target.length; j++) {
                if (tu[j]) continue;
                if (aspectEquivClasses[guess[i]] == aspectEquivClasses[target[j]]) {
                    valueOnly++;
                    tu[j] = true;
                    continue outer;
                }
            }
        }
        return new MatchStats(correct, valueOnly);
    }

    private static int[] computeAspectEquivClasses(List<Ingredient> aspects) {
        int n = aspects.size();
        int[] equiv = new int[n];
        int nextClass = 0;
        for (int i = 0; i < n; i++) {
            boolean found = false;
            for (int j = 0; j < i; j++) {
                if (areIngredientsCompatible(aspects.get(i), aspects.get(j))) {
                    equiv[i] = equiv[j];
                    found = true;
                    break;
                }
            }
            if (!found) {
                equiv[i] = nextClass++;
            }
        }
        return equiv;
    }

    private static boolean areIngredientsCompatible(Ingredient a, Ingredient b) {
        ItemStack[] itemsA = a.getItems();
        ItemStack[] itemsB = b.getItems();
        if (itemsA.length == 0 || itemsB.length == 0) return false;
        return ItemStack.isSameItem(itemsA[0], itemsB[0]);
    }

    private void filterCandidates(int blackPins, int whitePins) {
        ArrayList<int[]> filtered = new ArrayList<int[]>();
        for (int[] c : this.candidates) {
            MatchStats s = matchStats(this.currentGuess, c);
            if (s.correct != blackPins || s.valueOnly != whitePins) continue;
            filtered.add(c);
        }
        int before = this.candidates.size();
        this.candidates = filtered;
        RSIntegrationMod.LOGGER.debug("[RSI-Embers-Infer] Filtered {}->{} (black={} white={})", (Object)before, (Object)this.candidates.size(), (Object)blackPins, (Object)whitePins);
    }

    private void buildFirstAttemptSpecs() {
        this.firstAttemptSpecs = new ArrayList<IngredientSpec>();
        Ingredient tabletIng = Reflect.getField((Object)this.recipe, "tablet").map(o -> (Ingredient)o).orElse(null);
        if (tabletIng != null) {
            this.firstAttemptSpecs.add(new IngredientSpec(tabletIng, 1));
        }
        for (int i = 0; i < this.inputsSize; ++i) {
            this.firstAttemptSpecs.add(new IngredientSpec(this.aspects.get(this.currentGuess[i]), 1));
            this.firstAttemptSpecs.add(new IngredientSpec(this.inputs.get(i), 1));
        }
    }

    private boolean placeMaterialsAndSpark(List<ItemStack> materials) {
        boolean hasTablet = Reflect.getField((Object)this.recipe, "tablet").map(o -> (Ingredient)o).orElse(null) != null;
        int expectedCount = (hasTablet ? 1 : 0) + 2 * this.inputsSize;
        if (materials.size() < expectedCount) {
            RSIntegrationMod.LOGGER.error("[RSI-Embers-Infer] Material count mismatch: expected >= {}, got {}", (Object)expectedCount, (Object)materials.size());
            if (this.player != null) {
                this.player.sendSystemMessage(Component.translatable("rsi.embers.error.bottom_pedestal_missing"));
            }
            return false;
        }
        int idx = 0;
        try {
            Object tabletInv = Reflect.getField((Object)this.tablet, "inventory").orElse(null);
            if (tabletInv != null) {
                Reflect.invoke(tabletInv, "setStackInSlot", 0, materials.get(idx++));
            }
            ((BlockEntity) this.tablet).setChanged();
            for (int i = 0; i < this.inputsSize; ++i) {
                EreAlchemyBatchDelegate.PedestalInfo pi = this.pedestals.get(i);
                BlockPos bottomPos = pi.pos().below();
                if (!this.level.isLoaded(bottomPos)) {
                    RSIntegrationMod.LOGGER.warn("[RSI-Embers-Infer] Bottom pedestal chunk unloaded at {} \u2014 aborting", (Object)bottomPos);
                    this.clearAndRefundSurvivors();
                    return false;
                }
                BlockEntity be = this.level.getBlockEntity(bottomPos);
                if (be != null && EmbersReflection.alchemyPedestalBEClass != null && EmbersReflection.alchemyPedestalBEClass.isInstance(be)) {
                    Object bottomInv = Reflect.getField((Object)be, "inventory").orElse(null);
                    if (bottomInv != null) {
                        Reflect.invoke(bottomInv, "setStackInSlot", 0, materials.get(idx++));
                    }
                } else {
                    RSIntegrationMod.LOGGER.error("[RSI-Embers-Infer] No bottom pedestal at {}", (Object)bottomPos);
                    if (this.player != null) {
                        this.player.sendSystemMessage(Component.translatable("rsi.embers.error.bottom_pedestal_missing"));
                    }
                    this.clearAndRefundSurvivors();
                    return false;
                }
                be.setChanged();
                Object topInv = Reflect.getField((Object)pi.be(), "inventory").orElse(null);
                if (topInv != null) {
                    Reflect.invoke(topInv, "setStackInSlot", 0, materials.get(idx++));
                }
                ((BlockEntity) pi.be()).setChanged();
            }
            int progressBefore = Reflect.getIntField((Object)this.tablet, "progress").orElse(-999);
            Reflect.invoke((Object)this.tablet, "sparkProgress", this.tablet, 1000.0);
            int progressAfter = Reflect.getIntField((Object)this.tablet, "progress").orElse(-999);
            if (progressAfter == 0) {
                RSIntegrationMod.LOGGER.warn("[RSI-Embers-Infer] sparkProgress FAILED: progress stayed 0");
                if (this.player != null) {
                    this.player.sendSystemMessage(Component.translatable("rsi.embers.error.placement_failed"));
                }
                this.clearAndRefundSurvivors();
                return false;
            }
            RSIntegrationMod.LOGGER.warn("[RSI-Embers-Infer] sparkProgress OK: progress {} -> {}",
                    (Object)progressBefore, (Object)progressAfter);
            return true;
        }
        catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Embers-Infer] Placement failed", (Throwable)e);
            if (this.player != null) {
                this.player.sendSystemMessage(Component.translatable("rsi.embers.error.placement_failed"));
            }
            this.clearAndRefundSurvivors();
            return false;
        }
    }

    private boolean extractAndSparkNext() {
        ArrayList<ItemStack> materials = new ArrayList<ItemStack>();
        Ingredient tabletIng = Reflect.getField((Object)this.recipe, "tablet").map(o -> (Ingredient)o).orElse(null);
        if (tabletIng == null) {
            return false;
        }
        ItemStack t = this.extractOne(tabletIng);
        if (t.isEmpty()) {
            this.refundPartial(materials);
            return false;
        }
        materials.add(t);
        for (int i = 0; i < this.inputsSize; ++i) {
            ItemStack a = this.extractOne(this.aspects.get(this.currentGuess[i]));
            if (a.isEmpty()) {
                this.refundPartial(materials);
                return false;
            }
            materials.add(a);
            ItemStack in = this.extractOne(this.inputs.get(i));
            if (in.isEmpty()) {
                this.refundPartial(materials);
                return false;
            }
            materials.add(in);
        }
        boolean ok = this.placeMaterialsAndSpark(materials);
        if (ok) {
            ++this.attemptCount;
            this.waitStartTick = this.level.getServer().getTickCount();
        }
        return ok;
    }

    private ItemStack extractOne(Ingredient ing) {
        if (this.usingSharedLedger && this.sharedLedger != null) {
            return CraftPacketUtils.ensureMaterialAvailable((ServerPlayer)this.player, this.level.dimension(), (BlockPos)this.machinePos, (Ingredient)ing, (int)1, (ExtractionLedger)this.sharedLedger);
        }
        if (this.network == null) {
            return ItemStack.EMPTY;
        }
        return RSIntegrationNetwork.extractFromNetwork((INetwork)this.network, (Ingredient)ing, (int)1, this.player);
    }

    private void refundPartial(List<ItemStack> stacks) {
        if (this.usingSharedLedger && this.sharedLedger != null) {
            this.sharedLedger.rollback(this.player);
            return;
        }
        if (this.network == null) {
            for (ItemStack s : stacks) {
                if (s.isEmpty() || this.player == null) continue;
                ItemHandlerHelper.giveItemToPlayer(this.player, s.copy());
            }
            return;
        }
        for (ItemStack s : stacks) {
            ItemStack leftover;
            if (s.isEmpty()) continue;
            IStorageTracker tracker = this.network.getItemStorageTracker();
            if (tracker != null && this.player != null) {
                tracker.changed(this.player, s.copy());
            }
            if ((leftover = this.network.insertItem(s, s.getCount(), Action.PERFORM)).isEmpty()) continue;
            RSIntegrationMod.LOGGER.warn("[RSI-Embers-Infer] Refund leftover: {} x{}", (Object)leftover.getHoverName().getString(), (Object)leftover.getCount());
            if (this.player == null) continue;
            ItemHandlerHelper.giveItemToPlayer(this.player, (ItemStack)leftover);
        }
    }

    private void clearAndRefundSurvivors() {
        ArrayList<ItemStack> toRefund = new ArrayList<ItemStack>();

        // Clear tablet inventory — use direct BlockEntity call, not reflection
        BlockEntity tabletBE = this.level.getBlockEntity(this.machinePos);
        if (tabletBE != null && !tabletBE.isRemoved()) {
            Object tabletInv = Reflect.getField((Object)tabletBE, "inventory").orElse(null);
            if (tabletInv != null) {
                ItemStack s = Reflect.invoke(tabletInv, "getStackInSlot", 0).map(o -> (ItemStack)o).orElse(ItemStack.EMPTY);
                if (!s.isEmpty()) {
                    toRefund.add(s.copy());
                    Reflect.invoke(tabletInv, "setStackInSlot", 0, ItemStack.EMPTY);
                    tabletBE.setChanged();
                }
            }
        }

        // Clear pedestals
        if (this.pedestals != null) {
            for (EreAlchemyBatchDelegate.PedestalInfo top : this.pedestals) {
                // Top pedestal
                BlockEntity topBE = this.level.getBlockEntity(top.pos());
                if (topBE != null && !topBE.isRemoved()) {
                    Object topInv = Reflect.getField((Object)topBE, "inventory").orElse(null);
                    if (topInv != null) {
                        ItemStack as = Reflect.invoke(topInv, "getStackInSlot", 0).map(o -> (ItemStack)o).orElse(ItemStack.EMPTY);
                        if (!as.isEmpty()) {
                            toRefund.add(as.copy());
                            Reflect.invoke(topInv, "setStackInSlot", 0, ItemStack.EMPTY);
                            topBE.setChanged();
                        }
                    }
                }

                // Bottom pedestal
                BlockPos bp = top.pos().below();
                if (this.level.isLoaded(bp)) {
                    BlockEntity be = this.level.getBlockEntity(bp);
                    if (be != null && !be.isRemoved() && EmbersReflection.alchemyPedestalBEClass != null && EmbersReflection.alchemyPedestalBEClass.isInstance(be)) {
                        Object botInv = Reflect.getField((Object)be, "inventory").orElse(null);
                        if (botInv != null) {
                            ItemStack is = Reflect.invoke(botInv, "getStackInSlot", 0).map(o -> (ItemStack)o).orElse(ItemStack.EMPTY);
                            if (!is.isEmpty()) {
                                toRefund.add(is.copy());
                                Reflect.invoke(botInv, "setStackInSlot", 0, ItemStack.EMPTY);
                                be.setChanged();
                            }
                        }
                    }
                }
            }
        }
        if (this.network == null) {
            for (ItemStack s2 : toRefund) {
                if (s2.isEmpty() || this.player == null) continue;
                ItemHandlerHelper.giveItemToPlayer(this.player, (ItemStack)s2.copy());
            }
            return;
        }
        if (!toRefund.isEmpty()) {
            for (ItemStack s2 : toRefund) {
                ItemStack leftover;
                if (s2.isEmpty()) continue;
                IStorageTracker tracker = this.network.getItemStorageTracker();
                if (tracker != null && this.player != null) {
                    tracker.changed(this.player, (Object)s2.copy());
                }
                if ((leftover = this.network.insertItem(s2, s2.getCount(), Action.PERFORM)).isEmpty()) continue;
                RSIntegrationMod.LOGGER.warn("[RSI-Embers-Infer] Survivor refund partial: {} x{}", (Object)leftover.getHoverName().getString(), (Object)leftover.getCount());
                if (this.player == null) continue;
                ItemHandlerHelper.giveItemToPlayer(this.player, (ItemStack)leftover);
            }
        }
    }

    private ItemStack getRecipeOutput() {
        return ModRecipeHandlers.tryGetResultItem((Recipe)this.recipe, (RegistryAccess)this.level.registryAccess());
    }

    @Nullable
    private static List<Ingredient> readIngredientList(Object recipe, String fieldName) {
        try {
            return Reflect.getField((Object)recipe, (String)fieldName).map(o -> (List)o).orElse(null);
        }
        catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Embers-Infer] Cannot read {}", (Object)fieldName, e);
            return null;
        }
    }

    private static long pow(int base, int exp) {
        long r = 1L;
        for (int i = 0; i < exp; ++i) {
            if ((r *= (long)base) <= 50000L) continue;
            return r;
        }
        return r;
    }

    private static void shuffle(int[] arr) {
        Random rng = new Random();
        for (int i = arr.length - 1; i > 0; --i) {
            int j = rng.nextInt(i + 1);
            int tmp = arr[i];
            arr[i] = arr[j];
            arr[j] = tmp;
        }
    }

    static {
        PROGRESS_CACHE = new ConcurrentHashMap<String, InferenceProgress>();
    }

    private static enum Phase {
        INIT,
        PLACED,
        WAITING,
        DONE_SUCCESS,
        DONE_FAILED;

    }

    private record InferenceProgress(List<int[]> allCombinations, List<int[]> candidates, int[] currentGuess, int attemptCount, int consecutiveZeroBlackPins, int aspectsSize, int inputsSize, long timestamp) {
    }

    private record MatchStats(int correct, int valueOnly) {
    }
}
