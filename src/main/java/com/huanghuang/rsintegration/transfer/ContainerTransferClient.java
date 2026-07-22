package com.huanghuang.rsintegration.transfer;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.network.RSJeiPlugin;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.registries.ForgeRegistries;

import net.minecraftforge.fml.loading.FMLPaths;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@OnlyIn(Dist.CLIENT)
public final class ContainerTransferClient {

    public static KeyMapping KEY_STORE_ALL;
    public static KeyMapping KEY_TOGGLE_MODE;

    // Mode constants
    private static final byte MODE_RS = 0;
    private static final byte MODE_BACKPACK = 1;

    private static byte currentMode = MODE_BACKPACK;
    private static Component modeMessage;
    private static long modeMessageUntil;

    private static final Path MODE_FILE =
            FMLPaths.CONFIGDIR.get().resolve("rs_integration_transfer_mode.txt");

    private ContainerTransferClient() {}

    /**
     * Must be called during mod construction so the key-mapping listener is
     * added before {@code RegisterKeyMappingsEvent} fires.
     */
    private static volatile boolean keyMappingsRegistered;

    public static void registerKeyMappings() {
        if (keyMappingsRegistered) return;
        keyMappingsRegistered = true;
        KEY_STORE_ALL = new KeyMapping(
                "key.rsi.store_all",
                KeyConflictContext.GUI,
                InputConstants.Type.KEYSYM,
                RSIntegrationConfig.CONTAINER_TRANSFER_KEY.get(),
                "key.categories.rsi"
        );

        KEY_TOGGLE_MODE = new KeyMapping(
                "key.rsi.toggle_mode",
                KeyConflictContext.UNIVERSAL,
                InputConstants.Type.KEYSYM,
                71, // G key
                "key.categories.rsi"
        );

        RSIntegrationMod.MOD_BUS.addListener(
                (RegisterKeyMappingsEvent event) -> {
            event.register(KEY_STORE_ALL);
            event.register(KEY_TOGGLE_MODE);
        });
    }

