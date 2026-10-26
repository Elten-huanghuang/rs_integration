package com.huanghuang.rsintegration.crafting.graph;

import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.api.execution.CollectedOutput;
import com.huanghuang.rsintegration.crafting.MaterialMatcher;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
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
    private final List<CollectedOutput> pendingResources =
            new ArrayList<>();
    private boolean mismatchLogged;

    public NodeOutputAccumulator(List<OutputDeclaration> declarations) {
        this.declarations = List.copyOf(declarations);
        for (OutputDeclaration declaration : this.declarations) {
            published.put(declaration.id(), 0L);
        }
    }

    public List<Publication> add(List<ItemStack> actual) {
        return add(actual, List.of());
    }

    public List<Publication> add(
            List<ItemStack> actual,
            List<CollectedOutput> actualResources
    ) {
        Objects.requireNonNull(actual, "actual");
        for (ItemStack stack : actual) {
            if (stack != null && !stack.isEmpty()) pending.add(stack.copy());
        }
        if (actualResources != null) {
            for (CollectedOutput output : actualResources) {
                if (output != null && output.amount() > 0L) {
                    pendingResources.add(output);
                }
            }
        }
        List<Publication> publications = new ArrayList<>();
        for (OutputDeclaration declaration : declarations) {
            if (declaration.kind() == OutputKind.DYNAMIC) continue;
            long limit = declaration.maximumQuantity();
            long remaining = limit - published.get(declaration.id());
            if (remaining <= 0L) continue;
            if (declaration.resource().kind()
                    == IntegrationResourceKey.ResourceKind.FLUID) {
                takeFluid(
                        declaration,
                        remaining,
                        publications
                );
                continue;
            }
            for (ItemStack stack : pending) {
                if (remaining <= 0L) break;
                if (stack.isEmpty()
                        || !MaterialMatcher.matchesOutputDeclaration(declaration.material(), stack)) continue;
                int take = (int) Math.min(remaining, stack.getCount());
                ItemStack fragment = stack.copyWithCount(take);
                stack.shrink(take);
                publications.add(new Publication(
                        declaration.id(),
                        declaration.material(),
                        fragment,
                        declaration.resource()
                ));
                published.merge(declaration.id(), (long) take, Long::sum);
                remaining -= take;
            }
        }
        pending.removeIf(ItemStack::isEmpty);
        pendingResources.removeIf(output -> output.amount() <= 0L);
        if (publications.isEmpty()
                && (!pending.isEmpty() || !pendingResources.isEmpty())
                && !mismatchLogged) {
            mismatchLogged = true;
            ItemStack actualStack = pending.isEmpty()
                    ? ItemStack.EMPTY : pending.get(0);
            String expected = declarations.stream()
                    .map(declaration -> declaration.resource().id()
                            + " tag=" + declaration.resource().identity())
                    .collect(Collectors.joining("; "));
            LOGGER.debug(
                    "[RSI-GraphOutput] unmatched actual={} tag={} declarations=[{}]",
                    actualStack.isEmpty()
                            ? "fluid-resource"
                            : BuiltInRegistries.ITEM.getKey(
                            actualStack.getItem()),
                    actualStack.isEmpty()
                            ? pendingResources.get(0).resource().identity()
                            : actualStack.getTag(),
                    expected);
        }
        return List.copyOf(publications);
    }

    private void takeFluid(
            OutputDeclaration declaration,
            long limit,
            List<Publication> publications
    ) {
        long remaining = limit;
        for (int index = 0;
             index < pendingResources.size() && remaining > 0L;
             index++) {
            CollectedOutput output = pendingResources.get(index);
            if (!matchesResource(
                    declaration.resource(),
                    output.resource())) {
                continue;
            }
            long take = Math.min(remaining, output.amount());
            long accepted = 0L;
            while (accepted < take) {
                int chunk = (int) Math.min(
                        Integer.MAX_VALUE, take - accepted);
                ItemStack token = declaration.material()
                        .toStack(chunk);
                if (token.isEmpty()) break;
                publications.add(new Publication(
                        declaration.id(),
                        declaration.material(),
                        token,
                        declaration.resource()
                ));
                accepted += chunk;
            }
            if (accepted <= 0L) continue;
            published.merge(
                    declaration.id(), accepted, Long::sum);
            remaining -= accepted;
            long left = output.amount() - accepted;
            if (left > 0L) {
                pendingResources.set(index, new CollectedOutput(
                        output.resource(),
                        left,
                        output.outputKind(),
                        output.portId()
                ));
            } else {
                pendingResources.remove(index);
                index--;
            }
        }
    }

    private static boolean matchesResource(
            IntegrationResourceKey expected,
            IntegrationResourceKey actual
    ) {
        return expected.kind() == actual.kind()
                && expected.id().equals(actual.id())
                && (expected.identity().isEmpty()
                || expected.identity().equals(actual.identity()));
    }

    /** Checks prospective outputs without consuming or publishing the live accumulator. */
    public boolean canCompleteWith(List<ItemStack> actual) {
        return canCompleteWith(actual, List.of());
    }

    public boolean canCompleteWith(
            List<ItemStack> actual,
            List<CollectedOutput> actualResources
    ) {
        NodeOutputAccumulator probe = new NodeOutputAccumulator(declarations);
        probe.published.clear();
        probe.published.putAll(published);
        for (ItemStack stack : pending) probe.pending.add(stack.copy());
        probe.pendingResources.addAll(pendingResources);
        probe.add(actual, actualResources);
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
            if (actual < declaration.minimumQuantity()) {
                shortages.add(new Shortage(declaration.id(), declaration.kind(),
                        declaration.resource(), declaration.minimumQuantity(), actual));
            }
        }
        return List.copyOf(shortages);
    }

    public String describeShortages() {
        return shortages().stream()
                .map(shortage -> shortage.kind().name().toLowerCase(Locale.ROOT)
                        + " " + shortage.resource().id()
                        + " expected=" + shortage.expected()
                        + " published=" + shortage.published()
                        + " missing=" + shortage.missing())
                .collect(Collectors.joining(", "));
    }

    public List<ItemStack> drainSurplus() {
        List<ItemStack> result = new ArrayList<>(
                pending.size() + pendingResources.size());
        for (ItemStack stack : pending) result.add(stack.copy());
        pending.clear();
        for (CollectedOutput output : pendingResources) {
            long remaining = output.amount();
            while (remaining > 0L) {
                int chunk = (int) Math.min(
                        Integer.MAX_VALUE, remaining);
                ItemStack token = InkFluidSupport.tokenFor(
                        output.resource(), chunk);
                if (token.isEmpty()) break;
                result.add(token);
                remaining -= chunk;
            }
        }
        pendingResources.clear();
        return List.copyOf(result);
    }

    public record Shortage(OutputPortId port, OutputKind kind,
                           IntegrationResourceKey resource,
                           long expected, long published) {
        public Shortage {
            Objects.requireNonNull(port, "port");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(resource, "resource");
            if (expected <= 0 || published < 0 || published >= expected) {
                throw new IllegalArgumentException("invalid output shortage");
            }
        }

        public long missing() {
            return expected - published;
        }
    }

    public record Publication(
            OutputPortId port,
            MaterialKey material,
            ItemStack stack,
            IntegrationResourceKey resource
    ) {
        public Publication {
            Objects.requireNonNull(port, "port");
            Objects.requireNonNull(material, "material");
            Objects.requireNonNull(stack, "stack");
            Objects.requireNonNull(resource, "resource");
            if (stack.isEmpty()) throw new IllegalArgumentException("publication must be non-empty");
            stack = stack.copy();
        }
    }
}
