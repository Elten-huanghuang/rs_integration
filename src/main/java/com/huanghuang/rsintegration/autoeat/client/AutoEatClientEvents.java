package com.huanghuang.rsintegration.autoeat.client;

import com.huanghuang.rsintegration.autoeat.AutoEatMode;
import com.huanghuang.rsintegration.autoeat.network.AutoEatPacket;
import com.huanghuang.rsintegration.autoeat.network.RequestBlacklistPacket;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.refinedmods.refinedstorage.api.network.grid.INetworkAwareGrid;
import com.refinedmods.refinedstorage.api.network.grid.GridType;
import com.refinedmods.refinedstorage.screen.grid.GridScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

@OnlyIn(Dist.CLIENT)
public final class AutoEatClientEvents {

    private AutoEatClientEvents() {}

    private static boolean blacklistRequested;

    @SubscribeEvent
    public static void onClientDisconnect(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientState.reset();
        blacklistRequested = false;
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof GridScreen screen)) return;
        if (!RSIntegrationConfig.ENABLE_AUTO_EAT.get()) return;
        if (screen.getMenu().getGrid().getGridType() != GridType.CRAFTING) return;
        if (!(screen.getMenu().getGrid() instanceof INetworkAwareGrid)) return;

        // Guard: ScreenEvent.Init.Post can fire multiple times (data sync,
        // double registration, etc.).  Scan existing listeners — if our
        // AutoEatButton is already on screen, skip to avoid stacking duplicates.
        for (Object listener : event.getListenersList()) {
            if (listener instanceof AutoEatButton) {
                return;
            }
        }

        int btnW = 64;
        int btnH = 20;
        int btnGap = 4;
        int x = screen.getGuiLeft() - btnW - 4;
        int yBase = screen.getGuiTop() + screen.getYSize() - (btnH * 3 + btnGap * 2) - 2;

        // Button 1 — Eat (one-shot)
        AutoEatButton eatBtn = new AutoEatButton(x, yBase, btnW, btnH,
                Component.translatable("rsi.autoeat.btn.eat"),
                Tooltip.create(Component.translatable("rsi.autoeat.btn.eat.tooltip")),
                btn -> NetworkHandler.CHANNEL.sendToServer(
                        new AutoEatPacket(ClientState.currentMode, ClientState.selectedItem))
        );
        event.addListener(eatBtn);

        // Button 2 — Select / Blacklist
        AutoEatButton selectBtn = new AutoEatButton(x, yBase + btnH + btnGap, btnW, btnH,
                getSelectLabel(),
                Tooltip.create(getSelectTooltip()),
                btn -> openSelectScreen()
        );
        event.addListener(selectBtn);

        // Button 3 — Mode switch
        AutoEatButton modeBtn = new AutoEatButton(x, yBase + (btnH + btnGap) * 2, btnW, btnH,
                ClientState.currentMode.displayName(),
                Tooltip.create(Component.translatable("rsi.autoeat.btn.mode.tooltip")),
                btn -> {
                    ClientState.cycleMode();
                    btn.setMessage(ClientState.currentMode.displayName());
                    selectBtn.setMessage(getSelectLabel());
                    selectBtn.setTooltip(Tooltip.create(getSelectTooltip()));
                }
        );
        event.addListener(modeBtn);

        // Sync blacklist from server (once per session)
        if (!blacklistRequested) {
            blacklistRequested = true;
            NetworkHandler.CHANNEL.sendToServer(new RequestBlacklistPacket());
        }
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
    private static class AutoEatButton extends Button {
        AutoEatButton(int x, int y, int w, int h, Component text, Tooltip tooltip, OnPress onPress) {
            super(x, y, w, h, text, onPress, DEFAULT_NARRATION);
            setTooltip(tooltip);
        }
    }
}
