package com.huanghuang.rsintegration.mods.distantworlds;

import com.huanghuang.rsintegration.mods.distantworlds.client.LithumAltarStatusCache;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
final class LithumAltarClientPacketHandler {
    private LithumAltarClientPacketHandler() {}

    static void handle(LithumAltarStatusSnapshot snapshot) {
        LithumAltarStatusCache.update(snapshot);
    }
}
