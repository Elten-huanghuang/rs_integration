package com.huanghuang.rsintegration.machine;

import com.huanghuang.rsintegration.network.binding.BindingEventHandler;
import com.huanghuang.rsintegration.network.binding.BindingStorage;
import com.huanghuang.rsintegration.ModItems;
import com.huanghuang.rsintegration.sidepanel.client.MachineTabHandler;
import com.huanghuang.rsintegration.sidepanel.data.BindingCache;
import com.huanghuang.rsintegration.sidepanel.data.BindingInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** BD-only machine-center entrypoint. Keeps RS Grid mixins and layout untouched. */
@net.minecraftforge.api.distmarker.OnlyIn(Dist.CLIENT)
public final class BeyondDimensionsMachineHubClient {
    // BD anchors native RightTabButton widgets at the 176px content edge;
    // the background texture itself continues to 194px for the tab overhang.
    // BD's native tabs use +176 because they intentionally overlap the last
    // 18px of the 194px texture. Favorites are a separate dock, so keep a
    // visible 4px gap to the right of the complete terminal surface instead
    // of placing them on top of the scrollbar.
    private static final int BD_TAB_X_OFFSET = 198;
    private static final int BD_FAVORITE_WIDTH = 23;
    private static final int BD_FAVORITE_HEIGHT = 26;
    // BD's native RightTabButton positions are top+6, top+36, ...:
    // 26px art with a 4px overlap gap (30px step).
    private static final int BD_FAVORITE_GAP = 4;
    // Use BD's own widget sprites. The tab is only the outer frame; the
    // 16x16 slot sprite supplies the native gray/blue hover behavior and the
    // icon is rendered independently at the same +3,+4 offset as BD itself.
    private static final ResourceLocation BD_RIGHT_TAB =
            new ResourceLocation("beyonddimensions", "textures/gui/sprites/widget/right_tab.png");
    private static final ResourceLocation BD_SLOT =
            new ResourceLocation("beyonddimensions", "textures/gui/sprites/widget/slot_button.png");
    private static final ResourceLocation BD_SLOT_HOVER =
            new ResourceLocation("beyonddimensions", "textures/gui/sprites/widget/slot_button_hovered.png");
    private static final ResourceLocation MACHINE_CENTER_ICON =
            new ResourceLocation("rs_integration", "textures/gui/machine_center_bd_icon_16x16.png");
    private static long lastLocalRefresh;
    private static long authoritativeSyncUntil;
    private static final int BD_LEFT_ICON_X_OFFSET = 0;
    private static final int BD_LEFT_ICON_Y_OFFSET = 0;
    private static final int BD_RIGHT_ICON_X_OFFSET = 3;
    private static final int BD_RIGHT_ICON_Y_OFFSET = 4;
    private static final int BD_ICON_SIZE = 16;
    // DimensionsNetGUI's native left controls are plain 16x16 IconButtons;
    // the 23x26 left_tab is reserved for other BD screens and would create
    // the extra framed box seen around the machine-center entry.
    private static final int MACHINE_CENTER_SIZE = 16;
    private static int machineCenterX;
    private static int machineCenterY;
    private static int resonanceBackpackX;
    private static int resonanceBackpackY;

    private BeyondDimensionsMachineHubClient() {}

    @SubscribeEvent
    public static void onRender(ScreenEvent.Render.Post event) {
        Screen screen = event.getScreen();
        if (!isTerminal(screen)) return;
        refreshLocalBindings();
        renderMachineCenterEntry(event);
        renderResonanceEntry(event);
        renderFavoriteStrip(event);
        if (!MachineHub.isVisible()) return;
        int centerX = screen.width / 2;
        int centerY = screen.height / 2;
        MachineHubRenderer.render(event.getGuiGraphics(), centerX + 88, centerY - 80,
                176, event.getMouseX(), event.getMouseY());
    }

