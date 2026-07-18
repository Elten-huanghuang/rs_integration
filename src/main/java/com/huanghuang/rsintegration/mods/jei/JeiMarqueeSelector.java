package com.huanghuang.rsintegration.mods.jei;

import com.huanghuang.rsintegration.crafting.plan.CraftingPlanScreen;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
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
import javax.annotation.Nullable;

public final class JeiMarqueeSelector {

    private JeiMarqueeSelector() {}

    // ── State ──
    private static boolean potentialDrag;
    private static boolean selecting;
    private static boolean dragging;
    private static boolean dragOnBookmarks;
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
    private static volatile Field bookmarkContentsField;
    private static volatile Field bookmarkListField;
    private static volatile Method bookmarkCreateMethod;
    private static volatile boolean reflectionProbed;

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

    /**
     * Checks whether a drag can start at (mx, my). Prefers bookmark overlay
     * when the mouse is over it; otherwise checks the ingredient list.
     * Sets {@link #dragOnBookmarks} accordingly.
     * <p>
     * Returns false when the mouse is directly over an ingredient — that is an
     * item-drag gesture, not a marquee.  Users can start a marquee from the
     * small gaps between slots or from the panel edges.
     */
    private static boolean canStartDrag(Screen screen, int mx, int my) {
        // JEI only renders overlays on inventory screens. Full-screen mod GUIs
        // (FTB Quests, FTB Chunks, world maps, etc.) must not be hijacked.
        if (!(screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen)) {
            return false;
        }

        IJeiRuntime runtime = RSJeiPlugin.getRuntime();
        if (runtime == null) return false;

        if (isOverVanillaGui(screen, mx, my)) return false;

        // Check bookmark overlay first (left panel)
        if (RSIntegrationConfig.ENABLE_JEI_BOOKMARK_MARQUEE_SELECTION.get()) {
            if (isOverBookmarkList(mx, my)) {
                IBookmarkOverlay bmOverlay = runtime.getBookmarkOverlay();
                if (bmOverlay != null && bmOverlay.getIngredientUnderMouse().isPresent()) {
                    return false;
                }
                IngredientGridWithNavigation bmGrid = getJeiGrid(bookmarkContentsField, bmOverlay);
                if (bmGrid != null && !isNearAnySlot(bmGrid, mx, my)) return false;
                dragOnBookmarks = true;
                return true;
            }
        }

        // Check ingredient list overlay (right panel)
        if (!RSIntegrationConfig.ENABLE_JEI_MARQUEE_SELECTION.get()) return false;

        IIngredientListOverlay overlay = runtime.getIngredientListOverlay();
        if (overlay == null || !overlay.isListDisplayed()) return false;
        if (!isOverJeiList(mx, my)) return false;

        // Mouse is on a specific ingredient → this is an item-drag, not marquee
        if (overlay.getIngredientUnderMouse().isPresent()) return false;
        // Mouse is far from all slots → likely over a pagination/config button
        IngredientGridWithNavigation grid = getJeiGrid(overlayContentsField, overlay);
        if (grid != null && !isNearAnySlot(grid, mx, my)) return false;

        dragOnBookmarks = false;
        return true;
    }

    /** Only allow drag start near a known slot, keeping page/config buttons safe. */
    private static boolean isNearAnySlot(IngredientGridWithNavigation grid, int mx, int my) {
        for (var slot : grid.getSlots().toList()) {
            ImmutableRect2i area = slot.getArea();
            if (area == null) continue;
            if (mx >= area.x() - 5 && mx <= area.x() + area.width() + 5
                    && my >= area.y() - 5 && my <= area.y() + area.height() + 5) {
                return true;
            }
        }
        return false;
    }

