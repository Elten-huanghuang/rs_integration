package com.huanghuang.rsintegration.feature.autoeat.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.huanghuang.rsintegration.feature.autoeat.AutoEatMode;
import com.huanghuang.rsintegration.feature.autoeat.network.AutoEatPacket;
import com.huanghuang.rsintegration.feature.autoeat.network.RequestBlacklistPacket;
import com.huanghuang.rsintegration.config.ClientSyncedConfig;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.huanghuang.rsintegration.mods.bd.BeyondDimensionsVersion;
import com.huanghuang.rsintegration.machine.client.BeyondDimensionsMachineHubClient;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

@OnlyIn(Dist.CLIENT)
public final class AutoEatClientEvents {

    private AutoEatClientEvents() {}

    private static final int BUTTON_WIDTH = 64;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_GAP = 4;
    private static final int SIDEBAR_GAP = 2;
    private static final ResourceLocation HUD_ICONS = new ResourceLocation("textures/gui/icons.png");
    private static final ResourceLocation RS_ICONS =
            new ResourceLocation("refinedstorage", "textures/icons.png");
    private static final ResourceLocation BD_SLOT =
            new ResourceLocation("beyonddimensions", "textures/gui/sprites/widget/slot_button.png");
    private static final ResourceLocation BD_SLOT_HOVER =
            new ResourceLocation("beyonddimensions", "textures/gui/sprites/widget/slot_button_hovered.png");
    /** 0.8 没有 BD 槽位贴图，改用纯色底绘制按钮。 */
    private static final int MODERN_SLOT = 0xFF1B2130;
    private static final int MODERN_SLOT_HOVER = 0xFF2C3547;
    private static final int MODERN_SLOT_BORDER = 0xFF46E0C8;
    /** 0.8 起终端与工作台界面合并后的屏幕类名。 */
    private static final String MODERN_BD_TERMINAL_SCREEN =
            "com.wintercogs.beyonddimensions.client.ui.storage.StorageScreen";

    private static boolean blacklistRequested;
    /**
     * 0.8 里自动进食改由侧边栏页签开关，面板收起时这些按钮不绘制也不响应点击，
     * 避免一直浮在存储界面左边挡视线。
     */
    private static boolean modernControlsOpen;

    /** 侧边栏“自动进食”页签点击：开关 0.8 的自动进食面板。 */
    public static void toggleModernControls() {
        modernControlsOpen = !modernControlsOpen;
        Screen screen = Minecraft.getInstance().screen;
        if (screen != null) applyModernPanelVisibility(screen);
    }

    /** 终端关闭时收起面板，下次打开保持干净。 */
    public static void resetModernControls() {
        modernControlsOpen = false;
    }

    /**
     * 只对 0.8 的 BD 终端生效：页签打开时三个按钮全部出现，关闭时全部隐藏
     * （0.8 没有“展开/收起”子菜单按钮，展开状态沿用旧配置没有意义）。
     */
    private static void applyModernPanelVisibility(Screen screen) {
        if (screen == null || !isBeyondDimensionsScreen(screen)) return;
        if (!BeyondDimensionsVersion.isModernTerminalUi()) return;
        for (Object listener : screen.children()) {
            if (!(listener instanceof AutoEatButton button)) continue;
            button.visible = modernControlsOpen;
        }
    }

