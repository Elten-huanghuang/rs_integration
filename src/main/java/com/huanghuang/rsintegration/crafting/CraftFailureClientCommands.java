package com.huanghuang.rsintegration.crafting;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.UUID;

@OnlyIn(Dist.CLIENT)
public final class CraftFailureClientCommands {
    private static final CraftFailureLinks LINKS = new CraftFailureLinks();

    private CraftFailureClientCommands() {}

    @SubscribeEvent
    public static void register(RegisterClientCommandsEvent event) {
        CraftFailureLinks.register(event.getDispatcher(), text -> {
            if (!LINKS.request(text, CraftProgressTracker.sessionGeneration(), CraftProgressTracker::failure)) expired();
        });
    }

    public static void notifyFailure(UUID id) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) minecraft.gui.getChat().addMessage(CraftFailureLinks.message(id));
    }

    public static boolean handleClick(Style style) {
        String command = CraftFailureLinks.localCommand(style);
        if (command == null) return false;
        if (Minecraft.getInstance().player == null || !ClientCommandHandler.runCommand(command)) expired();
        return true;
    }

    private static void expired() {
        Minecraft.getInstance().gui.getChat().addMessage(Component.translatable("rsi.diagnostic.link_expired"));
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) { LINKS.clear(); return; }
        LINKS.tick(CraftProgressTracker.sessionGeneration(), CraftProgressTracker::failure,
                entry -> minecraft.setScreen(new CraftFailureScreen(
                        minecraft.screen instanceof ChatScreen ? null : minecraft.screen, entry)),
                CraftFailureClientCommands::expired);
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) { LINKS.clear(); }
}
