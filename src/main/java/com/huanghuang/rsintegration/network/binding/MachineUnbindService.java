package com.huanghuang.rsintegration.network.binding;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/** 客户端机器入口共用的解绑事务和用户反馈。 */
public final class MachineUnbindService {
    private MachineUnbindService() {}

    public record Result(boolean success, Component message) {}

    public static Result unbind(
            ServerPlayer player, ResourceLocation dimension, BlockPos position) {
        BindingStorage.BindingEntry entry =
                AltarBindingRegistry.findBindingEntry(
                        player, dimension, position);
        if (entry == null) {
            return new Result(false, Component.translatable(
                    "rsi.machine.error.not_bound"));
        }
        int removed = AltarBindingRegistry.removePlayerBinding(
                player, dimension, position);
        if (removed == 0) {
            return new Result(false, Component.translatable(
                    "rsi.machine.error.not_bound"));
        }
        Component name = BindingEventHandler.resolveBlockName(
                entry.blockKey(), entry.blockRegKey(), entry.displayStack());
        return new Result(true, Component.translatable(
                "gui.rs_integration.altar.unbound", name));
    }
}