    @SubscribeEvent
    public static void onClientLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        blacklistRequested = true;
        NetworkHandler.CHANNEL.sendToServer(new RequestBlacklistPacket());
    }

    @SubscribeEvent
    public static void onClientDisconnect(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientState.reset();
        ClientSyncedConfig.reset();
        blacklistRequested = false;
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        if (!isAutoEatEnabled()) return;
        if (!isStorageScreen(screen)) return;

        installControls(screen, button -> event.addListener(button));
    }

    /**
     * BD rebuilds its terminal by calling clearWidgets()/init() internally
     * when the page count changes. That path does not always emit a Forge init
     * event, so the controls are also repaired from Render.Post below.
     */
    public static void onScreenRender(ScreenEvent.Render.Post event) {
        Screen screen = event.getScreen();
        if (!isAutoEatEnabled() || !isStorageScreen(screen)) return;
        installControls(screen, button -> addRenderable(screen, button));
    }

    /**
     * Called from BD's own DimensionsNetGUI.init tail. BD rebuilds the same
     * screen instance after clearWidgets(), so an Init.Post event is not a
     * reliable lifecycle hook for these controls.
     */
    public static void installControlsAfterNativeInit(Screen screen) {
        if (screen == null || !isAutoEatEnabled() || !isStorageScreen(screen)) return;
        installControls(screen, button -> addRenderable(screen, button));
    }

    public static void installControlsAfterNativeInit(Screen screen, Consumer<Button> adder) {
        if (screen == null || adder == null || !isAutoEatEnabled() || !isStorageScreen(screen)) return;
        installControls(screen, button -> adder.accept(button));
    }

    private interface ControlAdder {
        void add(AutoEatButton button);
    }

    private static void installControls(Screen screen, ControlAdder adder) {

        // Guard: ScreenEvent.Init.Post can fire multiple times (data sync,
        // double registration, etc.).  Scan existing listeners — if our
        // AutoEatButton is already on screen, skip to avoid stacking duplicates.
        AutoEatButton existingEat = null;
        AutoEatButton existingSelect = null;
        AutoEatButton existingMode = null;
        AutoEatButton existingMenu = null;
        for (Object listener : screen.children()) {
            if (listener instanceof AutoEatButton) {
                switch (((AutoEatButton) listener).role) {
                    case MENU -> existingMenu = (AutoEatButton) listener;
                    case EAT -> existingEat = (AutoEatButton) listener;
                    case SELECT -> existingSelect = (AutoEatButton) listener;
                    case MODE -> existingMode = (AutoEatButton) listener;
                }
            }
        }

        boolean beyondDimensions = isBeyondDimensionsScreen(screen);
        // 0.8 的自动进食由侧边栏页签开关，不再需要“展开/收起”那个菜单按钮。
        boolean modern = beyondDimensions && BeyondDimensionsVersion.isModernTerminalUi();
        // RS Grid keeps its established left-side placement. BD's main panel
        // is a centered 176px surface; anchor the buttons beside that panel
        // instead of reusing the RS offset, which puts them roughly 150px
        // away on the BD terminal screen.
        int menuX;
        int menuY;
        int menuSize;
        int secondaryX;
        int secondaryY;
        if (modern) {
            // 面板改由页签开关后，三个按钮直接贴终端窗口左外侧的下缘排一列，
            // 与 0.7 的手感一致；不再往窗口内侧叠小图标。
            Rect2i window = BeyondDimensionsMachineHubClient.modernWindowBounds(screen);
            int stackHeight = BUTTON_HEIGHT * 3 + BUTTON_GAP * 2;
            menuSize = 16;
            menuX = Math.max(2, window.getX() - menuSize - 6);
            menuY = Math.max(2, window.getY() + 4);
            secondaryX = Math.max(2, window.getX() - BUTTON_WIDTH - 6);
            secondaryY = Math.max(2, window.getY() + window.getHeight() - stackHeight - 4);
        } else if (beyondDimensions) {
            // Keep the controls in the left gutter of BD's item grid. Resolve
            // the real container edge when available so GUI scaling does not
            // push them into the inventory slots.
            // DimensionsNetGUI keeps the panel centered at a fixed 176px
            // logical width, while its height changes with the row count.
            // The BD methods/fields are obfuscated in a production client,
            // so derive the anchors from its own rebuildImageHeight() method
            // instead of relying on names such as imageHeight.
            int imageHeight = resolveBDImageHeight(screen);
            int guiLeft = (screen.width - 176) / 2;
            int guiTop = (screen.height - imageHeight) / 2;
            // BD owns a vertical strip of 16px buttons at leftPos - 18.
            // Keep our controls further left so none of BD's native controls
            // are covered, and anchor to the actual dynamic panel bottom.
            menuSize = 16;
            menuX = Math.max(0, guiLeft - 18);
            // Machine Center and Resonance occupy slots 9 and 10. Auto Eat
            // continues the same native 18px-pitch strip in slot 11.
            menuY = guiTop + 6 + 18 * 10;
            secondaryX = Math.max(4, guiLeft - BUTTON_WIDTH - 26) + 25;
            secondaryY = Math.max(4, guiTop + imageHeight
                    - (BUTTON_HEIGHT * 4 + BUTTON_GAP * 3) - 4)
                    + BUTTON_HEIGHT + BUTTON_GAP;
        } else {
            // Preserve the original RS Grid-relative placement. Reflection is
            // used here so BD-only clients never link an RS screen class.
            int guiLeft = resolveScreenInt(screen, "getGuiLeft", "leftPos", screen.width / 2 - 88);
            int guiTop = resolveScreenInt(screen, "getGuiTop", "topPos", screen.height / 2 - 80);
            menuSize = 18;
            int resonanceRelX = resolveScreenInt(screen,
                    "rsi$getResonanceBackpackButtonX", "rsi$resonanceBackpackRelX", -20);
            int resonanceRelY = resolveScreenInt(screen,
                    "rsi$getResonanceBackpackButtonY", "rsi$resonanceBackpackRelY", Integer.MIN_VALUE);
            menuX = guiLeft + (resonanceRelX < 0 ? resonanceRelX : -20);
            if (resonanceRelY == Integer.MIN_VALUE || resonanceRelY <= 0) {
                int ySize = resolveScreenInt(screen, "getYSize", "imageHeight", 166);
                menuY = Math.max(4, guiTop + ySize - menuSize);
            } else {
                menuY = guiTop + resonanceRelY + menuSize + SIDEBAR_GAP;
            }
            int ySize = resolveScreenInt(screen, "getYSize", "imageHeight", 166);
            secondaryX = Math.max(4, guiLeft - BUTTON_WIDTH - 4);
            secondaryY = Math.max(4, guiTop + ySize
                    - (BUTTON_HEIGHT * 3 + BUTTON_GAP * 2) - 2);
        }

        boolean expanded = isMenuExpanded(screen);
        // 0.8 的整包已删掉 textures/gui/sprites/widget/，沿用 BD 贴图会画出一片空白，改用纯色底。
        MenuStyle menuStyle = !beyondDimensions ? MenuStyle.RS
                : BeyondDimensionsVersion.isModernTerminalUi() ? MenuStyle.MODERN : MenuStyle.BEYOND_DIMENSIONS;

        AutoEatButton menuBtn = new AutoEatButton(Role.MENU, menuX, menuY,
                menuSize, menuSize, getMenuLabel(), getMenuTooltip(expanded),
                btn -> {
                    boolean nextExpanded = !isMenuExpanded(screen);
                    rememberMenuExpanded(nextExpanded);
                    btn.setTooltip(getMenuTooltip(nextExpanded));
                    setSecondaryControlsVisible(screen, nextExpanded);
                });
        menuBtn.setMenuStyle(menuStyle);
        if (modern) {
            // 0.8 用页签开关面板，这个图标没有存在的意义，连同旧实例一起收起来。
            if (existingMenu != null) existingMenu.visible = false;
        } else if (existingMenu == null) {
            adder.add(menuBtn);
        } else {
            existingMenu.setX(menuX);
            existingMenu.setY(menuY);
            existingMenu.setWidth(menuSize);
            existingMenu.setHeight(menuSize);
            existingMenu.setMessage(getMenuLabel());
            existingMenu.setTooltip(getMenuTooltip(expanded));
            existingMenu.setMenuStyle(menuStyle);
        }

        // Keep the three established controls at their original screen anchors.
        AutoEatButton eatBtn = new AutoEatButton(Role.EAT, secondaryX, secondaryY,
                BUTTON_WIDTH, BUTTON_HEIGHT,
                Component.translatable("rsi.autoeat.btn.eat"),
                Tooltip.create(Component.translatable("rsi.autoeat.btn.eat.tooltip")
                        .append("\n")
                        .append(Component.translatable("rsi.autoeat.btn.eat.hint"))),
                btn -> NetworkHandler.CHANNEL.sendToServer(
                        new AutoEatPacket(ClientState.currentMode, ClientState.selectedItems))
        );
        setButtonVisible(eatBtn, expanded);
        if (existingEat == null) adder.add(eatBtn);
        else {
            existingEat.setX(secondaryX);
            existingEat.setY(secondaryY);
            setButtonVisible(existingEat, expanded);
        }

        // Level 2, button 2: Select / Blacklist
        AutoEatButton selectBtn = new AutoEatButton(Role.SELECT, secondaryX,
                secondaryY + BUTTON_HEIGHT + BUTTON_GAP, BUTTON_WIDTH, BUTTON_HEIGHT,
                getSelectLabel(),
                Tooltip.create(getSelectTooltip()),
                btn -> openSelectScreen()
        );
        setButtonVisible(selectBtn, expanded);
        if (existingSelect == null) adder.add(selectBtn);
        else {
            existingSelect.setX(secondaryX);
            existingSelect.setY(secondaryY + BUTTON_HEIGHT + BUTTON_GAP);
            existingSelect.setMessage(getSelectLabel());
            existingSelect.setTooltip(Tooltip.create(getSelectTooltip()));
            setButtonVisible(existingSelect, expanded);
        }

        // Level 2, button 3: Mode switch
        AutoEatButton modeBtn = new AutoEatButton(Role.MODE, secondaryX,
                secondaryY + (BUTTON_HEIGHT + BUTTON_GAP) * 2, BUTTON_WIDTH, BUTTON_HEIGHT,
                ClientState.currentMode.displayName(),
                Tooltip.create(Component.translatable("rsi.autoeat.btn.mode.tooltip")),
                btn -> {
                    ClientState.cycleMode();
                    btn.setMessage(ClientState.currentMode.displayName());
                    selectBtn.setMessage(getSelectLabel());
                    selectBtn.setTooltip(Tooltip.create(getSelectTooltip()));
                }
        );
        setButtonVisible(modeBtn, expanded);
        if (existingMode == null) adder.add(modeBtn);
        else {
            existingMode.setX(secondaryX);
            existingMode.setY(secondaryY + (BUTTON_HEIGHT + BUTTON_GAP) * 2);
            existingMode.setMessage(ClientState.currentMode.displayName());
            setButtonVisible(existingMode, expanded);
        }

        // Sync blacklist from server (once per session)
        if (!blacklistRequested) {
            blacklistRequested = true;
            NetworkHandler.CHANNEL.sendToServer(new RequestBlacklistPacket());
        }

        // 0.8 的自动进食由侧边栏页签控制显隐，放在最后统一套用，避免被上面的 setButtonVisible 覆盖。
        applyModernPanelVisibility(screen);
    }

    private static boolean isAutoEatEnabled() {
        return ClientSyncedConfig.isSynced()
                ? ClientSyncedConfig.ENABLE_AUTO_EAT
                : RSIntegrationConfig.ENABLE_AUTO_EAT.get();
    }

    /** Returns the bounds of the currently visible auto-eat controls for JEI avoidance. */
    public static List<Rect2i> getGuiExtraAreas(Screen screen) {
        if (screen == null || !isAutoEatEnabled() || !isStorageScreen(screen)) return List.of();
        if (BeyondDimensionsVersion.isModernTerminalUi() && !modernControlsOpen) return List.of();
        // 每个按钮单独声明，避免把按钮之间的空隙也让给配方浏览器。
        List<Rect2i> areas = new ArrayList<>();
        for (Object listener : screen.children()) {
            if (!(listener instanceof AutoEatButton button) || !button.visible) continue;
            areas.add(new Rect2i(button.getX(), button.getY(), button.getWidth(), button.getHeight()));
        }
        return List.copyOf(areas);
    }

    private static boolean isMenuExpanded(Screen screen) {
        return RSIntegrationConfig.AUTO_EAT_MENU_EXPANDED.get();
    }

    private static void rememberMenuExpanded(boolean expanded) {
        RSIntegrationConfig.AUTO_EAT_MENU_EXPANDED.set(expanded);
        RSIntegrationConfig.saveClientConfig();
    }

    private static Component getMenuLabel() {
        return Component.translatable("rsi.autoeat.btn.menu");
    }

    private static Tooltip getMenuTooltip(boolean expanded) {
        return Tooltip.create(Component.translatable(expanded
                ? "rsi.autoeat.btn.menu.collapse.tooltip"
                : "rsi.autoeat.btn.menu.expand.tooltip"));
    }

    private static void setSecondaryControlsVisible(Screen screen, boolean visible) {
        for (Object listener : screen.children()) {
            if (listener instanceof AutoEatButton button && button.role != Role.MENU) {
                setButtonVisible(button, visible);
            }
        }
    }

    private static void setButtonVisible(AutoEatButton button, boolean visible) {
        button.visible = visible;
        button.active = visible;
    }

    private static boolean isStorageScreen(Screen screen) {
        String name = screen.getClass().getName();
        // RS Grid and BD's terminal crafting screen are both valid storage
        // contexts. Keep this check by class name so BD-only clients never
        // resolve optional RS client classes.
        if (name.equals("com.refinedmods.refinedstorage.screen.grid.GridScreen")) return true;
        if (isBeyondDimensionsScreen(screen)) return true;
        return false;
    }

    private static boolean isBeyondDimensionsScreen(Screen screen) {
        String name = screen.getClass().getName();
        // 不按菜单类反推界面：配方树界面可能仍挂着 BD 的菜单，会被误判。
        if (name.endsWith("DimensionsCraftGUI") || name.endsWith("DimensionsTerminalCraftGUI")
                || name.contains("beyonddimensions.client.gui.DimensionsCraftGUI")) return true;
        // 0.8 起终端与各工作台界面合并到了 client.ui.storage.StorageScreen。
        return BeyondDimensionsVersion.isModernTerminalUi()
                && name.equals(MODERN_BD_TERMINAL_SCREEN);
    }

    private static int resolveScreenInt(Screen screen, String methodName, String fieldName, int fallback) {
        try {
            Object value = screen.getClass().getMethod(methodName).invoke(screen);
            return value instanceof Number number ? number.intValue() : fallback;
        } catch (ReflectiveOperationException | SecurityException ignored) {
            Class<?> type = screen.getClass();
            while (type != null) {
                try {
                    Field field = type.getDeclaredField(fieldName);
                    field.setAccessible(true);
                    Object value = field.get(screen);
                    if (value instanceof Number number) return number.intValue();
                } catch (ReflectiveOperationException | SecurityException ignoredField) {
                    type = type.getSuperclass();
                }
            }
            return fallback;
        }
    }

    private static void addRenderable(Screen screen, AutoEatButton button) {
        try {
            // The mapped parameter type differs between Forge/Mojmap builds
            // (RenderableWidget vs GuiEventListener). Find the actual method
            // instead of requiring one exact erased signature.
            for (Method method : Screen.class.getDeclaredMethods()) {
                // addRenderableWidget is the development mapping; m_142416_
                // is the name in the Forge runtime jar used by players.
                if (!("addRenderableWidget".equals(method.getName())
                        || "m_142416_".equals(method.getName()))
                        || method.getParameterCount() != 1
                        || !method.getParameterTypes()[0].isAssignableFrom(button.getClass())) {
                    continue;
                }
                method.setAccessible(true);
                method.invoke(screen, button);
                return;
            }
        } catch (ReflectiveOperationException | SecurityException ignored) {
            // The normal Init.Post path remains available if a hardened
            // runtime refuses reflective access during Render.Post.
        }
    }

    private static int resolveBDImageHeight(Screen screen) {
        try {
            // This method belongs to BD itself and therefore remains named
            // rebuildImageHeight after re-obfuscation of Minecraft classes.
            Method method = findMethod(screen.getClass(), "rebuildImageHeight");
            if (method == null) throw new NoSuchMethodException("rebuildImageHeight");
            method.setAccessible(true);
            Object value = method.invoke(screen);
            if (value instanceof Number number) return number.intValue();
        } catch (ReflectiveOperationException | SecurityException ignored) {
            // Fall through to the known BD formula used by DimensionsNetGUI.
        }
        int lines = resolveBDLines(screen);
        return 24 + 18 + Math.max(0, lines - 2) * 18 + 26 + 89;
    }

    private static int resolveBDLines(Screen screen) {
        try {
            Field menuField = findField(screen.getClass(), "menu", "f_97732_");
            if (menuField != null) {
                menuField.setAccessible(true);
                Object menu = menuField.get(screen);
                if (menu != null) {
                    Method method = menu.getClass().getMethod("getLines");
                    Object value = method.invoke(menu);
                    if (value instanceof Number number) return number.intValue();
                }
            }
        } catch (ReflectiveOperationException | SecurityException ignored) {
            // Use the minimum two rows when the optional BD menu is hidden.
        }
        return 2;
    }

    private static Field findField(Class<?> type, String... names) {
        while (type != null) {
            for (String name : names) {
                try {
                    return type.getDeclaredField(name);
                } catch (NoSuchFieldException ignored) {
                    // Try the next mapping/name in this class hierarchy.
                }
            }
            type = type.getSuperclass();
        }
        return null;
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

    private static Component getSelectLabel() {
        return ClientState.currentMode == AutoEatMode.STACK
                ? Component.translatable("rsi.autoeat.btn.select")
                : Component.translatable("rsi.autoeat.btn.blacklist");
    }

    private static Component getSelectTooltip() {
        return ClientState.currentMode == AutoEatMode.STACK
                ? Component.translatable("rsi.autoeat.btn.select.tooltip")
                : Component.translatable("rsi.autoeat.btn.blacklist.tooltip");
    }

    private static void openSelectScreen() {
        Minecraft.getInstance().setScreen(new AutoEatScreen(ClientState.currentMode));
    }

    /**
     * Dedicated button subclass that doubles as an {@code instanceof} marker
     * for the re-entry guard: ScreenEvent.Init.Post can fire more than once,
     * and checking for this type prevents stacking duplicate buttons.
     */
    private enum Role { MENU, EAT, SELECT, MODE }

    /** 自动进食面板按钮的外观来源。 */
    private enum MenuStyle {
        /** RS 网格：沿用 RS 图标表的 hunger 按钮。 */
        RS,
        /** 超越维度 0.7：沿用 BD 的 18px 槽位贴图。 */
        BEYOND_DIMENSIONS,
        /** 超越维度 0.8：贴图已删除，改用纯色底。 */
        MODERN
    }

    private static class AutoEatButton extends Button {
        private final Role role;
        private MenuStyle menuStyle = MenuStyle.RS;

        AutoEatButton(Role role, int x, int y, int w, int h, Component text, Tooltip tooltip, OnPress onPress) {
            super(x, y, w, h, text, onPress, DEFAULT_NARRATION);
            this.role = role;
            setTooltip(tooltip);
        }

        void setMenuStyle(MenuStyle style) {
            this.menuStyle = style;
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            if (role != Role.MENU) {
                super.renderWidget(graphics, mouseX, mouseY, partialTick);
                return;
            }

            boolean hovered = isHoveredOrFocused();
            if (menuStyle == MenuStyle.MODERN) {
                graphics.fill(getX(), getY(), getX() + width, getY() + height,
                        hovered ? MODERN_SLOT_HOVER : MODERN_SLOT);
                graphics.renderOutline(getX(), getY(), width, height, MODERN_SLOT_BORDER);
            } else if (menuStyle == MenuStyle.BEYOND_DIMENSIONS) {
                ResourceLocation texture = hovered ? BD_SLOT_HOVER : BD_SLOT;
                graphics.blit(texture, getX(), getY(), 0, 0,
                        width, height, width, height);
            } else {
                int textureY = hovered ? 35 : 16;
                graphics.blit(RS_ICONS, getX(), getY(), 238, textureY, 18, 18);
                if (hovered) {
                    RenderSystem.enableBlend();
                    RenderSystem.defaultBlendFunc();
                    graphics.setColor(1.0F, 1.0F, 1.0F, 0.5F);
                    graphics.blit(RS_ICONS, getX(), getY(), 238, 54, 18, 18);
                    graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
                    RenderSystem.disableBlend();
                }
            }
            // Vanilla draws the empty hunger outline first and the full-food
            // fill second; the fill sprite alone has no dark outer contour.
            graphics.blit(HUD_ICONS, getX() + (width - 9) / 2, getY() + (height - 9) / 2,
                    16, 27, 9, 9);
            graphics.blit(HUD_ICONS, getX() + (width - 9) / 2, getY() + (height - 9) / 2,
                    52, 27, 9, 9);
        }
    }
}
