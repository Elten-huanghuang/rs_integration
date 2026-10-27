package com.huanghuang.rsintegration.machine.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import com.huanghuang.rsintegration.ModItems;
import com.huanghuang.rsintegration.machine.client.MachineTabHandler;
import com.huanghuang.rsintegration.machine.client.data.BindingCache;
import com.huanghuang.rsintegration.network.binding.BindingInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import org.lwjgl.glfw.GLFW;
import com.huanghuang.rsintegration.machine.client.MachineFavoritesClient;
import com.huanghuang.rsintegration.machine.client.MachineTabRenderer;
import com.huanghuang.rsintegration.mods.bd.BeyondDimensionsVersion;
import net.minecraft.world.item.Items;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;

/** BD-only machine-center entrypoint. Keeps RS Grid mixins and layout untouched. */
@OnlyIn(Dist.CLIENT)
public final class BeyondDimensionsMachineHubClient {
    // BD anchors native RightTabButton widgets at the 176px content edge;
    // the background texture itself continues to 194px for the tab overhang.
    // BD's native tabs use +176 because they intentionally overlap the last
    // 18px of the 194px texture. Favorites are a separate dock, so keep a
    // visible 4px gap to the right of the complete terminal surface instead
    // of placing them on top of the scrollbar.
    private static final int BD_TAB_X_OFFSET = 198;
    /** 0.8 起终端与工作台界面合并后的屏幕类名。 */
    private static final String MODERN_BD_TERMINAL_SCREEN =
            "com.wintercogs.beyonddimensions.client.ui.storage.StorageScreen";
    private static final int BD_FAVORITE_WIDTH = 23;
    private static final int BD_FAVORITE_HEIGHT = 26;
    // BD's native RightTabButton positions are top+6, top+36, ...:
    // 26px art with a 4px overlap gap (30px step).
    private static final int BD_FAVORITE_GAP = 4;
    /** 0.8 的终端窗口比 0.7 大，机器中心页暂按屏幕居中的数据区估算，实机截图后再微调。 */
    private static final int MODERN_PAGE_WIDTH = 292;
    private static final int MODERN_PAGE_HEIGHT = 214;
    /** MachineHubRenderer 把面板画在锚点左侧，这里按面板宽度反推锚点。 */
    private static final int MODERN_HUB_WIDTH = 172;
    /** 收藏条相对 0.8 窗口顶部的内缩，避开窗口标题栏。 */
    private static final int MODERN_FAVORITE_TOP_INSET = 6;
    /** 读到的窗口矩形至少要这么大，才认为它是真实的终端窗口而不是菜单格子尺寸。 */
    private static final int MIN_TERMINAL_WIDTH = 176;
    private static final int MIN_TERMINAL_HEIGHT = 166;
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
    private static long lastBindingSyncRequest;
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
    private static boolean machineCenterAvailable;

    private BeyondDimensionsMachineHubClient() {}

    @SubscribeEvent
    public static void onRender(ScreenEvent.Render.Post event) {
        Screen screen = event.getScreen();
        if (!isTerminal(screen)) return;
        if (BeyondDimensionsVersion.isModernTerminalUi()) {
            renderModernPage(screen, event.getGuiGraphics(), event.getMouseX(), event.getMouseY());
            renderModernFavorites(screen, event.getGuiGraphics(), event.getMouseX(), event.getMouseY());
            return;
        }
        if (!MachineHub.isVisible()) return;
        int centerX = screen.width / 2;
        int centerY = screen.height / 2;
        MachineHubRenderer.render(event.getGuiGraphics(), centerX + 88, centerY - 80,
                176, event.getMouseX(), event.getMouseY());
    }

    /** 关闭终端时收起覆盖面板并清掉自动进食面板状态。 */
    @SubscribeEvent
    public static void onScreenClosing(ScreenEvent.Closing event) {
        if (!isTerminal(event.getScreen())) return;
        MachineHubPage.reset();
    }

