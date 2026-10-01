package com.huanghuang.rsintegration.unifiedgrid;

import com.huanghuang.rsintegration.unifiedgrid.client.UnifiedGridClient;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** 此类只在客户端分发器内解析，包注册不直接链接屏幕类型。 */
@OnlyIn(Dist.CLIENT)
public final class UnifiedGridClientPacketHandler {
    private UnifiedGridClientPacketHandler() { }

    public static void handle(UnifiedGridUpdatePacket packet) { UnifiedGridClient.apply(packet); }
}
