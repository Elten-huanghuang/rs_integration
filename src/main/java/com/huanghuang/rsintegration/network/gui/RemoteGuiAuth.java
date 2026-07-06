package com.huanghuang.rsintegration.network.gui;

import com.huanghuang.rsintegration.RSIntegrationMod;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.world.ForgeChunkManager;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.server.ServerLifecycleHooks;

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
 * ghost-container access if the machine block is mined while the GUI is open.
 * Chunk force-loading via {@link ForgeChunkManager} prevents dupes from chunk
 * unloads mid-interaction.</p>
 */
public final class RemoteGuiAuth {
    private static final long AUTH_TTL_MS = 5 * 60 * 1000; // 5 minutes

    private record Authorization(ResourceKey<Level> dim, BlockPos pos, String expectedBlock,
                                  long timestamp, int staleContainerId,
                                  int chunkX, int chunkZ) {}

    private static final Map<UUID, Authorization> ACTIVE = new ConcurrentHashMap<>();

    private RemoteGuiAuth() {}

    /** Call BEFORE opening the remote GUI. Grants bypass for the specified machine.
     * @param expectedBlock the registry name of the block (e.g. "minecraft:furnace"),
     *                      used to detect if the block is mined while the GUI is open */
    public static void authorize(ServerPlayer player, ResourceKey<Level> dim, BlockPos pos, String expectedBlock) {
        int staleId = player.containerMenu != null ? player.containerMenu.containerId : -1;
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        ACTIVE.put(player.getUUID(), new Authorization(dim, pos, expectedBlock, System.currentTimeMillis(),
                staleId, cx, cz));

        // Force-load the machine's chunk while the GUI is open.
        // BlockPos as owner: refcounted per-chunk; two players on the same
        // machine both add tickets, and the chunk unloads only when the last
        // one releases.
        var server = player.getServer();
        if (server != null) {
            var targetLevel = server.getLevel(dim);
            if (targetLevel != null) {
                ForgeChunkManager.forceChunk(targetLevel, RSIntegrationMod.MOD_ID,
                        pos, cx, cz, true, true);
            }
        }
    }

    /** Check if a player is authorized for ANY remote container. */
    public static boolean isAuthorized(ServerPlayer player, AbstractContainerMenu menu) {
        var auth = ACTIVE.get(player.getUUID());
        if (auth == null) return false;
        if (isExpired(auth)) {
            releaseAndRemove(player.getUUID());
            return false;
        }
        if (!isBlockStillThere(player, auth)) {
            releaseAndRemove(player.getUUID());
            return false;
        }
        return true;
    }

    /** Check that the authorized block hasn't been mined/replaced. */
    private static boolean isBlockStillThere(ServerPlayer player, Authorization auth) {
        if (auth.expectedBlock() == null) return true;
        var level = player.level();
        if (level.dimension() != auth.dim()) {
            var server = player.getServer();
            if (server == null) return false;
            level = server.getLevel(auth.dim());
            if (level == null) return false;
        }
        if (level instanceof ServerLevel sl && !sl.hasChunkAt(auth.pos())) {
            return true;
        }
        ResourceLocation currentBlock = ForgeRegistries.BLOCKS.getKey(
                level.getBlockState(auth.pos()).getBlock());
        return auth.expectedBlock().equals(currentBlock.toString());
    }

    /** Called when the player closes ANY container.
     *  Only removes the authorization if the closing container is NOT the stale
     *  one (the RS Grid we just navigated away from). */
    public static void deauthorize(UUID playerId, AbstractContainerMenu closingMenu) {
        var auth = ACTIVE.get(playerId);
        if (auth == null) return;
        if (auth.staleContainerId() >= 0 && closingMenu != null
                && closingMenu.containerId == auth.staleContainerId()) {
            return;
        }
        releaseAndRemove(playerId);
    }

    /** Cleanup on player logout. */
    public static void onPlayerLogout(UUID playerId) {
        releaseAndRemove(playerId);
    }

    /** Release chunk force-load and remove the authorization. */
    private static void releaseAndRemove(UUID playerId) {
        var auth = ACTIVE.remove(playerId);
        if (auth == null) return;
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        var level = server.getLevel(auth.dim());
        if (level == null) return;
        ForgeChunkManager.forceChunk(level, RSIntegrationMod.MOD_ID,
                auth.pos(), auth.chunkX(), auth.chunkZ(), false, true);
    }

    public static boolean hasActiveAuthorization(UUID playerId) {
        return hasActiveAuthorizationForBlock(playerId, null);
    }

    /** Check if player has active authorization for a specific block type. */
    public static boolean hasActiveAuthorizationForBlock(UUID playerId, @javax.annotation.Nullable net.minecraft.world.level.block.Block block) {
        var auth = ACTIVE.get(playerId);
        if (auth == null) return false;
        if (isExpired(auth)) {
            releaseAndRemove(playerId);
            return false;
        }
        if (block != null && auth.expectedBlock() != null) {
            ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(block);
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
            if (isExpired(entry.getValue())) {
                releaseAndRemove(entry.getKey());
            }
        }
    }

    /**
     * Safety net: when a chunk unloads, force-close any player's remote GUI
     * whose machine is in that chunk.  This prevents ghost-container interaction
     * with an unloaded BlockEntity (item dupes / loss).
     */
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        int cx = event.getChunk().getPos().x;
        int cz = event.getChunk().getPos().z;
        var it = ACTIVE.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            var auth = entry.getValue();
            if (auth.chunkX() != cx || auth.chunkZ() != cz) continue;
            if (!auth.dim().equals(level.dimension())) continue;
            var server = level.getServer();
            if (server != null) {
                var player = server.getPlayerList().getPlayer(entry.getKey());
                if (player != null) {
                    player.closeContainer();
                }
            }
            releaseAndRemove(entry.getKey());
        }
    }

    /** Number of active authorizations (for diagnostics). */
    public static int activeCount() {
        return ACTIVE.size();
    }

    private static boolean isExpired(Authorization auth) {
        return System.currentTimeMillis() - auth.timestamp() > AUTH_TTL_MS;
    }
}
