package com.huanghuang.rsintegration.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.huanghuang.rsintegration.config.ConfigEditorModel.ConfigFile;
import com.huanghuang.rsintegration.config.ConfigEditorModel.Option;
import net.minecraftforge.common.ForgeConfigSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigEditorModelTest {
    @Test
    void savedChangesSurviveReadingTheTomlFileAgain(@TempDir Path directory) {
        Fixture fixture = new Fixture(false);
        Path path = directory.resolve("client.toml");
        try (CommentedFileConfig config = CommentedFileConfig.builder(path).sync().build()) {
            config.load();
            fixture.file.spec().setConfig(config);
            ConfigEditorModel model = new ConfigEditorModel(List.of(fixture.file));
            model.entries().stream().filter(entry -> entry.path().equals("options.count"))
                    .findFirst().orElseThrow().setText("9");
            model.save(file -> file.spec().save());
        }
        try (CommentedFileConfig reloaded = CommentedFileConfig.builder(path).sync().build()) {
            reloaded.load();
            assertEquals(9, reloaded.getInt("options.count"));
            assertEquals(Boolean.TRUE, reloaded.get("options.enabled"));
            assertEquals(List.of("original"), reloaded.get("options.names"));
        }
    }

    @Test
    void realConfigDefinitionsHaveValidDefaultsAndExposeAllFourFiles() {
        ConfigEditorModel model = new ConfigEditorModel(ConfigEditorModel.defaultFiles());
        assertEquals(List.of("client", "common", "server", "storage"), model.files().stream().map(ConfigFile::id).toList());
        assertTrue(model.isValid(), () -> model.entries().stream().filter(entry -> !entry.isValid()).map(Option::path).toList().toString());
        assertFalse(model.isDirty());
        assertTrue(model.entries().size() > 200);
    }

    @Test
    void configCommentsSupplyNamesDescriptionsAndCategoryLabels() {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.comment("测试分类", "分类说明").push("options");
        builder.comment("启用测试", "第一行说明", "第二行说明").define("enabled", true);
        builder.comment("只有名称").define("singleLine", true);
        builder.define("withoutComment", false);
        builder.pop();
        ConfigFile file = new ConfigFile("client", "test.toml", builder.build(), false);
        ConfigEditorModel model = new ConfigEditorModel(List.of(file));
        Option enabled = model.entries().stream().filter(entry -> entry.option().equals("enabled")).findFirst().orElseThrow();
        assertEquals("启用测试", enabled.displayName());
        assertEquals("第一行说明\n第二行说明", enabled.description());
        assertEquals("测试分类", file.categoryName("options"));
        assertEquals("missing", file.categoryName("missing"));
        Option singleLine = model.entries().stream().filter(entry -> entry.option().equals("singleLine")).findFirst().orElseThrow();
        assertEquals("只有名称", singleLine.displayName());
        assertEquals("", singleLine.description());
        Option withoutComment = model.entries().stream().filter(entry -> entry.option().equals("withoutComment")).findFirst().orElseThrow();
        assertEquals("withoutComment", withoutComment.displayName());
        assertEquals("", withoutComment.description());
    }

    @Test
    void allEditableRealOptionsAndCategoriesHaveChineseMetadataInTheirDefinitions() {
        ConfigEditorModel model = new ConfigEditorModel(ConfigEditorModel.defaultFiles());
        for (Option entry : model.entries()) {
            String path = entry.file().id() + ":" + entry.path();
            assertTrue(entry.displayName().matches("(?s).*[\\u4e00-\\u9fff].*"), path + " 缺少中文名称");
            assertTrue(entry.description().matches("(?s).*[\\u4e00-\\u9fff].*"), path + " 缺少中文说明");
            assertTrue(entry.file().categoryName(entry.category()).matches("(?s).*[\\u4e00-\\u9fff].*"), path + " 缺少中文分类");
        }
    }

    @Test
    void oneEntryPerLineListInputPreservesPunctuationAndValidatesWithoutMutatingDrafts() {
        Fixture fixture = new Fixture(true);
        Option list = fixture.entry("options.names");
        String converted = ConfigEditorModel.listInputToJson("minecraft:stone\n\n带逗号,的值\na\"b\n");
        assertTrue(list.accepts(converted));
        assertEquals(list.defaultText(), list.text());
        assertFalse(fixture.model.isDirty());
        list.setText(converted);
        fixture.model.save(file -> {});
        assertEquals(List.of("minecraft:stone", "带逗号,的值", "a\"b"), fixture.names.get());
    }

    @Test
    void draftsDoNotMutateLiveValuesAndSaveOnlyChangedFiles() {
        Fixture fixture = new Fixture(true);
        Option number = fixture.entry("options.count");
        number.setText("7");
        fixture.entry("options.enabled").setText("false");
        assertEquals(3, fixture.count.get());
        assertTrue(fixture.enabled.get());
        assertTrue(fixture.model.isDirty());
        List<ConfigFile> saved = new ArrayList<>();
        fixture.model.save(saved::add);
        assertEquals(7, fixture.count.get());
        assertFalse(fixture.enabled.get());
        assertEquals(List.of(fixture.file), saved);
        assertFalse(fixture.model.isDirty());
    }

    @Test
    void invalidValuesPreventTheWholeBatchFromBeingApplied() {
        Fixture fixture = new Fixture(true);
        fixture.entry("options.enabled").setText("false");
        for (String invalid : List.of("11", "-1", "1.5", "garbage", "2147483648")) {
            fixture.entry("options.count").setText(invalid);
            assertFalse(fixture.model.isValid(), invalid);
            assertThrows(IllegalStateException.class, () -> fixture.model.save(file -> {}));
            assertTrue(fixture.enabled.get());
            assertEquals(3, fixture.count.get());
        }
    }

    @Test
    void floatingPointValuesRejectNonFiniteNumbersAndRespectRanges() {
        Fixture fixture = new Fixture(true);
        Option scale = fixture.entry("options.scale");
        for (String invalid : List.of("NaN", "Infinity", "-Infinity", "1.1", "0.1")) {
            scale.setText(invalid);
            assertFalse(scale.isValid(), invalid);
        }
        scale.setText("0.75");
        assertTrue(scale.isValid());
        fixture.model.save(file -> {});
        assertEquals(0.75, fixture.scale.get());
    }

    @Test
    void listEditorPreservesCommasUnicodeAndEscapedCharacters() {
        Fixture fixture = new Fixture(true);
        Option list = fixture.entry("options.names");
        list.setText("[\"minecraft:stone\", \"带逗号,的值\", \"a\\\"b\"]");
        assertTrue(list.isValid());
        fixture.model.save(file -> {});
        assertEquals(List.of("minecraft:stone", "带逗号,的值", "a\"b"), fixture.names.get());
        for (String invalid : List.of("{}", "[1]", "[null]", "[\"\"]")) {
            list.setText(invalid);
            assertFalse(list.isValid(), invalid);
        }
        list.setText("[]");
        assertTrue(list.isValid());
    }

    @Test
    void restoreDefaultsChangesDraftsAndNotTheRunningConfiguration() {
        Fixture fixture = new Fixture(true);
        fixture.count.set(8);
        ConfigEditorModel model = new ConfigEditorModel(List.of(fixture.file));
        Option entry = model.entries().stream().filter(value -> value.path().equals("options.count")).findFirst().orElseThrow();
        entry.resetDefault();
        assertEquals("3", entry.text());
        assertEquals(8, fixture.count.get());
        model.save(file -> {});
        assertEquals(3, fixture.count.get());
    }

    @Test
    void saveFailureRestoresAllRunningValuesAndKeepsDrafts() {
        Fixture fixture = new Fixture(true);
        fixture.entry("options.enabled").setText("false");
        fixture.entry("options.count").setText("8");
        assertThrows(IllegalStateException.class, () -> fixture.model.save(file -> {
            throw new IllegalStateException("模拟磁盘写入失败");
        }));
        assertTrue(fixture.enabled.get());
        assertEquals(3, fixture.count.get());
        assertTrue(fixture.model.isDirty());
        assertEquals("8", fixture.entry("options.count").text());
    }

    @Test
    void externalReloadConflictsAreDetectedBeforeAnyWrite() {
        Fixture fixture = new Fixture(true);
        fixture.entry("options.enabled").setText("false");
        fixture.entry("options.count").setText("8");
        fixture.count.set(5);
        assertThrows(IllegalStateException.class, () -> fixture.model.save(file -> {}));
        assertEquals(5, fixture.count.get());
        assertTrue(fixture.enabled.get());
    }

    @Test
    void unloadedConfigsCanBeViewedWithoutLoadingOrWritingThem() {
        Fixture fixture = new Fixture(false);
        assertEquals("3", fixture.entry("options.count").text());
        fixture.entry("options.count").setText("8");
        assertThrows(IllegalStateException.class, () -> fixture.model.save(file -> {}));
        assertFalse(fixture.file.spec().isLoaded());
    }

    @Test
    void internalSchemaVersionIsHiddenAndRestartMetadataIsRetained() {
        Fixture fixture = new Fixture(true);
        assertEquals(4, fixture.model.entries().size());
        assertTrue(fixture.entry("options.enabled").requiresRestart());
        assertFalse(fixture.entry("options.count").requiresRestart());
    }

    private static final class Fixture {
        final ForgeConfigSpec.BooleanValue enabled;
        final ForgeConfigSpec.IntValue count;
        final ForgeConfigSpec.DoubleValue scale;
        final ForgeConfigSpec.ConfigValue<List<? extends String>> names;
        final ConfigFile file;
        final ConfigEditorModel model;

        Fixture(boolean loaded) {
            ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
            builder.defineInRange("configSchemaVersion", 1, 1, 2);
            builder.push("options");
            enabled = builder.worldRestart().define("enabled", true);
            count = builder.defineInRange("count", 3, 0, 10);
            scale = builder.defineInRange("scale", 0.7, 0.4, 1.0);
            names = builder.defineListAllowEmpty("names", List.of("original"),
                    value -> value instanceof String string && !string.isEmpty());
            builder.pop();
            ForgeConfigSpec spec = builder.build();
            if (loaded) {
                CommentedConfig config = CommentedConfig.inMemory();
                spec.correct(config);
                spec.setConfig(config);
            }
            file = new ConfigFile("client", "test.toml", spec, false);
            model = new ConfigEditorModel(List.of(file));
        }

        Option entry(String path) {
            return model.entries().stream().filter(entry -> entry.path().equals(path)).findFirst().orElseThrow();
        }
    }
}
