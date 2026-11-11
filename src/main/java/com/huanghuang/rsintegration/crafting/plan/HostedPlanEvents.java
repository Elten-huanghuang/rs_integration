package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.RSIntegrationMod;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** 只将已打开的计划页事件交给组件；原生容器继续 tick 和验证有效性。 */
@Mod.EventBusSubscriber(modid = RSIntegrationMod.MOD_ID, value = Dist.CLIENT)
public final class HostedPlanEvents {
    private HostedPlanEvents() {}

    private static Screen page(ScreenEvent event) {
        return event.getScreen() instanceof PlanScreenHost host ? host.rsi$planPage() : null;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void render(ScreenEvent.Render.Pre event) {
        Screen page = page(event);
        if (page == null) return;
        event.setCanceled(true);
        page.renderWithTooltip(event.getGuiGraphics(), event.getMouseX(), event.getMouseY(), event.getPartialTick());
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void click(ScreenEvent.MouseButtonPressed.Pre event) {
        Screen page = page(event);
        if (page == null) return;
        event.setCanceled(true);
        page.mouseClicked(event.getMouseX(), event.getMouseY(), event.getButton());
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void release(ScreenEvent.MouseButtonReleased.Pre event) {
        Screen page = page(event);
        if (page == null) return;
        event.setCanceled(true);
        page.mouseReleased(event.getMouseX(), event.getMouseY(), event.getButton());
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void drag(ScreenEvent.MouseDragged.Pre event) {
        Screen page = page(event);
        if (page == null) return;
        event.setCanceled(true);
        page.mouseDragged(event.getMouseX(), event.getMouseY(), event.getMouseButton(), event.getDragX(), event.getDragY());
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void scroll(ScreenEvent.MouseScrolled.Pre event) {
        Screen page = page(event);
        if (page == null) return;
        event.setCanceled(true);
        page.mouseScrolled(event.getMouseX(), event.getMouseY(), event.getScrollDelta());
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void key(ScreenEvent.KeyPressed.Pre event) {
        Screen page = page(event);
        if (page == null) return;
        event.setCanceled(true);
        page.keyPressed(event.getKeyCode(), event.getScanCode(), event.getModifiers());
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void keyUp(ScreenEvent.KeyReleased.Pre event) {
        Screen page = page(event);
        if (page == null) return;
        event.setCanceled(true);
        page.keyReleased(event.getKeyCode(), event.getScanCode(), event.getModifiers());
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void text(ScreenEvent.CharacterTyped.Pre event) {
        Screen page = page(event);
        if (page == null) return;
        event.setCanceled(true);
        page.charTyped(event.getCodePoint(), event.getModifiers());
    }

    @SubscribeEvent
    public static void close(ScreenEvent.Closing event) {
        if (event.getScreen() instanceof PlanScreenHost host) host.rsi$setPlanPage(null);
    }
}
