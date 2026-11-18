package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.IngredientSpec;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 一次操作的输入声明。材料 ID、库存归属和物理槽位在此确定。 */
public record MaterialPlan(List<Entry> entries, boolean delegateExtracted) {
    public enum Allocation { GRAPH, SUPPLEMENTAL }

    public MaterialPlan(List<Entry> entries) {
        this(entries, false);
    }

    public MaterialPlan {
        entries = List.copyOf(entries);
        if (delegateExtracted && !entries.isEmpty()) throw new IllegalArgumentException("private extraction with declared inputs");
        Set<String> ids = new HashSet<>();
        Set<Integer> slots = new HashSet<>();
        for (Entry entry : entries) {
            if (!ids.add(entry.id())) throw new IllegalArgumentException("duplicate material id " + entry.id());
            if (!slots.add(entry.inputSlot())) throw new IllegalArgumentException("duplicate input slot " + entry.inputSlot());
        }
    }

    public static MaterialPlan none() { return new MaterialPlan(List.of()); }
    public static MaterialPlan privateExtraction() { return new MaterialPlan(List.of(), true); }

    /** 普通图材料以配方槽号命名；空槽不产生材料需求。 */
    public static MaterialPlan graphSlots(List<IngredientSpec> slots) {
        if (slots == null) return privateExtraction();
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) {
            IngredientSpec spec = slots.get(i);
            if (spec != null && !spec.isEmpty()) entries.add(graphSlot(i, spec));
        }
        return new MaterialPlan(entries);
    }

    public static Entry graphSlot(int slot, IngredientSpec spec) {
        return new Entry("slot:" + slot, spec, Allocation.GRAPH, slot);
    }

    public List<Entry> graphEntries() {
        return entries.stream().filter(e -> e.allocation() == Allocation.GRAPH).toList();
    }

    public List<Entry> supplementalEntries() {
        return entries.stream().filter(e -> e.allocation() == Allocation.SUPPLEMENTAL).toList();
    }

    public List<Entry> reusableEntries() {
        return entries.stream().filter(Entry::reusable).toList();
    }

    public record Entry(String id, IngredientSpec spec, Allocation allocation, int inputSlot) {
        public Entry {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("missing material id");
            if (spec == null || spec.isEmpty()) throw new IllegalArgumentException("missing material spec");
            Objects.requireNonNull(allocation);
            if (inputSlot < 0) throw new IllegalArgumentException("negative input slot");
        }

        public boolean reusable() { return spec.consumption().reusableWithinWorkerBatch(); }
    }
}
