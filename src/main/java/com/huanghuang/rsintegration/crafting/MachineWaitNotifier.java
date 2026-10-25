package com.huanghuang.rsintegration.crafting;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;

/** 整条合成链只发送一次可恢复等待提示，避免反复补能造成聊天栏刷屏。 */
final class MachineWaitNotifier {
    private boolean notified;

    void notifyWait(ServerPlayer player, @Nullable Component reason) {
        if (notified || player == null || reason == null) return;
        notified = true;
        player.sendSystemMessage(reason);
    }
}
