package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.api.RSIPlanExtensions.Context;
import com.huanghuang.rsintegration.api.RSIPlanExtensions.Decoration;
import com.huanghuang.rsintegration.api.RSIPlanExtensions.Material;
import com.huanghuang.rsintegration.api.RSIPlanExtensions.MaterialNote;
import com.huanghuang.rsintegration.api.RSIPlanExtensions.Network;
import com.huanghuang.rsintegration.api.RSIPlanExtensions.Provider;
import com.huanghuang.rsintegration.api.RSIPlanExtensions.Source;
import com.huanghuang.rsintegration.crafting.MaterialSourceSelection;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

public final class PlanExtensionState {
    private static final Map<ResourceLocation, Provider> PROVIDERS = new LinkedHashMap<>();

    public static synchronized boolean register(ResourceLocation id, Provider provider) {
        return PROVIDERS.putIfAbsent(Objects.requireNonNull(id), Objects.requireNonNull(provider)) == null;
    }

    private static synchronized Map<ResourceLocation, Provider> providers() {
        return new LinkedHashMap<>(PROVIDERS);
    }

    private record Response(long sequence, Decoration decoration) {}

    private final Map<ResourceLocation, Response> responses = new LinkedHashMap<>();
    private UUID planId = UUID.randomUUID();
    private long revision;
    private long generation;

    void invalidate() {
        generation++;
        responses.clear();
    }

    void newPlan() {
        invalidate();
        planId = UUID.randomUUID();
        revision = 0;
    }

    void update(PlanResponse plan, MaterialSourceSelection selection, boolean inventoryChange, Executor executor) {
        update(plan, selection, inventoryChange, executor, providers());
    }

    void update(PlanResponse plan, MaterialSourceSelection selection, boolean inventoryChange, Executor executor,
                Map<ResourceLocation, Provider> registered) {
        ResourceLocation recipe = ResourceLocation.tryParse(plan.recipeId());
        if (registered.isEmpty() || recipe == null) return;
        long current = ++generation;
        if (inventoryChange) {
            revision++;
            responses.clear();
        }
        EnumSet<Source> sources = EnumSet.noneOf(Source.class);
        if (selection.inventory()) sources.add(Source.INVENTORY);
        if (selection.storageNetwork()) sources.add(Source.STORAGE_NETWORK);
        if (selection.backpacks()) sources.add(Source.BACKPACKS);
        List<Material> materials = plan.graph() == null ? List.of() : plan.materials().entrySet().stream()
                .filter(entry -> !entry.getKey().stack(1).isEmpty())
                .map(entry -> new Material(key(entry.getKey().stack(1)), entry.getValue().needed(),
                        entry.getValue().available(), entry.getValue().missingCount()))
                .toList();
        Context context = new Context(planId, revision, recipe, plan.repeatCount(),
                Optional.ofNullable(plan.storageReference())
                        .map(reference -> new Network(reference.backendId().value(), reference.networkId())),
                sources, materials, plan.graph() != null && !plan.outcome().incomplete());
        registered.forEach((id, provider) -> {
            responses.put(id, new Response(0, null));
            AtomicLong sequence = new AtomicLong();
            try {
                provider.onPlan(context, decoration -> {
                    Objects.requireNonNull(decoration);
                    long publication = sequence.incrementAndGet();
                    executor.execute(() -> {
                        if (generation != current) return;
                        Response previous = responses.get(id);
                        if (previous.sequence() < publication) {
                            responses.put(id, new Response(publication, decoration));
                        }
                    });
                });
            } catch (RuntimeException exception) {
                RSIntegrationMod.LOGGER.warn("[RSI-Plan] Extension {} failed", id, exception);
            }
        });
    }

    List<String> summaries() {
        return decorations().flatMap(decoration -> decoration.summaryLines().stream()).toList();
    }

    boolean hasNotes() {
        return decorations().anyMatch(decoration -> !decoration.materials().isEmpty());
    }

    List<MaterialNote> notes(ItemStack stack) {
        if (stack.isEmpty() || responses.isEmpty()) return List.of();
        IntegrationResourceKey key = key(stack);
        return decorations().map(decoration -> decoration.materials().get(key))
                .filter(Objects::nonNull).toList();
    }

    private Stream<Decoration> decorations() {
        return responses.values().stream().map(Response::decoration).filter(Objects::nonNull);
    }

    private static IntegrationResourceKey key(ItemStack stack) {
        IntegrationResourceKey fluid = InkFluidSupport.resource(stack);
        return fluid == null ? IntegrationResourceKey.item(stack) : fluid;
    }
}
