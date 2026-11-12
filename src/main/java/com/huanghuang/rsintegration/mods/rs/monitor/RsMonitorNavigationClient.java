package com.huanghuang.rsintegration.mods.rs.monitor;

import com.huanghuang.rsintegration.crafting.batch.BatchCraftNetworkHandler;
import com.huanghuang.rsintegration.crafting.planning.PlanningTasksScreen;
import com.huanghuang.rsintegration.crafting.progress.CraftProgressScreen;
import com.huanghuang.rsintegration.storage.StorageReference;
import com.huanghuang.rsintegration.mods.rs.monitor.RsMonitorNavigationServer.Result;
import com.refinedmods.refinedstorage.container.CraftingMonitorContainerMenu;
import com.refinedmods.refinedstorage.container.GridContainerMenu;
import com.refinedmods.refinedstorage.api.network.grid.GridType;
import com.refinedmods.refinedstorage.screen.grid.GridScreen;
import net.minecraft.core.GlobalPos;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.MenuScreens;
import com.refinedmods.refinedstorage.screen.CraftingMonitorScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import javax.annotation.Nullable;
import java.util.UUID;

/** 保留从有线 RS 合成终端进入原生监控器的返回位置。 */
public final class RsMonitorNavigationClient {
    private static GlobalPos pendingTerminal;
    private static long pendingAt;
    private static long nextRequest;
    private static long pendingRequest;
    private static Screen pendingScreen;

    private RsMonitorNavigationClient() {}

    public static void registerScreen() {
        MenuScreens.register(RsMonitorMenus.NETWORK_MONITOR.get(), CraftingMonitorScreen::new);
        MenuScreens.<GridContainerMenu, GridScreen>register(RsMonitorMenus.REMOTE_CRAFTING_GRID.get(),
                (menu, inventory, title) -> new GridScreen(menu, menu.getGrid(), inventory, title));
    }

    public static void openMonitor(GridScreen screen) {
        GridContainerMenu menu = screen.getMenu();
        if (menu.getGrid().getGridType() != GridType.CRAFTING) return;
        GlobalPos terminal;
        if (menu instanceof RemoteCraftingGridMenu remote) terminal = remote.terminal();
        else if (menu.getBlockEntity() != null && menu.getBlockEntity().getLevel() != null)
            terminal = GlobalPos.of(menu.getBlockEntity().getLevel().dimension(), menu.getBlockEntity().getBlockPos());
        else return;
        queueReturnTerminal(terminal);
        BatchCraftNetworkHandler.CHANNEL.sendToServer(new RsOpenCraftingMonitorPacket(terminal));
    }

    public static void openExecutionMonitor(@Nullable StorageReference network, @Nullable UUID craftId) {
        pendingScreen = Minecraft.getInstance().screen;
        pendingRequest = ++nextRequest;
        queueReturnTerminal(null);
        BatchCraftNetworkHandler.CHANNEL.sendToServer(new RsOpenMonitorForCraftPacket(pendingRequest, network, craftId));
    }

    public static void accept(RsMonitorNavigationResultPacket packet) {
        if (packet.requestId() != pendingRequest) return;
        var minecraft = Minecraft.getInstance();
        Screen source = pendingScreen;
        pendingRequest = 0L;
        pendingScreen = null;
        if (minecraft.screen != source) return;
        if (packet.result() == Result.GENERIC) minecraft.setScreen(new CraftProgressScreen());
        else if (packet.result().messageKey != null) {
            Component message = Component.translatable(packet.result().messageKey);
            if (source instanceof PlanningTasksScreen tasks) tasks.showMonitorNotice(message);
            else if (minecraft.player != null) minecraft.player.displayClientMessage(message, false);
        }
    }

    public static void queueReturnTerminal(GlobalPos terminal) {
        pendingTerminal = terminal;
        pendingAt = pendingTerminal == null ? 0L : System.currentTimeMillis();
    }

    public static GlobalPos consumePendingTerminal() {
        GlobalPos result = pendingTerminal;
        pendingTerminal = null;
        return result != null && System.currentTimeMillis() - pendingAt < 10_000L ? result : null;
    }

    public static void returnToTerminal(CraftingMonitorContainerMenu menu, MonitorNavigationAccess access) {
        BatchCraftNetworkHandler.CHANNEL.sendToServer(new RsReturnToCraftingTerminalPacket(
                menu.containerId, access.rsi$returnTerminal()));
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        pendingTerminal = null;
        pendingAt = 0L;
        pendingRequest = 0L;
        pendingScreen = null;
    }
}
