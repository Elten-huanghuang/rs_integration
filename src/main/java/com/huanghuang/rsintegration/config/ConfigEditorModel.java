package com.huanghuang.rsintegration.config;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/** 配置界面的编辑副本；取消、搜索和切换分类不会修改正在使用的配置。 */
public final class ConfigEditorModel {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public record ConfigFile(String id, String fileName, ForgeConfigSpec spec, boolean requiresRestart) {
        public String categoryName(String category) {
            return commentName(spec.getLevelComment(List.of(category)), category);
        }
    }

    // 配置注释首行作为名称，后续行作为说明，同时供 TOML 文件和配置界面复用。
    private static String commentName(String comment, String fallback) {
        return comment == null ? fallback : comment.lines().findFirst().map(String::strip)
                .filter(name -> !name.isEmpty()).orElse(fallback);
    }

    public static List<ConfigFile> defaultFiles() {
        return List.of(
                new ConfigFile("client", "rs_integration/client.toml", RSIntegrationConfig.CLIENT_SPEC, false),
                new ConfigFile("common", "rs_integration/common.toml", RSIntegrationConfig.COMMON_SPEC, true),
                new ConfigFile("server", "rs_integration/server.toml", RSIntegrationConfig.SERVER_SPEC, false),
                new ConfigFile("storage", "rs_integration/storage.toml", RSStorageConfig.SPEC, false));
    }

    private final List<ConfigFile> files;
    private final List<Option> entries = new ArrayList<>();

    public ConfigEditorModel(List<ConfigFile> files) {
        this.files = List.copyOf(files);
        files.forEach(file -> collect(file, file.spec().getValues()));
    }

    private void collect(ConfigFile file, UnmodifiableConfig values) {
        for (Object object : values.valueMap().values()) {
            if (object instanceof ForgeConfigSpec.ConfigValue<?> value) {
                String name = value.getPath().get(value.getPath().size() - 1);
                if (name.toLowerCase().contains("schema")) continue;
                ForgeConfigSpec.ValueSpec spec = file.spec().getSpec().get(value.getPath());
                entries.add(new Option(file, value, spec));
            } else if (object instanceof UnmodifiableConfig nested) {
                collect(file, nested);
            }
        }
    }

    public List<ConfigFile> files() { return files; }
    public List<Option> entries() { return List.copyOf(entries); }
    public boolean isDirty() { return entries.stream().anyMatch(Option::isDirty); }
    public boolean isValid() { return entries.stream().allMatch(Option::isValid); }

    public List<Option> changes() {
        return entries.stream().filter(Option::isDirty).toList();
    }

