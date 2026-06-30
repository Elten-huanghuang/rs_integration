package com.huanghuang.rsintegration.sidepanel.client;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.machine.MachineHub;
import com.huanghuang.rsintegration.machine.MachineHubRenderer;
import com.huanghuang.rsintegration.mixin.rs.AbstractContainerScreenAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Caches the RS GridScreen before opening a bound machine GUI, so that
 * pressing ESC in the machine GUI returns to the RS terminal instead of
 * the game world.
 *
 * <p>Call {@link #pushCurrent()} before the machine GUI opens.
 * The {@code ScreenMixin} (rs.ScreenCloseMixin) calls
 * {@link #onScreenRemoved(Screen)} when any screen is removed —
 * if the removed screen is NOT the cached screen, the cached screen
 * is restored.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class GuiNavStack {
    private static Screen cachedScreen;
    private static boolean pendingRestore;
    private static boolean hookRegistered;
    private static Screen restoreTarget;

    static {
        MinecraftForge.EVENT_BUS.register(GuiNavStack.class);
        hookRegistered = true;
    }

    private GuiNavStack() {}

    /** Save the currently displayed screen as the return target. */
    public static void pushCurrent() {
        Screen current = Minecraft.getInstance().screen;
        if (current != null) {
            cachedScreen = current;
            pendingRestore = true;
            registerLogoutHook();
            RSIntegrationMod.LOGGER.debug("[RSI-GuiNav] Pushed screen: {}",
                    current.getClass().getSimpleName());
        }
    }

    /**
     * Called from {@code ScreenCloseMixin} when any screen's {@code removed()}
     * method fires.  If the removed screen is different from the cached screen
     * (i.e. a machine GUI is closing), restore the cached RS GridScreen.
     */
    public static void onScreenRemoved(Screen removed) {
        if (!pendingRestore || removed == null) return;

        Screen cached = cachedScreen;
        if (cached == null) {
            pendingRestore = false;
            return;
        }

        // When the cached RS GridScreen itself is removed (because the machine
        // GUI is opening), do nothing — we WANT the machine GUI on top.
        if (removed == cached) return;

        // Safety: don't restore a screen that's already the current screen
        Screen current = Minecraft.getInstance().screen;
        if (current == cached) {
            pendingRestore = false;
            cachedScreen = null;
            return;
        }

        // A different screen (the machine GUI) closed — restore RS GridScreen
        // if the user has enabled return-to-RS in config.
        pendingRestore = false;
        cachedScreen = null;
        if (!RSIntegrationConfig.RETURN_TO_RS_AFTER_MACHINE_GUI.get()) {
            return;
        }
        // Defer to next client tick because we are inside
        // Minecraft.setScreen(null) → oldScreen.removed().  If we
        // called setScreen(cached) now, the outer setScreen would
        // still set this.screen = null and destroy the restore.
        restoreTarget = cached;
    }

    /** Clear state on logout to prevent stale references. */
    private static void registerLogoutHook() {
        if (hookRegistered) return;
        MinecraftForge.EVENT_BUS.register(GuiNavStack.class);
        hookRegistered = true;
    }

    @SubscribeEvent
    public static void onClientLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        cachedScreen = null;
        pendingRestore = false;
    }

    /**
     * Renders the Hub overlay on top of everything including JEI.
     * Uses LOWEST priority so it draws AFTER JEI's ingredient panel.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onScreenRenderPost(ScreenEvent.Render.Post event) {
        if (!MachineHub.isVisible()) return;
        Screen screen = event.getScreen();
        if (!(screen instanceof AbstractContainerScreen<?> acs)) return;
        if (!screen.getClass().getName().contains("GridScreen")) return;

        AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) acs;
        MachineHubRenderer.render(event.getGuiGraphics(), acc.getLeftPos(), acc.getTopPos(),
                acc.getImageWidth(), event.getMouseX(), event.getMouseY());
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        if (restoreTarget != null) {
            Screen target = restoreTarget;
            restoreTarget = null;
            Minecraft.getInstance().setScreen(target);
        }
    }
}
