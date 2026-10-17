package com.huanghuang.rsintegration;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.huanghuang.rsintegration.util.ModIds;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LanguageParityTest {

    @Test
    void englishAndChineseExposeTheSameTranslationKeys() throws Exception {
        Set<String> english = loadKeys("en_us.json");
        Set<String> chinese = loadKeys("zh_cn.json");
        Set<String> missingInEnglish = difference(chinese, english);
        Set<String> missingInChinese = difference(english, chinese);

        assertEquals(Set.of(), missingInEnglish, "missing English translations");
        assertEquals(Set.of(), missingInChinese, "missing Chinese translations");
    }

    @Test
    void letsDoMachineLabelsHaveChineseTranslations() throws Exception {
        Set<String> chinese = loadKeys("zh_cn.json");
        Set<String> required = Set.of(
                "rsi.batch.mod." + ModIds.ID_FC_COOKING_POT,
                "rsi.batch.mod." + ModIds.ID_FC_ROASTER,
                "rsi.batch.mod." + ModIds.ID_CANDLELIGHT_COOKING_POT,
                "rsi.batch.mod." + ModIds.ID_BAKERY_COOKING_POT);

        assertTrue(chinese.containsAll(required), () -> "missing Chinese machine labels: "
                + difference(required, chinese));
    }

    private static Set<String> loadKeys(String fileName) throws Exception {
        String path = "assets/rs_integration/lang/" + fileName;
        try (InputStream stream = LanguageParityTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(stream, "missing language resource " + path);
            JsonObject json = JsonParser.parseReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            return new LinkedHashSet<>(json.keySet());
        }
    }

    private static Set<String> difference(Set<String> left, Set<String> right) {
        Set<String> result = new LinkedHashSet<>(left);
        result.removeAll(right);
        return result;
    }
}
