package com.huanghuang.rsintegration.network.binding;

import net.minecraft.server.level.ServerPlayer;

import java.util.function.Consumer;

/** 让底层绑定索引通知上层刷新快照，避免直接依赖具体的网络处理器。 */
public final class MachineBindingSyncBridge {
    private static volatile Consumer<ServerPlayer> sender =
            ignored -> {};

    private MachineBindingSyncBridge() {}

    public static void install(Consumer<ServerPlayer> bindingSender) {
        sender = bindingSender == null ? ignored -> {} : bindingSender;
    }

    public static void send(ServerPlayer player) {
        if (player != null) sender.accept(player);
    }
}