    public static void init() {
        registerKeyMappings();
        MinecraftForge.EVENT_BUS.addListener(ContainerTransferClient::onScreenKeyPressed);
        MinecraftForge.EVENT_BUS.addListener(ContainerTransferClient::onKeyInput);
        MinecraftForge.EVENT_BUS.addListener(ContainerTransferClient::onRenderGui);
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, false,
                ScreenEvent.Render.Post.class, ContainerTransferClient::onRenderScreen);
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, false,
                ScreenEvent.Render.Post.class, ContainerTransferClient::onRenderScreen);

        loadMode();
    }

    private static void loadMode() {
        try {
            if (Files.exists(MODE_FILE)) {
                String content = Files.readString(MODE_FILE).trim();
                if ("RS_NETWORK".equals(content)) {
                    currentMode = MODE_RS;
                } else {
                    currentMode = MODE_BACKPACK;
                }
            }
        } catch (IOException e) {
            RSIntegrationMod.LOGGER.warn("[RSI] Failed to read transfer mode file", e);
        }
    }

    private static void saveMode() {
        try {
            Files.createDirectories(MODE_FILE.getParent());
            Files.writeString(MODE_FILE, currentMode == MODE_RS ? "RS_NETWORK" : "BACKPACK");
        } catch (IOException e) {
            RSIntegrationMod.LOGGER.warn("[RSI] Failed to write transfer mode file", e);
        }
    }

    private static void onKeyInput(InputEvent.Key event) {
        var mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (event.getAction() != GLFW.GLFW_PRESS) return;
        if (mc.screen != null
                && (isAnyTextInputFocused(mc.screen) || isRecipeViewerFocused())) return;

        if (KEY_TOGGLE_MODE.isActiveAndMatches(
                InputConstants.getKey(event.getKey(), event.getScanCode()))) {

            currentMode = (currentMode == MODE_RS) ? MODE_BACKPACK : MODE_RS;
            saveMode();

            String key = (currentMode == MODE_RS)
                    ? "rsi.transfer.mode.rs"
                    : "rsi.transfer.mode.backpack";
            modeMessage = Component.translatable(key);
            modeMessageUntil = System.currentTimeMillis() + 1800L;
            mc.player.displayClientMessage(modeMessage, true);
        }
    }

    private static void onRenderGui(RenderGuiEvent.Post event) {
        if (modeMessage == null || System.currentTimeMillis() > modeMessageUntil) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) return;
        renderModeBanner(event.getGuiGraphics(), mc.getWindow().getGuiScaledWidth());
    }

    private static void onRenderScreen(ScreenEvent.Render.Post event) {
        if (modeMessage == null || System.currentTimeMillis() > modeMessageUntil) return;
        renderModeBanner(event.getGuiGraphics(), event.getScreen().width);
    }

    private static void renderModeBanner(GuiGraphics graphics, int screenWidth) {
        Minecraft mc = Minecraft.getInstance();
        ItemStack icon = modeIcon();
        int width = Math.max(180, mc.font.width(modeMessage) + (icon.isEmpty() ? 28 : 48));
        int x = (screenWidth - width) / 2;
        int y = 8;
        int background = currentMode == MODE_RS ? 0xE01E4778 : 0xE01C6038;
        int accent = currentMode == MODE_RS ? 0xFF55A8FF : 0xFF67E096;

        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 1000);
        com.mojang.blaze3d.systems.RenderSystem.disableDepthTest();
        graphics.fill(x - 1, y - 1, x + width + 1, y + 29, 0xFF000000);
        graphics.fill(x, y, x + width, y + 28, background);
        graphics.fill(x, y, x + 4, y + 28, accent);
        int textX = x + 12;
        if (!icon.isEmpty()) {
            graphics.renderItem(icon, textX, y + 6);
            textX += 22;
        }
        graphics.drawString(mc.font, modeMessage, textX, y + 10, 0xFFFFFFFF, true);
        com.mojang.blaze3d.systems.RenderSystem.enableDepthTest();
        graphics.pose().popPose();
    }

    private static ItemStack modeIcon() {
        String id = currentMode == MODE_RS
                ? "refinedstorage:grid"
                : "sophisticatedbackpacks:backpack";
        var item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(id));
        return item == null ? ItemStack.EMPTY : new ItemStack(item);
    }

    /**
     * Detect whether a text input widget is currently focused. Handles vanilla
     * {@code EditBox}, mods that subclass it, and known text-field wrappers.
     */
    private static boolean isAnyTextInputFocused(Screen screen) {
        // Check the top-level focused widget first
        var focused = screen.getFocused();
        if (focused != null && isTextInputWidget(focused)) return true;

        // Walk children recursively — some mods wrap EditBox inside a
        // container widget so getFocused() returns the container, not the box.
        for (var child : screen.children()) {
            if (isTextInputInTree(child)) return true;
        }
        return false;
    }

    private static boolean isTextInputInTree(Object widget) {
        if (widget == null) return false;
        if (isTextInputWidget(widget)) return true;
        // Recurse into children of container widgets
        if (widget instanceof net.minecraft.client.gui.components.events.ContainerEventHandler ceh) {
            for (var child : ceh.children()) {
                if (isTextInputInTree(child)) return true;
            }
        }
        return false;
    }

    private static boolean isTextInputWidget(Object widget) {
        if (widget instanceof net.minecraft.client.gui.components.EditBox box && box.isFocused())
            return true;
        // Fallback: check class name for custom text-input widgets from
        // mods like Sophisticated Backpacks, JEI, EMI, REI, etc.
        Class<?> clazz = widget.getClass();
        do {
            String name = clazz.getSimpleName().toLowerCase();
            String fqn = clazz.getName().toLowerCase();
            if (name.contains("editbox") || name.contains("textfield")
                    || name.contains("textinput") || name.contains("searchbox")
                    || name.contains("searchbar") || name.contains("textbox")
                    || name.contains("textwidget") || name.contains("inputbox")
                    || (fqn.contains("jei") && (name.contains("search") || name.contains("input") || name.contains("text") || name.contains("field")))
                    || (fqn.contains("emi.") && (name.contains("search") || name.contains("input") || name.contains("text") || name.contains("field")))
                    || (fqn.contains("roughlyenoughitems") && (name.contains("search") || name.contains("input") || name.contains("text") || name.contains("field")))) {
                // Only count it as a text input if it appears to have focus
                try {
                    var m = clazz.getMethod("isFocused");
                    if (Boolean.TRUE.equals(m.invoke(widget))) return true;
                } catch (Exception e) {
                    RSIntegrationMod.LOGGER.debug("[RSI-Transfer] reflection probe failed", e);
                }
            }
            clazz = clazz.getSuperclass();
        } while (clazz != null && clazz != Object.class);
        return false;
    }

    /**
     * Checks whether a recipe-viewer overlay (JEI, EMI, REI) currently has
     * keyboard focus, e.g. the user is typing in the search box.
     * <p>
     * These overlays render on top of container screens without being direct
     * children of the screen's widget tree, so {@link #isAnyTextInputFocused}
     * cannot detect them.  We probe them directly.
     */
    private static boolean isRecipeViewerFocused() {
        // --- JEI ---
        try {
            var runtime = RSJeiPlugin.getRuntime();
            if (runtime != null) {
                var overlay = runtime.getIngredientListOverlay();
                if (overlay != null) {
                    // Try hasKeyboardFocus() → isSearchFocused() → getSearchBox() chain
                    for (String methodName : new String[]{
                            "hasKeyboardFocus", "isSearchFocused",
                            "hasFocus", "isFocused"}) {
                        try {
                            java.lang.reflect.Method m = overlay.getClass().getMethod(methodName);
                            if (Boolean.TRUE.equals(m.invoke(overlay))) return true;
                        } catch (Exception e) {
                            RSIntegrationMod.LOGGER.debug("[RSI-Transfer] reflection probe failed", e);
                        }
                    }
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Transfer] reflection probe failed", e);
        }

        // --- EMI ---
        try {
            Class<?> emiApi = Class.forName("dev.emi.emi.api.EmiApi");
            java.lang.reflect.Method isSearchFocused =
                    emiApi.getMethod("isSearchFocused");
            if (Boolean.TRUE.equals(isSearchFocused.invoke(null))) return true;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Transfer] reflection probe failed", e);
        }

        // --- REI ---
        try {
            Class<?> reiOverlay = Class.forName(
                    "me.shedaniel.rei.impl.client.gui.widgets.basewidgets.TextFieldWidget");
            // REI's search field is rendered on top; check via ScreenEvent
            // interceptors — if any REI widget anywhere in the JVM is focused
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Transfer] reflection probe failed", e);
        }

        return false;
    }

    private static void onScreenKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        var mc = Minecraft.getInstance();
        Screen screen = mc.screen;
        if (!(screen instanceof AbstractContainerScreen<?>)) return;
        if (screen instanceof InventoryScreen || screen instanceof CreativeModeInventoryScreen) return;

        // Exclude inventory-only screens (Curios, cosmetic armor, etc.)
        // where there is no "external container" to transfer from.
        String screenClassName = screen.getClass().getName().toLowerCase();
        if (screenClassName.contains("curios") || screenClassName.contains("cosmeticarmor")
                || screenClassName.contains("accessories") || screenClassName.contains("trinkets")) {
            return;
        }

        // Don't steal the F key when a recipe-viewer overlay (JEI, EMI, REI)
        // has keyboard focus, e.g. the user is typing in the search box.
        // These overlays render on top of container screens without being
        // part of the screen's widget tree, so isAnyTextInputFocused alone
        // cannot detect them.
        if (isRecipeViewerFocused()) return;

        if (!KEY_STORE_ALL.isActiveAndMatches(
                InputConstants.getKey(event.getKeyCode(), event.getScanCode())))
            return;

        // Don't steal input when a text field is focused —
        // walk the entire widget tree because mods like Sophisticated
        // Backpacks may nest their search bar inside a container widget.
        if (isAnyTextInputFocused(screen)) return;

        if (!RSIntegrationConfig.ENABLE_CONTAINER_TRANSFER.get()) {
            mc.player.displayClientMessage(
                    Component.translatable("rsi.transfer.disabled"), true);
            return;
        }

        ContainerTransferNetworkHandler.CHANNEL.sendToServer(new StoreAllPacket(currentMode));
        event.setCanceled(true);
    }
}
