package com.huanghuang.rsintegration.client.storage;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class FluidDiskTooltipArtTest {
    private static final ResourceLocation WATER = new ResourceLocation(
            "rs_integration", "textures/gui/disk_tooltip/water_disk_story.png");

    @ParameterizedTest
    @CsvSource({"infinity_water_disk, water_disk_story", "infinity_lava_disk, lava_disk_story"})
    void selectsTheMatchingBundledTexture(String item, String image) throws Exception {
        ResourceLocation itemId = new ResourceLocation("rs_integration", item);
        var theme = DiskTooltipBackground.themeFor(itemId);
        assertNotNull(theme);
        assertEquals(DiskTooltipBackground.Artwork.FLUID_STORY, theme.artwork());
        assertEquals("textures/gui/disk_tooltip/" + image + ".png", theme.background().getPath());
        String path = "/assets/" + theme.background().getNamespace() + "/" + theme.background().getPath();
        try (var stream = getClass().getResourceAsStream(path)) {
            assertNotNull(stream, path);
            var texture = ImageIO.read(stream);
            assertNotNull(texture, path);
            assertEquals(FluidDiskTooltipArt.TEXTURE_WIDTH, texture.getWidth());
            assertEquals(FluidDiskTooltipArt.TEXTURE_HEIGHT, texture.getHeight());
        }
    }

    @Test
    void respectsTheCustomBackgroundSwitch() {
        var spec = RSIntegrationConfig.CLIENT_SPEC;
        var config = CommentedConfig.inMemory();
        spec.correct(config);
        spec.setConfig(config);
        try {
            RSIntegrationConfig.TOOLTIP_CUSTOM_BACKGROUNDS.set(false);
            assertNull(DiskTooltipBackground.themeFor(new ResourceLocation("rs_integration", "infinity_water_disk")));
            assertNull(DiskTooltipBackground.themeFor(new ResourceLocation("rs_integration", "infinity_lava_disk")));
        } finally {
            spec.setConfig(null);
        }
    }

    @Test
    void compactTooltipExtendsTheSkyAndKeepsTheImageOnTheRight() {
        GuiGraphics graphics = mock(GuiGraphics.class);
        FluidDiskTooltipArt.draw(graphics, WATER, 4, 6, 200, 36);

        verify(graphics).blit(WATER, 4, 6, 136, 36, 0.0F, 0.0F, 1, 216, 384, 216);
        verify(graphics).blit(WATER, 140, 6, 64, 36, 0.0F, 0.0F, 384, 216, 384, 216);
    }

    @Test
    void expandedTooltipCropsFromTheLeftToKeepTheIslandInView() {
        GuiGraphics graphics = mock(GuiGraphics.class);
        FluidDiskTooltipArt.draw(graphics, WATER, 4, 6, 200, 144);

        verify(graphics).blit(WATER, 4, 6, 200, 144, 84.0F, 0.0F, 300, 216, 384, 216);
    }
}
