package com.huanghuang.rsintegration.sidepanel.client;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.lang.ref.WeakReference;

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
    private static WeakReference<Screen> cachedScreen = new WeakReference<>(null);
    private static boolean pendingRestore;
    private static boolean hookRegistered;

    private GuiNavStack() {}

    /** Save the currently displayed screen as the return target. */
    public static void pushCurrent() {
        Screen current = Minecraft.getInstance().getInstance().screen;
        if (current != null) {
            cachedScreen = new WeakReference<>(current);
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

        Screen cached = cachedScreen.get();
        if (cached == null) {
            pendingRestore = false;
            RSIntegrationMod.LOGGER.debug("[RSI-GuiNav] pendingRestore cleared: cached screen GC'd");
            return;
        }

        // When the cached RS GridScreen itself is removed (because the machine
        // GUI is opening), do nothing — we WANT the machine GUI on top.
        if (removed == cached) return;

        // Safety: don't restore a screen that's already the current screen
        Screen current = Minecraft.getInstance().getInstance().screen;
        if (current == cached) {
            pendingRestore = false;
            cachedScreen.clear();
            RSIntegrationMod.LOGGER.debug("[RSI-GuiNav] pendingRestore cleared: cached already on top");
            return;
        }

        // A different screen (the machine GUI) closed — restore RS GridScreen
        // if the user has enabled return-to-RS in config.
        pendingRestore = false;
        cachedScreen.clear();
        if (!RSIntegrationConfig.RETURN_TO_RS_AFTER_MACHINE_GUI.get()) {
            RSIntegrationMod.LOGGER.info("[RSI-GuiNav] Not restoring (returnToRsAfterMachineGui=false)");
            return;
        }
        RSIntegrationMod.LOGGER.info("[RSI-GuiNav] Restoring cached screen: {} (removed={})",
                cached.getClass().getSimpleName(), removed.getClass().getSimpleName());
        Minecraft.getInstance().getInstance().setScreen(cached);
    }

    /** Clear state on logout to prevent stale references. */
    private static void registerLogoutHook() {
        if (hookRegistered) return;
        MinecraftForge.EVENT_BUS.register(GuiNavStack.class);
        hookRegistered = true;
    }

    @SubscribeEvent
    public static void onClientLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        cachedScreen.clear();
        pendingRestore = false;
        RSIntegrationMod.LOGGER.debug("[RSI-GuiNav] Cleared on logout");
    }
}
