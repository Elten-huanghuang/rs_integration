package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.RSIntegrationMod;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

/** 单个计划响应分页；所有页到齐并校验后才允许客户端安装计划。 */
public record PlanResponsePagePacket(long sessionId, long requestId,
                                     int pageIndex, int pageCount, byte[] payload) {
    public static final int PAGE_BYTES = 128 * 1024;
    public static final int MAX_PAGES = 64;
    public static final int MAX_TOTAL_BYTES = PAGE_BYTES * MAX_PAGES;

    public PlanResponsePagePacket {
        if (sessionId < 0L) throw new IllegalArgumentException("negative page session");
        if (requestId < 0L) throw new IllegalArgumentException("negative request id");
        if (pageCount <= 0 || pageCount > MAX_PAGES) {
            throw new IllegalArgumentException("page count out of range: " + pageCount);
        }
        if (pageIndex < 0 || pageIndex >= pageCount) {
            throw new IllegalArgumentException("page index out of range: " + pageIndex);
        }
        if (payload == null || payload.length > PAGE_BYTES) {
            throw new IllegalArgumentException("page payload out of range");
        }
        payload = payload.clone();
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }

    public static List<PlanResponsePagePacket> split(long sessionId, long requestId, byte[] data) {
        if (data == null || data.length > MAX_TOTAL_BYTES) {
            throw new IllegalArgumentException("plan payload too large");
        }
        int pageCount = Math.max(1, SaturatingPageMath.ceilDiv(data.length, PAGE_BYTES));
        List<PlanResponsePagePacket> pages = new ArrayList<>(pageCount);
        for (int page = 0; page < pageCount; page++) {
            int from = page * PAGE_BYTES;
            int to = Math.min(data.length, from + PAGE_BYTES);
            pages.add(new PlanResponsePagePacket(sessionId, requestId, page, pageCount,
                    Arrays.copyOfRange(data, from, to)));
        }
        return List.copyOf(pages);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarLong(sessionId);
        buf.writeVarLong(requestId);
        buf.writeVarInt(pageIndex);
        buf.writeVarInt(pageCount);
        buf.writeByteArray(payload);
    }

    public static PlanResponsePagePacket decode(FriendlyByteBuf buf) {
        long sessionId = readNonNegativeVarLong(buf, "page session");
        long requestId = readNonNegativeVarLong(buf, "request id");
        int pageIndex = buf.readVarInt();
        int pageCount = buf.readVarInt();
        if (pageCount <= 0 || pageCount > MAX_PAGES) {
            throw new DecoderException("page count out of range: " + pageCount);
        }
        if (pageIndex < 0 || pageIndex >= pageCount) {
            throw new DecoderException("page index out of range: " + pageIndex);
        }
        byte[] payload = buf.readByteArray(PAGE_BYTES);
        if (buf.readableBytes() != 0) {
            throw new DecoderException("trailing bytes in plan response page");
        }
        return new PlanResponsePagePacket(sessionId, requestId, pageIndex, pageCount, payload);
    }

    public static void handle(PlanResponsePagePacket packet,
                              Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> PlanResponsePageClientHandler.handle(packet)));
        ctx.setPacketHandled(true);
    }

    private static long readNonNegativeVarLong(FriendlyByteBuf buf, String field) {
        long value = buf.readVarLong();
        if (value < 0L) throw new DecoderException(field + " must be non-negative");
        return value;
    }

    private static final class SaturatingPageMath {
        private SaturatingPageMath() {}

        private static int ceilDiv(int value, int divisor) {
            return value / divisor + (value % divisor == 0 ? 0 : 1);
        }
    }
}
