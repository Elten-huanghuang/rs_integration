package com.huanghuang.rsintegration.client.gui;

import com.google.gson.JsonParser;
import com.huanghuang.rsintegration.config.CraftingUiTheme;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 此处验证后处理调用与生命周期；真实 GPU 模糊画面仍需游戏内验收。 */
class CraftingGlassBackgroundTest extends BootstrapTest {
    @Test
    void privateBlurChainResizesRestoresTargetAndReleasesWhenSwitchingAway() {
        Minecraft game = mock(Minecraft.class);
        RenderTarget main = mock(RenderTarget.class);
        main.width = 800; main.height = 600;
        when(game.getMainRenderTarget()).thenReturn(main);
        GuiGraphics graphics = mock(GuiGraphics.class);
        try (var games = mockStatic(Minecraft.class); var render = mockStatic(RenderSystem.class);
             var chains = mockConstruction(PostChain.class)) {
            games.when(Minecraft::getInstance).thenReturn(game);
            render.when(RenderSystem::isOnRenderThread).thenReturn(true);
            render.when(RenderSystem::getProjectionMatrix).thenReturn(new Matrix4f());
            reload();
            CraftingGlassBackground.render(graphics, CraftingUiTheme.FROSTED_GLASS, 0.1f);
            CraftingGlassBackground.render(graphics, CraftingUiTheme.FROSTED_GLASS, 0.2f);
            assertEquals(1, chains.constructed().size());
            PostChain chain = chains.constructed().get(0);
            verify(chain, times(1)).resize(800, 600);
            verify(chain).process(0.1f); verify(chain).process(0.2f);
            main.width = 1280; main.height = 720;
            CraftingGlassBackground.render(graphics, CraftingUiTheme.FROSTED_GLASS, 0.3f);
            verify(chain).resize(1280, 720);
            verify(main, times(3)).bindWrite(true);
            render.verify(() -> RenderSystem.setShaderColor(1, 1, 1, 1), times(3));
            render.verify(() -> RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX), times(3));
            render.verify(() -> RenderSystem.depthFunc(GL11.GL_LEQUAL), times(3));
            verify(graphics, times(3)).flush();
            CraftingGlassBackground.render(graphics, CraftingUiTheme.BLACK_GLASS, 0);
            verify(chain).close();
            assertEquals(1, chains.constructed().size());
            CraftingGlassBackground.closed(null);
        }
    }

    @Test
    void failureRestoresFramebufferAndDoesNotRetryUntilResourceReload() {
        Minecraft game = mock(Minecraft.class);
        RenderTarget main = mock(RenderTarget.class);
        main.width = 800; main.height = 600;
        when(game.getMainRenderTarget()).thenReturn(main);
        try (var games = mockStatic(Minecraft.class); var render = mockStatic(RenderSystem.class);
             var chains = mockConstruction(PostChain.class, (chain, context) ->
                     doThrow(new IllegalStateException("模拟着色器失败")).when(chain).process(anyFloat()))) {
            games.when(Minecraft::getInstance).thenReturn(game);
            render.when(RenderSystem::isOnRenderThread).thenReturn(true);
            render.when(RenderSystem::getProjectionMatrix).thenReturn(new Matrix4f());
            reload();
            GuiGraphics graphics = mock(GuiGraphics.class);
            CraftingGlassBackground.render(graphics, CraftingUiTheme.FROSTED_GLASS, 0);
            CraftingGlassBackground.render(graphics, CraftingUiTheme.FROSTED_GLASS, 0);
            assertEquals(1, chains.constructed().size());
            verify(chains.constructed().get(0)).close();
            verify(main).bindWrite(true);
            reload();
            CraftingGlassBackground.render(graphics, CraftingUiTheme.FROSTED_GLASS, 0);
            assertEquals(2, chains.constructed().size());
            reload();
        }
    }

    @Test
    void frostUsesHorizontalAndVerticalPassesWithSeparateTemporaryTarget() throws Exception {
        var json = JsonParser.parseString(Files.readString(Path.of(
                "src/main/resources/assets/rs_integration/shaders/post/crafting_frost.json"))).getAsJsonObject();
        var passes = json.getAsJsonArray("passes");
        assertEquals(2, passes.size());
        assertEquals("swap", passes.get(0).getAsJsonObject().get("outtarget").getAsString());
        assertEquals("swap", passes.get(1).getAsJsonObject().get("intarget").getAsString());
        assertEquals("minecraft:main", passes.get(1).getAsJsonObject().get("outtarget").getAsString());
        assertEquals("[1.0,0.0]", passes.get(0).getAsJsonObject().getAsJsonArray("uniforms")
                .get(0).getAsJsonObject().get("values").toString());
        assertEquals("[0.0,1.0]", passes.get(1).getAsJsonObject().getAsJsonArray("uniforms")
                .get(0).getAsJsonObject().get("values").toString());
    }

    private static void reload() {
        RegisterClientReloadListenersEvent event = mock(RegisterClientReloadListenersEvent.class);
        doAnswer(call -> {
            ((ResourceManagerReloadListener) call.getArgument(0)).onResourceManagerReload(null);
            return null;
        }).when(event).registerReloadListener(any());
        CraftingGlassBackground.Reload.register(event);
    }
}
