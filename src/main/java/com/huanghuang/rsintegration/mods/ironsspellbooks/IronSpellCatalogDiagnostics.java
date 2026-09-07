package com.huanghuang.rsintegration.mods.ironsspellbooks;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class IronSpellCatalogDiagnostics {
    private static final int MAX_SAMPLES = 8;
    private final Map<String, Integer> failuresByStage = new LinkedHashMap<>();
    private final List<String> samples = new ArrayList<>();

    @FunctionalInterface
    interface Probe<T> {
        T read() throws ReflectiveOperationException;
    }

    record Result<T>(T value, boolean failed) {
        T orElse(T fallback) {
            return failed ? fallback : value;
        }
    }

    <T> Result<T> read(String stage, String spell, int level, Probe<T> probe) {
        try {
            return new Result<>(probe.read(), false);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            Throwable cause = failure;
            while (cause instanceof InvocationTargetException wrapper && wrapper.getCause() != null) {
                cause = wrapper.getCause();
            }
            if (cause instanceof VirtualMachineError fatal) throw fatal;
            if (cause instanceof ThreadDeath fatal) throw fatal;
            failuresByStage.merge(stage, 1, Integer::sum);
            if (samples.size() < MAX_SAMPLES) {
                samples.add("stage=" + bounded(stage) + " spell=" + bounded(spell) + " level=" + level
                        + " error=" + cause.getClass().getName() + ": " + bounded(cause.getMessage()));
            }
            return new Result<>(null, true);
        }
    }

    boolean hasFailures() {
        return !failuresByStage.isEmpty();
    }

    <T> T withFallback(String spell, int level, Probe<T> reflected, Probe<T> nativeRecipe) {
        Result<T> result = reflected == null ? new Result<>(null, true)
                : read("arcane_anvil_jei", spell, level, reflected);
        return result.failed() ? read("arcane_anvil_native", spell, level, nativeRecipe).orElse(null)
                : result.value();
    }

    String summary() {
        return "failuresByStage=" + failuresByStage + " samples=" + samples;
    }

    private static String bounded(String text) {
        if (text == null) return "unknown";
        String singleLine = text.replace('\n', ' ').replace('\r', ' ');
        return singleLine.length() <= 240 ? singleLine : singleLine.substring(0, 240) + "[truncated]";
    }
}
