package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.RSIntegrationMod;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
final class PlanResponsePageClientHandler {
    private PlanResponsePageClientHandler() {}

    static void handle(PlanResponsePagePacket packet) {
        try {
            byte[] payload = PlanResponsePageAssembler.accept(packet);
            if (payload == null) return;
            PlanResponsePacket decoded = PlanResponsePacket.decodePayload(payload);
            PlanResponseClientPacketHandler.handle(decoded.plan(), decoded.requestId());
        } catch (Throwable failure) {
            RSIntegrationMod.LOGGER.error(
                    "[RSI-PlanPkt] 计划分页装配失败 session={} request={} page={}/{}",
                    packet.sessionId(), packet.requestId(), packet.pageIndex() + 1,
                    packet.pageCount(), failure);
        }
    }
}
