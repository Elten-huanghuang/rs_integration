package com.huanghuang.rsintegration.client.storage;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.RenderTooltipEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GuixuTooltipArtTest {
    @Test
    void unifiedDiskUsesItsOwnArtistFrame() {
        assertEquals(DiskTooltipBackground.Artwork.GUIXU, DiskTooltipBackground.themeFor(
                new ResourceLocation("rs_integration", "unified_storage_disk")).artwork());
        assertFalse(GuixuTooltipArt.supportsFrame(120, 140));
        assertFalse(GuixuTooltipArt.supportsFrame(300, 24));
        assertTrue(GuixuTooltipArt.supportsFrame(220, 117));
    }

    @Test
    void asymmetricOrnamentsAndArrowFooterFitAtAllFourScreenCorners() {
        for (int mouseX : new int[]{0, 426}) {
            for (int mouseY : new int[]{0, 239}) {
                var event = mock(RenderTooltipEvent.Pre.class);
                var component = mock(ClientTooltipComponent.class);
                when(component.getWidth(any(Font.class))).thenReturn(300);
                when(component.getHeight()).thenReturn(102);
                when(event.getFont()).thenReturn(mock(Font.class));
                when(event.getComponents()).thenReturn(List.of(component));
                when(event.getTooltipPositioner()).thenReturn(DefaultTooltipPositioner.INSTANCE);
                when(event.getScreenWidth()).thenReturn(427);
                when(event.getScreenHeight()).thenReturn(240);
                when(event.getX()).thenReturn(mouseX);
                when(event.getY()).thenReturn(mouseY);
                GuixuTooltipArt.reserveFrameSpace(event);
                var x = ArgumentCaptor.forClass(Integer.class);
                var y = ArgumentCaptor.forClass(Integer.class);
                verify(event).setX(x.capture());
                verify(event).setY(y.capture());
                var position = DefaultTooltipPositioner.INSTANCE.positionTooltip(427, 240,
                        x.getValue(), y.getValue(), 300, 100);
                assertTrue(position.x() - 3 - GuixuTooltipArt.SIDE_SPACE - 20 >= 2);
                assertTrue(position.x() + 300 + 3 + GuixuTooltipArt.SIDE_SPACE + 7 <= 425);
                assertTrue(position.y() - 3 - GuixuTooltipArt.HEADER_SPACE - 11 >= 2);
                assertTrue(position.y() + 100 + 3 + GuixuTooltipArt.BOTTOM_SPACE + 10 <= 238);
            }
        }
    }
}
