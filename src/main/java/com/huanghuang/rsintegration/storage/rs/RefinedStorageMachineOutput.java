package com.huanghuang.rsintegration.storage.rs;

import com.huanghuang.rsintegration.network.RSIntegrationNetwork;
import com.huanghuang.rsintegration.util.InsertedStackDelta;
import com.huanghuang.rsintegration.util.TrackedNetworkInsertion;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;
import java.util.function.UnaryOperator;

/** 把当前 RS 网络接入机器取货，沿用正式会话及进度通知。 */
public final class RefinedStorageMachineOutput {
    private RefinedStorageMachineOutput() {}

    public static Optional<UnaryOperator<ItemStack>> resolve(ServerPlayer player) {
        var network = RSIntegrationNetwork.resolveNetworkFromPlayer(player);
        if (network == null) return Optional.empty();
        return Optional.of(output -> {
            ItemStack remainder = TrackedNetworkInsertion.insert(network, player, output);
            InsertedStackDelta.report(player, output, remainder);
            return remainder;
        });
    }
}
