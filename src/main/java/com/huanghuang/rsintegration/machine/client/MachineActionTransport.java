package com.huanghuang.rsintegration.machine.client;

import com.huanghuang.rsintegration.network.machine.bd.BeyondDimensionsMachineCollectPacket;
import com.huanghuang.rsintegration.network.machine.bd.BeyondDimensionsMachineInsertPacket;
import com.huanghuang.rsintegration.network.machine.bd.BeyondDimensionsOpenBoundMachineGuiPacket;
import com.huanghuang.rsintegration.network.machine.bd.BeyondDimensionsUnbindMachinePacket;
import com.huanghuang.rsintegration.machine.MachineSlotType;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.huanghuang.rsintegration.network.binding.BindingInfo;
import com.huanghuang.rsintegration.network.machine.MachineCollectPacket;
import com.huanghuang.rsintegration.network.machine.MachineInsertPacket;
import com.huanghuang.rsintegration.network.machine.OpenBoundMachineGuiPacket;
import com.huanghuang.rsintegration.network.machine.UnbindMachinePacket;
import net.minecraftforge.fml.ModList;

/** 根据机器绑定来源选择对应的网络协议，避免每个动作重复判断后端。 */
final class MachineActionTransport {
    enum Backend {
        REFINED_STORAGE,
        BEYOND_DIMENSIONS
    }

    private MachineActionTransport() {}

    static Backend backendOf(BindingInfo info) {
        return resolveBackend(
                info.itemKey(), ModList.get().isLoaded("beyonddimensions"));
    }

    static Backend resolveBackend(String itemKey, boolean beyondDimensionsLoaded) {
        return beyondDimensionsLoaded
                && itemKey != null
                && itemKey.startsWith("beyonddimensions:")
                ? Backend.BEYOND_DIMENSIONS
                : Backend.REFINED_STORAGE;
    }

    static void open(BindingInfo info, Backend backend) {
        if (backend == Backend.BEYOND_DIMENSIONS) {
            NetworkHandler.CHANNEL.sendToServer(
                    new BeyondDimensionsOpenBoundMachineGuiPacket(
                            info.dim(), info.pos()));
            return;
        }
        NetworkHandler.CHANNEL.sendToServer(
                new OpenBoundMachineGuiPacket(
                        info.dim(), info.pos(), info.itemKey()));
    }

    static void collect(
            BindingInfo info, boolean toNetwork, Backend backend) {
        if (backend == Backend.BEYOND_DIMENSIONS) {
            NetworkHandler.CHANNEL.sendToServer(
                    new BeyondDimensionsMachineCollectPacket(
                            info.dim(), info.pos(), toNetwork));
            return;
        }
        NetworkHandler.CHANNEL.sendToServer(
                new MachineCollectPacket(
                        info.dim(), info.pos(), toNetwork));
    }

    static void insert(
            BindingInfo info, MachineSlotType slot, Backend backend) {
        if (backend == Backend.BEYOND_DIMENSIONS) {
            NetworkHandler.CHANNEL.sendToServer(
                    new BeyondDimensionsMachineInsertPacket(
                            info.dim(), info.pos(), slot));
            return;
        }
        NetworkHandler.CHANNEL.sendToServer(
                new MachineInsertPacket(info.dim(), info.pos(), slot));
    }

    static void unbind(BindingInfo info, Backend backend) {
        if (backend == Backend.BEYOND_DIMENSIONS) {
            NetworkHandler.CHANNEL.sendToServer(
                    new BeyondDimensionsUnbindMachinePacket(
                            info.dim(), info.pos()));
            return;
        }
        NetworkHandler.CHANNEL.sendToServer(
                new UnbindMachinePacket(info.dim(), info.pos()));
    }
}
