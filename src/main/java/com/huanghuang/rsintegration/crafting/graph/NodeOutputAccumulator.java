package com.huanghuang.rsintegration.crafting.graph;

import com.huanghuang.rsintegration.crafting.MaterialMatcher;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Accumulates actual node outputs and publishes each declared unit once. */
public final class NodeOutputAccumulator {
    private final List<OutputDeclaration> declarations;
    private final Map<OutputPortId, Integer> published = new LinkedHashMap<>();
    private final List<ItemStack> pending = new ArrayList<>();

    public NodeOutputAccumulator(List<OutputDeclaration> declarations) {
        this.declarations = List.copyOf(declarations);
        for (OutputDeclaration declaration : this.declarations) {
            published.put(declaration.id(), 0);
        }
    }

    public List<Publication> add(List<ItemStack> actual) {
        Objects.requireNonNull(actual, "actual");
        for (ItemStack stack : actual) {
            if (stack != null && !stack.isEmpty()) pending.add(stack.copy());
        }
        List<Publication> publications = new ArrayList<>();
        for (OutputDeclaration declaration : declarations) {
            int remaining = declaration.quantity() - published.get(declaration.id());
            if (remaining <= 0) continue;
            for (ItemStack stack : pending) {
                if (remaining <= 0) break;
                if (stack.isEmpty() || !MaterialMatcher.matchesExact(declaration.material(), stack)) continue;
                int take = Math.min(remaining, stack.getCount());
                ItemStack fragment = stack.copyWithCount(take);
                stack.shrink(take);
                publications.add(new Publication(declaration.id(), declaration.material(), fragment));
                published.merge(declaration.id(), take, Integer::sum);
                remaining -= take;
            }
        }
        pending.removeIf(ItemStack::isEmpty);
        return List.copyOf(publications);
    }

    public boolean isComplete() {
        return shortages().isEmpty();
    }

    public List<Shortage> shortages() {
        List<Shortage> shortages = new ArrayList<>();
        for (OutputDeclaration declaration : declarations) {
            int actual = published.get(declaration.id());
            if (actual < declaration.quantity()) {
                shortages.add(new Shortage(declaration.id(), declaration.kind(),
                        declaration.material(), declaration.quantity(), actual));
            }
        }
        return List.copyOf(shortages);
    }

    public String describeShortages() {
        return shortages().stream()
                .map(shortage -> shortage.kind().name().toLowerCase(java.util.Locale.ROOT)
                        + " " + net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(shortage.material().item())
                        + " expected=" + shortage.expected()
                        + " published=" + shortage.published()
                        + " missing=" + shortage.missing())
                .collect(java.util.stream.Collectors.joining(", "));
    }

    public List<ItemStack> drainSurplus() {
        List<ItemStack> result = new ArrayList<>(pending.size());
        for (ItemStack stack : pending) result.add(stack.copy());
        pending.clear();
        return List.copyOf(result);
    }

    public record Shortage(OutputPortId port, OutputKind kind, MaterialKey material,
                           int expected, int published) {
        public Shortage {
            Objects.requireNonNull(port, "port");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(material, "material");
            if (expected <= 0 || published < 0 || published >= expected) {
                throw new IllegalArgumentException("invalid output shortage");
            }
        }

        public int missing() {
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
