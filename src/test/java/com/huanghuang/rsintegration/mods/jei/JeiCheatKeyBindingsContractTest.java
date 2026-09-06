package com.huanghuang.rsintegration.mods.jei;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Source contracts avoid loading optional JEI/client classes in the headless test runtime. */
class JeiCheatKeyBindingsContractTest {
    private static final Path JAVA_ROOT = Path.of("src/main/java/com/huanghuang/rsintegration");

    @Test
    void allActionsAreRegisteredWithTheirOriginalDefaults() throws IOException {
        String source = Files.readString(JAVA_ROOT.resolve("sidepanel/client/RSIKeyBindings.java"))
                .replaceAll("\\s+", " ");
        assertBinding(source, "KEY_JEI_GIVE_ONE", "jei_give_one", "MOUSE", "GLFW_MOUSE_BUTTON_LEFT");
        assertBinding(source, "KEY_JEI_GIVE_STACK", "jei_give_stack", "MOUSE", "GLFW_MOUSE_BUTTON_RIGHT");
        assertBinding(source, "KEY_JEI_DROP", "jei_drop", "KEYSYM", "GLFW_KEY_Q");
    }

    @Test
    void mouseAndKeyboardShareConfigurableDispatchAndKeepSafetyGates() throws IOException {
        String source = Files.readString(JAVA_ROOT.resolve("mods/jei/client/JeiCheatShortcuts.java"));
        assertTrue(source.contains("handleInput(event.getScreen(), InputConstants.Type.MOUSE.getOrCreate(event.getButton()))"));
        assertTrue(source.contains("handleInput(event.getScreen(), InputConstants.getKey(event.getKeyCode(), event.getScanCode()))"));
        for (String field : List.of("KEY_JEI_GIVE_ONE", "KEY_JEI_GIVE_STACK", "KEY_JEI_DROP")) {
            assertTrue(source.contains("matches(RSIKeyBindings." + field + ", input)"));
        }
        assertTrue(source.contains("!mapping.isUnbound() && mapping.isActiveAndMatches(input)"));
        assertFalse(source.contains("GLFW_"));
        assertFalse(source.contains("Screen.hasControlDown()"));
        assertFalse(source.contains("Screen.hasAltDown()"));
        assertFalse(source.contains("Screen.hasShiftDown()"));
        assertTrue(source.contains("hasTextFocus(screen)"));
        assertTrue(source.contains("getIngredientListOverlay().hasKeyboardFocus()"));
        assertTrue(source.contains("state.isOverlayEnabled() && state.isCheatItemsEnabled()"));
        assertTrue(source.contains("connection.isJeiOnServer()"));
        assertTrue(source.contains("NetworkHandler.CHANNEL.isRemotePresent(connection.getConnection())"));
    }

    @Test
    void bindingLabelsExistInBothLanguages() throws IOException {
        for (String locale : List.of("en_us", "zh_cn")) {
            var translations = JsonParser.parseString(Files.readString(Path.of(
                    "src/main/resources/assets/rs_integration/lang/" + locale + ".json"))).getAsJsonObject();
            for (String action : List.of("jei_give_one", "jei_give_stack", "jei_drop")) {
                assertTrue(translations.has("key.rsi." + action), locale + ": " + action);
                assertFalse(translations.get("key.rsi." + action).getAsString().isBlank());
            }
        }
    }

    private static void assertBinding(String source, String field, String name, String type, String key) {
        assertTrue(source.contains(field + " = new KeyMapping( \"key.rsi." + name
                + "\", KeyConflictContext.GUI, KeyModifier.CONTROL, InputConstants.Type."
                + type + ", GLFW." + key + ", \"key.categories.rsi\");"));
        assertTrue(source.contains("e.register(" + field + ");"));
    }
}
