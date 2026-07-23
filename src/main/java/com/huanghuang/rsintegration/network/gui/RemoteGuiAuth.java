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
                                  long timestamp, int staleContainerId, int menuId,
                                  int chunkX, int chunkZ) {}

    private static final Map<UUID, Authorization> ACTIVE = new ConcurrentHashMap<>();

    /**
     * Per-chunk viewer refcount. ForgeChunkManager does NOT reference-count
     * tickets — a ticket is keyed by (modId, owner=BlockPos) and stored as a
     * set, so two players force-loading the same machine's chunk share one
     * ticket and the first release unforces it for everyone. We track viewers
     * ourselves and only call forceChunk(false) when the last viewer leaves.
     * Key: (dimension, chunkX, chunkZ) packed as a string.
     */
    private static final Map<String, Integer> CHUNK_VIEWERS = new ConcurrentHashMap<>();

    private static String chunkKey(ResourceKey<Level> dim, int cx, int cz) {
        return dim.location() + "@" + cx + "," + cz;
    }

    private RemoteGuiAuth() {}

    /** Call BEFORE opening the remote GUI. Grants bypass for the specified machine.
     * @param expectedBlock the registry name of the block (e.g. "minecraft:furnace"),
     *                      used to detect if the block is mined while the GUI is open */
    public static void authorize(ServerPlayer player, ResourceKey<Level> dim, BlockPos pos, String expectedBlock) {
        // Release any prior authorization first. Navigating machine→machine
        // without closing the container overwrites the ACTIVE entry; without this
        // the old machine's force-load ticket is never released and leaks a
        // permanently force-loaded chunk for the rest of the session.
        releaseAndRemove(player.getUUID());

        int staleId = player.containerMenu != null ? player.containerMenu.containerId : -1;
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        ACTIVE.put(player.getUUID(), new Authorization(dim, pos, expectedBlock, System.currentTimeMillis(),
                staleId, -1, cx, cz));

        // Force-load the machine's chunk while the GUI is open.
        // Track viewers ourselves — ForgeChunkManager tickets are not
        // refcounted, so we must only add the physical ticket for the FIRST
        // viewer of a chunk and only release it when the LAST one leaves.
        var server = player.getServer();
        if (server != null) {
            var targetLevel = server.getLevel(dim);
            if (targetLevel != null) {
                String key = chunkKey(dim, cx, cz);
                int viewers = CHUNK_VIEWERS.merge(key, 1, Integer::sum);
                if (viewers == 1) {
                    ForgeChunkManager.forceChunk(targetLevel, RSIntegrationMod.MOD_ID,
                            pos, cx, cz, true, true);
                }
            }
        }
    }

    /** Bind the pending authorization to the newly opened remote menu. */
    public static boolean bindOpenedMenu(ServerPlayer player) {
        return bindOpenedMenu(player, player.containerMenu);
    }

    /** Bind the pending authorization to a specific newly opened menu. */
    public static boolean bindOpenedMenu(ServerPlayer player, @javax.annotation.Nullable AbstractContainerMenu menu) {
        if (menu == null) return false;
        UUID playerId = player.getUUID();
        Authorization auth = ACTIVE.get(playerId);
        if (auth == null || isExpired(auth) || !isBlockStillThere(player, auth)) {
            releaseAndRemove(playerId);
            return false;
        }
        ACTIVE.replace(playerId, auth, new Authorization(auth.dim(), auth.pos(), auth.expectedBlock(),
                auth.timestamp(), auth.staleContainerId(), menu.containerId, auth.chunkX(), auth.chunkZ()));
        return true;
    }

    /** Check if a player is authorized for the specific remote container. */
    public static boolean isAuthorized(ServerPlayer player, AbstractContainerMenu menu) {
        var auth = ACTIVE.get(player.getUUID());
        if (auth == null || menu == null) return false;
        if (isExpired(auth) || (auth.menuId() >= 0 && auth.menuId() != menu.containerId)) {
            if (isExpired(auth)) releaseAndRemove(player.getUUID());
            return false;
        }
        if (auth.menuId() < 0) return false;
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
            return false;
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

    /** Clear all server-owned authorization and chunk viewer state. */
    public static void clearServerState() {
        var server = ServerLifecycleHooks.getCurrentServer();
        for (UUID playerId : ACTIVE.keySet().toArray(UUID[]::new)) {
            releaseAndRemove(playerId);
        }
        ACTIVE.clear();
        if (!CHUNK_VIEWERS.isEmpty()) {
            if (server != null) {
                for (String key : CHUNK_VIEWERS.keySet()) {
                    RSIntegrationMod.LOGGER.debug("[RSI-GuiAuth] clearing stale chunk viewer {}", key);
                }
            }
            CHUNK_VIEWERS.clear();
        }
    }

    /** Release chunk force-load and remove the authorization. */
    private static void releaseAndRemove(UUID playerId) {
        var auth = ACTIVE.remove(playerId);
        if (auth == null) return;
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        var level = server.getLevel(auth.dim());
        if (level == null) return;
        // Only unforce the chunk when the LAST viewer releases it. Decrement
        // our own refcount; other players may still have the same machine open.
        String key = chunkKey(auth.dim(), auth.chunkX(), auth.chunkZ());
        Integer remaining = CHUNK_VIEWERS.computeIfPresent(key, (k, v) -> v <= 1 ? null : v - 1);
        if (remaining == null) {
            ForgeChunkManager.forceChunk(level, RSIntegrationMod.MOD_ID,
                    auth.pos(), auth.chunkX(), auth.chunkZ(), false, true);
        }
    }

    /** Check whether the player's currently open menu is the authorized menu. */
    public static boolean isAuthorizedCurrentMenu(ServerPlayer player) {
        return isAuthorized(player, player.containerMenu);
    }

    /**
     * Distance checks may be used by modded menus instead of the vanilla
     * AbstractContainerMenu helper. Only bypass checks aimed at the authorized
     * machine; unrelated entity/AI distance queries must retain vanilla values.
     */
    public static boolean isAuthorizedDistanceTarget(ServerPlayer player, double x, double y, double z) {
        Authorization auth = ACTIVE.get(player.getUUID());
        if (auth == null || !isAuthorizedCurrentMenu(player)) return false;
        double dx = x - (auth.pos().getX() + 0.5D);
        double dy = y - (auth.pos().getY() + 0.5D);
        double dz = z - (auth.pos().getZ() + 0.5D);
        return dx * dx + dy * dy + dz * dz <= 4.0D;
    }

    /** Check whether a player has any pending/active authorization. */
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
