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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class MachineFavoritesSavedData extends SavedData {
    static final String NAME = "rsi_machine_favorites";
    public static final int MAX_FAVORITES = 8;

    private final Map<UUID, List<MachineFavoriteKey>> byPlayer = new LinkedHashMap<>();

    MachineFavoritesSavedData() {}

    public static MachineFavoritesSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                MachineFavoritesSavedData::load, MachineFavoritesSavedData::new, NAME);
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag players = new ListTag();
        for (Map.Entry<UUID, List<MachineFavoriteKey>> playerEntry : byPlayer.entrySet()) {
            if (playerEntry.getValue().isEmpty()) continue;
            CompoundTag encodedPlayer = new CompoundTag();
            encodedPlayer.putUUID("player", playerEntry.getKey());
            ListTag favorites = new ListTag();
            for (MachineFavoriteKey key : playerEntry.getValue()) favorites.add(key.save());
            encodedPlayer.put("favorites", favorites);
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
        List<MachineFavoriteKey> favorites = byPlayer.get(playerId);
        if (favorites == null || !favorites.remove(key)) return false;
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
        if (favorites == null) return false;
        boolean removed = favorites.removeIf(favorite -> favorite.dimension().equals(dimension)
                && favorite.pos().equals(pos));
        if (!removed) return false;
        if (favorites.isEmpty()) byPlayer.remove(playerId);
        setDirty();
        return true;
    }

    public enum ToggleResult {
        ADDED,
        REMOVED,
        LIMIT_REACHED
    }
}
