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
 * The {@code ScreenCloseMixin} (rs.ScreenCloseMixin) calls
 * {@link #onScreenRemoved(Screen)} when any screen is removed —
 * if the removed screen is NOT the cached screen, the cached screen
 * is restored.</p>
 *
 * <p>{@link #pushCurrent()} only sets {@code cachedScreen} once —
 * subsequent calls from intermediate screens (JEI, CraftingPlanScreen)
 * set {@code expectingReplace} so the intermediate removal is correctly
 * ignored by {@link #onScreenRemoved}.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class GuiNavStack {
    private static Screen cachedScreen;
    private static boolean pendingRestore;
    /** true when we just called pushCurrent() and expect the cached
     *  screen to be replaced by a mod-initiated GUI.  Distinguishes
     *  "replaced by our GUI" from "user closed it manually". */
    private static boolean expectingReplace;
    private static boolean hookRegistered;
    private static Screen restoreTarget;

    static {
        MinecraftForge.EVENT_BUS.register(GuiNavStack.class);
        hookRegistered = true;
    }

    private GuiNavStack() {}

    /** Save the currently displayed screen as the return target.
     *  If a screen is already cached, does NOT overwrite it —
     *  the first/outermost RS GridScreen is always the return target. */
    public static void pushCurrent() {
        Screen current = Minecraft.getInstance().screen;
        if (current != null) {
            if (cachedScreen == null) {
                cachedScreen = current;
                pendingRestore = true;
                registerLogoutHook();
                RSIntegrationMod.LOGGER.debug("[RSI-GuiNav] Pushed screen: {}",
                        current.getClass().getSimpleName());
            }
            expectingReplace = true;
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
            expectingReplace = false;
            return;
        }

        if (removed == cached) {
            if (expectingReplace) {
                // Cached screen is being replaced by our machine GUI / intermediate
                // screen — keep it on standby for the eventual restore.
                expectingReplace = false;
                return;
            }
            // Cached screen was closed manually (user pressed E / ESC).
            // Clear state so a stale screen isn't restored later.
            pendingRestore = false;
            cachedScreen = null;
            expectingReplace = false;
            RSIntegrationMod.LOGGER.debug("[RSI-GuiNav] Cached screen closed manually — cleared");
            return;
        }

        // removed != cached — a machine GUI or intermediate screen is closing.
        if (expectingReplace) {
            // We're still in a replacement transition (e.g. intermediate
            // screen replaced by the actual machine GUI).  Keep the cached
            // screen on standby.
            expectingReplace = false;
            return;
        }

        // Genuine close of a machine GUI — restore the cached RS GridScreen.
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
        RSIntegrationMod.LOGGER.debug("[RSI-GuiNav] Scheduling restore of: {}",
                cached.getClass().getSimpleName());
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
        expectingReplace = false;
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
            RSIntegrationMod.LOGGER.debug("[RSI-GuiNav] Restoring screen: {}",
                    target.getClass().getSimpleName());
            Minecraft.getInstance().setScreen(target);
        }
    }
}
