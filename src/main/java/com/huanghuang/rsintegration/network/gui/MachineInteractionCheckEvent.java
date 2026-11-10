package com.huanghuang.rsintegration.network.gui;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.Cancelable;

/** 远程操作的权限探测，仍向领地模组发出右键事件，但不能切换机器绑定。 */
@Cancelable
public final class MachineInteractionCheckEvent extends PlayerInteractEvent.RightClickBlock {
    public MachineInteractionCheckEvent(ServerPlayer player, BlockPos position) {
        super(player, InteractionHand.MAIN_HAND, position,
                new BlockHitResult(
                        new Vec3(position.getX() + 0.5, position.getY() + 1.0,
                                position.getZ() + 0.5),
                        Direction.UP, position, false));
    }
}