    /**
     * 0.8 的机器中心挂在终端侧边栏页签上，但页签只当开关用：点击不会切换页面，
     * 所以存储内容始终留在屏幕上，这里只负责把机器网格覆盖画在存储界面之上。
     */
    private static void renderModernPage(Screen screen, GuiGraphics g, int mouseX, int mouseY) {
        if (!MachineHub.isVisible()) return;
        // 0.8 窗口大小随 BD 内部行数变化，这里跟着真实窗口矩形走，不再写死。
        WindowRect page = WindowRect.of(screen);
        int hubX = page.x() + (page.width() - MODERN_HUB_WIDTH) / 2;
        MachineHubRenderer.render(g, hubX + MODERN_HUB_WIDTH + 4, page.y() - 6,
                MODERN_HUB_WIDTH, mouseX, mouseY);
    }

    /** Draw terminal controls after BD's widgets but before its item tooltip. */
    public static void renderTerminalControlsBeforeTooltip(Screen screen, GuiGraphics graphics,
                                                            int mouseX, int mouseY) {
        if (!isTerminal(screen)) return;
        // 0.8 由侧边栏页签承载机器中心，不再叠浮动入口与收藏条。
        if (BeyondDimensionsVersion.isModernTerminalUi()) return;
        refreshLocalBindings();
        renderMachineCenterEntry(screen, graphics, mouseX, mouseY);
        renderResonanceEntry(screen, graphics, mouseX, mouseY);
        renderFavoriteStrip(screen, graphics, mouseX, mouseY);
    }

    /** Area occupied by BD's favorite-machine strip for recipe-browser avoidance. */
    public static List<Rect2i> getFavoriteExtraAreas(Screen screen) {
        if (!isTerminal(screen)) return List.of();
        if (BeyondDimensionsVersion.isModernTerminalUi()) {
            return modernFavoriteArea(screen);
        }
        refreshLocalBindings();
        List<BindingInfo> favorites = getFavoriteMachines();
        if (favorites.isEmpty()) return List.of();
        int left = resolveScreenInt(screen, "getGuiLeft", "leftPos", (screen.width - 176) / 2);
        int x = Math.min(left + BD_TAB_X_OFFSET,
                Math.max(2, screen.width - BD_FAVORITE_WIDTH - 2));
        int y = resolveBDTop(screen) + 6;
        // 逐个格子声明避让，避免整列占位把配方浏览器挤到远处。
        List<Rect2i> areas = new ArrayList<>(favorites.size());
        for (int i = 0; i < favorites.size(); i++) {
            areas.add(new Rect2i(x, y + i * (BD_FAVORITE_HEIGHT + BD_FAVORITE_GAP),
                    BD_FAVORITE_WIDTH, BD_FAVORITE_HEIGHT));
        }
        return areas;
    }

