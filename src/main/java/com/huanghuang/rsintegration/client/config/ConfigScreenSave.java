package com.huanghuang.rsintegration.client.config;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.ConfigEditPolicy;
import com.huanghuang.rsintegration.config.ConfigEditorModel;
import com.huanghuang.rsintegration.config.ConfigEditorModel.ConfigFile;
import com.huanghuang.rsintegration.config.ConfigEditorModel.Option;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.config.ConfigTracker;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

final class ConfigScreenSave {
    private ConfigScreenSave() {}

    static boolean isEditable(ConfigFile file) {
        Minecraft minecraft = Minecraft.getInstance();
        boolean remoteServer = minecraft.getConnection() != null && !minecraft.hasSingleplayerServer();
        return ConfigEditPolicy.canEdit(file.id(), file.spec().isLoaded(), remoteServer, minecraft.hasSingleplayerServer());
    }

    static void save(ConfigEditorModel model, Consumer<Throwable> completed) {
        Minecraft minecraft = Minecraft.getInstance();
        for (Option entry : model.changes()) {
            if (!isEditable(entry.file())) {
                completed.accept(new IllegalStateException("Config is read-only: " + entry.file().id()));
                return;
            }
        }
        MinecraftServer server = minecraft.getSingleplayerServer();
        Runnable action = () -> model.save(ConfigScreenSave::saveFile);
        // 世界配置和对应的规划缓存必须在集成服务端线程上更新。
        CompletableFuture.runAsync(action, server == null ? minecraft : server)
                .whenComplete((result, failure) -> minecraft.execute(() -> completed.accept(failure)));
    }

    private static void saveFile(ConfigFile file) {
        ModConfig config = ConfigTracker.INSTANCE.fileMap().get(file.fileName());
        if (config == null || config.getSpec() != file.spec() || !config.getModId().equals(RSIntegrationMod.MOD_ID)) {
            throw new IllegalStateException("Missing registered config: " + file.fileName());
        }
        config.save();
        file.spec().afterReload();
        ModList.get().getModContainerById(RSIntegrationMod.MOD_ID).orElseThrow()
                .dispatchConfigEvent(new ModConfigEvent.Reloading(config));
    }
}