    @SubscribeEvent
    public static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (!isTerminal(event.getScreen())) return;
        if (handleMachineCenterClick(event)) return;
        if (handleResonanceClick(event)) return;
        if (!MachineHub.isVisible() && handleFavoriteClick(event)) return;
        if (!MachineHub.isVisible()) return;
        if (MachineHubInputHandler.mouseClicked(event.getMouseX(), event.getMouseY(), event.getButton())) {
            event.setCanceled(true);
        }
    }

    private static void renderMachineCenterEntry(ScreenEvent.Render.Post event) {
        Screen screen = event.getScreen();
        int left = resolveScreenInt(screen, "getGuiLeft", "leftPos", (screen.width - 176) / 2);
        int top = resolveBDTop(screen);
        machineCenterX = Math.max(0, left - 18);
        // DimensionsNetGUI owns eight 16px left-side slots in order:
        // sort, second sort, reverse, search, add/remove page, craft, and
        // primary-network switcher. Keep the machine center below that last
        // native control so it never covers the primary-network button.
        machineCenterY = top + 6 + 18 * 8;
        boolean hovered = !MachineTabHandler.getAllMachines().isEmpty()
                && event.getMouseX() >= machineCenterX
                && event.getMouseX() < machineCenterX + MACHINE_CENTER_SIZE
                && event.getMouseY() >= machineCenterY
                && event.getMouseY() < machineCenterY + MACHINE_CENTER_SIZE;
        MachineTabHandler.setMachineCenterHovered(hovered);
        GuiGraphics g = event.getGuiGraphics();
        g.pose().pushPose();
        g.pose().translate(0, 0, 460);
        int iconX = machineCenterX + BD_LEFT_ICON_X_OFFSET;
        int iconY = machineCenterY + BD_LEFT_ICON_Y_OFFSET;
        g.blit(hovered ? BD_SLOT_HOVER : BD_SLOT, iconX, iconY, 0, 0,
                BD_ICON_SIZE, BD_ICON_SIZE, BD_ICON_SIZE, BD_ICON_SIZE);
        g.blit(MACHINE_CENTER_ICON, iconX, iconY, 0, 0,
                BD_ICON_SIZE, BD_ICON_SIZE, BD_ICON_SIZE, BD_ICON_SIZE);
        g.pose().popPose();
    }

    /** BD terminal slot 10: opens the generator's independent BD resonance space. */
    private static void renderResonanceEntry(ScreenEvent.Render.Post event) {
        Screen screen = event.getScreen();
        int left = resolveScreenInt(screen, "getGuiLeft", "leftPos", (screen.width - 176) / 2);
        resonanceBackpackX = Math.max(0, left - 18);
        resonanceBackpackY = machineCenterY + 18;
        boolean available = ModItems.DIMENSIONAL_RESONANCE_DISK != null;
        boolean hovered = available
                && event.getMouseX() >= resonanceBackpackX
                && event.getMouseX() < resonanceBackpackX + MACHINE_CENTER_SIZE
                && event.getMouseY() >= resonanceBackpackY
                && event.getMouseY() < resonanceBackpackY + MACHINE_CENTER_SIZE;
        MachineTabHandler.setResonanceBackpackHovered(hovered);
        if (!available) return;
        GuiGraphics g = event.getGuiGraphics();
        g.pose().pushPose();
        g.pose().translate(0, 0, 460);
        g.blit(hovered ? BD_SLOT_HOVER : BD_SLOT, resonanceBackpackX, resonanceBackpackY,
                0, 0, BD_ICON_SIZE, BD_ICON_SIZE, BD_ICON_SIZE, BD_ICON_SIZE);
        ItemStack icon = new ItemStack(ModItems.DIMENSIONAL_RESONANCE_DISK.get());
        g.renderItem(icon, resonanceBackpackX, resonanceBackpackY);
        g.pose().popPose();
    }

    private static boolean handleMachineCenterClick(ScreenEvent.MouseButtonPressed.Pre event) {
        if (event.getButton() != GLFW.GLFW_MOUSE_BUTTON_LEFT
                || !MachineTabHandler.isMachineCenterHovered()) return false;
        MachineTabHandler.toggleMachineCenter();
        event.setCanceled(true);
        return true;
    }

    private static boolean handleResonanceClick(ScreenEvent.MouseButtonPressed.Pre event) {
        if (event.getButton() != GLFW.GLFW_MOUSE_BUTTON_LEFT
                || !MachineTabHandler.isResonanceBackpackHovered()) return false;
        MachineTabHandler.toggleResonanceBackpack(true);
        event.setCanceled(true);
        return true;
    }

    @SubscribeEvent
    public static void onMouseReleased(ScreenEvent.MouseButtonReleased.Pre event) {
        if (!isTerminal(event.getScreen()) || !MachineHub.isVisible()) return;
        if (MachineHubInputHandler.mouseReleased(event.getMouseX(), event.getMouseY(), event.getButton())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onMouseScrolled(ScreenEvent.MouseScrolled.Pre event) {
        if (!isTerminal(event.getScreen()) || !MachineHub.isVisible()) return;
        if (MachineHubInputHandler.mouseScrolled(event.getMouseX(), event.getMouseY(), event.getScrollDelta())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        if (!isTerminal(event.getScreen())) return;
        if (MachineHubInputHandler.keyPressed(event.getKeyCode())) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onCharTyped(ScreenEvent.CharacterTyped.Pre event) {
        if (isTerminal(event.getScreen()) && MachineHubInputHandler.charTyped(event.getCodePoint(), event.getModifiers())) {
            event.setCanceled(true);
        }
    }

    private static boolean isTerminal(Screen screen) {
        if (screen == null || !ModList.get().isLoaded("beyonddimensions")) return false;
        String name = screen.getClass().getName();
        if (name.endsWith("DimensionsCraftGUI") || name.endsWith("DimensionsTerminalCraftGUI")
                || name.contains("beyonddimensions.client.gui.DimensionsCraftGUI")) return true;
        // Deliberately do not use the menu class as a fallback: the recipe-tree
        // screen can retain the BD menu while it is open.
        return false;
    }

    /** Draw BD-local machine favorites to the right of the terminal surface. */
    private static void renderFavoriteStrip(ScreenEvent.Render.Post event) {
        List<BindingInfo> favorites = getFavoriteMachines();
        if (favorites.isEmpty()) return;
        Screen screen = event.getScreen();
        int left = resolveScreenInt(screen, "getGuiLeft", "leftPos", (screen.width - 176) / 2);
        int top = resolveBDTop(screen);
        int x = Math.min(left + BD_TAB_X_OFFSET,
                Math.max(2, screen.width - BD_FAVORITE_WIDTH - 2));
        int y = top + 6;
        GuiGraphics g = event.getGuiGraphics();
        g.pose().pushPose();
        g.pose().translate(0, 0, 460);
        try {
            for (int i = 0; i < favorites.size(); i++) {
                int sy = y + i * (BD_FAVORITE_HEIGHT + BD_FAVORITE_GAP);
                boolean hovered = event.getMouseX() >= x && event.getMouseX() < x + BD_FAVORITE_WIDTH
                        && event.getMouseY() >= sy && event.getMouseY() < sy + BD_FAVORITE_HEIGHT;
                g.blit(BD_RIGHT_TAB, x, sy, 0, 0,
                        BD_FAVORITE_WIDTH, BD_FAVORITE_HEIGHT,
                        BD_FAVORITE_WIDTH, BD_FAVORITE_HEIGHT);
                int iconX = x + BD_RIGHT_ICON_X_OFFSET;
                int iconY = sy + BD_RIGHT_ICON_Y_OFFSET;
                g.blit(hovered ? BD_SLOT_HOVER : BD_SLOT, iconX, iconY, 0, 0,
                        BD_ICON_SIZE, BD_ICON_SIZE, BD_ICON_SIZE, BD_ICON_SIZE);
                ItemStack icon = com.huanghuang.rsintegration.sidepanel.client.MachineTabRenderer
                        .resolveIcon(favorites.get(i));
                if (icon.isEmpty()) icon = new ItemStack(net.minecraft.world.item.Items.BARRIER);
                g.renderItem(icon, iconX, iconY);
                g.renderItemDecorations(Minecraft.getInstance().font, icon, iconX, iconY);
                if (hovered) {
                    g.renderTooltip(Minecraft.getInstance().font,
                            Component.translatable(favorites.get(i).displayName()),
                            event.getMouseX(), event.getMouseY());
                }
            }
        } finally {
            g.pose().popPose();
        }
    }

    private static boolean handleFavoriteClick(ScreenEvent.MouseButtonPressed.Pre event) {
        if (event.getButton() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
        Screen screen = event.getScreen();
        List<BindingInfo> favorites = getFavoriteMachines();
        int left = resolveScreenInt(screen, "getGuiLeft", "leftPos", (screen.width - 176) / 2);
        int top = resolveBDTop(screen);
        int x = Math.min(left + BD_TAB_X_OFFSET,
                Math.max(2, screen.width - BD_FAVORITE_WIDTH - 2));
        int y = top + 6;
        for (int i = 0; i < favorites.size(); i++) {
            int sy = y + i * (BD_FAVORITE_HEIGHT + BD_FAVORITE_GAP);
            if (event.getMouseX() >= x && event.getMouseX() < x + BD_FAVORITE_WIDTH
                    && event.getMouseY() >= sy && event.getMouseY() < sy + BD_FAVORITE_HEIGHT) {
                MachineTabHandler.onClick(favorites.get(i));
                event.setCanceled(true);
                return true;
            }
        }
        return false;
    }

    private static List<BindingInfo> getFavoriteMachines() {
        if (ModList.get().isLoaded("refinedstorage")) {
            return com.huanghuang.rsintegration.sidepanel.client.MachineFavoritesClient
                    .getFavoriteMachines();
        }
        return MachineHub.getLocalFavoriteMachines();
    }

    private static int resolveInt(Screen screen, String fieldName, int fallback) {
        Class<?> type = screen.getClass();
        while (type != null) {
            try {
                java.lang.reflect.Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                Object value = field.get(screen);
                if (value instanceof Number n) return n.intValue();
            } catch (ReflectiveOperationException | SecurityException ignored) {
                type = type.getSuperclass();
            }
        }
        return fallback;
    }

    private static int resolveScreenInt(Screen screen, String methodName, String fieldName, int fallback) {
        try {
            Object value = screen.getClass().getMethod(methodName).invoke(screen);
            if (value instanceof Number n) return n.intValue();
        } catch (ReflectiveOperationException | SecurityException ignored) {
            // Fall through to the mapped field name used by BD's screen.
        }
        return resolveInt(screen, fieldName, fallback);
    }

    /** BD recenters the complete texture after every row-count rebuild. */
    private static int resolveBDTop(Screen screen) {
        int height = resolveBDImageHeight(screen);
        return (screen.height - height) / 2;
    }

    private static int resolveBDImageHeight(Screen screen) {
        try {
            java.lang.reflect.Method method = findMethod(screen.getClass(), "rebuildImageHeight");
            if (method != null) {
                method.setAccessible(true);
                Object value = method.invoke(screen);
                if (value instanceof Number n) return n.intValue();
            }
        } catch (ReflectiveOperationException | SecurityException ignored) {
            // Fall through to the minimum two-row BD terminal height.
        }
        int lines = resolveLines(screen);
        return 24 + 18 + Math.max(0, lines - 2) * 18 + 26 + 89;
    }

    private static java.lang.reflect.Method findMethod(Class<?> type, String name) {
        while (type != null) {
            try {
                return type.getDeclaredMethod(name);
            } catch (NoSuchMethodException ignored) {
                type = type.getSuperclass();
            }
        }
        return null;
    }

    private static int resolveLines(Screen screen) {
        Class<?> type = screen.getClass();
        while (type != null) {
            try {
                java.lang.reflect.Field field = type.getDeclaredField("menu");
                field.setAccessible(true);
                Object menu = field.get(screen);
                if (menu != null) {
                    java.lang.reflect.Method method = menu.getClass().getMethod("getLines");
                    Object value = method.invoke(menu);
                    if (value instanceof Number n) return n.intValue();
                }
            } catch (ReflectiveOperationException | SecurityException ignored) {
                type = type.getSuperclass();
            }
        }
        return 2;
    }

    private static void refreshLocalBindings() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        long now = System.currentTimeMillis();
        if (now < authoritativeSyncUntil) return;
        if (now - lastLocalRefresh < 250L) return;
        lastLocalRefresh = now;

        Map<String, BindingInfo> unique = new LinkedHashMap<>();
        List<ItemStack> stacks = new ArrayList<>();
        stacks.addAll(mc.player.getInventory().items);
        stacks.addAll(mc.player.getInventory().offhand);
        stacks.addAll(mc.player.getInventory().armor);
        // BD terminals are valid Curios equipment; include their bindings in
        // the local machine-center view just like inventory-held terminals.
        stacks.addAll(com.huanghuang.rsintegration.util.CuriosAccess.stacks(mc.player));
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) continue;
            ResourceLocation itemId = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem());
            if (itemId == null || !"beyonddimensions".equals(itemId.getNamespace())
                    || !"net_terminal_item".equals(itemId.getPath())) continue;
            for (BindingStorage.BindingEntry entry : BindingStorage.getBindings(stack)) {
                String key = itemId + "@" + entry.dim() + ":" + entry.pos();
                String display = BindingEventHandler.resolveBlockName(
                        entry.blockKey(), entry.blockRegKey(), entry.displayStack()).getString();
                unique.putIfAbsent(key, new BindingInfo(itemId.toString(), entry.dim(), entry.pos(),
                        entry.blockKey(), display, entry.blockRegKey(), entry.displayStack()));
            }
        }
        BindingCache.getInstance().updateBindings(List.copyOf(unique.values()));
        MachineHub.refreshMachines();
    }

    /** Prevent a stale client inventory snapshot from undoing a server sync. */
    static void markAuthoritativeBindingSync() {
        authoritativeSyncUntil = System.currentTimeMillis() + 1000L;
        lastLocalRefresh = authoritativeSyncUntil;
    }
}
