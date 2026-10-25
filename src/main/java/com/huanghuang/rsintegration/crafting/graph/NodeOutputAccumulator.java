package com.huanghuang.rsintegration.crafting.graph;

import com.huanghuang.rsintegration.crafting.MaterialMatcher;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import java.util.Locale;
import java.util.stream.Collectors;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Accumulates actual node outputs and publishes each declared unit once. */
public final class NodeOutputAccumulator {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private final List<OutputDeclaration> declarations;
    private final Map<OutputPortId, Long> published = new LinkedHashMap<>();
    private final List<ItemStack> pending = new ArrayList<>();
    private boolean mismatchLogged;

    public NodeOutputAccumulator(List<OutputDeclaration> declarations) {
        this.declarations = List.copyOf(declarations);
        for (OutputDeclaration declaration : this.declarations) {
            published.put(declaration.id(), 0L);
        }
    }

    public List<Publication> add(List<ItemStack> actual) {
        Objects.requireNonNull(actual, "actual");
        for (ItemStack stack : actual) {
            if (stack != null && !stack.isEmpty()) pending.add(stack.copy());
        }
        List<Publication> publications = new ArrayList<>();
        for (OutputDeclaration declaration : declarations) {
            if (declaration.kind() == OutputKind.DYNAMIC) continue;
            long remaining = declaration.quantity() - published.get(declaration.id());
            if (remaining <= 0L) continue;
            for (ItemStack stack : pending) {
                if (remaining <= 0L) break;
                if (stack.isEmpty()
                        || !MaterialMatcher.matchesOutputDeclaration(declaration.material(), stack)) continue;
                int take = (int) Math.min(remaining, stack.getCount());
                ItemStack fragment = stack.copyWithCount(take);
                stack.shrink(take);
                publications.add(new Publication(declaration.id(), declaration.material(), fragment));
                published.merge(declaration.id(), (long) take, Long::sum);
                remaining -= take;
            }
        }
        pending.removeIf(ItemStack::isEmpty);
        if (publications.isEmpty() && !pending.isEmpty() && !mismatchLogged) {
            mismatchLogged = true;
            ItemStack actualStack = pending.get(0);
            String expected = declarations.stream()
                    .map(declaration -> BuiltInRegistries.ITEM.getKey(declaration.material().item())
                            + " tag=" + declaration.material().tag())
                    .collect(Collectors.joining("; "));
            LOGGER.debug(
                    "[RSI-GraphOutput] unmatched actual={} tag={} declarations=[{}]",
                    BuiltInRegistries.ITEM.getKey(actualStack.getItem()), actualStack.getTag(), expected);
        }
        return List.copyOf(publications);
    }

    /** Checks prospective outputs without consuming or publishing the live accumulator. */
    public boolean canCompleteWith(List<ItemStack> actual) {
        NodeOutputAccumulator probe = new NodeOutputAccumulator(declarations);
        probe.published.clear();
        probe.published.putAll(published);
        for (ItemStack stack : pending) probe.pending.add(stack.copy());
        probe.add(actual);
        return probe.isComplete();
    }
    public boolean isComplete() {
        return shortages().isEmpty();
    }

    public long publishedQuantity(OutputPortId port) {
        return published.getOrDefault(port, 0L);
    }

    public List<Shortage> shortages() {
        List<Shortage> shortages = new ArrayList<>();
        for (OutputDeclaration declaration : declarations) {
            if (declaration.kind() == OutputKind.DYNAMIC
                    || !declaration.probability().isGuaranteed()) continue;
            long actual = published.get(declaration.id());
            if (actual < declaration.quantity()) {
                shortages.add(new Shortage(declaration.id(), declaration.kind(),
                        declaration.material(), declaration.quantity(), actual));
            }
        }
        return List.copyOf(shortages);
    }

    public String describeShortages() {
        return shortages().stream()
                .map(shortage -> shortage.kind().name().toLowerCase(Locale.ROOT)
                        + " " + BuiltInRegistries.ITEM
                        .getKey(shortage.material().item())
                        + " expected=" + shortage.expected()
                        + " published=" + shortage.published()
                        + " missing=" + shortage.missing())
                .collect(Collectors.joining(", "));
    }

    public List<ItemStack> drainSurplus() {
        List<ItemStack> result = new ArrayList<>(pending.size());
        for (ItemStack stack : pending) result.add(stack.copy());
        pending.clear();
        return List.copyOf(result);
    }

    public record Shortage(OutputPortId port, OutputKind kind, MaterialKey material,
                           long expected, long published) {
        public Shortage {
            Objects.requireNonNull(port, "port");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(material, "material");
            if (expected <= 0 || published < 0 || published >= expected) {
                throw new IllegalArgumentException("invalid output shortage");
            }
        }

        public long missing() {
            return expected - published;
        }
    }

    public record Publication(OutputPortId port, MaterialKey material, ItemStack stack) {
        public Publication {
            Objects.requireNonNull(port, "port");
            Objects.requireNonNull(material, "material");
            Objects.requireNonNull(stack, "stack");
            if (stack.isEmpty()) throw new IllegalArgumentException("publication must be non-empty");
            stack = stack.copy();
        }
    }
}
