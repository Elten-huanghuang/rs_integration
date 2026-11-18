package com.huanghuang.rsintegration.machine;

import com.huanghuang.rsintegration.network.gui.MachineInteractionAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.util.FakePlayer;

import java.util.Optional;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/** 统一编排机器中心的手动取货和补料。 */
public final class MachineManualActions {
    public enum CollectionPolicy {
        CURRENT_NETWORK,
        BOUND_NETWORK
    }

    private MachineManualActions() {}

    public static void collect(
            ServerPlayer player, ResourceLocation dimension, BlockPos position, boolean toNetwork,
            CollectionPolicy policy, Supplier<Optional<UnaryOperator<ItemStack>>> resolveDestination) {
        if (player instanceof FakePlayer) return;
        var access = MachineInteractionAccess.resolve(player, dimension, position, true, true);
        if (!access.success()) {
            MachineInteractionAccess.sendError(player, access.error());
            return;
        }

        // 已绑定的网络必须先解析成功；当前网络则在取货后再查找。
        Optional<UnaryOperator<ItemStack>> destination = Optional.empty();
        if (toNetwork && policy == CollectionPolicy.BOUND_NETWORK) {
            destination = resolveDestination.get();
            if (destination.isEmpty()) {
                player.sendSystemMessage(Component.translatable("rsi.generic.error.network_unavailable"));
                return;
            }
        }

        var extraction = FurnaceMachineAccess.extractOutput(access.target().blockEntity());
        if (!extraction.success()) {
            sendFurnaceError(player, extraction);
            return;
        }
        ItemStack output = extraction.stack();
        if (toNetwork) {
            if (policy == CollectionPolicy.CURRENT_NETWORK) destination = resolveDestination.get();
            ItemStack remainder = destination.map(insert -> insert.apply(output)).orElse(output);
            if (!remainder.isEmpty()) player.drop(remainder, false);
        } else if (!player.getInventory().add(output)) {
            player.drop(output, false);
        }
        player.sendSystemMessage(Component.translatable("rsi.machine.collected", output.getCount(),
                policy == CollectionPolicy.BOUND_NETWORK ? output.getHoverName()
                        : Component.translatable(output.getDescriptionId())));
    }

    public static void insert(ServerPlayer player, ResourceLocation dimension, BlockPos position, MachineSlotType slot) {
        if (player instanceof FakePlayer) return;
        var access = MachineInteractionAccess.resolve(player, dimension, position, true, true);
        if (!access.success()) {
            MachineInteractionAccess.sendError(player, access.error());
            return;
        }
        var insertion = FurnaceMachineAccess.insertCarried(player, access.target().blockEntity(), slot);
        if (!insertion.success()) sendFurnaceError(player, insertion);
    }

    private static void sendFurnaceError(ServerPlayer player, FurnaceMachineAccess.Result result) {
        Component error = FurnaceMachineAccess.errorMessage(result.error());
        if (error != null) player.sendSystemMessage(error);
    }
}
