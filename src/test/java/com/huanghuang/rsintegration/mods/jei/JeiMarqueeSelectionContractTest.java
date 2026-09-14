package com.huanghuang.rsintegration.mods.jei;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Source contract avoids loading optional JEI client classes in headless tests. */
class JeiMarqueeSelectionContractTest {
    private static final Path SOURCE = Path.of(
            "src/main/java/com/huanghuang/rsintegration/mods/jei/JeiMarqueeSelector.java");

    @Test
    void ctrlClickTogglesOneSlotBeforeClearingTheSelection() throws Exception {
        String source = Files.readString(SOURCE).replace("\r\n", "\n");
        int toggle = source.indexOf("Screen.hasControlDown()\n                && toggleSelectionAt(mx, my)");
        int clear = source.indexOf("if (selecting && !dragging) clearSelection();");

        assertTrue(toggle >= 0, "Ctrl+click must toggle the ingredient under the mouse");
        assertTrue(clear > toggle,
                "slot toggling must happen before ordinary clicks clear the selection");
        assertTrue(source.indexOf("event.setCanceled(true);", toggle) > toggle,
                "the toggle must consume the click before JEI handles it");
    }

    @Test
    void marqueeMouseHandlerRunsBeforeJeiCheatShortcuts() throws Exception {
        String source = Files.readString(SOURCE).replace("\r\n", "\n");
        assertTrue(source.contains("@SubscribeEvent(priority = EventPriority.HIGHEST)\n"
                        + "    public static void onMousePressed"),
                "Ctrl+click selection toggles must run before the JEI give-one shortcut");
    }
}
