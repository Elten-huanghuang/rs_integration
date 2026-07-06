package com.huanghuang.rsintegration.mods.jei;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.sidepanel.client.RSIKeyBindings;
import com.huanghuang.rsintegration.network.RSJeiPlugin;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.runtime.IBookmarkOverlay;
import mezz.jei.api.runtime.IEditModeConfig;
import mezz.jei.api.runtime.IJeiRuntime;
import mezz.jei.api.runtime.IIngredientListOverlay;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.runtime.IScreenHelper;
import mezz.jei.common.util.ImmutableRect2i;
import mezz.jei.gui.bookmarks.BookmarkList;
import mezz.jei.gui.bookmarks.IBookmark;
import mezz.jei.gui.bookmarks.IngredientBookmark;
import mezz.jei.gui.overlay.IngredientGridWithNavigation;
import mezz.jei.gui.overlay.IngredientListOverlay;
import mezz.jei.gui.recipes.RecipeTransferButton;
import mezz.jei.gui.recipes.RecipesGui;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class JeiMarqueeSelector {

    private JeiMarqueeSelector() {}

    // ── State ──
    private static boolean potentialDrag;
    private static boolean selecting;
    private static boolean dragging;
    private static int dragOriginX, dragOriginY;
    private static int startX, startY, endX, endY;

    private static List<ImmutableRect2i> selectedSlotAreas = Collections.emptyList();
    private static List<ITypedIngredient<?>> cachedIngredients = Collections.emptyList();

    private static final int FILL_COLOR      = 0x33999999;
    private static final int BORDER_COLOR    = 0xCCAAAAAA;
    private static final int HIGHLIGHT_COLOR = 0x44AAAAAA;
    private static final int DRAG_THRESHOLD  = 3;

    // ── Reflection cache ──
    private static volatile Field overlayContentsField;
    private static volatile Field bookmarkListField;
    private static volatile Method bookmarkCreateMethod;
    private static volatile boolean reflectionProbed;

    // ── Bounding-box cache (only recompute on screen resize) ──
    private static int cachedJeiMinX, cachedJeiMinY, cachedJeiMaxX, cachedJeiMaxY;
    private static int cachedScreenW, cachedScreenH;

    // ── Registration ──

    public static void register() {
        MinecraftForge.EVENT_BUS.register(JeiMarqueeSelector.class);
        RSIntegrationMod.LOGGER.debug("[RSI-JEI-Marquee] Registered");
    }

    public static void unregister() {
        MinecraftForge.EVENT_BUS.unregister(JeiMarqueeSelector.class);
        clearSelection();
    }

    // ═══════════════════════════════════════════════════════════
    //  State Reset Hooks
    // ═══════════════════════════════════════════════════════════

    // Clear stale selection when switching screens (e.g. clicking recipe → new GUI)
    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Pre event) {
        clearSelection();
    }

    // Force-reset state machine when all GUIs are closed (back to game view)
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            if (Minecraft.getInstance().screen == null && (selecting || dragging || potentialDrag)) {
                clearSelection();
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Shortcut dispatch
    // ═══════════════════════════════════════════════════════════

    private static boolean dispatchShortcut(Screen screen, InputConstants.Key input, int mx, int my) {
        IJeiRuntime runtime = RSJeiPlugin.getRuntime();
        if (runtime == null) return false;

        boolean typingSafe = !isAnyEditBoxFocused(screen);

        KeyMapping km;

        km = RSIKeyBindings.KEY_CLEAR_SEARCH;
        if (km != null && km.isActiveAndMatches(input)) {
            if (!isOverVanillaGui(screen, mx, my)) {
                runtime.getIngredientFilter().setFilterText("");
                return true;
            }
        }

        km = RSIKeyBindings.KEY_MOD_FILTER;
        if (km != null && km.isActiveAndMatches(input)) {
            IIngredientListOverlay overlay = runtime.getIngredientListOverlay();
            if (overlay != null) {
                var opt = overlay.getIngredientUnderMouse();
                if (opt.isPresent()) {
                    String modId = getModId(opt.get());
                    if (modId != null && !modId.isEmpty()) {
                        runtime.getIngredientFilter().setFilterText("@" + modId);
                        clearSelection();
                        return true;
                    }
                }
            }
        }

        km = RSIKeyBindings.KEY_TRANSFER_RECIPE;
        if (km != null && km.isActiveAndMatches(input)) {
            if (typingSafe && screen instanceof RecipesGui) {
                Button target = null;
                double best = Double.MAX_VALUE;
                for (var child : screen.children()) {
                    if (child instanceof Button btn
                            && child instanceof RecipeTransferButton) {
                        if (btn.active && btn.visible) {
                            double dist = (btn.getX() - mx) * (btn.getX() - mx)
                                        + (btn.getY() - my) * (btn.getY() - my);
                            if (dist < best) { best = dist; target = btn; }
                        }
                    }
                }
                if (target != null) {
                    target.onPress();
                    return true;
                }
            }
        }

        return false;
    }

    // ═══════════════════════════════════════════════════════════
    //  Drag start check
    // ═══════════════════════════════════════════════════════════

    private static boolean canStartDrag(Screen screen, int mx, int my) {
        IJeiRuntime runtime = RSJeiPlugin.getRuntime();
        if (runtime == null) return false;

        IIngredientListOverlay overlay = runtime.getIngredientListOverlay();
        if (overlay == null || !overlay.isListDisplayed()) return false;

        // 1. Avoid overlapping vanilla GUI elements
        if (isOverVanillaGui(screen, mx, my)) return false;

        // 2. Restrict drag initiation to within the JEI item list bounding box
        if (!isOverJeiList(mx, my)) return false;

        return true;
    }

    /**
     * Computes the bounding rectangle of all JEI item-list slots.
     * Drag initiation is only allowed within this area, preventing false
     * triggers from mods that don't properly declare exclusion zones.
     */
    private static boolean isOverJeiList(int mx, int my) {
        Minecraft mc = Minecraft.getInstance();
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();

        // Return cached result if screen hasn't resized
        if (sw == cachedScreenW && sh == cachedScreenH && cachedJeiMaxX > cachedJeiMinX) {
            int padding = 30;
            return mx >= cachedJeiMinX - padding && mx <= cachedJeiMaxX + padding
                && my >= cachedJeiMinY - padding && my <= cachedJeiMaxY + padding;
        }

        probeReflection();
        if (overlayContentsField == null) return false;
        IJeiRuntime runtime = RSJeiPlugin.getRuntime();
        if (runtime == null) return false;
        IIngredientListOverlay overlay = runtime.getIngredientListOverlay();
        if (overlay == null) return false;

        try {
            IngredientGridWithNavigation grid = (IngredientGridWithNavigation) overlayContentsField.get(overlay);
            if (grid == null) return false;

            int[] minX = {Integer.MAX_VALUE}, minY = {Integer.MAX_VALUE};
            int[] maxX = {Integer.MIN_VALUE}, maxY = {Integer.MIN_VALUE};

            grid.getSlots().forEach(slot -> {
                ImmutableRect2i area = slot.getArea();
                if (area != null) {
                    if (area.x() < minX[0]) minX[0] = area.x();
                    if (area.y() < minY[0]) minY[0] = area.y();
                    if (area.x() + area.width() > maxX[0]) maxX[0] = area.x() + area.width();
                    if (area.y() + area.height() > maxY[0]) maxY[0] = area.y() + area.height();
                }
            });
            if (minX[0] > maxX[0]) return false;

            cachedJeiMinX = minX[0]; cachedJeiMinY = minY[0];
            cachedJeiMaxX = maxX[0]; cachedJeiMaxY = maxY[0];
            cachedScreenW = sw; cachedScreenH = sh;

            int padding = 30;
            return mx >= minX[0] - padding && mx <= maxX[0] + padding
                && my >= minY[0] - padding && my <= maxY[0] + padding;
        } catch (Exception e) {
            return false;
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Mouse handlers
    // ═══════════════════════════════════════════════════════════

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        Screen screen = event.getScreen();
        if (screen == null) return;
        int mx = (int) event.getMouseX();
        int my = (int) event.getMouseY();
        IJeiRuntime runtime = RSJeiPlugin.getRuntime();

        // ── Middle-click over JEI area → clear search ──
        if (event.getButton() == 2 && runtime != null && !isOverVanillaGui(screen, mx, my)) {
            runtime.getIngredientFilter().setFilterText("");
            event.setCanceled(true);
            return;
        }

        // ── Alt + left-click on ingredient → filter by mod ──
        if (event.getButton() == 0 && Screen.hasAltDown() && runtime != null) {
            IIngredientListOverlay overlay = runtime.getIngredientListOverlay();
            if (overlay != null) {
                var opt = overlay.getIngredientUnderMouse();
                if (opt.isPresent()) {
                    String modId = getModId(opt.get());
                    if (modId != null && !modId.isEmpty()) {
                        runtime.getIngredientFilter().setFilterText("@" + modId);
                        clearSelection();
                        event.setCanceled(true);
                        return;
                    }
                }
            }
        }

        if (event.getButton() != 0) return;

        if (selecting && !dragging) clearSelection();

        if (canStartDrag(screen, mx, my)) {
            potentialDrag = true;
            dragOriginX = mx;
            dragOriginY = my;
        }
    }

    @SubscribeEvent
    public static void onMouseDrag(ScreenEvent.MouseDragged.Pre event) {
        if (!potentialDrag && !dragging) return;

        int mx = (int) event.getMouseX();
        int my = (int) event.getMouseY();

        if (potentialDrag && !dragging) {
            int dx = mx - dragOriginX;
            int dy = my - dragOriginY;
            if (dx * dx + dy * dy >= DRAG_THRESHOLD * DRAG_THRESHOLD) {
                potentialDrag = false;
                selecting = true;
                dragging = true;
                startX = dragOriginX;
                startY = dragOriginY;
                endX = mx;
                endY = my;
                updateRealTimeSelection();
                event.setCanceled(true);
                return;
            }
        }

        if (dragging) {
            endX = mx;
            endY = my;
            updateRealTimeSelection();
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onMouseReleased(ScreenEvent.MouseButtonReleased.Pre event) {
        if (event.getButton() != 0) return;

        if (potentialDrag) {
            potentialDrag = false;
            return;
        }

        if (dragging) {
            dragging = false;
            endX = (int) event.getMouseX();
            endY = (int) event.getMouseY();
            updateRealTimeSelection();
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onScroll(ScreenEvent.MouseScrolled.Pre event) {
        if (selecting && !dragging) {
            clearSelection();
        }
    }



    @SubscribeEvent
    public static void onScreenClose(ScreenEvent.Closing event) {
        clearSelection();
    }


    private static final int KEY_PAGE_UP = 266;
    private static final int KEY_PAGE_DOWN = 267;
    private static final int KEY_HOME = 268;
    private static final int KEY_END = 269;

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onKeyPress(ScreenEvent.KeyPressed.Pre event) {
        int key = event.getKeyCode();
        int scan = event.getScanCode();

        if (selecting && !dragging) {
            if (key == KEY_PAGE_UP || key == KEY_PAGE_DOWN
                    || key == KEY_HOME || key == KEY_END) {
                clearSelection();
                return;
            }
        }
        InputConstants.Key input = InputConstants.getKey(key, scan);

        Minecraft mc = Minecraft.getInstance();
        int mx = (int) (mc.mouseHandler.xpos()
                * (double) mc.getWindow().getGuiScaledWidth()
                / (double) mc.getWindow().getScreenWidth());
        int my = (int) (mc.mouseHandler.ypos()
                * (double) mc.getWindow().getGuiScaledHeight()
                / (double) mc.getWindow().getScreenHeight());

        if (dispatchShortcut(event.getScreen(), input,
                mx, my)) {
            event.setCanceled(true);
            return;
        }

        if (selecting && !dragging) {
            if (key == InputConstants.KEY_A) {
                batchBookmark(cachedIngredients);
                clearSelection();
                event.setCanceled(true);
                return;
            }
            if (key == InputConstants.KEY_H) {
                batchHide(cachedIngredients);
                clearSelection();
                event.setCanceled(true);
                return;
            }
            if (key == InputConstants.KEY_ESCAPE) {
                clearSelection();
                event.setCanceled(true);
                return;
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Render (highlights under selection box)
    // ═══════════════════════════════════════════════════════════

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRender(ScreenEvent.Render.Post event) {
        IJeiRuntime runtime = RSJeiPlugin.getRuntime();
        if (runtime == null) { clearSelection(); return; }
        IIngredientListOverlay overlay = runtime.getIngredientListOverlay();
        if (overlay == null || !overlay.isListDisplayed()) {
            clearSelection();
            return;
        }

        GuiGraphics gfx = event.getGuiGraphics();
        RenderSystem.enableBlend();

        // Draw per-slot highlights first (visible both during and after drag)
        if (!selectedSlotAreas.isEmpty()) {
            for (ImmutableRect2i area : selectedSlotAreas) {
                int ax = area.x();
                int ay = area.y();
                int aw = area.width();
                int ah = area.height();

                gfx.fill(ax, ay, ax + aw, ay + ah, HIGHLIGHT_COLOR);
                gfx.fill(ax, ay, ax + aw, ay + 1, BORDER_COLOR);
                gfx.fill(ax, ay + ah - 1, ax + aw, ay + ah, BORDER_COLOR);
                gfx.fill(ax, ay, ax + 1, ay + ah, BORDER_COLOR);
                gfx.fill(ax + aw - 1, ay, ax + aw, ay + ah, BORDER_COLOR);
            }
        }

        // Draw the selection box on top
        if (dragging) {
            int x1 = Math.min(startX, endX);
            int y1 = Math.min(startY, endY);
            int x2 = Math.max(startX, endX);
            int y2 = Math.max(startY, endY);

            gfx.fill(x1, y1, x2, y2, FILL_COLOR);
            gfx.fill(x1, y1, x2, y1 + 1, BORDER_COLOR);
            gfx.fill(x1, y2 - 1, x2, y2, BORDER_COLOR);
            gfx.fill(x1, y1, x1 + 1, y2, BORDER_COLOR);
            gfx.fill(x2 - 1, y1, x2, y2, BORDER_COLOR);
        }

        RenderSystem.disableBlend();
    }

    // ═══════════════════════════════════════════════════════════
    //  Real-time selection (center-point hit-test)
    // ═══════════════════════════════════════════════════════════

    private static void updateRealTimeSelection() {
        IJeiRuntime runtime = RSJeiPlugin.getRuntime();
        if (runtime == null) return;
        IIngredientListOverlay overlay = runtime.getIngredientListOverlay();
        if (overlay == null) return;

        probeReflection();
        if (overlayContentsField == null) return;

        IngredientGridWithNavigation grid;
        try {
            grid = (IngredientGridWithNavigation) overlayContentsField.get(overlay);
        } catch (Exception e) { return; }
        if (grid == null) return;

        int x1 = Math.min(startX, endX);
        int y1 = Math.min(startY, endY);
        int x2 = Math.max(startX, endX);
        int y2 = Math.max(startY, endY);

        List<ImmutableRect2i> areas = new ArrayList<>();
        List<ITypedIngredient<?>> ingredients = new ArrayList<>();

        grid.getSlots().forEach(slot -> {
            ImmutableRect2i area = slot.getArea();
            if (area == null) return;
            if (slot.getOptionalElement().isEmpty()) return;

            int centerX = area.x() + area.width() / 2;
            int centerY = area.y() + area.height() / 2;

            if (centerX >= x1 && centerX <= x2 && centerY >= y1 && centerY <= y2) {
                areas.add(area);
                ingredients.add(slot.getOptionalElement().get().getTypedIngredient());
            }
        });

        selectedSlotAreas = areas;
        cachedIngredients = ingredients;
    }

    // ═══════════════════════════════════════════════════════════
    //  Bulk operations
    // ═══════════════════════════════════════════════════════════

    private static void batchBookmark(List<ITypedIngredient<?>> ingredients) {
        if (ingredients.isEmpty()) return;
        IJeiRuntime runtime = RSJeiPlugin.getRuntime();
        if (runtime == null) return;

        probeReflection();
        if (bookmarkListField == null || bookmarkCreateMethod == null) return;

        IIngredientManager im = runtime.getIngredientManager();
        IBookmarkOverlay bmOverlay = runtime.getBookmarkOverlay();
        if (bmOverlay == null) return;

        BookmarkList list;
        try {
            list = (BookmarkList) bookmarkListField.get(bmOverlay);
        } catch (Exception e) { return; }
        if (list == null) return;

        int added = 0;
        for (ITypedIngredient<?> ing : ingredients) {
            if (ing == null) continue;
            try {
                IBookmark bookmark = (IBookmark) bookmarkCreateMethod.invoke(null, ing, im);
                if (bookmark != null && list.add(bookmark)) added++;
            } catch (Exception ignored) {}
        }
        RSIntegrationMod.LOGGER.debug("[RSI-JEI-Marquee] Bookmarked {} items", added);
    }

    private static void batchHide(List<ITypedIngredient<?>> ingredients) {
        if (ingredients.isEmpty()) return;
        IJeiRuntime runtime = RSJeiPlugin.getRuntime();
        if (runtime == null) return;
        IEditModeConfig config = runtime.getEditModeConfig();
        for (ITypedIngredient<?> ing : ingredients) {
            if (ing != null) {
                config.hideIngredientUsingConfigFile(ing, IEditModeConfig.HideMode.SINGLE);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════

    private static boolean isOverVanillaGui(Screen screen, int mx, int my) {
        IJeiRuntime runtime = RSJeiPlugin.getRuntime();
        if (runtime == null) return false;
        IScreenHelper helper = runtime.getScreenHelper();
        return helper.getGuiExclusionAreas(screen)
                .anyMatch(area -> mx >= area.getX() && mx < area.getX() + area.getWidth()
                        && my >= area.getY() && my < area.getY() + area.getHeight());
    }

    /** Checks whether any text input widget on the screen currently has focus.
     *  When the JEI search box is focused, keys like Backspace and T should
     *  go to the text field, not trigger shortcuts. */
    private static boolean isAnyEditBoxFocused(Screen screen) {
        if (screen == null) return false;
        var focused = screen.getFocused();
        if (focused instanceof EditBox) return true;
        // Some modded screens wrap EditBox differently — check class name
        if (focused != null) {
            Class<?> c = focused.getClass();
            while (c != null) {
                if (c.getName().endsWith("EditBox") || c.getName().endsWith("TextField")) return true;
                c = c.getSuperclass();
            }
        }
        return false;
    }

    private static String getModId(ITypedIngredient<?> ingredient) {
        Object ing = ingredient.getIngredient();
        if (ing instanceof ItemStack stack && !stack.isEmpty()) {
            ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
            return key.getNamespace();
        }
        if (ing instanceof FluidStack fluid && !fluid.isEmpty()) {
            ResourceLocation key = ForgeRegistries.FLUIDS.getKey(fluid.getFluid());
            return key.getNamespace();
        }
        return null;
    }

    private static void clearSelection() {
        selecting = false;
        dragging = false;
        potentialDrag = false;
        startX = startY = endX = endY = 0;
        selectedSlotAreas = Collections.emptyList();
        cachedIngredients = Collections.emptyList();
    }

    // ═══════════════════════════════════════════════════════════
    //  Reflection
    // ═══════════════════════════════════════════════════════════

    private static void probeReflection() {
        if (reflectionProbed) return;
        reflectionProbed = true;
        try {
            Class<?> overlayClass = IngredientListOverlay.class;
            overlayContentsField = overlayClass.getDeclaredField("contents");
            overlayContentsField.setAccessible(true);

            Class<?> bmOverlayClass = mezz.jei.gui.overlay.bookmarks.BookmarkOverlay.class;
            bookmarkListField = bmOverlayClass.getDeclaredField("bookmarkList");
            bookmarkListField.setAccessible(true);

            bookmarkCreateMethod = IngredientBookmark.class.getMethod("create",
                    ITypedIngredient.class, IIngredientManager.class);
            bookmarkCreateMethod.setAccessible(true);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-JEI-Marquee] Reflection probe failed: {}", e.toString());
        }
    }
}
