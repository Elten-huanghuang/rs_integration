package com.huanghuang.rsintegration.feature.advancement;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.api.execution.RecursiveCraftSucceededEvent;
import net.minecraft.advancements.Advancement;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = RSIntegrationMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RecursiveCraftAdvancement {
    private static final ResourceLocation SKIP_LIFE =
            ResourceLocation.fromNamespaceAndPath(RSIntegrationMod.MOD_ID, "skip_life");

    private RecursiveCraftAdvancement() {}

    @SubscribeEvent
    public static void onCraftSucceeded(RecursiveCraftSucceededEvent event) {
        if (event.producedItems().isEmpty()) return;

        ServerPlayer player = event.player();
        Advancement advancement = player.server.getAdvancements().getAdvancement(SKIP_LIFE);
        if (advancement != null) {
            player.getAdvancements().award(advancement, "crafted_item");
        }
    }
}
