package com.huanghuang.rsintegration.network;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authorizes a player to interact with a remote container.
 *
 * <p>Usage: Before opening a remote machine GUI, call
 * {@code authorize(player, dim, pos, expectedBlock)}.
 * The player is granted access to the container until it is closed.</p>
 *
 * <p><b>Safety:</b> Authorizations auto-expire after {@link #AUTH_TTL_MS} to prevent
 * permanent ghost-container access.  The {@code expectedBlock} field prevents
 * ghost-container access if the machine block is mined while the GUI is open.</p>
 */
public final class RemoteGuiAuth {
    private static final long AUTH_TTL_MS = 5 * 60 * 1000; // 5 minutes

    private record Authorization(ResourceKey<Level> dim, BlockPos pos, String expectedBlock, long timestamp) {}

    private static final Map<UUID, Authorization> ACTIVE = new ConcurrentHashMap<>();

    private RemoteGuiAuth() {}

    /** Call BEFORE opening the remote GUI. Grants bypass for the specified machine.
     * @param expectedBlock the registry name of the block (e.g. "minecraft:furnace"),
     *                      used to detect if the block is mined while the GUI is open */
    public static void authorize(ServerPlayer player, ResourceKey<Level> dim, BlockPos pos, String expectedBlock) {
        ACTIVE.put(player.getUUID(), new Authorization(dim, pos, expectedBlock, System.currentTimeMillis()));
    }

    /** Check if a player is authorized for ANY remote container.
     * Reference — should be used sparingly; prefer dimension-aware checks. */
    public static boolean isAuthorized(ServerPlayer player, AbstractContainerMenu menu) {
        var auth = ACTIVE.get(player.getUUID());
        if (auth == null) return false;
        if (isExpired(auth)) {
            ACTIVE.remove(player.getUUID());
            return false;
        }

        // Verify the block still exists at the authorized position (§13.7 F-1)
        if (!isBlockStillThere(player, auth)) {
            ACTIVE.remove(player.getUUID());
            return false;
        }

        return true;
    }

    /** Check that the authorized block hasn't been mined/replaced. */
    private static boolean isBlockStillThere(ServerPlayer player, Authorization auth) {
        if (auth.expectedBlock() == null) return true; // legacy auth without block check
        var level = player.level();
        if (level.dimension() != auth.dim()) {
            // Cross-dimension: check via server
            var server = player.getServer();
            if (server == null) return false;
            level = server.getLevel(auth.dim());
            if (level == null) return false;
        }
        ResourceLocation currentBlock = BuiltInRegistries.BLOCK.getKey(
                level.getBlockState(auth.pos()).getBlock());
        return auth.expectedBlock().equals(currentBlock.toString());
    }

    /** Called when the player closes the remote GUI (onClose). */
    public static void deauthorize(UUID playerId) {
        ACTIVE.remove(playerId);
    }

    /** Cleanup on player logout. */
    public static void onPlayerLogout(UUID playerId) {
        ACTIVE.remove(playerId);
    }

    public static boolean hasActiveAuthorization(UUID playerId) {
        return hasActiveAuthorizationForBlock(playerId, null);
    }

    /** Check if player has active authorization for a specific block type.
     * When {@code block} is null, checks for any active authorization (backwards compat). */
    public static boolean hasActiveAuthorizationForBlock(UUID playerId, @javax.annotation.Nullable net.minecraft.world.level.block.Block block) {
        var auth = ACTIVE.get(playerId);
        if (auth == null) return false;
        if (isExpired(auth)) {
            ACTIVE.remove(playerId);
            return false;
        }
        if (block != null && auth.expectedBlock() != null) {
            ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(block);
            if (!auth.expectedBlock().equals(blockId.toString())) {
                return false;
            }
        }
        return true;
    }

    /** Periodic cleanup of expired entries (call from a tick handler). */
    public static void cleanExpired() {
        var it = ACTIVE.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            if (isExpired(entry.getValue())) it.remove();
        }
    }

    private static boolean isExpired(Authorization auth) {
        return System.currentTimeMillis() - auth.timestamp() > AUTH_TTL_MS;
    }
}
