package com.huanghuang.rsintegration.mods.rs.recentsearch;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.fml.loading.FMLPaths;

import javax.annotation.Nullable;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

final class RecentSearchScope {
    private RecentSearchScope() {}

    @Nullable
    static Path historyPath(Minecraft minecraft) {
        if (minecraft.player == null) return null;
        String scope = scopeKey(minecraft);
        if (scope == null) return null;
        return FMLPaths.CONFIGDIR.get()
                .resolve("rs_integration")
                .resolve("recent-search")
                .resolve(hashScope(scope))
                .resolve(minecraft.player.getUUID() + ".json");
    }

    static String hashScope(String scope) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(scope.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    @Nullable
    private static String scopeKey(Minecraft minecraft) {
        MinecraftServer integratedServer = minecraft.getSingleplayerServer();
        if (integratedServer != null) {
            Path worldRoot = integratedServer.getWorldPath(LevelResource.ROOT)
                    .toAbsolutePath().normalize();
            Path fileName = worldRoot.getFileName();
            return "singleplayer:" + (fileName == null ? worldRoot : fileName);
        }

        ServerData server = minecraft.getCurrentServer();
        if (server != null && server.ip != null && !server.ip.isBlank()) {
            return "multiplayer:" + server.ip.trim().toLowerCase(Locale.ROOT);
        }

        if (minecraft.getConnection() != null) {
            SocketAddress address = minecraft.getConnection().getConnection().getRemoteAddress();
            if (address != null) return "remote:" + address;
        }
        return null;
    }
}
