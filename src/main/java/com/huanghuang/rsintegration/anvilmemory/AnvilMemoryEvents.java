package com.huanghuang.rsintegration.anvilmemory;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.AnvilRepairEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public final class AnvilMemoryEvents {
    private AnvilMemoryEvents() {}

    @SubscribeEvent
    public static void onRepair(AnvilRepairEvent event) {
        if (!RSIntegrationConfig.ENABLE_ANVIL_MEMORY.get()
                || !(event.getEntity() instanceof ServerPlayer player)) return;
        AnvilMemoryAdapter adapter = AnvilMemoryAdapters.find(player.containerMenu);
        if (adapter == null || !RSIntegrationConfig.ANVIL_MEMORY_ADAPTERS.get().contains(adapter.id())) return;
        AnvilMemoryData.remember(player, adapter.id(), event.getRight(),
                RSIntegrationConfig.ANVIL_MEMORY_REMEMBER_NBT.get());
        AnvilMemoryNetworkHandler.sendSync(player, adapter);
    }
}
