package com.huanghuang.rsintegration.network.binding;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraftforge.fml.ModList;

final class ExplicitMachineBindingPacketValidator {
    private ExplicitMachineBindingPacketValidator() {}

    static void bind(ServerPlayer player, BlockPos pos, InteractionHand hand) {
        if (!player.level().hasChunkAt(pos)) return;
        if (player.getMainHandItem().isEmpty() && player.getOffhandItem().isEmpty()) return;
        // Apotheosis spawners are a one-shot GUI target, not persistent machine
        // bindings. Keep the optional integration isolated from BD-only class
        // loading while consuming the explicit Alt+right-click action.
        if (ModList.get().isLoaded("apotheosis")
                && tryOpenApotheosisSpawner(player, pos)) return;
        BindingEventHandler.handleExplicitBind(player, pos, hand);
    }

    private static boolean tryOpenApotheosisSpawner(ServerPlayer player, BlockPos pos) {
        try {
            Class<?> handler = Class.forName(
                    "com.huanghuang.rsintegration.mods.apotheosis.ApothSpawnerInteractionHandler");
            return (boolean) handler.getMethod("tryOpenExplicit", ServerPlayer.class, BlockPos.class)
                    .invoke(null, player, pos);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }
}
