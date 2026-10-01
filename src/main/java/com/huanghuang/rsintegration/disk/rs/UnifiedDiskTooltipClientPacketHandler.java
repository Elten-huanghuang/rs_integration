package com.huanghuang.rsintegration.disk.rs;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** 仅在客户端执行，专用服务器注册包时不链接客户端缓存。 */
@OnlyIn(Dist.CLIENT)
public final class UnifiedDiskTooltipClientPacketHandler {
    private UnifiedDiskTooltipClientPacketHandler() {}
    public static void handle(UnifiedDiskTooltipResponsePacket packet) { UnifiedDiskTooltipClient.accept(packet); }
}
