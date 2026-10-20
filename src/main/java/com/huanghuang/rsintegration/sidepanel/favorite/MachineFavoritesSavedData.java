package com.huanghuang.rsintegration.sidepanel.favorite;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class MachineFavoritesSavedData extends SavedData {
    static final String NAME = "rsi_machine_favorites";
    public static final int MAX_FAVORITES = 64;
    public static final int MAX_BLOCKED = 4096;

    private final Map<UUID, List<MachineFavoriteKey>> byPlayer = new LinkedHashMap<>();
    private final Map<UUID, Set<MachineFavoriteKey>> blockedByPlayer = new LinkedHashMap<>();

    MachineFavoritesSavedData() {}

    public static MachineFavoritesSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                MachineFavoritesSavedData::load, MachineFavoritesSavedData::new, NAME);
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag players = new ListTag();
        Set<UUID> playerIds = new LinkedHashSet<>(byPlayer.keySet());
        playerIds.addAll(blockedByPlayer.keySet());
        for (UUID playerId : playerIds) {
            List<MachineFavoriteKey> favorites = byPlayer.getOrDefault(playerId, List.of());
            Set<MachineFavoriteKey> blocked = blockedByPlayer.getOrDefault(playerId, Set.of());
            if (favorites.isEmpty() && blocked.isEmpty()) continue;
            CompoundTag encodedPlayer = new CompoundTag();
            encodedPlayer.putUUID("player", playerId);
            ListTag encodedFavorites = new ListTag();
            for (MachineFavoriteKey key : favorites) encodedFavorites.add(key.save());
            encodedPlayer.put("favorites", encodedFavorites);
            ListTag encodedBlocked = new ListTag();
            for (MachineFavoriteKey key : blocked) encodedBlocked.add(key.save());
            encodedPlayer.put("blocked", encodedBlocked);
            players.add(encodedPlayer);
        }
        tag.put("players", players);
        return tag;
    }

    static MachineFavoritesSavedData load(CompoundTag tag) {
        MachineFavoritesSavedData data = new MachineFavoritesSavedData();
        ListTag players = tag.getList("players", Tag.TAG_COMPOUND);
        for (int playerIndex = 0; playerIndex < players.size(); playerIndex++) {
            CompoundTag encodedPlayer = players.getCompound(playerIndex);
            if (!encodedPlayer.hasUUID("player")) continue;
            UUID playerId = encodedPlayer.getUUID("player");
            ListTag encodedFavorites = encodedPlayer.getList("favorites", Tag.TAG_COMPOUND);
            List<MachineFavoriteKey> favorites = new ArrayList<>(MAX_FAVORITES);
            Set<MachineFavoriteKey> unique = new HashSet<>();
            for (int index = 0; index < encodedFavorites.size()
                    && favorites.size() < MAX_FAVORITES; index++) {
                MachineFavoriteKey key = MachineFavoriteKey.load(encodedFavorites.getCompound(index));
                if (key != null && unique.add(key)) favorites.add(key);
            }
            if (!favorites.isEmpty()) data.byPlayer.put(playerId, favorites);
            ListTag encodedBlocked = encodedPlayer.getList("blocked", Tag.TAG_COMPOUND);
            Set<MachineFavoriteKey> blocked = new LinkedHashSet<>();
            for (int index = 0; index < encodedBlocked.size(); index++) {
                MachineFavoriteKey key = MachineFavoriteKey.load(encodedBlocked.getCompound(index));
                if (key != null) blocked.add(key);
            }
            if (!blocked.isEmpty()) data.blockedByPlayer.put(playerId, blocked);
        }
        return data;
    }

    public List<MachineFavoriteKey> getFavorites(UUID playerId) {
        List<MachineFavoriteKey> favorites = byPlayer.get(playerId);
        return favorites == null ? List.of() : List.copyOf(favorites);
    }

    public ToggleResult toggle(UUID playerId, MachineFavoriteKey key) {
        List<MachineFavoriteKey> favorites = byPlayer.computeIfAbsent(
                playerId, ignored -> new ArrayList<>(MAX_FAVORITES));
        if (favorites.remove(key)) {
            if (favorites.isEmpty()) byPlayer.remove(playerId);
            setDirty();
            return ToggleResult.REMOVED;
        }
        if (favorites.size() >= MAX_FAVORITES) return ToggleResult.LIMIT_REACHED;
        favorites.add(key);
        setDirty();
        return ToggleResult.ADDED;
    }

    public boolean remove(UUID playerId, MachineFavoriteKey key) {
        boolean removedBlocked = removeBlocked(playerId, key);
        List<MachineFavoriteKey> favorites = byPlayer.get(playerId);
        if (favorites == null || !favorites.remove(key)) return removedBlocked;
        if (favorites.isEmpty()) byPlayer.remove(playerId);
        setDirty();
        return true;
    }

    /**
     * A machine binding is identified by its dimension and position. The
     * display key may change when a mod updates or a legacy binding migrates,
     * so unbinding must not leave an old favorite behind on that basis.
     */
    public boolean removeAt(UUID playerId, ResourceLocation dimension, BlockPos pos) {
        List<MachineFavoriteKey> favorites = byPlayer.get(playerId);
        boolean removed = favorites != null && favorites.removeIf(favorite ->
                favorite.dimension().equals(dimension) && favorite.pos().equals(pos));
        if (removed && favorites.isEmpty()) byPlayer.remove(playerId);
        Set<MachineFavoriteKey> blocked = blockedByPlayer.get(playerId);
        boolean removedBlocked = blocked != null && blocked.removeIf(key ->
                key.dimension().equals(dimension) && key.pos().equals(pos));
        if (blocked != null && blocked.isEmpty()) blockedByPlayer.remove(playerId);
        if (removed || removedBlocked) {
            setDirty();
            return true;
        }
        return false;
    }

    public List<MachineFavoriteKey> getBlocked(UUID playerId) {
        Set<MachineFavoriteKey> blocked = blockedByPlayer.get(playerId);
        return blocked == null ? List.of() : List.copyOf(blocked);
    }

    public boolean isBlocked(UUID playerId, MachineFavoriteKey key) {
        Set<MachineFavoriteKey> blocked = blockedByPlayer.get(playerId);
        return blocked != null && blocked.contains(key);
    }

    public ToggleResult toggleBlocked(UUID playerId, MachineFavoriteKey key) {
        Set<MachineFavoriteKey> blocked = blockedByPlayer.computeIfAbsent(
                playerId, ignored -> new LinkedHashSet<>());
        if (blocked.remove(key)) {
            if (blocked.isEmpty()) blockedByPlayer.remove(playerId);
            setDirty();
            return ToggleResult.REMOVED;
        }
        blocked.add(key);
        setDirty();
        return ToggleResult.ADDED;
    }

    private boolean removeBlocked(UUID playerId, MachineFavoriteKey key) {
        Set<MachineFavoriteKey> blocked = blockedByPlayer.get(playerId);
        if (blocked == null || !blocked.remove(key)) return false;
        if (blocked.isEmpty()) blockedByPlayer.remove(playerId);
        setDirty();
        return true;
    }

    public enum ToggleResult {
        ADDED,
        REMOVED,
        LIMIT_REACHED
    }
}
