package com.huanghuang.rsintegration.mods.rs.monitor;

import com.huanghuang.rsintegration.crafting.AsyncCraftManager;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoints;
import com.huanghuang.rsintegration.network.RSIntegrationNetwork;
import com.huanghuang.rsintegration.storage.StorageReference;
import com.huanghuang.rsintegration.storage.rs.RefinedStorageBackend;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.network.grid.GridType;
import com.refinedmods.refinedstorage.api.network.security.Permission;
import com.refinedmods.refinedstorage.apiimpl.API;
import com.refinedmods.refinedstorage.apiimpl.network.grid.factory.GridBlockGridFactory;
import com.refinedmods.refinedstorage.apiimpl.network.node.GridNetworkNode;
import com.refinedmods.refinedstorage.blockentity.grid.GridBlockEntity;
import com.refinedmods.refinedstorage.container.CraftingMonitorContainerMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

import javax.annotation.Nullable;
import java.util.Comparator;

/** 只通过 RS 原生菜单工厂导航；每次按服务端当前网络重新检查目标。 */
public final class RsMonitorNavigationServer {
    private RsMonitorNavigationServer() {}

    public enum Result {
        OPENED(null), GENERIC(null), UNAVAILABLE("rsi.monitor.navigation.unavailable"),
        DENIED("rsi.monitor.navigation.denied"),
        NO_TERMINAL("rsi.monitor.navigation.no_terminal");

        @Nullable public final String messageKey;
        Result(@Nullable String messageKey) { this.messageKey = messageKey; }
    }

    static Result openForTask(ServerPlayer player, RsOpenMonitorForCraftPacket request) {
        StorageReference reference = request.network();
        boolean ownsCraft = false;
        if (request.craftId() != null) {
            var chain = AsyncCraftManager.getInstance().getCraft(request.craftId());
            if (chain == null || chain.isDone() || !chain.belongsTo(player.getUUID())) return Result.UNAVAILABLE;
            reference = chain.monitorStorageReference();
            ownsCraft = true;
        } else if (reference == null) {
            var endpoint = CraftStorageEndpoints.resolveDefault(player).orElse(null);
            if (endpoint == null || endpoint.session() == null) return Result.GENERIC;
            reference = endpoint.session().reference();
        }
        if (reference == null || !"refinedstorage".equals(reference.backendId().value())) return Result.GENERIC;
        // 计划里的坐标仅用于定位；只有当前访问上下文或本人正在执行的订单才允许导航。
        if (!ownsCraft && !RSIntegrationNetwork.hasCurrentNetworkAccess(player, reference)) return Result.DENIED;
        return openMonitor(player, RefinedStorageBackend.resolveNativeNetwork(reference, player));
    }

    static Result openMonitor(ServerPlayer player, @Nullable INetwork network) {
        Result permission = checkPermission(player, network);
        if (permission != Result.OPENED) return permission;
        RsMonitorMenus.open(player, network);
        return Result.OPENED;
    }
    static Result returnToTerminal(ServerPlayer player, int containerId, @Nullable GlobalPos preferred) {
        if (!(player.containerMenu instanceof CraftingMonitorContainerMenu menu)
                || menu.containerId != containerId || !menu.stillValid(player)) return Result.UNAVAILABLE;
        INetwork network = RsMonitorServer.network(menu);
        Result permission = checkPermission(player, network);
        if (permission != Result.OPENED) return permission;
        GlobalPos terminal = findTerminal(player, network, preferred);
        if (terminal == null) return Result.NO_TERMINAL;
        GridBlockEntity block = resolveTerminal(player, network, terminal);
        if (block == null) return Result.NO_TERMINAL;
        if (terminal.dimension().equals(player.serverLevel().dimension()) && canOpenBlock(player, terminal.pos())) {
            API.instance().getGridManager().openGrid(GridBlockGridFactory.ID, player, terminal.pos());
        } else {
            RsMonitorMenus.openTerminal(player, block);
        }
        return player.containerMenu != menu ? Result.OPENED : Result.UNAVAILABLE;
    }

    @Nullable
    static GlobalPos findTerminal(ServerPlayer player, INetwork network, @Nullable GlobalPos preferred) {
        if (resolveTerminal(player, network, preferred) != null) return preferred;
        return network.getNodeGraph().all().stream().map(entry -> entry.getNode())
                .filter(node -> node instanceof GridNetworkNode grid && grid.getGridType() == GridType.CRAFTING
                        && node.getLevel() != null && node.getNetwork() == network)
                .map(node -> GlobalPos.of(node.getLevel().dimension(), node.getPos()))
                .filter(pos -> resolveTerminal(player, network, pos) != null)
                .min(Comparator.comparing((GlobalPos pos) -> !pos.dimension().equals(player.serverLevel().dimension()))
                        .thenComparingDouble(pos -> pos.dimension().equals(player.serverLevel().dimension())
                                ? pos.pos().distToCenterSqr(player.position()) : 0)
                        .thenComparing(pos -> pos.dimension().location().toString())
                        .thenComparing(GlobalPos::pos))
                .orElse(null);
    }

    @Nullable
    static GridBlockEntity resolveTerminal(ServerPlayer player, INetwork network, @Nullable GlobalPos position) {
        if (position == null || player.getServer() == null) return null;
        var level = player.getServer().getLevel(position.dimension());
        if (level == null || !level.isLoaded(position.pos())) return null;
        return level.getBlockEntity(position.pos()) instanceof GridBlockEntity block && isTerminal(block, network)
                ? block : null;
    }

    static boolean isTerminal(GridBlockEntity block, INetwork network) {
        var level = block.getLevel();
        return level != null && !block.isRemoved() && level.isLoaded(block.getBlockPos())
                && level.getBlockEntity(block.getBlockPos()) == block
                && block.getNode() != null && block.getNode().getNetwork() == network
                && block.getNode().getGridType() == GridType.CRAFTING && block.getNode().isGridActive();
    }

    private static Result checkPermission(ServerPlayer player, @Nullable INetwork network) {
        if (network == null || !network.canRun()) return Result.UNAVAILABLE;
        return network.getSecurityManager().hasPermission(Permission.MODIFY, player)
                && network.getSecurityManager().hasPermission(Permission.AUTOCRAFTING, player)
                ? Result.OPENED : Result.DENIED;
    }

    static boolean canOpenBlock(ServerPlayer player, BlockPos position) {
        var level = player.serverLevel();
        // 原生工厂从客户端当前维度读取方块实体，不能给它传未同步的远端坐标。
        return level.isLoaded(position)
                && level.getChunkSource().chunkMap.getPlayers(new ChunkPos(position), false).contains(player);
    }
}
