package com.huanghuang.rsintegration.sidepanel.client;
import java.lang.reflect.Field;

import com.huanghuang.rsintegration.RSIntegrationMod;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraftforge.event.TickEvent;

import com.huanghuang.rsintegration.config.GuiTimingConfig;
import com.huanghuang.rsintegration.machine.MachineHub;
import com.huanghuang.rsintegration.machine.MachineHubRenderer;
import com.huanghuang.rsintegration.sidepanel.RSSidePanelNetworkHandler;
import com.huanghuang.rsintegration.sidepanel.network.ReturnToRSPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;

/**
 * Manages the "return to RS Grid after closing machine GUI" flow.
 *
 * <p>Instead of caching a live {@link Screen} (which holds a stale
 * {@code ContainerMenu} that causes item duplication when restored),
 * we store the RS Grid's {@link BlockPos} and send a
 * {@link ReturnToRSPacket} so the server legally re-opens the grid
 * through the normal Forge pipeline.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class GuiNavStack {

    private static BlockPos cachedGridPos;
    private static ResourceLocation cachedGridDim;
    private static int pendingRestores;
    private static long pushTimestamp;

    static {
        MinecraftForge.EVENT_BUS.register(GuiNavStack.class);
    }

    private GuiNavStack() {}

    /**
     * Save the RS Grid's position before a machine GUI opens.  When the
     * player presses ESC we'll ask the server to re-open the grid at this
     * position instead of restoring a dead client-side Screen.
     */
    public static void pushCurrent() {
        if (!ModList.get().isLoaded("refinedstorage")) {
            // The return-to-grid flow is RS-specific.  BD machine GUI paths
            // must not initialize or inspect RS client classes.
            return;
        }
        Screen current = Minecraft.getInstance().screen;
        if (current != null && cachedGridPos == null) {
            extractGridPosition(current);
        }
        pendingRestores++;
        pushTimestamp = System.currentTimeMillis();
    }

    /** Try to find the RS Grid's BlockPos from the current GridScreen. */
    private static void extractGridPosition(Screen screen) {
        try {
            if (!(screen instanceof AbstractContainerScreen<?> acs)) return;
            var menu = acs.getMenu();

            // 1. Try getGrid() — RS GridContainerMenu may return IGrid (which is
            //    implemented by GridBlockEntity but the interface itself does not
            //    extend BlockEntity, so instanceof check fails for proxies/wrappers).
            try {
                var m = menu.getClass().getMethod("getGrid");
                Object grid = m.invoke(menu);
                BlockEntity be = unwrapBlockEntity(grid);
                if (be != null) {
                    cachePos(be, "getGrid()");
                    return;
                }
            } catch (NoSuchMethodException e) {
                RSIntegrationMod.LOGGER.debug("[RSI-SidePanel] reflection probe failed", e);
            }

            // 2. Scan all declared fields across the class hierarchy for any object
            //    that can yield a BlockEntity (direct BlockEntity, IGrid, INetworkNode, etc.).
            Class<?> clazz = menu.getClass();
            while (clazz != null && clazz != Object.class) {
                for (var field : clazz.getDeclaredFields()) {
                    if (field.getType().isPrimitive()) continue;
                    field.setAccessible(true);
                    Object value = field.get(menu);
                    if (value == null) continue;
                    BlockEntity be = unwrapBlockEntity(value);
                    if (be != null) {
                        cachePos(be, "field " + field.getName());
                        return;
                    }
                }
                clazz = clazz.getSuperclass();
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-GuiNav] Failed to extract grid position", e);
        }
    }

    /**
     * Try to extract a {@link BlockEntity} from an arbitrary object.
     * <ul>
     *   <li>Direct instanceof BlockEntity</li>
     *   <li>Try {@code getNode()}, {@code getNetworkNode()}, {@code getBlockEntity()}</li>
     *   <li>Try {@code getOwner()} for IGrid proxies</li>
     * </ul>
     */
    private static BlockEntity unwrapBlockEntity(Object obj) {
        if (obj == null) return null;
        if (obj instanceof BlockEntity be) return be;
        for (String methodName : new String[]{"getNode", "getNetworkNode", "getBlockEntity", "getOwner"}) {
            try {
                var m = obj.getClass().getMethod(methodName);
                Object result = m.invoke(obj);
                if (result instanceof BlockEntity be) return be;
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.debug("[RSI-SidePanel] reflection probe failed", e);
            }
        }
        return null;
    }

    private static void cachePos(BlockEntity be, String source) {
        cachedGridPos = be.getBlockPos();
        cachedGridDim = Minecraft.getInstance().player.level().dimension().location();
        RSIntegrationMod.LOGGER.debug("[RSI-GuiNav] Cached grid pos via {}: {} dim={}",
                source, cachedGridPos, cachedGridDim);
    }

    /**
     * Called from {@code MinecraftMixin} when the game is about to close
     * the current screen ({@code setScreen(null)}).  Returns {@code null}
     * — we never restore a cached Screen.  Instead we send a packet to
     * let the server re-open the RS Grid.
     */
    public static Screen popRestoreTarget(Screen closing) {
        if (pendingRestores > 0) pendingRestores--;

        if (pendingRestores > 0) return null;

        if (!ModList.get().isLoaded("refinedstorage")) {
            clearPending();
            return null;
        }

        // Send packet to server to re-open RS Grid (if we have a position)
        if (cachedGridPos != null && cachedGridDim != null) {
            RSIntegrationMod.LOGGER.debug("[RSI-GuiNav] Requesting return to RS Grid at {} dim={}",
                    cachedGridPos, cachedGridDim);
            RSSidePanelNetworkHandler.CHANNEL.sendToServer(
                    new ReturnToRSPacket(cachedGridDim, cachedGridPos));
        }
        clearPending();
        return null; // Never restore a cached Screen
    }

    public static void onScreenChanged(Screen newScreen) {
        if (newScreen instanceof PauseScreen) {
            clearPending();
        }
    }

    public static void clearPending() {
        pendingRestores = 0;
        cachedGridPos = null;
        cachedGridDim = null;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        long now = System.currentTimeMillis();
        if (pendingRestores > 0 && hasPushTimedOut(now, pushTimestamp, configuredPushTimeoutMs())) {
            RSIntegrationMod.LOGGER.debug("[RSI-GuiNav] Push timeout ({}ms) — clearing stale state",
                    now - pushTimestamp);
            clearPending();
        }
    }

    static boolean hasPushTimedOut(long now, long startedAt, long timeoutMs) {
        return now - startedAt > timeoutMs;
    }

    private static long configuredPushTimeoutMs() {
        try {
            return GuiTimingConfig.loadNavigationTimeoutMs();
        } catch (Exception ignored) {
            return GuiTimingConfig.DEFAULT_NAVIGATION_TIMEOUT_MS;
        }
    }

    @SubscribeEvent
    public static void onClientLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        clearPending();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onScreenRenderPost(ScreenEvent.Render.Post event) {
        if (!MachineHub.isVisible()) return;
        Screen screen = event.getScreen();
        if (!(screen instanceof AbstractContainerScreen<?> acs)) return;
        if (!isRefinedStorageGrid(screen)) return;

        MachineHubRenderer.render(event.getGuiGraphics(), acs.getGuiLeft(), acs.getGuiTop(),
                acs.getXSize(), event.getMouseX(), event.getMouseY());
    }

    /** Resolve the optional RS screen by name so BD-only clients can load this class. */
    private static boolean isRefinedStorageGrid(Screen screen) {
        if (!ModList.get().isLoaded("refinedstorage") || screen == null) return false;
        Class<?> type = screen.getClass();
        while (type != null) {
            if ("com.refinedmods.refinedstorage.screen.grid.GridScreen".equals(type.getName())) {
                return true;
            }
            type = type.getSuperclass();
        }
        return false;
    }
}
