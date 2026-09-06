package com.huanghuang.rsintegration.mods.jei.client;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.ClientSyncedConfig;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.mods.jei.JeiCheatDropPacket;
import com.huanghuang.rsintegration.network.RSJeiPlugin;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.huanghuang.rsintegration.sidepanel.client.RSIKeyBindings;
import com.mojang.blaze3d.platform.InputConstants;
import mezz.jei.api.runtime.IJeiRuntime;
import mezz.jei.common.Internal;
import mezz.jei.common.config.GiveMode;
import mezz.jei.common.network.packets.PacketGiveItemStack;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Optional;

/** Extra cheat gestures adapted from the supplied JEI shortcut mod. */
public final class JeiCheatShortcuts {
    private static boolean registered;
    private static boolean compatibilityWarningLogged;

    private JeiCheatShortcuts() {}

    public static void register() {
        if (registered) return;
        compatibilityWarningLogged = false;
        MinecraftForge.EVENT_BUS.register(JeiCheatShortcuts.class);
        registered = true;
    }

    public static void unregister() {
        if (!registered) return;
        MinecraftForge.EVENT_BUS.unregister(JeiCheatShortcuts.class);
        registered = false;
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (handleInput(event.getScreen(), InputConstants.Type.MOUSE.getOrCreate(event.getButton()))) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        if (handleInput(event.getScreen(), InputConstants.getKey(event.getKeyCode(), event.getScanCode()))) {
            event.setCanceled(true);
        }
    }

    private static boolean handleInput(Screen screen, InputConstants.Key input) {
        // Resolve one action before sending anything, even if bindings overlap.
        boolean giveOne = matches(RSIKeyBindings.KEY_JEI_GIVE_ONE, input);
        boolean giveStack = matches(RSIKeyBindings.KEY_JEI_GIVE_STACK, input);
        boolean drop = matches(RSIKeyBindings.KEY_JEI_DROP, input);
        if (!giveOne && !giveStack && !drop) return false;
        IJeiRuntime runtime = activeRuntime(screen);
        if (runtime == null) return false;
        Optional<ItemStack> ingredient = itemUnderMouse(runtime);
        if (ingredient.isEmpty()) return false;
        ItemStack stack = ingredient.get();
        if (giveOne || giveStack) return giveItem(stack, giveOne ? 1 : stack.getMaxStackSize());
        return dropItem(stack);
    }

    private static boolean matches(KeyMapping mapping, InputConstants.Key input) {
        return mapping != null && !mapping.isUnbound() && mapping.isActiveAndMatches(input);
    }

    private static boolean giveItem(ItemStack stack, int count) {
        if (count <= 0) return false;
        try {
            var connection = Internal.getServerConnection();
            if (connection == null || !connection.isJeiOnServer()) return false;
            connection.sendPacketToServer(new PacketGiveItemStack(
                    stack.copyWithCount(count), GiveMode.INVENTORY));
            return true;
        } catch (RuntimeException | LinkageError failure) {
            warnCompatibility(failure);
            return false;
        }
    }

    private static boolean dropItem(ItemStack stack) {
        var connection = Minecraft.getInstance().getConnection();
        if (connection == null || !NetworkHandler.CHANNEL.isRemotePresent(connection.getConnection())) return false;
        int count = stack.getMaxStackSize();
        if (count <= 0) return false;
        NetworkHandler.CHANNEL.sendToServer(new JeiCheatDropPacket(stack.copyWithCount(count)));
        return true;
    }

    private static IJeiRuntime activeRuntime(Screen screen) {
        if (Minecraft.getInstance().player == null || hasTextFocus(screen)) return null;
        boolean enabled = ClientSyncedConfig.isSynced()
                ? ClientSyncedConfig.ENABLE_JEI : RSIntegrationConfig.ENABLE_JEI.get();
        if (!enabled) return null;
        IJeiRuntime runtime = RSJeiPlugin.getRuntime();
        if (runtime == null || runtime.getIngredientListOverlay().hasKeyboardFocus()) return null;
        try {
            var state = Internal.getClientToggleState();
            return state.isOverlayEnabled() && state.isCheatItemsEnabled() ? runtime : null;
        } catch (RuntimeException | LinkageError failure) {
            warnCompatibility(failure);
            return null;
        }
    }

    private static Optional<ItemStack> itemUnderMouse(IJeiRuntime runtime) {
        var overlay = runtime.getIngredientListOverlay();
        if (overlay.isListDisplayed()) {
            var stack = overlay.getIngredientUnderMouse().flatMap(ingredient -> ingredient.getItemStack())
                    .filter(item -> !item.isEmpty());
            if (stack.isPresent()) return stack;
        }
        return runtime.getBookmarkOverlay().getIngredientUnderMouse()
                .flatMap(ingredient -> ingredient.getItemStack()).filter(item -> !item.isEmpty());
    }

    private static boolean hasTextFocus(Screen screen) {
        GuiEventListener focused = screen.getFocused();
        while (focused != null) {
            if (focused instanceof EditBox) return true;
            for (Class<?> type = focused.getClass(); type != null; type = type.getSuperclass()) {
                if (type.getSimpleName().endsWith("EditBox")
                        || type.getSimpleName().endsWith("TextField")) return true;
            }
            GuiEventListener next = focused instanceof ContainerEventHandler container
                    ? container.getFocused() : null;
            if (next == focused) break;
            focused = next;
        }
        return false;
    }

    private static void warnCompatibility(Throwable failure) {
        if (compatibilityWarningLogged) return;
        compatibilityWarningLogged = true;
        RSIntegrationMod.LOGGER.warn("[RSI-JEI] Cheat shortcut unavailable for this JEI runtime", failure);
    }
}
