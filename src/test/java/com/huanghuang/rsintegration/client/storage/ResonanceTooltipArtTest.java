package com.huanghuang.rsintegration.client.storage;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.RenderTooltipEvent;
import org.joml.Vector2i;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResonanceTooltipArtTest {
    @Test
    void onlyTheStorageDiskUsesTheNewArtwork() {
        assertEquals(DiskTooltipBackground.Artwork.RESONANCE, DiskTooltipBackground.themeFor(
                new ResourceLocation("rs_integration", "resonance_storage_disk")).artwork());
        assertEquals(DiskTooltipBackground.Artwork.NEBULA, DiskTooltipBackground.themeFor(
                new ResourceLocation("rs_integration", "resonance_network_generator")).artwork());
    }

    @Test
    void frameAndPaddingFitAtAllFourScreenCorners() {
        for (int mouseX : new int[]{0, 426}) {
            for (int mouseY : new int[]{0, 239}) {
                var event = event(DefaultTooltipPositioner.INSTANCE, mouseX, mouseY);
                ResonanceTooltipArt.reserveFrameSpace(event);
                var x = ArgumentCaptor.forClass(Integer.class);
                var y = ArgumentCaptor.forClass(Integer.class);
                verify(event).setX(x.capture());
                verify(event).setY(y.capture());
                var position = DefaultTooltipPositioner.INSTANCE.positionTooltip(427, 240,
                        x.getValue(), y.getValue(), 300, 100);
                assertTrue(position.x() - 3 - ResonanceTooltipArt.SIDE_SPACE - 17 >= 2);
                assertTrue(position.x() + 300 + 3 + ResonanceTooltipArt.SIDE_SPACE + 17 <= 425);
                assertTrue(position.y() - 3 - ResonanceTooltipArt.HEADER_SPACE - 6 >= 2);
                assertTrue(position.y() + 100 + 3 + ResonanceTooltipArt.BOTTOM_SPACE <= 238);
            }
        }
    }

    @Test
    void customTooltipPositionersKeepTheirOwnCoordinates() {
        ClientTooltipPositioner positioner = (screenWidth, screenHeight, x, y, width, height) -> new Vector2i(10, 10);
        var event = event(positioner, 30, 40);
        ResonanceTooltipArt.reserveFrameSpace(event);
        verify(event, never()).setX(anyInt());
        verify(event, never()).setY(anyInt());
    }

    @Test
    void signalGlitchesAreBriefAndRepeatEveryEightSeconds() {
        assertEquals(0, ResonanceTooltipArt.glitchOffset(1.59));
        assertEquals(1, ResonanceTooltipArt.glitchOffset(1.64));
        assertEquals(-1, ResonanceTooltipArt.glitchOffset(1.72));
        assertEquals(0, ResonanceTooltipArt.glitchOffset(1.77));
        assertEquals(1, ResonanceTooltipArt.glitchOffset(5.54));
        assertEquals(-1, ResonanceTooltipArt.glitchOffset(5.62));
        assertEquals(0, ResonanceTooltipArt.glitchOffset(5.67));
        for (double time : new double[]{0, 1.64, 1.72, 5.54, 5.62, 7.99}) {
            assertEquals(ResonanceTooltipArt.glitchOffset(time), ResonanceTooltipArt.glitchOffset(time + 8));
        }
    }

    private static RenderTooltipEvent.Pre event(ClientTooltipPositioner positioner, int x, int y) {
        var event = mock(RenderTooltipEvent.Pre.class);
        var font = mock(Font.class);
        var component = mock(ClientTooltipComponent.class);
        when(component.getWidth(any(Font.class))).thenReturn(300);
        when(component.getHeight()).thenReturn(102);
        when(event.getFont()).thenReturn(font);
        when(event.getComponents()).thenReturn(List.of(component));
        when(event.getTooltipPositioner()).thenReturn(positioner);
        when(event.getScreenWidth()).thenReturn(427);
        when(event.getScreenHeight()).thenReturn(240);
        when(event.getX()).thenReturn(x);
        when(event.getY()).thenReturn(y);
        return event;
    }
}
