package com.huanghuang.rsintegration.feature.transmutation.client;

import com.huanghuang.rsintegration.client.recipe.RecipeBrowserBridge;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class TransmutationTableClientTest extends BootstrapTest {
    @Test
    void recipeViewerSearchFocusPreventsSpaceFromStartingTransmutation() {
        Screen screen = mock(Screen.class);
        try (var browser = mockStatic(RecipeBrowserBridge.class)) {
            browser.when(RecipeBrowserBridge::hasSearchFocus).thenReturn(true);
            assertTrue(TransmutationTableClient.hasTextFocus(screen));
        }
    }

    @Test
    void nativeTextFieldsAlsoKeepSpaceForTyping() {
        Screen screen = mock(Screen.class);
        when(screen.getFocused()).thenReturn(mock(EditBox.class));
        try (var browser = mockStatic(RecipeBrowserBridge.class)) {
            assertTrue(TransmutationTableClient.hasTextFocus(screen));
            browser.verifyNoInteractions();
        }
    }

    @Test
    void unfocusedScreenAllowsTheShortcut() {
        try (var browser = mockStatic(RecipeBrowserBridge.class)) {
            assertFalse(TransmutationTableClient.hasTextFocus(mock(Screen.class)));
        }
    }
}
