package com.huanghuang.rsintegration.client.gui;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.CraftingUiTheme;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

/** 自有后处理链：在控件绘制前模糊场景，不替换 GameRenderer 的全局特效。 */
@Mod.EventBusSubscriber(modid = RSIntegrationMod.MOD_ID, value = Dist.CLIENT)
public final class CraftingGlassBackground {
    private static final ResourceLocation EFFECT = new ResourceLocation(RSIntegrationMod.MOD_ID, "shaders/post/crafting_frost.json");
    private static PostChain blur;
    private static RenderTarget target;
    private static int width, height;
    private static boolean failed;

    private CraftingGlassBackground() {}

    public static void render(GuiGraphics graphics, CraftingUiTheme theme, float partialTick) {
        if (theme != CraftingUiTheme.FROSTED_GLASS) { close(); return; }
        Minecraft game = Minecraft.getInstance();
        RenderTarget main = game == null ? null : game.getMainRenderTarget();
        if (main == null || failed || !RenderSystem.isOnRenderThread()) return;
        graphics.flush();
        Matrix4f projection = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting sorting = RenderSystem.getVertexSorting();
        try {
            if (target != main) {
                close();
                blur = new PostChain(game.getTextureManager(), game.getResourceManager(), main, EFFECT);
                target = main;
                width = height = 0;
            }
            if (width != main.width || height != main.height) {
                blur.resize(main.width, main.height);
                width = main.width; height = main.height;
            }
            blur.process(partialTick);
        } catch (Exception failure) {
            close(); failed = true;
            LogUtils.getLogger().warn("[RSI] 磨砂背景模糊不可用，暂时保留透明玻璃；重载资源后重试", failure);
        } finally {
            main.bindWrite(true);
            RenderSystem.setProjectionMatrix(projection, sorting);
            // 后处理全屏四边形会写入主目标的深度；GUI 的后续批次会重新开启深度测试。
            // 必须清掉这层深度，否则面板、字体和物品会整层被挡住，只剩模糊场景。
            RenderSystem.depthMask(true);
            RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.enableDepthTest();
            RenderSystem.setShaderColor(1, 1, 1, 1);
        }
    }

    private static void close() {
        if (blur != null) blur.close();
        blur = null; target = null;
    }

    @SubscribeEvent public static void closed(ScreenEvent.Closing event) { close(); }

    @Mod.EventBusSubscriber(modid = RSIntegrationMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Reload {
        private Reload() {}
        @SubscribeEvent public static void register(RegisterClientReloadListenersEvent event) {
            event.registerReloadListener((ResourceManagerReloadListener) manager -> {
                Runnable release = () -> { close(); failed = false; };
                if (RenderSystem.isOnRenderThread()) release.run();
                else RenderSystem.recordRenderCall(release::run);
            });
        }
    }
}