    public void save(Consumer<ConfigFile> saveFile) {
        List<Option> changes = changes();
        if (changes.isEmpty()) return;
        Set<ConfigFile> changedFiles = new LinkedHashSet<>();
        // 先校验整批修改，任何非法值或外部重载冲突都不能造成部分更新。
        for (Option entry : changes) {
            if (!entry.isValid() || !entry.file.spec().isLoaded()) {
                throw new IllegalStateException("Invalid or unloaded config: " + entry.path());
            }
            if (!Objects.equals(entry.initial, entry.value.get())) {
                throw new IllegalStateException("Config changed while editing: " + entry.path());
            }
            changedFiles.add(entry.file);
        }
        try {
            changes.forEach(entry -> set(entry.value, entry.parsed));
            changedFiles.forEach(saveFile);
        } catch (RuntimeException failure) {
            changes.forEach(entry -> set(entry.value, entry.initial));
            for (ConfigFile file : changedFiles) {
                try {
                    saveFile.accept(file);
                } catch (RuntimeException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            throw failure;
        }
        changes.forEach(Option::acceptSavedValue);
    }

    @SuppressWarnings("unchecked")
    private static <T> void set(ForgeConfigSpec.ConfigValue<T> value, Object replacement) {
        value.set((T) replacement);
    }

    public static final class Option {
        private final ConfigFile file;
        private final ForgeConfigSpec.ConfigValue<?> value;
        private final ForgeConfigSpec.ValueSpec spec;
        private Object initial;
        private Object parsed;
        private String text;
        private boolean valid;

        private Option(ConfigFile file, ForgeConfigSpec.ConfigValue<?> value, ForgeConfigSpec.ValueSpec spec) {
            this.file = file;
            this.value = value;
            this.spec = spec;
            initial = copy(file.spec().isLoaded() ? value.get() : value.getDefault());
            setText(format(initial));
        }

        public ConfigFile file() { return file; }
        public String path() { return String.join(".", value.getPath()); }
        public String category() { return value.getPath().size() > 1 ? value.getPath().get(0) : "general"; }
        public String option() { return value.getPath().get(value.getPath().size() - 1); }
        public String displayName() { return commentName(spec.getComment(), option()); }
        public String description() {
            String comment = spec.getComment();
            return comment == null ? "" : String.join("\n", comment.lines().skip(1).toList()).strip();
        }
        public String range() { return spec.getRange() == null ? "" : spec.getRange().toString(); }
        public String text() { return text; }
        public Object parsed() { return parsed; }
        public boolean isBoolean() { return value.getDefault() instanceof Boolean; }
        public boolean isList() { return value.getDefault() instanceof List<?>; }
        public boolean isValid() { return valid; }
        public boolean isDirty() { return !valid || !Objects.equals(initial, parsed); }
        public String defaultText() { return format(value.getDefault()); }

        public boolean requiresRestart() {
            return file.requiresRestart() || spec.needsWorldRestart();
        }

        public void resetDefault() { setText(defaultText()); }

        public boolean accepts(String candidate) {
            try {
                return spec.test(parse(candidate, value.getDefault()));
            } catch (RuntimeException ignored) {
                return false;
            }
        }

        public void setText(String text) {
            this.text = text;
            try {
                parsed = parse(text, value.getDefault());
                valid = spec.test(parsed);
            } catch (RuntimeException ignored) {
                parsed = null;
                valid = false;
            }
        }

        private void acceptSavedValue() {
            initial = copy(parsed);
            text = format(parsed);
        }
    }

    private static Object copy(Object value) {
        return value instanceof List<?> list ? List.copyOf(list) : value;
    }

    private static String format(Object value) {
        return value instanceof List<?> ? JSON.toJson(value) : String.valueOf(value);
    }

    public static String listInputToJson(String input) {
        return JSON.toJson(input.lines().map(String::trim).filter(value -> !value.isEmpty()).toList());
    }

    private static Object parse(String text, Object defaultValue) {
        String trimmed = text.trim();
        if (defaultValue instanceof Boolean) {
            if (!trimmed.equals("true") && !trimmed.equals("false")) throw new IllegalArgumentException();
            return Boolean.valueOf(trimmed);
        }
        if (defaultValue instanceof Integer) return Integer.valueOf(trimmed);
        if (defaultValue instanceof Long) return Long.valueOf(trimmed);
        if (defaultValue instanceof Double) {
            double value = Double.parseDouble(trimmed);
            if (!Double.isFinite(value)) throw new IllegalArgumentException();
            return value;
        }
        if (defaultValue instanceof Enum<?> enumeration) {
            for (Object constant : enumeration.getDeclaringClass().getEnumConstants()) {
                if (((Enum<?>) constant).name().equals(trimmed)) return constant;
            }
            throw new IllegalArgumentException();
        }
        if (defaultValue instanceof List<?>) {
            JsonElement json = JsonParser.parseString(trimmed);
            if (!json.isJsonArray()) throw new IllegalArgumentException();
            List<String> result = new ArrayList<>();
            for (JsonElement element : json.getAsJsonArray()) {
                if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                    throw new IllegalArgumentException();
                }
                result.add(element.getAsString());
            }
            return List.copyOf(result);
        }
        return text;
    }
}
