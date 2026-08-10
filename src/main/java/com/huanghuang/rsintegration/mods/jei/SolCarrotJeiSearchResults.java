package com.huanghuang.rsintegration.mods.jei;

import com.huanghuang.rsintegration.mods.rs.SolCarrotSearchStatus;
import mezz.jei.gui.ingredients.IListElement;
import mezz.jei.gui.search.ElementPrefixParser;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** Builds only SolCarrot's player-dependent JEI tooltip token results. */
public final class SolCarrotJeiSearchResults {
    private static final class CachedResults {
        private long revision = Long.MIN_VALUE;
        private final Map<SolCarrotSearchStatus.Query, Set<IListElement<?>>> results =
                new java.util.EnumMap<>(SolCarrotSearchStatus.Query.class);
    }

    private static final Map<Object, CachedResults> CACHE = new WeakHashMap<>();

    private SolCarrotJeiSearchResults() {}

    @Nullable
    public static SolCarrotSearchStatus.Query query(
            ElementPrefixParser.TokenInfo tokenInfo) {
        if (tokenInfo.prefixInfo().getPrefix() != '#') return null;
        SolCarrotSearchStatus.refresh(
                net.minecraft.client.Minecraft.getInstance().player);
        SolCarrotSearchStatus.Query query =
                SolCarrotSearchStatus.classify(tokenInfo.token());
        return query == SolCarrotSearchStatus.Query.NONE ? null : query;
    }

    public static Set<IListElement<?>> filter(
            Object searchBackend,
            Iterable<? extends IListElement<?>> elements,
            SolCarrotSearchStatus.Query query) {
        CachedResults cached = CACHE.computeIfAbsent(
                searchBackend, ignored -> new CachedResults());
        long revision = SolCarrotSearchStatus.revision();
        if (cached.revision != revision) {
            cached.revision = revision;
            cached.results.clear();
        }
        Set<IListElement<?>> existing = cached.results.get(query);
        if (existing != null) return existing;

        Set<IListElement<?>> matches = Collections.newSetFromMap(new IdentityHashMap<>());
        for (IListElement<?> element : elements) {
            if (SolCarrotSearchStatus.matches(
                    query, element.getTypedIngredient().getIngredient())) {
                matches.add(element);
            }
        }
        cached.results.put(query, matches);
        return matches;
    }
}
