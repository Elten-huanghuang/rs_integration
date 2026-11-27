package com.huanghuang.rsintegration.crafting;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.Comparator;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.stream.Stream;

/** 单次兼容预览的独占活跃时间。按需求的代表物品归类，不参与选路或库存匹配。 */
final class PreviewMaterialProfile {
    private static final int MAX_ITEMS = 512;
    private final Map<ResourceLocation, Row> items = new HashMap<>();
    private final Row other = new Row("<other>");
    private final LongSupplier clock;
    private final Function<ItemStack, ResourceLocation> materialId;
    private Frame current;
    private long since;
    private boolean running;

    enum Stage { STOCK, FIND, SCORE, VIABLE, SELECT, INPUTS, OUTPUTS, CAPTURE, VALIDATE, MERGE, LIVE_STOCK, SETTLE, SEED }

    static final class Frame {
        final Row row;
        Stage stage = Stage.STOCK;
        String failure;

        Frame(Row row) { this.row = row; }
    }

    static final class Row {
        final String name;
        long nanos;
        final long[] stages = new long[Stage.values().length];
        final Map<String, Integer> failures = new HashMap<>();
        int calls, scanned, scored, attempts, rollbacks, fallbacks;

        Row(String name) { this.name = name; }

        String describe() {
            var top = Arrays.stream(Stage.values()).filter(s -> stages[s.ordinal()] != 0)
                    .sorted(Comparator.comparingLong((Stage s) -> stages[s.ordinal()]).reversed())
                    .limit(3).map(s -> String.format(Locale.ROOT, "%s:%.2f", s, stages[s.ordinal()] / 1e6)).toList();
            var reasons = failures.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry::getKey))
                    .limit(3).map(e -> e.getKey() + "=" + e.getValue()).toList();
            return String.format(Locale.ROOT, "%s{selfMs=%.2f,calls=%d,scanned=%d,scored=%d,tried=%d,rollback=%d,fallback=%d,stageMs=%s,failed=%s}",
                    name, nanos / 1e6, calls, scanned, scored, attempts, rollbacks, fallbacks, top, reasons);
        }
    }

    PreviewMaterialProfile(LongSupplier clock) {
        this(clock, stack -> BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    PreviewMaterialProfile(LongSupplier clock, Function<ItemStack, ResourceLocation> materialId) {
        this.clock = clock;
        this.materialId = materialId;
    }

    void startSlice() {
        since = clock.getAsLong();
        running = true;
    }

    void endSlice() {
        account();
        running = false;
    }

    private void account() {
        if (!running) return;
        long now = clock.getAsLong();
        if (current != null) {
            current.row.nanos += now - since;
            current.row.stages[current.stage.ordinal()] += now - since;
        }
        since = now;
    }

    Frame enter(Ingredient ingredient) {
        Row row = other;
        for (ItemStack stack : ingredient.getItems()) {
            if (stack.isEmpty()) continue;
            var id = materialId.apply(stack);
            row = items.get(id);
            if (row == null && items.size() < MAX_ITEMS) {
                row = new Row(id.toString());
                items.put(id, row);
            }
            if (row == null) row = other;
            break;
        }
        Frame previous = current;
        switchTo(new Frame(row));
        row.calls++;
        return previous;
    }

    void switchTo(Frame frame) {
        account();
        current = frame;
    }

    void stage(Stage stage) {
        if (current == null || current.stage == stage) return;
        account();
        current.stage = stage;
    }

    void scanned() { if (current != null) current.row.scanned++; }
    void scored() { if (current != null) current.row.scored++; }
    void attempted() {
        if (current == null) return;
        current.row.attempts++;
        current.failure = null;
    }
    void failure(String reason) { if (current != null) current.failure = reason; }

    void failedSlot(int slot, Ingredient ingredient) {
        if (current == null) return;
        for (var stack : ingredient.getItems()) {
            if (!stack.isEmpty()) {
                failure("input[" + slot + "]:" + materialId.apply(stack));
                return;
            }
        }
        failure("input[" + slot + "]:<predicate>");
    }

    void rolledBack(ResourceLocation recipe, String reason) {
        if (current == null) return;
        var row = current.row;
        row.rollbacks++;
        String key = recipe + "/" + (current.failure == null ? reason : current.failure);
        if (row.failures.size() >= 12 && !row.failures.containsKey(key)) key = "<other>";
        row.failures.merge(key, 1, Integer::sum);
        current.failure = null;
    }
    void fallback() { if (current != null) current.row.fallbacks++; }

    List<String> selected(List<ResourceLocation> ids) {
        return ids.stream().filter(items::containsKey).map(id -> items.get(id).describe()).toList();
    }

    List<String> hottest() {
        return Stream.concat(items.values().stream(), Stream.of(other))
                .filter(row -> row.calls != 0)
                .sorted(Comparator.comparingLong((Row row) -> row.nanos).reversed().thenComparing(row -> row.name))
                .limit(8).map(Row::describe).toList();
    }
}
