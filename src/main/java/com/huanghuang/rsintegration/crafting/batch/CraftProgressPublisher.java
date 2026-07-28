package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftProgressSnapshot;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Stateful full/delta progress publisher extracted from the craft execution state machine. */
public final class CraftProgressPublisher {
    private final UUID craftId;
    private CraftProgressSnapshot lastSent;
    private boolean terminalSent;

    public CraftProgressPublisher(UUID craftId) {
        this.craftId = craftId;
    }

    public void publish(ServerPlayer player, CraftProgressSnapshot snapshot, int tickCounter) {
        if (player == null || player.connection == null) return;
        boolean terminal = snapshot.isTerminal();
        if (terminal) {
            if (terminalSent) return;
            terminalSent = true;
        } else if (samePayload(lastSent, snapshot)) {
            return;
        }
        RSIntegrationMod.LOGGER.debug("[RSI-progress] craft={} sequence={} result={} reason={} nodes={}/{} running={}",
                craftId, snapshot.sequence(), snapshot.result(), snapshot.reason(),
                snapshot.completedNodes(), snapshot.totalNodes(), snapshot.runningNodes());
        if (!terminal && lastSent != null && tickCounter % 100 != 0) {
            BatchCraftNetworkHandler.CHANNEL.sendTo(
                    new CraftProgressDeltaPacket(craftId, lastSent.sequence(), snapshot,
                            changedNodes(lastSent, snapshot)),
                    player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
        } else {
            BatchCraftNetworkHandler.CHANNEL.sendTo(new CraftProgressPacket(snapshot),
                    player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
        }
        if (!terminal) lastSent = snapshot;
    }

    static List<CraftProgressSnapshot.NodeProgress> changedNodes(
            CraftProgressSnapshot previous, CraftProgressSnapshot current) {
        Map<Integer, CraftProgressSnapshot.NodeProgress> old = new HashMap<>();
        for (CraftProgressSnapshot.NodeProgress node : previous.nodes()) old.put(node.nodeId(), node);
        List<CraftProgressSnapshot.NodeProgress> changed = new ArrayList<>();
        for (CraftProgressSnapshot.NodeProgress node : current.nodes()) {
            if (!Objects.equals(old.get(node.nodeId()), node)) changed.add(node);
        }
        return List.copyOf(changed);
    }

    static boolean samePayload(CraftProgressSnapshot previous, CraftProgressSnapshot current) {
        if (previous == null) return false;
        return previous.result() == current.result()
                && previous.reason() == current.reason()
                && previous.completedNodes() == current.completedNodes()
                && previous.totalNodes() == current.totalNodes()
                && previous.runningNodes() == current.runningNodes()
                && Objects.equals(previous.technicalDetail(), current.technicalDetail())
                && Objects.equals(previous.nodes(), current.nodes());
    }
}
