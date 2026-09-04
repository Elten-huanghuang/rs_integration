package com.huanghuang.rsintegration.mods.sophisticatedbackpacks;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.AccessLogRecord;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackStorage;
import net.p3pp3rf1y.sophisticatedcore.api.IStorageWrapper;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Persistent player identity used when a placed backpack runs upgrades without an entity. */
public final class BackpackOperationOwner {
    public static final String OWNER_UUID_TAG = "RSIOwnerUUID";
    public static final String OWNER_NAME_TAG = "RSIOwnerName";
    private static final String FALLBACK_NAME = "RSI_Backpack";

    private BackpackOperationOwner() {}

    public static void write(CompoundTag tag, GameProfile profile) {
        if (tag == null || profile == null || profile.getId() == null) return;
        tag.putUUID(OWNER_UUID_TAG, profile.getId());
        tag.putString(OWNER_NAME_TAG, safeName(profile.getName()));
    }

    public static Optional<GameProfile> read(CompoundTag tag) {
        if (tag == null || !tag.hasUUID(OWNER_UUID_TAG)) return Optional.empty();
        return Optional.of(new GameProfile(tag.getUUID(OWNER_UUID_TAG),
                safeName(tag.getString(OWNER_NAME_TAG))));
    }

    /** Recovers old upgrades from Sophisticated Backpacks' persistent access log. */
    public static Optional<GameProfile> resolve(CompoundTag tag, IStorageWrapper storageWrapper,
                                                MinecraftServer server) {
        Optional<GameProfile> stored = read(tag);
        if (stored.isPresent() || storageWrapper == null || server == null) return stored;
        Optional<UUID> contentsUuid = storageWrapper.getContentsUuid();
        if (contentsUuid.isEmpty()) return Optional.empty();
        Map<UUID, AccessLogRecord> accessLogs = BackpackStorage.get().getAccessLogs();
        AccessLogRecord accessLog = accessLogs.get(contentsUuid.orElseThrow());
        if (accessLog == null || accessLog.getPlayerName() == null
                || accessLog.getPlayerName().isBlank()) {
            return Optional.empty();
        }
        return server.getProfileCache().get(accessLog.getPlayerName());
    }

    public static ServerPlayer createOfflinePlayer(Level level, BlockPos pos, GameProfile profile) {
        if (!(level instanceof ServerLevel serverLevel) || profile == null || profile.getId() == null) {
            return null;
        }
        FakePlayer player = FakePlayerFactory.get(serverLevel,
                new GameProfile(profile.getId(), safeName(profile.getName())));
        player.setPos(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
        return player;
    }

    private static String safeName(String name) {
        if (name == null || !name.matches("[A-Za-z0-9_]{1,16}")) return FALLBACK_NAME;
        return name;
    }
}