    @SubscribeEvent
    public static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (!isTerminal(event.getScreen())) return;
        if (BeyondDimensionsVersion.isModernTerminalUi()) {
            if (handleModernFavoriteClick(event)) return;
            if (MachineHub.isVisible()
                    && MachineHubInputHandler.mouseClicked(event.getMouseX(), event.getMouseY(), event.getButton())) {
                event.setCanceled(true);
            }
            return;
        }
        if (handleMachineCenterClick(event)) return;
        if (handleResonanceClick(event)) return;
        if (!MachineHub.isVisible() && handleFavoriteClick(event)) return;
        if (!MachineHub.isVisible()) return;
        if (MachineHubInputHandler.mouseClicked(event.getMouseX(), event.getMouseY(), event.getButton())) {
            event.setCanceled(true);
        }
    }

    private static void renderMachineCenterEntry(Screen screen, GuiGraphics g, int mouseX, int mouseY) {
        int left = resolveScreenInt(screen, "getGuiLeft", "leftPos", (screen.width - 176) / 2);
        int top = resolveBDTop(screen);
        machineCenterX = Math.max(0, left - 18);
        // DimensionsNetGUI owns eight 16px left-side slots in order:
        // sort, second sort, reverse, search, add/remove page, craft, and
        // primary-network switcher. Keep the machine center below that last
        // native control so it never covers the primary-network button.
        machineCenterY = top + 6 + 18 * 8;
        machineCenterAvailable = !MachineTabHandler.getAllMachines().isEmpty();
        boolean hovered = machineCenterAvailable
                && mouseX >= machineCenterX
                && mouseX < machineCenterX + MACHINE_CENTER_SIZE
                && mouseY >= machineCenterY
                && mouseY < machineCenterY + MACHINE_CENTER_SIZE;
        MachineTabHandler.setMachineCenterHovered(hovered);
        if (!machineCenterAvailable) return;
        int iconX = machineCenterX + BD_LEFT_ICON_X_OFFSET;
        int iconY = machineCenterY + BD_LEFT_ICON_Y_OFFSET;
        g.blit(hovered ? BD_SLOT_HOVER : BD_SLOT, iconX, iconY, 0, 0,
                BD_ICON_SIZE, BD_ICON_SIZE, BD_ICON_SIZE, BD_ICON_SIZE);
        g.blit(MACHINE_CENTER_ICON, iconX, iconY, 0, 0,
                BD_ICON_SIZE, BD_ICON_SIZE, BD_ICON_SIZE, BD_ICON_SIZE);
    }

    /** BD terminal slot 10: opens the generator's independent BD resonance space. */
    private static void renderResonanceEntry(Screen screen, GuiGraphics g, int mouseX, int mouseY) {
        int left = resolveScreenInt(screen, "getGuiLeft", "leftPos", (screen.width - 176) / 2);
        resonanceBackpackX = Math.max(0, left - 18);
        resonanceBackpackY = machineCenterY
                + (machineCenterAvailable ? MACHINE_CENTER_SIZE + 2 : 0);
        boolean available = ModItems.DIMENSIONAL_RESONANCE_DISK != null;
        boolean hovered = available
                && mouseX >= resonanceBackpackX
                && mouseX < resonanceBackpackX + MACHINE_CENTER_SIZE
                && mouseY >= resonanceBackpackY
                && mouseY < resonanceBackpackY + MACHINE_CENTER_SIZE;
        MachineTabHandler.setResonanceBackpackHovered(hovered);
        if (!available) return;
        g.blit(hovered ? BD_SLOT_HOVER : BD_SLOT, resonanceBackpackX, resonanceBackpackY,
                0, 0, BD_ICON_SIZE, BD_ICON_SIZE, BD_ICON_SIZE, BD_ICON_SIZE);
        ItemStack icon = new ItemStack(ModItems.DIMENSIONAL_RESONANCE_DISK.get());
        g.renderItem(icon, resonanceBackpackX, resonanceBackpackY);
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
        // 0.8 起终端与各工作台界面合并到了 client.ui.storage.StorageScreen；
        // 不按菜单类反推，避免配方树界面被误判。
        return BeyondDimensionsVersion.isModernTerminalUi()
                && name.equals(MODERN_BD_TERMINAL_SCREEN);
    }

    /** Draw BD-local machine favorites to the right of the terminal surface. */
    private static void renderFavoriteStrip(Screen screen, GuiGraphics g, int mouseX, int mouseY) {
        List<BindingInfo> favorites = getFavoriteMachines();
        if (favorites.isEmpty()) return;
        int left = resolveScreenInt(screen, "getGuiLeft", "leftPos", (screen.width - 176) / 2);
        int top = resolveBDTop(screen);
        int x = Math.min(left + BD_TAB_X_OFFSET,
                Math.max(2, screen.width - BD_FAVORITE_WIDTH - 2));
        int y = top + 6;
        for (int i = 0; i < favorites.size(); i++) {
            int sy = y + i * (BD_FAVORITE_HEIGHT + BD_FAVORITE_GAP);
            boolean hovered = mouseX >= x && mouseX < x + BD_FAVORITE_WIDTH
                    && mouseY >= sy && mouseY < sy + BD_FAVORITE_HEIGHT;
            g.blit(BD_RIGHT_TAB, x, sy, 0, 0,
                    BD_FAVORITE_WIDTH, BD_FAVORITE_HEIGHT,
                    BD_FAVORITE_WIDTH, BD_FAVORITE_HEIGHT);
            int iconX = x + BD_RIGHT_ICON_X_OFFSET;
            int iconY = sy + BD_RIGHT_ICON_Y_OFFSET;
            g.blit(hovered ? BD_SLOT_HOVER : BD_SLOT, iconX, iconY, 0, 0,
                    BD_ICON_SIZE, BD_ICON_SIZE, BD_ICON_SIZE, BD_ICON_SIZE);
            ItemStack icon = MachineTabRenderer
                    .resolveIcon(favorites.get(i));
            if (icon.isEmpty()) icon = new ItemStack(Items.BARRIER);
            g.renderItem(icon, iconX, iconY);
            g.renderItemDecorations(Minecraft.getInstance().font, icon, iconX, iconY);
            if (hovered) {
                g.renderTooltip(Minecraft.getInstance().font,
                        MachineDisplayNameResolver.displayName(favorites.get(i)), mouseX, mouseY);
            }
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

    /**
     * 0.8 的终端窗口会随 BD 内部行数配置变高变宽，所以收藏条不能写死坐标：
     * 先按真实窗口矩形定位，一列排满后自动向右加列；窗口右侧放不下时退到窗口内侧，
     * 保证无论对手窗口多大，收藏条都完整落在屏幕里。
     */
    private static void renderModernFavorites(Screen screen, GuiGraphics g, int mouseX, int mouseY) {
        for (FavoriteSlot slot : layoutModernFavorites(screen)) {
            boolean hovered = slot.contains(mouseX, mouseY);
            g.fill(slot.x(), slot.y(), slot.x() + BD_FAVORITE_WIDTH, slot.y() + BD_FAVORITE_HEIGHT,
                    hovered ? 0xE01B2A2E : 0xCC101A1E);
            g.renderOutline(slot.x(), slot.y(), BD_FAVORITE_WIDTH, BD_FAVORITE_HEIGHT,
                    hovered ? 0xFFE8F5E9 : 0xFF2E4A42);
            int iconX = slot.x() + BD_RIGHT_ICON_X_OFFSET;
            int iconY = slot.y() + BD_RIGHT_ICON_Y_OFFSET;
            ItemStack icon = MachineTabRenderer.resolveIcon(slot.info());
            if (icon.isEmpty()) icon = new ItemStack(Items.BARRIER);
            g.renderItem(icon, iconX, iconY);
            if (hovered) {
                g.renderTooltip(Minecraft.getInstance().font,
                        MachineDisplayNameResolver.displayName(slot.info()), mouseX, mouseY);
            }
        }
    }

    private static boolean handleModernFavoriteClick(ScreenEvent.MouseButtonPressed.Pre event) {
        if (event.getButton() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
        for (FavoriteSlot slot : layoutModernFavorites(event.getScreen())) {
            if (!slot.contains(event.getMouseX(), event.getMouseY())) continue;
            MachineTabHandler.onClick(slot.info());
            event.setCanceled(true);
            return true;
        }
        return false;
    }

    /**
     * 收藏条占用的区域，供配方浏览器避让。
     *
     * <p>这里按“每一格”给出矩形，而不是把整列收成一个大方块：JEI 只会绕开真正有图标的格子，
     * 列与列之间、列表末尾的空白仍然可以摆配方，界面才不会因为避让区过大而整体被推走。</p>
     */
    private static List<Rect2i> modernFavoriteArea(Screen screen) {
        List<FavoriteSlot> slots = layoutModernFavorites(screen);
        if (slots.isEmpty()) return List.of();
        List<Rect2i> areas = new ArrayList<>(slots.size());
        for (FavoriteSlot slot : slots) {
            areas.add(new Rect2i(slot.x(), slot.y(), BD_FAVORITE_WIDTH, BD_FAVORITE_HEIGHT));
        }
        return areas;
    }

    /** 计算 0.8 收藏条每一格的位置：纵向优先排满，再向右扩列。 */
    private static List<FavoriteSlot> layoutModernFavorites(Screen screen) {
        List<BindingInfo> favorites = getFavoriteMachines();
        if (favorites.isEmpty()) return List.of();
        List<FavoritePos> positions = layoutFavoritePositions(screen, favorites.size());
        List<FavoriteSlot> slots = new ArrayList<>(positions.size());
        for (int index = 0; index < positions.size(); index++) {
            FavoritePos position = positions.get(index);
            slots.add(new FavoriteSlot(position.x(), position.y(), favorites.get(index)));
        }
        return slots;
    }

    /**
     * 只算坐标不碰绑定数据，方便单测校验“窗口变高就多排几格、格子多了再向右扩列”。
     */
    static List<FavoritePos> layoutFavoritePositions(Screen screen, int count) {
        if (count <= 0) return List.of();
        WindowRect page = WindowRect.of(screen);
        int top = Math.max(2, page.y() + MODERN_FAVORITE_TOP_INSET);
        int available = screen.height - top - 2;
        int rowsPerColumn = Math.max(1,
                (available + BD_FAVORITE_GAP) / (BD_FAVORITE_HEIGHT + BD_FAVORITE_GAP));
        int columns = (count + rowsPerColumn - 1) / rowsPerColumn;
        int width = columns * BD_FAVORITE_WIDTH + (columns - 1) * BD_FAVORITE_GAP;
        int x = page.x() + page.width() + BD_FAVORITE_GAP;
        if (x + width > screen.width - 2) {
            x = Math.min(page.x() + page.width() - BD_FAVORITE_GAP - width, screen.width - 2 - width);
        }
        x = Math.max(2, x);
        List<FavoritePos> positions = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            int column = index / rowsPerColumn;
            int row = index % rowsPerColumn;
            positions.add(new FavoritePos(
                    x + column * (BD_FAVORITE_WIDTH + BD_FAVORITE_GAP),
                    top + row * (BD_FAVORITE_HEIGHT + BD_FAVORITE_GAP)));
        }
        return positions;
    }

    /** 0.8 终端窗口矩形，供其它客户端控件（例如自动进食按钮）对齐真实窗口。 */
    public static Rect2i modernWindowBounds(Screen screen) {
        WindowRect page = WindowRect.of(screen);
        return new Rect2i(page.x(), page.y(), page.width(), page.height());
    }

    /** 0.8 终端窗口矩形：读得到真实值就用，读不到则按内容尺寸居中估算。 */
    private record WindowRect(int x, int y, int width, int height) {
        private static WindowRect of(Screen screen) {
            int left = resolveScreenInt(screen, "getGuiLeft", "leftPos", -1);
            int top = resolveScreenInt(screen, "getGuiTop", "topPos", -1);
            int width = resolveScreenInt(screen, "getXSize", "imageWidth", 0);
            int height = resolveScreenInt(screen, "getYSize", "imageHeight", 0);
            boolean plausible = width >= MIN_TERMINAL_WIDTH && height >= MIN_TERMINAL_HEIGHT
                    && left >= 0 && top >= 0
                    && left + width <= screen.width + 8 && top + height <= screen.height + 8
                    // 默认值（0,0,176,166）也满足上面的条件，但那不是真实窗口；
                    // 真实终端至少会伸到屏幕右半边，用它把“没初始化”的矩形挡掉。
                    && left + width >= screen.width / 2;
            if (plausible) return new WindowRect(left, top, width, height);
            int fallbackWidth = Math.min(MODERN_PAGE_WIDTH, screen.width);
            int fallbackHeight = Math.min(MODERN_PAGE_HEIGHT, screen.height);
            return new WindowRect((screen.width - fallbackWidth) / 2,
                    (screen.height - fallbackHeight) / 2, fallbackWidth, fallbackHeight);
        }
    }

    private record FavoriteSlot(int x, int y, BindingInfo info) {
        private boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX < x + BD_FAVORITE_WIDTH
                    && mouseY >= y && mouseY < y + BD_FAVORITE_HEIGHT;
        }
    }

    /** 收藏格子的纯坐标，单独抽出来便于单元测试。 */
    record FavoritePos(int x, int y) {}

    private static List<BindingInfo> getFavoriteMachines() {
        if (ModList.get().isLoaded("refinedstorage")) {
            return MachineFavoritesClient
                    .getFavoriteMachines();
        }
        return MachineHub.getLocalFavoriteMachines();
    }

    private static int resolveInt(Screen screen, String fieldName, int fallback) {
        Class<?> type = screen.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(fieldName);
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
            Method method = findMethod(screen.getClass(), "rebuildImageHeight");
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

    private static Method findMethod(Class<?> type, String name) {
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
                Field field = type.getDeclaredField("menu");
                field.setAccessible(true);
                Object menu = field.get(screen);
                if (menu != null) {
                    Method method = menu.getClass().getMethod("getLines");
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
        if (!BindingCache.getInstance().isEmpty()) return;
        long now = System.currentTimeMillis();
        if (now - lastBindingSyncRequest < 2000L) return;
        lastBindingSyncRequest = now;
        MachineTabHandler.requestBindingSync();
    }

    /** 服务端快照到达后重置空缓存请求节奏。 */
    static void markAuthoritativeBindingSync() {
        lastBindingSyncRequest = 0L;
    }
}
