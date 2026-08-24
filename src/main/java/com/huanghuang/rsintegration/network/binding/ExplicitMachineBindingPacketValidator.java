package com.huanghuang.rsintegration.network.binding;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;

final class ExplicitMachineBindingPacketValidator {
    private ExplicitMachineBindingPacketValidator() {}

    static void bind(ServerPlayer player, BlockPos pos, InteractionHand hand) {
        if (!player.level().hasChunkAt(pos)) return;
        if (player.getMainHandItem().isEmpty() && player.getOffhandItem().isEmpty()) return;
        BindingEventHandler.handleExplicitBind(player, pos, hand);
    }
}
