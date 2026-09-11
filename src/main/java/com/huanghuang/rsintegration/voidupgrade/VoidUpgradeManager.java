package com.huanghuang.rsintegration.voidupgrade;

import com.huanghuang.rsintegration.ModItems;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.network.node.INetworkNode;
import com.refinedmods.refinedstorage.apiimpl.network.node.GridNetworkNode;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandlerModifiable;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public final class VoidUpgradeManager {
    private static final Map<INetwork, VoidUpgradeCoordinator> COORDINATORS =
            new IdentityHashMap<>();

    private VoidUpgradeManager() {}

    public static void onGridConnected(GridNetworkNode node, INetwork network) {
        if (hasUpgrade(node)) refresh(network);
    }

    public static void onGridDisconnected(INetwork network) {
        if (COORDINATORS.containsKey(network)) refresh(network);
    }

    public static void onGridFilterChanged(GridNetworkNode node) {
        INetwork network = node.getNetwork();
        if (network != null && (hasUpgrade(node) || COORDINATORS.containsKey(network))) {
            refresh(network);
        }
    }

    public static void tick(INetwork network) {
        VoidUpgradeCoordinator coordinator = COORDINATORS.get(network);
        if (coordinator != null) coordinator.tick();
    }

    public static void remove(INetwork network) {
        VoidUpgradeCoordinator coordinator = COORDINATORS.remove(network);
        if (coordinator != null) coordinator.detach();
    }

    private static void refresh(INetwork network) {
        List<VoidUpgradeConfig> configs = collect(network);
        VoidUpgradeCoordinator coordinator = COORDINATORS.get(network);
        if (configs.isEmpty()) {
            if (coordinator != null) {
                coordinator.detach();
                COORDINATORS.remove(network);
            }
            return;
        }
        if (coordinator == null) {
            coordinator = new VoidUpgradeCoordinator(network);
            COORDINATORS.put(network, coordinator);
        }
        coordinator.setRules(configs);
        if (!coordinator.isActive()) COORDINATORS.remove(network);
    }

    private static List<VoidUpgradeConfig> collect(INetwork network) {
        List<VoidUpgradeConfig> configs = new ArrayList<>();
        for (var entry : network.getNodeGraph().all()) {
            INetworkNode node = entry.getNode();
            if (!(node instanceof GridNetworkNode grid) || grid.getNetwork() != network) continue;
            IItemHandlerModifiable handler = grid.getFilter();
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                ItemStack stack = handler.getStackInSlot(slot);
                if (isUpgrade(stack)) {
                    VoidUpgradeConfig config = VoidUpgradeConfig.fromStack(stack);
                    if (!config.rules().isEmpty()) configs.add(config);
                }
            }
        }
        return configs;
    }

    private static boolean hasUpgrade(GridNetworkNode node) {
        IItemHandlerModifiable handler = node.getFilter();
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            if (isUpgrade(handler.getStackInSlot(slot))) return true;
        }
        return false;
    }

    private static boolean isUpgrade(ItemStack stack) {
        return !stack.isEmpty() && ModItems.RS_VOID_UPGRADE != null
                && stack.is(ModItems.RS_VOID_UPGRADE.get());
    }
}
