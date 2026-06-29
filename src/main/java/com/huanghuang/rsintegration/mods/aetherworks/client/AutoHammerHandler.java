package com.huanghuang.rsintegration.mods.aetherworks.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class AutoHammerHandler {

    private static boolean wasHitTimeoutZero = true;
    private static int lastAnvilProgress;

    private AutoHammerHandler() {}

    public static void tryAutoHammer() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        if (!AetherworksHelper.isHoldingHammer(mc.player)) return;

        BlockEntity anvil = AetherworksHelper.getTargetAnvil();
        if (anvil == null) {
            wasHitTimeoutZero = true;
            lastAnvilProgress = 0;
            return;
        }

        int progress = AetherworksHelper.getAnvilProgress(anvil);
        if (progress == 0 && lastAnvilProgress >= 2) {
            lastAnvilProgress = progress;
            wasHitTimeoutZero = true;
            return;
        }
        lastAnvilProgress = progress;

        int hitTimeout = AetherworksHelper.getAnvilHitTimeout(anvil);
        if (hitTimeout > 0 && wasHitTimeoutZero) {
            sendHammerPacket(mc, anvil.getBlockPos());
            wasHitTimeoutZero = false;
        } else if (hitTimeout == 0) {
            wasHitTimeoutZero = true;
        }
    }

    private static void sendHammerPacket(Minecraft mc, BlockPos pos) {
        BlockHitResult hit = new BlockHitResult(
                new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5),
                Direction.UP, pos, false);
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
        mc.player.swing(InteractionHand.MAIN_HAND);
    }

    static void reset() {
        wasHitTimeoutZero = true;
        lastAnvilProgress = 0;
    }
}