    /** Extract the IngredientGridWithNavigation from an overlay via reflection. */
    @Nullable
    private static IngredientGridWithNavigation getJeiGrid(Field field, Object overlay) {
        try {
            return (IngredientGridWithNavigation) field.get(overlay);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Computes the bounding rectangle of all JEI item-list slots.
     * Drag initiation is only allowed within this area, preventing false
     * triggers from mods that don't properly declare exclusion zones.
     * <p>
     * Slot positions are recomputed on every call — cheap enough for a
     * mouse-press event, and immune to layout shifts from search,
     * bookmark toggling, or JEI config changes.
     */
    private static boolean isOverJeiList(int mx, int my) {
        probeReflection();
        if (overlayContentsField == null) return false;
        IJeiRuntime runtime = RSJeiPlugin.getRuntime();
        if (runtime == null) return false;
        IIngredientListOverlay overlay = runtime.getIngredientListOverlay();
        if (overlay == null) return false;

        try {
            IngredientGridWithNavigation grid = (IngredientGridWithNavigation) overlayContentsField.get(overlay);
            if (grid == null) return false;

            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;

            for (var slot : grid.getSlots().toList()) {
                ImmutableRect2i area = slot.getArea();
                if (area == null) continue;
                if (area.x() < minX) minX = area.x();
                if (area.y() < minY) minY = area.y();
                int rx = area.x() + area.width();
                int ry = area.y() + area.height();
                if (rx > maxX) maxX = rx;
                if (ry > maxY) maxY = ry;
            }
            if (minX > maxX) return false;

            int padding = 5;
            return mx >= minX - padding && mx <= maxX + padding
                && my >= minY - padding && my <= maxY + padding;
        } catch (Exception e) {
            return false;
        }
    }

    /** Same as {@link #isOverJeiList} but for the bookmark panel on the left. */
    private static boolean isOverBookmarkList(int mx, int my) {
        probeReflection();
        if (bookmarkContentsField == null) return false;
        IJeiRuntime runtime = RSJeiPlugin.getRuntime();
        if (runtime == null) return false;
        IBookmarkOverlay bmOverlay = runtime.getBookmarkOverlay();
        if (bmOverlay == null) return false;

        try {
            IngredientGridWithNavigation grid = (IngredientGridWithNavigation) bookmarkContentsField.get(bmOverlay);
            if (grid == null) return false;

            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;

            for (var slot : grid.getSlots().toList()) {
                ImmutableRect2i area = slot.getArea();
                if (area == null) continue;
                if (area.x() < minX) minX = area.x();
                if (area.y() < minY) minY = area.y();
                int rx = area.x() + area.width();
                int ry = area.y() + area.height();
                if (rx > maxX) maxX = rx;
                if (ry > maxY) maxY = ry;
            }
            if (minX > maxX) return false;

            int padding = 5;
            return mx >= minX - padding && mx <= maxX + padding
                && my >= minY - padding && my <= maxY + padding;
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
        // Our own full-screen planning UI owns its whole area — JEI's list overlays it, but the
        // marquee and JEI click-shortcuts must not hijack drags meant for the recipe tree.
        if (screen instanceof CraftingPlanScreen) return;
        int mx = (int) event.getMouseX();
        int my = (int) event.getMouseY();
        IJeiRuntime runtime = RSJeiPlugin.getRuntime();

        // ── Alt + middle-click over JEI area → clear search ──
        KeyMapping km = RSIKeyBindings.KEY_CLEAR_SEARCH;
        InputConstants.Key mouseKey = InputConstants.Type.MOUSE.getOrCreate(event.getButton());
        if (km != null && km.isActiveAndMatches(mouseKey)
                && runtime != null && !isOverVanillaGui(screen, mx, my)) {
            runtime.getIngredientFilter().setFilterText("");
            event.setCanceled(true);
            return;
        }

        // ── Alt + left-click on ingredient → filter by mod ──
        km = RSIKeyBindings.KEY_MOD_FILTER;
        if (km != null && km.isActiveAndMatches(mouseKey)
                && runtime != null) {
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

        // Must check before shortcut dispatch: page keys and action keys
        // (A/H/Escape) must never steal input from a focused text field.
        Screen screen = event.getScreen();
        boolean typingSafe = !isAnyEditBoxFocused(screen);

        if (selecting && !dragging && typingSafe) {
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

        if (dispatchShortcut(screen, input,
                mx, my)) {
            event.setCanceled(true);
            return;
        }

        if (selecting && !dragging && typingSafe) {
            if (key == InputConstants.KEY_A) {
                if (dragOnBookmarks) {
                    batchUnbookmark(cachedIngredients);
                } else {
                    batchBookmark(cachedIngredients);
                }
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

        boolean anyVisible;
        if (dragOnBookmarks) {
            anyVisible = runtime.getBookmarkOverlay() != null;
        } else {
            IIngredientListOverlay overlay = runtime.getIngredientListOverlay();
            anyVisible = overlay != null && overlay.isListDisplayed();
        }
        if (!anyVisible) { clearSelection(); return; }

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

        probeReflection();

        IngredientGridWithNavigation grid;
        if (dragOnBookmarks) {
            if (bookmarkContentsField == null) return;
            IBookmarkOverlay bmOverlay = runtime.getBookmarkOverlay();
            if (bmOverlay == null) return;
            try {
                grid = (IngredientGridWithNavigation) bookmarkContentsField.get(bmOverlay);
            } catch (Exception e) { return; }
        } else {
            if (overlayContentsField == null) return;
            IIngredientListOverlay overlay = runtime.getIngredientListOverlay();
            if (overlay == null) return;
            try {
                grid = (IngredientGridWithNavigation) overlayContentsField.get(overlay);
            } catch (Exception e) { return; }
        }
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
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-JEI-Marquee] bookmark create failed", e); }
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

    private static void batchUnbookmark(List<ITypedIngredient<?>> ingredients) {
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

        int removed = 0;
        for (ITypedIngredient<?> ing : ingredients) {
            if (ing == null) continue;
            try {
                IBookmark bookmark = (IBookmark) bookmarkCreateMethod.invoke(null, ing, im);
                if (bookmark != null && list.remove(bookmark)) removed++;
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-JEI-Marquee] bookmark remove failed", e); }
        }
        RSIntegrationMod.LOGGER.debug("[RSI-JEI-Marquee] Unbookmarked {} items", removed);
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
        dragOnBookmarks = false;
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
            bookmarkContentsField = bmOverlayClass.getDeclaredField("contents");
            bookmarkContentsField.setAccessible(true);
            bookmarkListField = bmOverlayClass.getDeclaredField("bookmarkList");
            bookmarkListField.setAccessible(true);

            bookmarkCreateMethod = IngredientBookmark.class.getMethod("create",
                    ITypedIngredient.class, IIngredientManager.class);
            bookmarkCreateMethod.setAccessible(true);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-JEI-Marquee] Reflection probe failed", e);
        }
    }
}
