package com.huanghuang.rsintegration.crafting.plan;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** 客户端分页装配器，带会话上限、总内存上限和超时清理。 */
@OnlyIn(Dist.CLIENT)
final class PlanResponsePageAssembler {
    private static final long TIMEOUT_NANOS = 30_000_000_000L;
    private static final int MAX_SESSIONS = 8;
    private static final long MAX_GLOBAL_BYTES = 16L * 1024L * 1024L;
    private static final Map<Long, PendingResponse> PENDING = new LinkedHashMap<>();
    private static long pendingBytes;

    private PlanResponsePageAssembler() {}

    static synchronized byte[] accept(PlanResponsePagePacket packet) {
        long now = System.nanoTime();
        removeExpired(now);
        PendingResponse pending = PENDING.get(packet.sessionId());
        if (pending == null) {
            while (PENDING.size() >= MAX_SESSIONS) {
                Iterator<Long> iterator = PENDING.keySet().iterator();
                if (!iterator.hasNext()) break;
                remove(iterator.next());
            }
            pending = new PendingResponse(packet.requestId(), packet.pageCount(), now);
            PENDING.put(packet.sessionId(), pending);
        }
        if (pending.requestId != packet.requestId()
                || pending.pageCount != packet.pageCount()) {
            remove(packet.sessionId());
            throw new IllegalArgumentException("plan page session metadata mismatch");
        }

        byte[] incoming = packet.payload();
        byte[] existing = pending.pages[packet.pageIndex()];
        if (existing != null) {
            if (!Arrays.equals(existing, incoming)) {
                remove(packet.sessionId());
                throw new IllegalArgumentException("conflicting duplicate plan page");
            }
            return null;
        }
        if (pendingBytes + incoming.length > MAX_GLOBAL_BYTES) {
            remove(packet.sessionId());
            throw new IllegalArgumentException("plan page memory limit exceeded");
        }
        pending.pages[packet.pageIndex()] = incoming;
        pending.received++;
        pending.bytes += incoming.length;
        pending.updatedAt = now;
        pendingBytes += incoming.length;
        if (pending.received != pending.pageCount) return null;

        ByteArrayOutputStream output = new ByteArrayOutputStream((int) pending.bytes);
        for (byte[] page : pending.pages) output.writeBytes(page);
        byte[] assembled = output.toByteArray();
        remove(packet.sessionId());
        return assembled;
    }

    static synchronized void resetForTests() {
        PENDING.clear();
        pendingBytes = 0L;
    }

    private static void removeExpired(long now) {
        Iterator<Map.Entry<Long, PendingResponse>> iterator = PENDING.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, PendingResponse> entry = iterator.next();
            if (now - entry.getValue().updatedAt < TIMEOUT_NANOS) continue;
            pendingBytes -= entry.getValue().bytes;
            iterator.remove();
        }
    }

    private static void remove(long sessionId) {
        PendingResponse removed = PENDING.remove(sessionId);
        if (removed != null) pendingBytes -= removed.bytes;
    }

    private static final class PendingResponse {
        private final long requestId;
        private final int pageCount;
        private final byte[][] pages;
        private int received;
        private long bytes;
        private long updatedAt;

        private PendingResponse(long requestId, int pageCount, long updatedAt) {
            this.requestId = requestId;
            this.pageCount = pageCount;
            this.pages = new byte[pageCount][];
            this.updatedAt = updatedAt;
        }
    }
}
