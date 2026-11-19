package com.huanghuang.rsintegration.crafting.plan;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

import java.util.Arrays;

public final class PackReplayPages {
    private PackReplayPages() {}

    public static Decoded roundTrip(PlanResponse response) {
        byte[] payload = PlanResponsePacket.encodePayload(response, 1L);
        var pages = PlanResponsePagePacket.split(1L, 1L, payload);
        byte[] assembled = null;
        for (int i = pages.size() - 1; i >= 0; i--) {
            var buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                pages.get(i).encode(buffer);
                assembled = PlanResponsePageAssembler.accept(PlanResponsePagePacket.decode(buffer));
            } finally {
                buffer.release();
            }
            if ((assembled != null) != (i == 0)) {
                throw new IllegalStateException("计划分页没有在全部页面到齐时组装");
            }
        }
        if (!Arrays.equals(payload, assembled)) throw new IllegalStateException("分页后计划载荷不一致");
        var packet = PlanResponsePacket.decodePayload(assembled);
        return new Decoded(packet.plan(), packet.requestId(), payload.length, pages.size());
    }

    public record Decoded(PlanResponse plan, long requestId, int bytes, int pages) {}
}
