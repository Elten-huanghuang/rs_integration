package com.huanghuang.rsintegration.feature.transmutation;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;

/** 不引用可选模组类型，客户端和服务端均可安全检查此接口。 */
public interface TransmutationTableMenuAccess {
    int rsIntegration$getRerollState();

    void rsIntegration$onResultsChanged(Player player, BlockEntity table);
}
