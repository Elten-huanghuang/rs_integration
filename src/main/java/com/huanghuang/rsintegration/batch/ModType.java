package com.huanghuang.rsintegration.batch;

import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nullable;
import java.util.*;
import java.util.function.Supplier;

/**
 * Registered mod type for multi-block recipe classification and delegate creation.
 *
 * <p>Each supported mod creates one {@link ModType} instance via {@link #register}
 * with its recipe class-name prefix, block-key keywords, and delegate factory.
 * The core engine (recipe indexing, resolution, async chains) then queries the
 * registry rather than using hardcoded if/switch blocks — adding a new mod
 * only requires calling {@link #register} once.</p>
 */
public final class ModType {

    private static final Map<String, ModType> BY_ID = new LinkedHashMap<>();

    // ── instance fields ───────────────────────────────────────────

    private final String id;
    private final String[] recipePrefixes;
    private final String[] blockKeyKeywords;
    private final String[] blockKeyPrefixes;
    private final Supplier<IBatchDelegate> delegateFactory;

    // ── built-in constants (registered in static {}) ──────────────

    public static final ModType GENERIC;
    public static final ModType GOETY;
    public static final ModType MALUM;
    public static final ModType FORBIDDEN_ARCANUS;
    public static final ModType EIDOLON;
    public static final ModType WIZARDS_REBORN;

    static {
        GENERIC = register("generic",
                new String[0], new String[0], new String[0],
                com.huanghuang.rsintegration.batch.delegate.GenericBatchDelegate::new);

        // Lazy delegate construction to avoid ClassNotFoundException
        GOETY = register("goety",
                new String[]{"com.Polarice3.Goety."},
                new String[]{"goety"},
                new String[0],
                delegateSupplier("com.huanghuang.rsintegration.batch.delegate.GoetyBatchDelegate"));

        MALUM = register("malum",
                new String[]{"com.sammy.malum."},
                new String[]{"malum"},
                new String[0],
                delegateSupplier("com.huanghuang.rsintegration.batch.delegate.MalumBatchDelegate"));

        FORBIDDEN_ARCANUS = register("forbidden_arcanus",
                new String[]{"com.stal111.forbidden_arcanus."},
                new String[]{"forbidden_arcanus"},
                new String[0],
                delegateSupplier("com.huanghuang.rsintegration.batch.delegate.FaBatchDelegate"));

        EIDOLON = register("eidolon",
                new String[]{"elucent.eidolon."},
                new String[]{"eidolon"},
                new String[0],
                delegateSupplier("com.huanghuang.rsintegration.batch.delegate.EidolonBatchDelegate"));

        WIZARDS_REBORN = register("wizards_reborn",
                new String[]{"mod.maxbogomol.wizards_reborn."},
                new String[]{"wizards_reborn"},
                new String[]{"crystal_ritual"},
                delegateSupplier("com.huanghuang.rsintegration.batch.delegate.WRBatchDelegate"));
    }

    // ── constructors ──────────────────────────────────────────────

    private ModType(String id, String[] recipePrefixes, String[] blockKeyKeywords,
                    String[] blockKeyPrefixes, Supplier<IBatchDelegate> delegateFactory) {
        this.id = id;
        this.recipePrefixes = recipePrefixes;
        this.blockKeyKeywords = blockKeyKeywords;
        this.blockKeyPrefixes = blockKeyPrefixes;
        this.delegateFactory = delegateFactory;
    }

    // ── public API ────────────────────────────────────────────────

    public String id() { return id; }

    @Nullable
    public IBatchDelegate createDelegate() {
        return delegateFactory.get();
    }

    // ── registry ──────────────────────────────────────────────────

    /**
     * Register a new mod type. Call once per mod during mod construction.
     *
     * @param id               unique string ID, e.g. "blood_magic"
     * @param recipePrefixes   fully-qualified class name prefixes for {@link #classifyRecipe}
     * @param blockKeyKeywords lowercase keywords for {@link #fromBlockKey} description-ID matching
     * @param blockKeyPrefixes optional prefix patterns (e.g. "crystal_ritual")
     * @param delegateFactory  supplier for a new {@link IBatchDelegate} instance
     */
    public static ModType register(String id, String[] recipePrefixes,
                                    String[] blockKeyKeywords,
                                    String[] blockKeyPrefixes,
                                    Supplier<IBatchDelegate> delegateFactory) {
        ModType type = new ModType(id, recipePrefixes, blockKeyKeywords,
                blockKeyPrefixes, delegateFactory);
        BY_ID.put(id, type);
        return type;
    }

    public static Collection<ModType> values() {
        return Collections.unmodifiableCollection(BY_ID.values());
    }

    @Nullable
    public static ModType byId(String id) {
        return BY_ID.get(id);
    }

    // ── classification ────────────────────────────────────────────

    /**
     * Classify a recipe by iterating registered mod-type prefixes.
     * Returns null for unknown types.
     */
    @Nullable
    public static ModType classifyRecipe(Recipe<?> recipe) {
        String cn = recipe.getClass().getName();
        for (ModType mt : BY_ID.values()) {
            if (mt == GENERIC) continue;
            for (String prefix : mt.recipePrefixes) {
                if (cn.startsWith(prefix)) return mt;
            }
        }
        return null;
    }

    /**
     * Map a blockKey from {@code BindingEntry} to a ModType.
     * blockKey format: "{prefix}||block.description.id" or just "block.description.id".
     */
    @Nullable
    public static ModType fromBlockKey(@Nullable String blockKey) {
        if (blockKey == null) return null;
        String lower = blockKey.toLowerCase(Locale.ROOT);
        for (ModType mt : BY_ID.values()) {
            if (mt == GENERIC) continue;
            // 1. Explicit prefix ("goety||block.goety.dark_altar")
            for (String prefix : mt.blockKeyPrefixes) {
                if (lower.startsWith(prefix.toLowerCase(Locale.ROOT) + "||")) return mt;
            }
            // 2. Registered keywords
            for (String kw : mt.blockKeyKeywords) {
                if (lower.contains(kw.toLowerCase(Locale.ROOT))) return mt;
            }
            // 3. Fallback: check if blockKey contains the mod type id itself
            // (handles both prefixed and unprefixed bindings)
            if (lower.contains(mt.id().toLowerCase(Locale.ROOT))) return mt;
        }
        return null;
    }

    // ── helpers ───────────────────────────────────────────────────

    @Override
    public String toString() { return id; }

    /**
     * Supplier that lazily loads a delegate class via reflection to avoid
     * {@link ClassNotFoundException} when the target mod is absent.
     */
    @SuppressWarnings("unchecked")
    private static Supplier<IBatchDelegate> delegateSupplier(String className) {
        return () -> {
            try {
                Class<? extends IBatchDelegate> clazz =
                        (Class<? extends IBatchDelegate>) Class.forName(className);
                return clazz.getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                com.huanghuang.rsintegration.RSIntegrationMod.LOGGER.error(
                        "[RSI] Failed to load delegate class '{}': {}", className, e.toString());
                return null;
            }
        };
    }
}
