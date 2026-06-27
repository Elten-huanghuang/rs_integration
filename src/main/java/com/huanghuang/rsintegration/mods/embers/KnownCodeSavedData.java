package com.huanghuang.rsintegration.mods.embers;

import com.huanghuang.rsintegration.RSIntegrationMod;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persistent cache for Embers alchemy recipe codes.
 *
 * <p>Since {@code code = computeCode(worldSeed, recipeId)} is deterministic,
 * computed codes are cached here and persisted to disk via
 * {@link SavedData} so they survive server restarts.
 *
 * <p>Codes are keyed by recipe ID string and invalidated when the world seed
 * changes (new world / different save).
 */
public final class KnownCodeSavedData extends SavedData {

    private static final String NAME = "rsi_embers_codes";

    private final Map<String, int[]> codes = new ConcurrentHashMap<>();
    private long worldSeed;

    private KnownCodeSavedData() {}

    private KnownCodeSavedData(long seed) {
        this.worldSeed = seed;
    }

    // ── SavedData API ──────────────────────────────────────────────

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putLong("worldSeed", worldSeed);
        ListTag entries = new ListTag();
        for (var e : codes.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putString("recipeId", e.getKey());
            entry.putIntArray("code", e.getValue());
            entries.add(entry);
        }
        tag.put("entries", entries);
        return tag;
    }

    private static KnownCodeSavedData load(CompoundTag tag) {
        KnownCodeSavedData data = new KnownCodeSavedData();
        data.worldSeed = tag.getLong("worldSeed");
        ListTag entries = tag.getList("entries", Tag.TAG_COMPOUND);
        for (int i = 0; i < entries.size(); i++) {
            CompoundTag entry = entries.getCompound(i);
            String recipeId = entry.getString("recipeId");
            int[] code = entry.getIntArray("code");
            if (!recipeId.isEmpty() && code.length > 0) {
                data.codes.put(recipeId, code);
            }
        }
        RSIntegrationMod.LOGGER.debug("[RSI-Embers] Loaded {} cached alchemy codes (seed={})",
                data.codes.size(), data.worldSeed);
        return data;
    }

    // ── singleton access ───────────────────────────────────────────

    public static KnownCodeSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                KnownCodeSavedData::load, KnownCodeSavedData::new, NAME);
    }

    // ── public API ─────────────────────────────────────────────────

    @Nullable
    public int[] getCode(String recipeId) {
        return codes.get(recipeId);
    }

    public boolean hasCode(String recipeId) {
        return codes.containsKey(recipeId);
    }

    public void putCode(String recipeId, int[] code) {
        codes.put(recipeId, code);
        setDirty();
        RSIntegrationMod.LOGGER.debug("[RSI-Embers] Cached code for {} ({} pedestals, {} total)",
                recipeId, code.length, codes.size());
    }

    public long getWorldSeed() {
        return worldSeed;
    }

    public void setWorldSeed(long seed) {
        if (this.worldSeed != seed) {
            codes.clear();
            this.worldSeed = seed;
            setDirty();
            RSIntegrationMod.LOGGER.debug("[RSI-Embers] World seed changed to {}, cache cleared", seed);
        }
    }

    public int size() {
        return codes.size();
    }

    public void clearAll() {
        codes.clear();
        setDirty();
        RSIntegrationMod.LOGGER.debug("[RSI-Embers] Code cache manually cleared");
    }
}
