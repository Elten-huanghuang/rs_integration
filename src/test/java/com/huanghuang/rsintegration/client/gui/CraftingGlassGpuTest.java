package com.huanghuang.rsintegration.client.gui;

import com.huanghuang.rsintegration.config.CraftingUiTheme;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 单独启用的真实显卡回归；创建隐藏测试窗口，不接触玩家正在运行的游戏。 */
@EnabledIfEnvironmentVariable(named = "RSI_GL_SMOKE", matches = "true")
class CraftingGlassGpuTest extends BootstrapTest {
    @Test
    void blurLeavesDepthClearAndRealGuiBatchStillAppears() throws Exception {
        System.setProperty("org.lwjgl.librarypath", Path.of("build/gl-smoke-natives").toAbsolutePath().toString());
        assertTrue(GLFW.glfwInit(), "GLFW 必须能创建测试上下文");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        long window = GLFW.glfwCreateWindow(96, 96, "RSI glass regression", 0, 0);
        assertNotEquals(0, window);
        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();
        RenderSystem.initRenderThread();
        RenderSystem.initRenderer(0, false);
        Path jar = Path.of(System.getProperty("user.home"), ".gradle/caches/forge_gradle/minecraft_repo/versions/1.20.1/client.jar");
        try (ZipFile assets = new ZipFile(jar.toFile()); var games = mockStatic(Minecraft.class);
             var renderers = mockStatic(GameRenderer.class)) {
            ResourceManager resources = mock(ResourceManager.class, CALLS_REAL_METHODS);
            PackResources pack = mock(PackResources.class);
            when(pack.packId()).thenReturn("GPU 测试资源");
            when(resources.getResource(any(ResourceLocation.class))).thenAnswer(call -> {
                ResourceLocation id = call.getArgument(0);
                String name = "assets/" + id.getNamespace() + "/" + id.getPath();
                Path local = Path.of("src/main/resources", name);
                if (Files.exists(local)) return Optional.of(new Resource(pack, () -> Files.newInputStream(local)));
                var entry = assets.getEntry(name);
                return entry == null ? Optional.empty() : Optional.of(new Resource(pack, () -> assets.getInputStream(entry)));
            });
            Minecraft game = mock(Minecraft.class);
            Window screen = mock(Window.class);
            when(screen.getWidth()).thenReturn(96); when(screen.getHeight()).thenReturn(96);
            when(game.getWindow()).thenReturn(screen);
            when(game.getResourceManager()).thenReturn(resources);
            games.when(Minecraft::getInstance).thenReturn(game);
            TextureTarget main = new TextureTarget(96, 96, true, false);
            when(game.getMainRenderTarget()).thenReturn(main);
            try (ShaderInstance shader = new ShaderInstance(resources, "rendertype_gui", DefaultVertexFormat.POSITION_COLOR)) {
                renderers.when(GameRenderer::getRendertypeGuiShader).thenReturn(shader);
                main.setClearColor(0.12f, 0.23f, 0.34f, 1);
                main.clear(false); main.bindWrite(true);
                RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0, 96, 96, 0, 1000, 21000), VertexSorting.ORTHOGRAPHIC_Z);
                RenderSystem.getModelViewStack().setIdentity();
                RenderSystem.getModelViewStack().translate(0, 0, -11000);
                RenderSystem.applyModelViewMatrix();
                RenderSystem.setShaderColor(1, 1, 1, 1);
                GuiGraphics graphics = new GuiGraphics(game, MultiBufferSource.immediate(new BufferBuilder(256)));
                graphics.fill(0, 0, 48, 96, 0xFFEE3344);
                graphics.fill(48, 0, 96, 96, 0xFF2233EE);
                graphics.flush();
                CraftingGlassBackground.render(graphics, CraftingUiTheme.FROSTED_GLASS, 0);
                var blurred = BufferUtils.createByteBuffer(4);
                GL11.glReadPixels(47, 48, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, blurred);
                assertTrue((blurred.get(0) & 255) < 220 && (blurred.get(2) & 255) > 85,
                        "实际着色器必须混合背景边界，不能因加载失败悄悄跳过模糊");
                var depth = BufferUtils.createFloatBuffer(1);
                GL11.glReadPixels(48, 48, 1, 1, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, depth);
                assertEquals(1f, depth.get(0), 0.0001f, "后处理深度必须清空，不能遮挡 GUI");
                graphics.fill(20, 20, 76, 76, 0xFF35B880);
                graphics.flush();
                var pixel = BufferUtils.createByteBuffer(4);
                GL11.glReadPixels(48, 48, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixel);
                assertEquals(0x35, pixel.get(0) & 255, 1);
                assertEquals(0xB8, pixel.get(1) & 255, 1);
                assertEquals(0x80, pixel.get(2) & 255, 1);
                assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
            } finally {
                CraftingGlassBackground.closed(null);
                main.destroyBuffers();
            }
        } finally {
            GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
        }
    }
}
