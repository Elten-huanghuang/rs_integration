package com.huanghuang.rsintegration.client;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.update.ModrinthUpdateChecker;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.ModList;

/** 客户端一次性后台检查，进入世界后最多提示一次。 */
public final class UpdateCheckClient {
    private static boolean started;
    private static boolean notified;
    private static volatile ModrinthUpdateChecker.Update pending;

    private UpdateCheckClient() {}

    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !RSIntegrationConfig.CLIENT_SPEC.isLoaded()
                || !RSIntegrationConfig.ENABLE_UPDATE_CHECK.get()) return;
        if (!started) {
            started = true;
            String current = ModList.get().getModContainerById(RSIntegrationMod.MOD_ID)
                    .orElseThrow().getModInfo().getVersion().toString();
            String minecraft = SharedConstants.getCurrentVersion().getName();
            Thread checker = new Thread(() -> {
                try {
                    pending = ModrinthUpdateChecker.check(current, minecraft);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (Exception failure) {
                    RSIntegrationMod.debug("[RSI-Update] Modrinth 更新检测失败：{}", failure.toString());
                }
            }, "RSI Modrinth Update Check");
            checker.setDaemon(true);
            checker.start();
        }
        Minecraft minecraft = Minecraft.getInstance();
        ModrinthUpdateChecker.Update update = pending;
        if (!notified && update != null && minecraft.player != null) {
            notified = true;
            Component download = Component.translatable("rsi.update.download").withStyle(style -> style
                    .withColor(ChatFormatting.GREEN).withUnderlined(true)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, update.downloadUrl()))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                            Component.translatable("rsi.update.download_hover"))));
            minecraft.player.sendSystemMessage(Component.translatable("rsi.update.available", update.version())
                    .withStyle(ChatFormatting.YELLOW).append(" ").append(download));
        }
    }
}
