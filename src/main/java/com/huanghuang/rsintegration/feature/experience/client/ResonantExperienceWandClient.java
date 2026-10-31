package com.huanghuang.rsintegration.feature.experience.client;

import com.huanghuang.rsintegration.feature.experience.ExperienceWandNetwork.StatePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;

import java.util.function.Consumer;

public final class ResonantExperienceWandClient {
    private ResonantExperienceWandClient() {}

    public static void accept(StatePacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) return;
        if (packet.open()) minecraft.setScreen(new ExperienceWandScreen(packet));
        else if (minecraft.screen instanceof ExperienceWandScreen screen) screen.accept(packet);
    }

    public static void initialize(Consumer<IClientItemExtensions> consumer) {
        consumer.accept(new IClientItemExtensions() {
            private ResonantExperienceWandRenderer renderer;

            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                // 延迟到首次渲染，避免物品注册阶段访问尚未初始化的 Minecraft。
                if (renderer == null) {
                    renderer = new ResonantExperienceWandRenderer();
                }
                return renderer;
            }
        });
    }
}
