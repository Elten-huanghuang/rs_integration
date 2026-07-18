package com.huanghuang.rsintegration.mods.distantworlds;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.reflection.probes.DistantWorldsReflection;
import net.minecraft.world.InteractionHand;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public final class LithumCoreInteractionHandler {
    private LithumCoreInteractionHandler() {}

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!RSIntegrationConfig.ENABLE_DISTANT_WORLDS.get()
                || event.getHand() != InteractionHand.MAIN_HAND
                || !event.getEntity().getItemInHand(event.getHand()).isEmpty()) return;
        if (DistantWorldsReflection.lithumCoreBlockClass == null
                || !DistantWorldsReflection.lithumCoreBlockClass.isInstance(
                event.getLevel().getBlockState(event.getPos()).getBlock())) return;
        event.setCanceled(true);
        event.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
    }
}
