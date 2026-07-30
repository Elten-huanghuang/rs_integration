package com.huanghuang.rsintegration.util;

import com.huanghuang.rsintegration.RSIntegrationMod;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.world.ForgeChunkManager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Process-wide reference counting for RSI-owned forced chunk tickets.
 *
 * <p>Forge stores these tickets as a set keyed by mod id, owner position and
 * chunk. Without a shared count, independent RSI features can unknowingly
 * share one physical ticket and the first feature to finish removes it for all
 * remaining users.</p>
 */
public final class ForcedChunkTicketManager {
    private static final RefCounter<TicketKey> REFERENCES = new RefCounter<>();

    private ForcedChunkTicketManager() {
    }

    /** Retain the ticket for an owner. Must be called on the server thread. */
    public static synchronized boolean retain(ServerLevel level, BlockPos owner) {
        if (level == null || owner == null) return false;
        if (!level.hasChunkAt(owner)) {
            RSIntegrationMod.LOGGER.debug("[RSI-ChunkTicket] Refusing to retain unloaded chunk at {}", owner);
            return false;
        }
        TicketKey key = TicketKey.of(level.dimension(), owner);
        if (!REFERENCES.retain(key)) return true;

        boolean forced = false;
        try {
            forced = ForgeChunkManager.forceChunk(level, RSIntegrationMod.MOD_ID, key.owner(),
                    key.chunkX(), key.chunkZ(), true, true);
            if (!forced) {
                RSIntegrationMod.LOGGER.warn("[RSI-ChunkTicket] Forge rejected ticket acquisition at {}",
                        key.owner());
            }
            return forced;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-ChunkTicket] Failed to acquire ticket at {}",
                    key.owner(), e);
            return false;
        } finally {
            if (!forced) REFERENCES.release(key);
        }
    }

    /**
     * Release one logical holder. Returns false only when the final physical
     * release threw and should be retried by the caller.
     */
    public static synchronized boolean release(ServerLevel level, BlockPos owner) {
        if (level == null || owner == null) return true;
        TicketKey key = TicketKey.of(level.dimension(), owner);
        if (!REFERENCES.release(key)) return true;

        try {
            boolean released = ForgeChunkManager.forceChunk(level, RSIntegrationMod.MOD_ID, key.owner(),
                    key.chunkX(), key.chunkZ(), false, true);
            if (!released) {
                RSIntegrationMod.LOGGER.debug("[RSI-ChunkTicket] Forge reported no ticket to release at {}",
                        key.owner());
            }
            return true;
        } catch (Exception e) {
            REFERENCES.retain(key);
            RSIntegrationMod.LOGGER.warn("[RSI-ChunkTicket] Failed to release ticket at {}",
                    key.owner(), e);
            return false;
        }
    }

    /** Remove every outstanding RSI ticket during server shutdown. */
    public static synchronized void clear(MinecraftServer server) {
        for (TicketKey key : REFERENCES.drain()) {
            if (server == null) continue;
            ServerLevel level = server.getLevel(key.dimension());
            if (level == null) continue;
            try {
                ForgeChunkManager.forceChunk(level, RSIntegrationMod.MOD_ID, key.owner(),
                        key.chunkX(), key.chunkZ(), false, true);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-ChunkTicket] Failed to clear ticket at {}",
                        key.owner(), e);
            }
        }
    }

    record TicketKey(ResourceKey<Level> dimension, BlockPos owner, int chunkX, int chunkZ) {
        private static TicketKey of(ResourceKey<Level> dimension, BlockPos owner) {
            BlockPos immutableOwner = owner.immutable();
            return new TicketKey(dimension, immutableOwner,
                    immutableOwner.getX() >> 4, immutableOwner.getZ() >> 4);
        }
    }

    static final class RefCounter<K> {
        private final Map<K, Integer> counts = new HashMap<>();

        boolean retain(K key) {
            int count = counts.getOrDefault(key, 0) + 1;
            counts.put(key, count);
            return count == 1;
        }

        boolean release(K key) {
            Integer count = counts.get(key);
            if (count == null) return false;
            if (count > 1) {
                counts.put(key, count - 1);
                return false;
            }
            counts.remove(key);
            return true;
        }

        List<K> drain() {
            List<K> keys = List.copyOf(counts.keySet());
            counts.clear();
            return keys;
        }
    }
}
