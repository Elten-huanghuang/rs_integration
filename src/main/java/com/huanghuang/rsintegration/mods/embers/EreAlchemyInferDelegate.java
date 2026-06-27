package com.huanghuang.rsintegration.mods.embers;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.network.RSIntegration;
import com.huanghuang.rsintegration.util.Reflect;
import com.rekindled.embers.blockentity.AlchemyPedestalBlockEntity;
import com.rekindled.embers.blockentity.AlchemyPedestalTopBlockEntity;
import com.rekindled.embers.blockentity.AlchemyTabletBlockEntity;
import com.rekindled.embers.recipe.AlchemyRecipe;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.util.Action;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.entity.BlockEntity;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mode 1 trial-and-error inference for Embers Alchemy.
 *
 * <p>Uses real block interaction (tablet + pedestals) with a minimax state
 * machine to discover the correct aspect placement through iterative trials.
 * Each failed attempt consumes some materials (per Embers' failure mechanics);
 * surviving items are refunded to RS before the next attempt.
 *
 * <p>After success, the discovered code is persisted to {@link KnownCodeSavedData}
 * so Mode 2 can reuse it immediately.
 */
public final class EreAlchemyInferDelegate implements IBatchDelegate {

    private static final int MAX_ATTEMPTS = 20;
    private static final int CONSECUTIVE_ZERO_BLACK_LIMIT = 5;
    private static final int MINIMAX_THRESHOLD = 1024;
    private static final int MAX_COMBINATIONS = 50_000;
    private static final int PROGRESS_TIMEOUT_TICKS = 600; // 30 s per attempt

    // ── inference state cache ──────────────────────────────────────
    // Survives recipe switches so interrupted inference can resume.

    private record InferenceProgress(List<int[]> allCombinations, List<int[]> candidates,
                                     int[] currentGuess, int attemptCount,
                                     int consecutiveZeroBlackPins,
                                     int aspectsSize, int inputsSize) {}

    private static final Map<String, InferenceProgress> PROGRESS_CACHE = new ConcurrentHashMap<>();

    private void saveProgress() {
        if (recipe == null) return;
        PROGRESS_CACHE.put(recipe.getId().toString(), new InferenceProgress(
                allCombinations, new ArrayList<>(candidates),
                currentGuess != null ? currentGuess.clone() : null,
                attemptCount, consecutiveZeroBlackPins,
                aspectsSize, inputsSize));
        RSIntegrationMod.LOGGER.debug("[RSI-Embers-Infer] Saved progress for {}: {} candidates, {} attempts",
                recipe.getId(), candidates.size(), attemptCount);
    }

    @Nullable
    private static InferenceProgress takeProgress(String recipeId) {
        return PROGRESS_CACHE.remove(recipeId);
    }

    private static void clearProgress(String recipeId) {
        PROGRESS_CACHE.remove(recipeId);
    }

    private enum Phase {
        INIT, PLACED, WAITING, DONE_SUCCESS, DONE_FAILED
    }

    // ── block / recipe state ──
    private ServerLevel level;
    private BlockPos machinePos;
    private AlchemyRecipe recipe;
    private List<Ingredient> aspects;
    private List<Ingredient> inputs;
    private int aspectsSize;
    private int inputsSize;
    private AlchemyTabletBlockEntity tablet;
    private List<AlchemyPedestalTopBlockEntity> pedestals;
    @Nullable private INetwork network;
    @Nullable private ServerPlayer player;

    // ── inference state ──
    private Phase phase = Phase.INIT;
    private List<int[]> allCombinations;
    private List<int[]> candidates;
    private int[] currentGuess;
    private int attemptCount;
    private int consecutiveZeroBlackPins;
    private int waitStartTick;
    private ItemStack successResult = ItemStack.EMPTY;
    private boolean succeeded;

    // cached by getRequiredMaterials
    private List<IngredientSpec> firstAttemptSpecs;

    // ── IBatchDelegate impl ───────────────────────────────────────

    @Override
    public boolean validateAndInit(ServerPlayer player, ResourceLocation recipeId,
                                   @Nullable ResourceLocation dim, BlockPos pos) {
        this.player = player;
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
        // returns null for any position in an unloaded chunk.
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
            RSIntegrationMod.LOGGER.warn("[RSI-Embers-Infer] No AlchemyTabletBlockEntity at {}", pos);
            player.sendSystemMessage(Component.translatable("rsi.embers.error.not_tablet"));
            return false;
        }
        this.tablet = tbe;

        Recipe<?> r = lvl.getRecipeManager().byKey(recipeId).orElse(null);
        if (!(r instanceof AlchemyRecipe ar)) {
            RSIntegrationMod.LOGGER.warn("[RSI-Embers-Infer] Recipe {} is not an AlchemyRecipe", recipeId);
            player.sendSystemMessage(Component.translatable("rsi.embers.error.not_alchemy_recipe", recipeId.toString()));
            return false;
        }
        this.recipe = ar;

        this.aspects = readIngredientList(ar, "aspects");
        this.inputs = readIngredientList(ar, "inputs");
        if (aspects == null || inputs == null || aspects.isEmpty() || inputs.isEmpty()) {
            RSIntegrationMod.LOGGER.warn("[RSI-Embers-Infer] Cannot read aspects/inputs for {}", recipeId);
            player.sendSystemMessage(Component.translatable("rsi.embers.error.cannot_read_fields"));
            return false;
        }
        this.aspectsSize = aspects.size();
        this.inputsSize = inputs.size();

        // Don't use AlchemyTabletBlockEntity.getNearbyPedestals() — it calls
        // AlchemyPedestalTopBlockEntity.isValid() which requires items in BOTH
        // top and bottom slots. Empty pedestals (normal before craft) return 0.
        this.pedestals = EreAlchemyBatchDelegate.scanPedestals(lvl, pos);
        if (pedestals.size() < inputsSize) {
            RSIntegrationMod.LOGGER.warn("[RSI-Embers-Infer] Need {} pedestals, found {}",
                    inputsSize, pedestals.size());
            player.sendSystemMessage(Component.translatable("rsi.embers.error.pedestals_insufficient",
                    inputsSize, pedestals.size()));
            return false;
        }
        // Pedestals must be empty (not in use by another craft)
        for (AlchemyPedestalTopBlockEntity p : pedestals) {
            if (!p.inventory.getStackInSlot(0).isEmpty()) {
                RSIntegrationMod.LOGGER.warn("[RSI-Embers-Infer] Pedestal top at {} is occupied", p.getBlockPos());
                player.sendSystemMessage(Component.translatable("rsi.embers.error.pedestal_invalid"));
                return false;
            }
            BlockEntity bottomBE = lvl.getBlockEntity(p.getBlockPos().below());
            if (!(bottomBE instanceof AlchemyPedestalBlockEntity bottom) || bottom.isRemoved()
                    || !bottom.inventory.getStackInSlot(0).isEmpty()) {
                RSIntegrationMod.LOGGER.warn("[RSI-Embers-Infer] Pedestal bottom at {} missing or occupied",
                        p.getBlockPos().below());
                player.sendSystemMessage(Component.translatable("rsi.embers.error.pedestal_invalid"));
                return false;
            }
        }

        this.network = RSIntegration.resolveNetworkFromPlayer(player);

        // Check for saved inference progress from a prior interrupted run
        InferenceProgress saved = takeProgress(recipeId.toString());
        if (saved != null && saved.aspectsSize() == aspectsSize && saved.inputsSize() == inputsSize) {
            this.allCombinations = saved.allCombinations();
            this.candidates = new ArrayList<>(saved.candidates());
            this.currentGuess = saved.currentGuess() != null ? saved.currentGuess().clone() : null;
            this.attemptCount = saved.attemptCount();
            this.consecutiveZeroBlackPins = saved.consecutiveZeroBlackPins();
            RSIntegrationMod.LOGGER.debug("[RSI-Embers-Infer] Restored progress for {}: {} candidates remain, {} attempts so far",
                    recipeId, candidates.size(), attemptCount);
        } else {
            if (saved != null) {
                RSIntegrationMod.LOGGER.debug("[RSI-Embers-Infer] Saved progress mismatch (aspects/inputs size changed), discarding");
            }
            // Generate candidate space
            long total = pow(aspectsSize, inputsSize);
            if (total > MAX_COMBINATIONS) {
                RSIntegrationMod.LOGGER.warn("[RSI-Embers-Infer] {} combinations exceeds limit {} — use Mode 2",
                        total, MAX_COMBINATIONS);
                player.sendSystemMessage(Component.translatable("rsi.embers.error.too_many_combos",
                        total, MAX_COMBINATIONS));
                return false;
            }
            generateAllCombinations();
            this.candidates = new ArrayList<>(allCombinations);
            this.currentGuess = selectInitialGuess();
            this.attemptCount = 0;
            this.consecutiveZeroBlackPins = 0;
        }
        this.succeeded = false;
        this.phase = Phase.INIT;

        buildFirstAttemptSpecs();

        // Lock LAST, after all validation, to prevent permanent lock leaks
        // when any earlier check returns false.
        if (!EreAlchemyLock.tryLock(lvl.dimension(), pos, player.getUUID())) {
            player.sendSystemMessage(Component.translatable("rsi.embers.error.tablet_locked"));
            return false;
        }

        RSIntegrationMod.LOGGER.debug("[RSI-Embers-Infer] Init OK: recipe={} aspects={} inputs={} combos={} firstGuess={}",
                recipeId, aspectsSize, inputsSize, allCombinations.size(),
                Arrays.toString(currentGuess));

        // Notify player that trial-and-error inference is starting
        if (player != null) {
            player.displayClientMessage(Component.translatable("rsi.embers.infer.started",
                    allCombinations.size(), MAX_ATTEMPTS), true);
        }
        return true;
    }

    @Override
    @Nullable
    public List<IngredientSpec> getRequiredMaterials() {
        return firstAttemptSpecs;
    }

    @Override
    public boolean tryStartSingleCraft(ServerPlayer player) {
        return false; // chain path only
    }

    @Override
    public boolean tryStartWithMaterials(ServerPlayer player, List<ItemStack> materials,
                                         ExtractionLedger sharedLedger) {
        if (tablet == null || pedestals == null || currentGuess == null) return false;
        if (!tablet.outputHandler.getStackInSlot(0).isEmpty()) {
            RSIntegrationMod.LOGGER.warn("[RSI-Embers-Infer] Tablet output slot not empty");
            player.sendSystemMessage(Component.translatable("rsi.embers.error.tablet_output_occupied"));
            return false;
        }

        if (!placeMaterialsAndSpark(materials)) return false;

        attemptCount++;
        waitStartTick = level.getServer().getTickCount();
        phase = Phase.PLACED;
        RSIntegrationMod.LOGGER.debug("[RSI-Embers-Infer] Attempt {} started: guess={}",
                attemptCount, Arrays.toString(currentGuess));
        return true;
    }

    @Override
    public boolean isCraftComplete(ServerLevel level) {
        if (tablet == null || tablet.isRemoved()) {
            phase = Phase.DONE_FAILED;
            return true;
        }
        if (phase == Phase.DONE_SUCCESS || phase == Phase.DONE_FAILED) return true;
        if (phase == Phase.INIT) return false;

        // serverTick increments progress each tick until 40, then processes
        // the recipe and resets progress to 0. Result goes to IBin (below)
        // or tablet inventory. Polling outputHandler alone misses the IBin path.
        if (phase == Phase.PLACED || phase == Phase.WAITING) {
            if (tablet.progress > 0) {
                phase = Phase.WAITING;
                return false; // still processing
            }

            // progress == 0 → craft finished (or spark was rejected)
            int elapsed = level.getServer().getTickCount() - waitStartTick;
            if (elapsed < 10) return false; // too soon, wait a tick

            // Look for result in tablet output slot or IBin below
            ItemStack result = findResultAnywhere();
            if (result.isEmpty()) {
                // No result found yet — could still be in transit
                if (elapsed > PROGRESS_TIMEOUT_TICKS) {
                    RSIntegrationMod.LOGGER.warn("[RSI-Embers-Infer] Timeout waiting for alchemy result");
                    phase = Phase.DONE_FAILED;
                    return true;
                }
                return false;
            }

            // Process result
            return processResult(result);
        }
        return false;
    }

    /** Search for the alchemy result in both the tablet output slot and IBin below. */
    private ItemStack findResultAnywhere() {
        ItemStack r = tablet.outputHandler.getStackInSlot(0);
        if (!r.isEmpty()) return r;

        BlockPos below = machinePos.below();
        if (!level.isLoaded(below)) level.getChunk(below);
        BlockEntity be = level.getBlockEntity(below);
        if (be instanceof com.rekindled.embers.api.tile.IBin bin) {
            r = bin.getInventory().getStackInSlot(0);
            if (!r.isEmpty()) return r;
        }
        return ItemStack.EMPTY;
    }

    private boolean processResult(ItemStack result) {
        ItemStack expectedOutput = getRecipeOutput();
        boolean isSuccess = !expectedOutput.isEmpty() && ItemStack.isSameItem(result, expectedOutput);
        if (isSuccess) {
            successResult = result.copy();
            succeeded = true;
            phase = Phase.DONE_SUCCESS;

            long seed = level.getSeed();
            int[] codeIndices = currentGuess.clone();
            KnownCodeSavedData.get(level).putCode(recipe.getId().toString(), codeIndices);
            clearProgress(recipe.getId().toString());

            RSIntegrationMod.LOGGER.debug("[RSI-Embers-Infer] SUCCESS after {} attempts: code={}",
                    attemptCount, Arrays.toString(codeIndices));
            if (player != null) {
                player.displayClientMessage(Component.translatable("rsi.embers.infer.success", attemptCount), true);
            }
            return true;
        }

        // Failure — read feedback NBT
        int blackPins = 0;
        int whitePins = 0;
        CompoundTag nbt = result.getTag();
        if (nbt != null) {
            blackPins = nbt.getInt("blackPins");
            whitePins = nbt.getInt("whitePins");
        }

        RSIntegrationMod.LOGGER.debug("[RSI-Embers-Infer] Attempt {} FAIL: blackPins={} whitePins={} guess={}",
                attemptCount, blackPins, whitePins, Arrays.toString(currentGuess));

        if (player != null) {
            player.displayClientMessage(Component.translatable("rsi.embers.infer.progress",
                    attemptCount, blackPins, whitePins, candidates.size()), true);
        }

        // Remove failure item from output slot
        tablet.outputHandler.extractItem(0, 64, false);
        tablet.setChanged();

        // Stop-loss: consecutive zero blackPins
        if (blackPins == 0) {
            consecutiveZeroBlackPins++;
            if (consecutiveZeroBlackPins >= CONSECUTIVE_ZERO_BLACK_LIMIT) {
                RSIntegrationMod.LOGGER.warn("[RSI-Embers-Infer] {} consecutive 0-blackPin, aborting",
                        consecutiveZeroBlackPins);
                if (player != null) {
                    player.displayClientMessage(Component.translatable("rsi.embers.infer.failed_zero_black",
                            CONSECUTIVE_ZERO_BLACK_LIMIT), true);
                }
                clearAndRefundSurvivors();
                phase = Phase.DONE_FAILED;
                return true;
            }
        } else {
            consecutiveZeroBlackPins = 0;
        }

        // Max attempts
        if (attemptCount >= MAX_ATTEMPTS) {
            RSIntegrationMod.LOGGER.warn("[RSI-Embers-Infer] Max attempts {} reached", MAX_ATTEMPTS);
            if (player != null) {
                player.displayClientMessage(Component.translatable("rsi.embers.infer.failed_max", MAX_ATTEMPTS), true);
            }
            clearAndRefundSurvivors();
            phase = Phase.DONE_FAILED;
            return true;
        }

        // Clear and refund surviving pedestal items
        clearAndRefundSurvivors();

        // Filter candidates
        filterCandidates(blackPins, whitePins);
        if (candidates.isEmpty()) {
            RSIntegrationMod.LOGGER.warn("[RSI-Embers-Infer] No candidates remain after filtering");
            if (player != null) {
                player.displayClientMessage(Component.translatable("rsi.embers.infer.failed_no_candidates"), true);
            }
            clearAndRefundSurvivors();
            phase = Phase.DONE_FAILED;
            return true;
        }

        // Select next guess
        if (candidates.size() == 1) {
            currentGuess = candidates.get(0);
        } else {
            currentGuess = selectNextGuess();
        }
        if (currentGuess == null) {
            clearAndRefundSurvivors();
            phase = Phase.DONE_FAILED;
            return true;
        }

        // Extract fresh materials, place, spark for next attempt
        if (!extractAndSparkNext()) {
            phase = Phase.DONE_FAILED;
            return true;
        }

        saveProgress();
        return false; // keep waiting
    }

    @Override
    public ItemStack collectResult(ServerPlayer player) {
        EreAlchemyLock.unlock(level.dimension(), machinePos);
        if (succeeded && !successResult.isEmpty()) {
            ItemStack out = successResult.copy();
            successResult = ItemStack.EMPTY;

            // Clear result from wherever it ended up (tablet output or IBin)
            ItemStack cleared = tablet.outputHandler.extractItem(0, 64, false);
            if (cleared.isEmpty()) {
                // Result may be in IBin below
                BlockPos below = machinePos.below();
                if (!level.isLoaded(below)) level.getChunk(below);
                BlockEntity be = level.getBlockEntity(below);
                if (be instanceof com.rekindled.embers.api.tile.IBin bin) {
                    bin.getInventory().extractItem(0, 64, false);
                }
            }
            tablet.setChanged();

            // Clear any surviving pedestal items (aspects/inputs) and refund to RS.
            // Embers should have consumed them on success, but as defense-in-depth
            // we recover anything that survived — otherwise the next craft fails
            // because validateAndInit finds occupied pedestals.
            clearAndRefundSurvivors();

            return out;
        }
        return ItemStack.EMPTY;
    }

    @Override
    public void onBatchFailed(ServerPlayer player, String reason) {
        EreAlchemyLock.unlock(level.dimension(), machinePos);
        clearAndRefundSurvivors();
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

    // ── combination generation ────────────────────────────────────

    private void generateAllCombinations() {
        allCombinations = new ArrayList<>();
        int[] buf = new int[inputsSize];
        generateRecursive(0, buf);
    }

    private void generateRecursive(int pos, int[] buf) {
        if (pos == inputsSize) {
            allCombinations.add(buf.clone());
            return;
        }
        for (int a = 0; a < aspectsSize; a++) {
            buf[pos] = a;
            generateRecursive(pos + 1, buf);
        }
    }

    // ── guess selection ───────────────────────────────────────────

    private int[] selectInitialGuess() {
        int[] guess = new int[inputsSize];
        for (int i = 0; i < inputsSize; i++) {
            guess[i] = i % aspectsSize;
        }
        shuffle(guess);
        return guess;
    }

    @Nullable
    private int[] selectNextGuess() {
        if (candidates.size() > MINIMAX_THRESHOLD) {
            return candidates.get(new Random().nextInt(candidates.size()));
        }
        return selectMinimax();
    }

    @Nullable
    private int[] selectMinimax() {
        int bestScore = Integer.MAX_VALUE;
        int[] bestCandidate = null;

        for (int[] c : candidates) {
            Map<Long, Integer> partitions = new HashMap<>();
            for (int[] r : candidates) {
                var s = matchStats(c, r);
                long key = ((long) s.correct << 32) | (s.valueOnly & 0xFFFFFFFFL);
                partitions.merge(key, 1, Integer::sum);
            }
            int worst = 0;
            for (int v : partitions.values()) {
                if (v > worst) worst = v;
            }
            if (worst < bestScore) {
                bestScore = worst;
                bestCandidate = c;
                if (bestScore == 1) break;
            }
        }
        return bestCandidate;
    }

    private record MatchStats(int correct, int valueOnly) {}

    private static MatchStats matchStats(int[] guess, int[] target) {
        int correct = 0, valueOnly = 0;
        boolean[] gu = new boolean[guess.length];
        boolean[] tu = new boolean[target.length];

        for (int i = 0; i < guess.length; i++) {
            if (guess[i] == target[i]) { correct++; gu[i] = tu[i] = true; }
        }
        for (int i = 0; i < guess.length; i++) {
            if (gu[i]) continue;
            for (int j = 0; j < target.length; j++) {
                if (tu[j]) continue;
                if (guess[i] == target[j]) { valueOnly++; tu[j] = true; break; }
            }
        }
        return new MatchStats(correct, valueOnly);
    }

    private void filterCandidates(int blackPins, int whitePins) {
        List<int[]> filtered = new ArrayList<>();
        for (int[] c : candidates) {
            var s = matchStats(currentGuess, c);
            if (s.correct == blackPins && s.valueOnly == whitePins) {
                filtered.add(c);
            }
        }
        int before = candidates.size();
        candidates = filtered;
        RSIntegrationMod.LOGGER.debug("[RSI-Embers-Infer] Filtered {}→{} (black={} white={})",
                before, candidates.size(), blackPins, whitePins);
    }

    // ── material placement / extraction ───────────────────────────

    private void buildFirstAttemptSpecs() {
        firstAttemptSpecs = new ArrayList<>();
        firstAttemptSpecs.add(new IngredientSpec(recipe.tablet, 1));
        for (int i = 0; i < inputsSize; i++) {
            firstAttemptSpecs.add(new IngredientSpec(aspects.get(currentGuess[i]), 1));
            firstAttemptSpecs.add(new IngredientSpec(inputs.get(i), 1));
        }
    }

    private boolean placeMaterialsAndSpark(List<ItemStack> materials) {
        int idx = 0;
        try {
            tablet.inventory.setStackInSlot(0, materials.get(idx++));
            tablet.setChanged();

            for (int i = 0; i < inputsSize; i++) {
                AlchemyPedestalTopBlockEntity top = pedestals.get(i);

                // Place aspect on BOTTOM pedestal, input on TOP.
                // Embers' PedestalContents(bottomStack, topStack) treats bottom as aspect.
                BlockPos bottomPos = top.getBlockPos().below();
                if (!level.isLoaded(bottomPos)) level.getChunk(bottomPos);
                BlockEntity be = level.getBlockEntity(bottomPos);
                if (be instanceof AlchemyPedestalBlockEntity bottom) {
                    bottom.inventory.setStackInSlot(0, materials.get(idx++));
                    bottom.setChanged();
                } else {
                    RSIntegrationMod.LOGGER.error("[RSI-Embers-Infer] No bottom pedestal at {}", bottomPos);
                    if (player != null) player.sendSystemMessage(Component.translatable("rsi.embers.error.bottom_pedestal_missing"));
                    clearAndRefundSurvivors();
                    return false;
                }

                top.inventory.setStackInSlot(0, materials.get(idx++));
                top.setChanged();
            }

            // sparkStrength must be >= 1000.0 otherwise sparkProgress returns immediately.
            tablet.sparkProgress(tablet, 1000.0);
            RSIntegrationMod.LOGGER.debug("[RSI-Embers-Infer] Sparked tablet, progress={}", tablet.progress);
            return true;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Embers-Infer] Placement failed", e);
            if (player != null) player.sendSystemMessage(Component.translatable("rsi.embers.error.placement_failed"));
            clearAndRefundSurvivors();
            return false;
        }
    }

    private boolean extractAndSparkNext() {
        List<ItemStack> materials = new ArrayList<>();

        ItemStack t = extractOne(recipe.tablet);
        if (t.isEmpty()) { refundPartial(materials); return false; }
        materials.add(t);

        for (int i = 0; i < inputsSize; i++) {
            ItemStack a = extractOne(aspects.get(currentGuess[i]));
            if (a.isEmpty()) { refundPartial(materials); return false; }
            materials.add(a);

            ItemStack in = extractOne(inputs.get(i));
            if (in.isEmpty()) { refundPartial(materials); return false; }
            materials.add(in);
        }

        boolean ok = placeMaterialsAndSpark(materials);
        if (ok) {
            attemptCount++;
            waitStartTick = level.getServer().getTickCount();
        }
        return ok;
    }

    private ItemStack extractOne(Ingredient ing) {
        if (network == null) return ItemStack.EMPTY;
        return RSIntegration.extractFromNetwork(network, ing, 1);
    }

    private void refundPartial(List<ItemStack> stacks) {
        if (network == null) return;
        for (ItemStack s : stacks) {
            if (s.isEmpty()) continue;
            var tracker = network.getItemStorageTracker();
            if (tracker != null && player != null) tracker.changed(player, s.copy());
            ItemStack leftover = network.insertItem(s, s.getCount(), Action.PERFORM);
            if (!leftover.isEmpty()) {
                RSIntegrationMod.LOGGER.warn("[RSI-Embers-Infer] Refund leftover: {} x{}",
                        leftover.getHoverName().getString(), leftover.getCount());
            }
        }
    }

    /** Clear surviving items from pedestals/tablet after a failed attempt and refund them. */
    private void clearAndRefundSurvivors() {
        List<ItemStack> toRefund = new ArrayList<>();

        if (tablet != null && !tablet.isRemoved()) {
            ItemStack s = tablet.inventory.getStackInSlot(0);
            if (!s.isEmpty()) { toRefund.add(s.copy()); tablet.inventory.setStackInSlot(0, ItemStack.EMPTY); tablet.setChanged(); }
        }

        if (pedestals != null) {
            for (AlchemyPedestalTopBlockEntity top : pedestals) {
                if (top.isRemoved()) continue;
                ItemStack as = top.inventory.getStackInSlot(0);
                if (!as.isEmpty()) { toRefund.add(as.copy()); top.inventory.setStackInSlot(0, ItemStack.EMPTY); top.setChanged(); }

                BlockPos bp = top.getBlockPos().below();
                if (!level.isLoaded(bp)) continue;
                BlockEntity be = level.getBlockEntity(bp);
                if (be instanceof AlchemyPedestalBlockEntity bot && !bot.isRemoved()) {
                    ItemStack is = bot.inventory.getStackInSlot(0);
                    if (!is.isEmpty()) { toRefund.add(is.copy()); bot.inventory.setStackInSlot(0, ItemStack.EMPTY); bot.setChanged(); }
                }
            }
        }

        if (network != null && !toRefund.isEmpty()) {
            for (ItemStack s : toRefund) {
                if (s.isEmpty()) continue;
                var tracker = network.getItemStorageTracker();
                if (tracker != null && player != null) tracker.changed(player, s.copy());
                ItemStack leftover = network.insertItem(s, s.getCount(), Action.PERFORM);
                if (!leftover.isEmpty()) {
                    RSIntegrationMod.LOGGER.debug("[RSI-Embers-Infer] Survivor refund partial: {} x{}",
                            leftover.getHoverName().getString(), leftover.getCount());
                }
            }
        }
    }

    private void clearAllSlots() {
        if (tablet != null && !tablet.isRemoved()) {
            tablet.inventory.setStackInSlot(0, ItemStack.EMPTY);
            tablet.setChanged();
        }
        if (pedestals != null && level != null) {
            for (AlchemyPedestalTopBlockEntity top : pedestals) {
                if (top.isRemoved()) continue;
                top.inventory.setStackInSlot(0, ItemStack.EMPTY);
                top.setChanged();
                BlockPos bp = top.getBlockPos().below();
                if (!level.isLoaded(bp)) continue;
                BlockEntity be = level.getBlockEntity(bp);
                if (be instanceof AlchemyPedestalBlockEntity bot && !bot.isRemoved()) {
                    bot.inventory.setStackInSlot(0, ItemStack.EMPTY);
                    bot.setChanged();
                }
            }
        }
    }

    // ── helpers ───────────────────────────────────────────────────

    private ItemStack getRecipeOutput() {
        return com.huanghuang.rsintegration.recipe.ModRecipeHandlers
                .tryGetResultItem(recipe, level.registryAccess());
    }

    @SuppressWarnings("unchecked")
    @Nullable
    private static List<Ingredient> readIngredientList(AlchemyRecipe recipe, String fieldName) {
        try {
            var f = Reflect.findField(recipe.getClass(), fieldName);
            if (f.isPresent()) {
                f.get().setAccessible(true);
                return (List<Ingredient>) f.get().get(recipe);
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Embers-Infer] Cannot read {}: {}", fieldName, e.toString());
        }
        return null;
    }

    private static long pow(int base, int exp) {
        long r = 1;
        for (int i = 0; i < exp; i++) {
            r *= base;
            if (r > MAX_COMBINATIONS) return r;
        }
        return r;
    }

    private static void shuffle(int[] arr) {
        Random rng = new Random();
        for (int i = arr.length - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            int tmp = arr[i];
            arr[i] = arr[j];
            arr[j] = tmp;
        }
    }
}
