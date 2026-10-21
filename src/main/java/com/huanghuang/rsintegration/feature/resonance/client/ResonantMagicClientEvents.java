package com.huanghuang.rsintegration.feature.resonance.client;

import com.huanghuang.rsintegration.RSIntegrationMod;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

/** 客户端注册入口；Botania 渲染类只在对应模组存在时加载。 */
@Mod.EventBusSubscriber(modid = RSIntegrationMod.MOD_ID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ResonantMagicClientEvents {
    private static final String BOTANIA_RENDERER =
            "com.huanghuang.rsintegration.mods.botania.client.BotaniaResonantManaPoolRenderer";

    private ResonantMagicClientEvents() {}

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        if (!ModList.get().isLoaded("botania")) return;
        try {
            Class<?> renderer = Class.forName(BOTANIA_RENDERER, true,
                    ResonantMagicClientEvents.class.getClassLoader());
            renderer.getMethod("register", EntityRenderersEvent.RegisterRenderers.class)
                    .invoke(null, event);
        } catch (ReflectiveOperationException | LinkageError exception) {
            RSIntegrationMod.LOGGER.warn("[RSI] 无法注册 Botania 共振魔力池渲染器", exception);
        }
    }
}
