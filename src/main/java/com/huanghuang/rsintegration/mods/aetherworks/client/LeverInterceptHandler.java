package com.huanghuang.rsintegration.mods.aetherworks.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

@OnlyIn(Dist.CLIENT)
public final class LeverInterceptHandler {

    private LeverInterceptHandler() {}

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getSide().isServer()) return;

        var level = event.getLevel();
        var player = event.getEntity();

        if (!AetherworksHelper.isHoldingHammer(player)) return;

        // Anvil: shift+right-click → config screen
        BlockEntity clicked = level.getBlockEntity(event.getPos());
        if (clicked == null) clicked = level.getBlockEntity(event.getPos().below());
        if (AetherworksHelper.isAnvil(clicked)) {
            if (!player.isShiftKeyDown()) return;
            event.setCanceled(true);
            Minecraft.getInstance().setScreen(new AnvilConfigScreen());
            return;
        }

        // Lever: right-click → bind / shift+right-click → unbind
        if (level.getBlockState(event.getPos()).getBlock() instanceof LeverBlock) {
            if (!isNearAnvilOrForge(level, event.getPos())) return;
            event.setCanceled(true);
            if (player.isShiftKeyDown()) {
                LeverBinder.unbind();
            } else {
                LeverBinder.bindLever(event.getPos());
            }
        }
    }

    private static boolean isNearAnvilOrForge(net.minecraft.world.level.LevelAccessor level,
                                              net.minecraft.core.BlockPos leverPos) {
        for (int dx = -5; dx <= 5; dx++)
            for (int dy = -3; dy <= 3; dy++)
                for (int dz = -5; dz <= 5; dz++) {
                    BlockEntity be = level.getBlockEntity(leverPos.offset(dx, dy, dz));
                    if (AetherworksHelper.isAnvil(be) || AetherworksHelper.isForge(be))
                        return true;
                }
        return false;
    }
}
