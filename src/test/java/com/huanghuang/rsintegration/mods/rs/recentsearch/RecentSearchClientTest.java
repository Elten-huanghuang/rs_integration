package com.huanghuang.rsintegration.mods.rs.recentsearch;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.refinedmods.refinedstorage.screen.grid.GridScreen;
import net.minecraftforge.client.event.ScreenEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RecentSearchClientTest extends BootstrapTest {
    @Test
    void closingAnUninitializedGridDoesNotReadSearchHistoryOrThrow() throws Exception {
        GridScreen screen = mock(GridScreen.class);
        invoke("onScreenClosing", ScreenEvent.Closing.class, new ScreenEvent.Closing(screen));
    }

    @Test
    void eventsIgnoreAGridWhoseSearchWidgetWasNotInitialized() throws Exception {
        GridScreen screen = mock(GridScreen.class);
        var render = mock(ScreenEvent.Render.Post.class);
        var click = mock(ScreenEvent.MouseButtonPressed.Pre.class);
        var key = mock(ScreenEvent.KeyPressed.Pre.class);
        var character = mock(ScreenEvent.CharacterTyped.Pre.class);
        when(render.getScreen()).thenReturn(screen);
        when(click.getScreen()).thenReturn(screen);
        when(key.getScreen()).thenReturn(screen);
        when(character.getScreen()).thenReturn(screen);

        invoke("onRender", ScreenEvent.Render.Post.class, render);
        invoke("onMousePressed", ScreenEvent.MouseButtonPressed.Pre.class, click);
        invoke("onKeyPressed", ScreenEvent.KeyPressed.Pre.class, key);
        invoke("onCharacterTyped", ScreenEvent.CharacterTyped.Pre.class, character);
    }

    private static void invoke(String name, Class<?> eventType, ScreenEvent event) throws Exception {
        var method = RecentSearchClient.class.getDeclaredMethod(name, eventType);
        method.setAccessible(true);
        assertDoesNotThrow(() -> method.invoke(null, event));
    }
}
