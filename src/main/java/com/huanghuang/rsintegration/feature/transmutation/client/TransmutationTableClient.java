package com.huanghuang.rsintegration.feature.transmutation.client;

import com.huanghuang.rsintegration.client.recipe.RecipeBrowserBridge;
import com.huanghuang.rsintegration.feature.transmutation.TransmutationTableMenuAccess;
import com.huanghuang.rsintegration.feature.transmutation.TransmutationTableReroll;
import com.huanghuang.rsintegration.feature.transmutation.TransmutationTableReroll.Outcome;
import com.huanghuang.rsintegration.feature.villager.tradelock.VillagerTradeLockService;
import com.huanghuang.rsintegration.feature.villager.tradelock.VillagerTradeLockSnapshotPacket;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.lwjgl.glfw.GLFW;

public final class TransmutationTableClient {
    private static final String SCREEN =
            "com.github.alexthe666.alexsmobs.client.gui.GUITransmutationTable";
    private static final TransmutationHoldController HOLD = new TransmutationHoldController();
    private static Screen activeScreen;

    private TransmutationTableClient() {}

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void keyPressed(ScreenEvent.KeyPressed.Pre event) {
        if (event.getKeyCode() != GLFW.GLFW_KEY_SPACE || event.getModifiers() != 0
                || !isTable(event.getScreen())
                || hasTextFocus(event.getScreen())) return;
        // 吞掉系统连发，避免空格同时触发已聚焦的原版嬗变按钮。
        event.setCanceled(true);
        if (!HOLD.press()) return;
        activeScreen = event.getScreen();
        NetworkHandler.CHANNEL.sendToServer(new VillagerTradeLockSnapshotPacket(
                RecipeBrowserBridge.favoriteItems(VillagerTradeLockService.MAX_BOOKMARKS)));
    }

    @SubscribeEvent
    public static void keyReleased(ScreenEvent.KeyReleased.Pre event) {
        if (event.getKeyCode() == GLFW.GLFW_KEY_SPACE) HOLD.release();
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (!InputConstants.isKeyDown(minecraft.getWindow().getWindow(), GLFW.GLFW_KEY_SPACE)) {
            HOLD.release();
            activeScreen = null;
            return;
        }
        if (!minecraft.isWindowActive() || minecraft.player == null || minecraft.gameMode == null
                || minecraft.screen != activeScreen || !isTable(minecraft.screen)
                || hasTextFocus(minecraft.screen)
                || Screen.hasShiftDown() || Screen.hasControlDown() || Screen.hasAltDown()) {
            HOLD.cancel();
            activeScreen = null;
            return;
        }
        AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) minecraft.screen;
        TransmutationTableMenuAccess menu = (TransmutationTableMenuAccess) screen.getMenu();
        Outcome previousStop = HOLD.stoppedWith();
        if (HOLD.nextRequest(menu.rsIntegration$getRerollState())) {
            minecraft.gameMode.handleInventoryButtonClick(
                    screen.getMenu().containerId, TransmutationTableReroll.BUTTON_ID);
        }
        Outcome stopped = HOLD.stoppedWith();
        if (stopped != null && stopped != previousStop) {
            minecraft.player.displayClientMessage(Component.translatable(stopped == Outcome.MATCHED
                    ? "rsi.transmutation.matched" : "rsi.transmutation.unavailable"), true);
        }
    }

    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        HOLD.release();
        activeScreen = null;
    }

    private static boolean isTable(Screen screen) {
        return screen != null && SCREEN.equals(screen.getClass().getName())
                && screen instanceof AbstractContainerScreen<?> container
                && container.getMenu() instanceof TransmutationTableMenuAccess;
    }

    static boolean hasTextFocus(Screen screen) {
        return screen.getFocused() instanceof EditBox || RecipeBrowserBridge.hasSearchFocus();
    }
}
