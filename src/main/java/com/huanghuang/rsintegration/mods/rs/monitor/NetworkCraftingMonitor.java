package com.huanghuang.rsintegration.mods.rs.monitor;

import com.huanghuang.rsintegration.storage.StorageReference;
import com.huanghuang.rsintegration.storage.rs.RefinedStorageBackend;
import com.huanghuang.rsintegration.storage.rs.RefinedStorageReference;
import com.refinedmods.refinedstorage.api.autocrafting.ICraftingManager;
import com.refinedmods.refinedstorage.api.autocrafting.task.ICraftingTask;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.network.security.Permission;
import com.refinedmods.refinedstorage.blockentity.craftingmonitor.ICraftingMonitor;
import com.refinedmods.refinedstorage.blockentity.data.BlockEntitySynchronizationParameter;
import com.refinedmods.refinedstorage.container.CraftingMonitorContainerMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/** 向 RS 原生监控菜单提供网络上下文；任务监听、更新和取消仍由 RS 负责。 */
public final class NetworkCraftingMonitor implements ICraftingMonitor {
    private final INetwork network;
    private final ICraftingManager manager;
    private final ServerPlayer owner;
    private final StorageReference reference;
    private final Consumer<Optional<UUID>> select;
    private Optional<UUID> selected = Optional.empty();

    NetworkCraftingMonitor(INetwork network, ServerPlayer owner) {
        this.network = network;
        this.manager = network.getCraftingManager();
        this.owner = owner;
        this.reference = RefinedStorageReference.fromNetwork(network);
        this.select = null;
    }

    NetworkCraftingMonitor(Consumer<Optional<UUID>> select) {
        this.network = null;
        this.manager = null;
        this.owner = null;
        this.reference = null;
        this.select = select;
    }

    @Nullable INetwork network() { return network; }

    boolean stillValid(Player player) {
        if (owner == null) return true;
        return player == owner && network.canRun()
                && network.getSecurityManager().hasPermission(Permission.MODIFY, player)
                && network.getSecurityManager().hasPermission(Permission.AUTOCRAFTING, player)
                && RefinedStorageBackend.resolveNativeNetwork(reference, owner) == network;
    }

    @Override public Component getTitle() { return Component.translatable("gui.refinedstorage.crafting_monitor"); }
    @Override public Collection<ICraftingTask> getTasks() {
        return manager == null || !stillValid(owner) ? List.of() : manager.getTasks();
    }
    // 关闭时 RS 会从这里取原管理器移除监听，网络失效时也必须保留这个对象。
    @Override public ICraftingManager getCraftingManager() { return manager; }
    @Override public void onCancelled(ServerPlayer player, @Nullable UUID taskId) {
        if (owner != null && stillValid(player) && player.containerMenu instanceof CraftingMonitorContainerMenu menu
                && menu.getCraftingMonitor() == this) {
            network.getItemGridHandler().onCraftingCancelRequested(player, taskId);
        }
    }
    @Override public BlockEntitySynchronizationParameter<Integer, ?> getRedstoneModeParameter() { return null; }
    @Override public boolean isActiveOnClient() { return true; }
    @Override public void onClosed(Player player) {}
    @Override public Optional<UUID> getTabSelected() { return selected; }
    @Override public int getTabPage() { return 0; }
    @Override public int getSlotId() { return -1; }
    @Override public void onTabPageChanged(int page) {}
    @Override public void onTabSelectionChanged(Optional<UUID> selection) {
        selected = selection;
        if (select != null) select.accept(selection);
    }
}
