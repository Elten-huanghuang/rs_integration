package com.huanghuang.rsintegration.mods.embers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** Persistent per-player state for interrupted Embers alchemy inference. */
public final class EreAlchemyProgressSavedData extends SavedData {
    private static final String NAME = "rsi_embers_inference_progress";

    private final Map<ProgressKey, Progress> progressByRecipe = new HashMap<>();

    EreAlchemyProgressSavedData() {}

    public static EreAlchemyProgressSavedData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(
                EreAlchemyProgressSavedData::load, EreAlchemyProgressSavedData::new, NAME);
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag entries = new ListTag();
        for (Map.Entry<ProgressKey, Progress> mapEntry : progressByRecipe.entrySet()) {
            ProgressKey key = mapEntry.getKey();
            Progress progress = mapEntry.getValue();
            CompoundTag entry = new CompoundTag();
            entry.putUUID("player", key.playerId());
            entry.putString("recipeId", key.recipeId());
            entry.putInt("attemptCount", progress.attemptCount());
            entry.putInt("consecutiveZeroBlackPins", progress.consecutiveZeroBlackPins());
            entry.putInt("aspectsSize", progress.aspectsSize());
            entry.putInt("inputsSize", progress.inputsSize());
            entry.putIntArray("currentGuess", progress.currentGuess());

            List<int[]> candidateRows = progress.candidates();
            int[] candidates = new int[candidateRows.size() * progress.inputsSize()];
            for (int row = 0; row < candidateRows.size(); row++) {
                System.arraycopy(candidateRows.get(row), 0, candidates,
                        row * progress.inputsSize(), progress.inputsSize());
            }
            entry.putInt("candidateCount", candidateRows.size());
            entry.putIntArray("candidates", candidates);
            entries.add(entry);
        }
        tag.put("entries", entries);
        return tag;
    }

    static EreAlchemyProgressSavedData load(CompoundTag tag) {
        EreAlchemyProgressSavedData data = new EreAlchemyProgressSavedData();
        ListTag entries = tag.getList("entries", Tag.TAG_COMPOUND);
        for (int i = 0; i < entries.size(); i++) {
            CompoundTag entry = entries.getCompound(i);
            if (!entry.hasUUID("player")) continue;
            UUID playerId = entry.getUUID("player");
            String recipeId = entry.getString("recipeId");
            int aspectsSize = entry.getInt("aspectsSize");
            int inputsSize = entry.getInt("inputsSize");
            int[] currentGuess = entry.getIntArray("currentGuess");
            if (recipeId.isEmpty() || aspectsSize <= 0 || inputsSize <= 0
                    || !validGuess(currentGuess, aspectsSize, inputsSize)) {
                continue;
            }

            int candidateCount = entry.getInt("candidateCount");
            int[] flattenedCandidates = entry.getIntArray("candidates");
            if (candidateCount <= 0
                    || (long) candidateCount * inputsSize != flattenedCandidates.length) continue;
            List<int[]> candidates = new ArrayList<>(candidateCount);
            boolean candidatesValid = true;
            for (int row = 0; row < candidateCount; row++) {
                int[] candidate = new int[inputsSize];
                System.arraycopy(flattenedCandidates, row * inputsSize,
                        candidate, 0, inputsSize);
                if (!validGuess(candidate, aspectsSize, inputsSize)) {
                    candidatesValid = false;
                    break;
                }
                candidates.add(candidate);
            }
            if (!candidatesValid) continue;

            Progress progress = new Progress(
                    candidates,
                    currentGuess,
                    Math.max(0, entry.getInt("attemptCount")),
                    Math.max(0, entry.getInt("consecutiveZeroBlackPins")),
                    aspectsSize,
                    inputsSize);
            data.progressByRecipe.put(new ProgressKey(playerId, recipeId), progress);
        }
        return data;
    }

    private static boolean validGuess(int[] guess, int aspectsSize, int inputsSize) {
        if (guess.length != inputsSize) return false;
        for (int aspect : guess) {
            if (aspect < 0 || aspect >= aspectsSize) return false;
        }
        return true;
    }

    @Nullable
    public Progress getProgress(UUID playerId, String recipeId) {
        Progress progress = progressByRecipe.get(new ProgressKey(playerId, recipeId));
        return progress == null ? null : progress.copy();
    }

    public void putProgress(UUID playerId, String recipeId, Progress progress) {
        progressByRecipe.put(new ProgressKey(playerId, recipeId), progress.copy());
        setDirty();
    }

    public void removeProgress(UUID playerId, String recipeId) {
        if (progressByRecipe.remove(new ProgressKey(playerId, recipeId)) != null) {
            setDirty();
        }
    }

    public int size() {
        return progressByRecipe.size();
    }

    public void clearAll() {
        if (progressByRecipe.isEmpty()) return;
        progressByRecipe.clear();
        setDirty();
    }

    private record ProgressKey(UUID playerId, String recipeId) {}

    public record Progress(List<int[]> candidates, int[] currentGuess, int attemptCount,
                           int consecutiveZeroBlackPins, int aspectsSize, int inputsSize) {
        public Progress {
            candidates = copyCandidates(candidates);
            currentGuess = currentGuess.clone();
        }

        @Override
        public List<int[]> candidates() {
            return copyCandidates(candidates);
        }

        @Override
        public int[] currentGuess() {
            return currentGuess.clone();
        }

        Progress copy() {
            return new Progress(candidates, currentGuess, attemptCount,
                    consecutiveZeroBlackPins, aspectsSize, inputsSize);
        }

        private static List<int[]> copyCandidates(List<int[]> candidates) {
            List<int[]> copy = new ArrayList<>(candidates.size());
            for (int[] candidate : candidates) copy.add(candidate.clone());
            return List.copyOf(copy);
        }
    }
}
