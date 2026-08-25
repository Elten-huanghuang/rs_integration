package com.huanghuang.rsintegration.autoeat.client;

import com.huanghuang.rsintegration.autoeat.AutoEatMode;
import com.huanghuang.rsintegration.autoeat.network.AutoEatPacket;
import com.huanghuang.rsintegration.autoeat.network.RequestBlacklistPacket;
import com.huanghuang.rsintegration.config.ClientSyncedConfig;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.lang.reflect.Field;

@OnlyIn(Dist.CLIENT)
public final class AutoEatClientEvents {

    private AutoEatClientEvents() {}

    private static boolean blacklistRequested;

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
        for (Object listener : screen.children()) {
            if (listener instanceof AutoEatButton) {
                switch (((AutoEatButton) listener).role) {
                    case EAT -> existingEat = (AutoEatButton) listener;
                    case SELECT -> existingSelect = (AutoEatButton) listener;
                    case MODE -> existingMode = (AutoEatButton) listener;
                }
            }
        }

        int btnW = 64;
        int btnH = 20;
        int btnGap = 4;
        boolean beyondDimensions = isBeyondDimensionsScreen(screen);
        // RS Grid keeps its established left-side placement. BD's main panel
        // is a centered 176px surface; anchor the buttons beside that panel
        // instead of reusing the RS offset, which puts them roughly 150px
        // away on the BD terminal screen.
        int x;
        int yBase;
        if (beyondDimensions) {
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
            x = Math.max(4, guiLeft - btnW - 26);
            yBase = Math.max(4, guiTop + imageHeight - (btnH * 4 + btnGap * 3) - 4);
            // BD's native side-button slot is 18px tall. Move the custom
            // three-button stack down by exactly one of its own slots.
            x += 25;
            yBase += btnH + btnGap;
        } else {
            // Preserve the original RS Grid-relative placement. Reflection is
            // used here so BD-only clients never link an RS screen class.
            int guiLeft = resolveScreenInt(screen, "getGuiLeft", "leftPos", screen.width / 2 - 88);
            int guiTop = resolveScreenInt(screen, "getGuiTop", "topPos", screen.height / 2 - 80);
            int ySize = resolveScreenInt(screen, "getYSize", "imageHeight", 166);
            x = Math.max(4, guiLeft - btnW - 4);
            yBase = Math.max(4, guiTop + ySize - (btnH * 3 + btnGap * 2) - 2);
        }

        // Button 1 — Eat (one-shot)
        AutoEatButton eatBtn = new AutoEatButton(Role.EAT, x, yBase, btnW, btnH,
                Component.translatable("rsi.autoeat.btn.eat"),
                Tooltip.create(Component.translatable("rsi.autoeat.btn.eat.tooltip")
                        .append("\n")
                        .append(Component.translatable("rsi.autoeat.btn.eat.hint"))),
                btn -> NetworkHandler.CHANNEL.sendToServer(
                        new AutoEatPacket(ClientState.currentMode, ClientState.selectedItem))
        );
        if (existingEat == null) adder.add(eatBtn);
        else { existingEat.setX(x); existingEat.setY(yBase); }

        // Button 2 — Select / Blacklist
        AutoEatButton selectBtn = new AutoEatButton(Role.SELECT, x, yBase + btnH + btnGap, btnW, btnH,
                getSelectLabel(),
                Tooltip.create(getSelectTooltip()),
                btn -> openSelectScreen()
        );
        if (existingSelect == null) adder.add(selectBtn);
        else {
            existingSelect.setX(x); existingSelect.setY(yBase + btnH + btnGap);
            existingSelect.setMessage(getSelectLabel());
            existingSelect.setTooltip(Tooltip.create(getSelectTooltip()));
        }

        // Button 3 — Mode switch
        AutoEatButton modeBtn = new AutoEatButton(Role.MODE, x, yBase + (btnH + btnGap) * 2, btnW, btnH,
                ClientState.currentMode.displayName(),
                Tooltip.create(Component.translatable("rsi.autoeat.btn.mode.tooltip")),
                btn -> {
                    ClientState.cycleMode();
                    btn.setMessage(ClientState.currentMode.displayName());
                    selectBtn.setMessage(getSelectLabel());
                    selectBtn.setTooltip(Tooltip.create(getSelectTooltip()));
                }
        );
        if (existingMode == null) adder.add(modeBtn);
        else { existingMode.setX(x); existingMode.setY(yBase + (btnH + btnGap) * 2); }

        // Sync blacklist from server (once per session)
        if (!blacklistRequested) {
            blacklistRequested = true;
            NetworkHandler.CHANNEL.sendToServer(new RequestBlacklistPacket());
        }
    }

    private static boolean isAutoEatEnabled() {
        return ClientSyncedConfig.isSynced()
                ? ClientSyncedConfig.ENABLE_AUTO_EAT
                : RSIntegrationConfig.ENABLE_AUTO_EAT.get();
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
        if (name.endsWith("DimensionsCraftGUI") || name.endsWith("DimensionsTerminalCraftGUI")
                || name.contains("beyonddimensions.client.gui.DimensionsCraftGUI")) return true;
        // Do not infer a terminal from its menu: the recipe-tree screen can
        // retain the BD menu while it is open, which would incorrectly place
        // the auto-eat controls on the tree UI.
        return false;
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
            for (java.lang.reflect.Method method : Screen.class.getDeclaredMethods()) {
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
            java.lang.reflect.Method method = findMethod(screen.getClass(), "rebuildImageHeight");
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
                    java.lang.reflect.Method method = menu.getClass().getMethod("getLines");
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
    private enum Role { EAT, SELECT, MODE }

    private static class AutoEatButton extends Button {
        private final Role role;
        AutoEatButton(Role role, int x, int y, int w, int h, Component text, Tooltip tooltip, OnPress onPress) {
            super(x, y, w, h, text, onPress, DEFAULT_NARRATION);
            this.role = role;
            setTooltip(tooltip);
        }
    }
}
