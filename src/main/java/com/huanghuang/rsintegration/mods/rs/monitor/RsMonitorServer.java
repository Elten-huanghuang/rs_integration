package com.huanghuang.rsintegration.mods.rs.monitor;

import com.huanghuang.rsintegration.crafting.AsyncCraftChain;
import com.huanghuang.rsintegration.crafting.AsyncCraftManager;
import com.huanghuang.rsintegration.crafting.batch.CraftStatusRequestPacket;
import com.huanghuang.rsintegration.mixin.refinedstorage.CraftingManagerNetworkAccessor;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.huanghuang.rsintegration.storage.StorageReference;
import com.huanghuang.rsintegration.storage.rs.RefinedStorageReference;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.network.security.Permission;
import com.refinedmods.refinedstorage.container.CraftingMonitorContainerMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/** 原生监控菜单的网络范围。进度仍走本人的既有全量/增量通道。 */
public final class RsMonitorServer {
    private RsMonitorServer() {}

    @Nullable
    public static INetwork network(CraftingMonitorContainerMenu menu) {
        if (menu.getCraftingMonitor() instanceof NetworkCraftingMonitor monitor) return monitor.network();
        var manager = menu.getCraftingMonitor().getCraftingManager();
        return manager instanceof CraftingManagerNetworkAccessor access ? access.rsi$getNetwork() : null;
    }

    @Nullable
    public static StorageReference authorizedNetwork(ServerPlayer player, CraftingMonitorContainerMenu menu) {
        if (player.containerMenu != menu || !menu.stillValid(player)) return null;
        INetwork network = network(menu);
        if (network == null || !network.canRun()
                || !network.getSecurityManager().hasPermission(Permission.AUTOCRAFTING, player)) return null;
        return RefinedStorageReference.fromNetwork(network);
    }

    public static void synchronize(ServerPlayer player, CraftingMonitorContainerMenu menu,
                                   MonitorScopeState state) {
        StorageReference reference = authorizedNetwork(player, menu);
        List<AsyncCraftChain> chains = reference == null ? List.of()
                : AsyncCraftManager.getInstance().activeCraftsFor(player.getUUID()).stream()
                .filter(chain -> reference.equals(chain.monitorStorageReference())).toList();
        var previous = state.current();
        var changed = state.update(reference, chains.stream().map(AsyncCraftChain::getCraftId).toList());
        if (changed == null) {
            synchronizeMaterials(player, menu, state, chains);
            return;
        }
        // 只对首次进入本菜单的任务补元数据和状态，不因打开监控器重复遍历每棵执行图。
        for (AsyncCraftChain chain : chains) {
            if (previous == null || !previous.crafts().contains(chain.getCraftId())) {
                CraftStatusRequestPacket.sendStatus(player, chain);
            }
        }
        NetworkHandler.CHANNEL.sendTo(new RsMonitorScopePacket(menu.containerId, changed),
                player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
        synchronizeMaterials(player, menu, state, chains);
    }

    private static void synchronizeMaterials(ServerPlayer player, CraftingMonitorContainerMenu menu,
                                              MonitorScopeState state, List<AsyncCraftChain> chains) {
        UUID selected = menu.getCraftingMonitor().getTabSelected().orElse(null);
        if (selected == null) return;
        for (AsyncCraftChain chain : chains) if (selected.equals(chain.getCraftId())) {
            if (!state.materials().shouldSample(selected, chain.monitorMaterialRevision())) return;
            var packet = state.materials().update(menu.containerId, state.current().session(), selected, chain.monitorMaterialView());
            if (packet != null) NetworkHandler.CHANNEL.sendTo(packet, player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
            return;
        }
    }

    public static void cancel(ServerPlayer player, RsMonitorCancelPacket packet) {
        if (!(player.containerMenu instanceof CraftingMonitorContainerMenu menu)
                || menu.containerId != packet.containerId()
                || !(menu instanceof MonitorMenuAccess access)) return;
        StorageReference reference = authorizedNetwork(player, menu);
        if (!access.rsi$monitorScope().allows(packet.session(), packet.craftId(), reference)) return;
        AsyncCraftChain chain = AsyncCraftManager.getInstance().getCraft(packet.craftId());
        if (chain == null || chain.isDone() || !chain.belongsTo(player.getUUID())
                || !reference.equals(chain.monitorStorageReference())) return;
        chain.cancel("Player cancelled from RS crafting monitor",
                Component.translatable("rsi.async.abort.player_cancelled"));
    }
}
