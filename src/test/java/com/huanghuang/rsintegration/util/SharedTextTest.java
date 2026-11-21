package com.huanghuang.rsintegration.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SharedTextTest extends BootstrapTest {
    @Test
    void sharedKeysAndEquipmentDetailsExistInBothLanguages() throws IOException {
        for (String locale : List.of("en_us", "zh_cn")) {
            try (var reader = Files.newBufferedReader(Path.of(
                    "src/main/resources/assets/rs_integration/lang", locale + ".json"))) {
                JsonObject translations = JsonParser.parseReader(reader).getAsJsonObject();
                for (SharedText label : SharedText.values()) {
                    assertTranslated(translations, locale, label.key());
                }
                for (String family : List.of("fluid_container", "pmmo_salvage")) {
                    assertTranslated(translations, locale, SharedText.machineTypeKey(family));
                }
                for (EquipmentTooltip.Kind kind : EquipmentTooltip.Kind.values()) {
                    for (int line = 1; line <= kind.details(); line++) {
                        assertTranslated(translations, locale, kind.detailKey(line));
                    }
                }
            }
        }
    }

    @Test
    void templatesKeepNestedComponentsForRendering() {
        Component first = Component.translatable("block.eidolon.worktable");
        Component second = Component.translatable("dimension.minecraft.overworld");
        var contents = assertInstanceOf(TranslatableContents.class, SharedText.DETAIL_PAIR.text(first, second).getContents());
        assertEquals(SharedText.DETAIL_PAIR.key(), contents.getKey());
        assertSame(first, contents.getArgs()[0]);
        assertSame(second, contents.getArgs()[1]);
    }

    private static void assertTranslated(JsonObject translations, String locale, String key) {
        assertTrue(translations.has(key) && !translations.get(key).getAsString().isBlank(), locale + ": " + key);
    }
}
