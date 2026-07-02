package com.huanghuang.rsintegration;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.util.ModIds;
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
    @Nullable private final Supplier<IBatchDelegate> inferDelegateFactory;

    // ── built-in constants (registered in static {}) ──────────────

    public static final ModType GENERIC;
    public static final ModType CUSTOM_GUI;
    public static final ModType FARMINGFORBLOCKHEADS_MARKET;

    static {
        GENERIC = register("generic",
                new String[0], new String[0], new String[0],
                com.huanghuang.rsintegration.crafting.batch.GenericBatchDelegate::new);

        CUSTOM_GUI = register("custom_gui",
                new String[0],
                new String[0],
                new String[0],
                com.huanghuang.rsintegration.crafting.batch.GenericBatchDelegate::new);

        FARMINGFORBLOCKHEADS_MARKET = register("farmingforblockheads",
                new String[]{"com.huanghuang.rsintegration.crafting.MarketRecipeWrapper"},
                new String[]{"market", "farmingforblockheads"},
                new String[]{"market"},
                delegateSupplier("com.huanghuang.rsintegration.mods.farmingforblockheads.MarketBatchDelegate"));
    }

    // ── constructors ──────────────────────────────────────────────

    private ModType(String id, String[] recipePrefixes, String[] blockKeyKeywords,
                    String[] blockKeyPrefixes, Supplier<IBatchDelegate> delegateFactory,
                    @Nullable Supplier<IBatchDelegate> inferDelegateFactory) {
        this.id = id;
        this.recipePrefixes = recipePrefixes;
        this.blockKeyKeywords = blockKeyKeywords;
        this.blockKeyPrefixes = blockKeyPrefixes;
        this.delegateFactory = delegateFactory;
        this.inferDelegateFactory = inferDelegateFactory;
    }

    // ── public API ────────────────────────────────────────────────

    public String id() { return id; }

    @Nullable
    public IBatchDelegate createDelegate() {
        return delegateFactory.get();
    }

    /** Create the infer-mode delegate (Mode 1 trial-and-error), if available. */
    @Nullable
    public IBatchDelegate createInferDelegate() {
        if (inferDelegateFactory != null) return inferDelegateFactory.get();
        return createDelegate();
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
        return register(id, recipePrefixes, blockKeyKeywords, blockKeyPrefixes,
                delegateFactory, null);
    }

    public static ModType register(String id, String[] recipePrefixes,
                                    String[] blockKeyKeywords,
                                    String[] blockKeyPrefixes,
                                    Supplier<IBatchDelegate> delegateFactory,
                                    @Nullable Supplier<IBatchDelegate> inferDelegateFactory) {
        ModType type = new ModType(id, recipePrefixes, blockKeyKeywords,
                blockKeyPrefixes, delegateFactory, inferDelegateFactory);
        BY_ID.put(id, type);
        return type;
    }

    public static Collection<ModType> values() {
        return Collections.unmodifiableCollection(BY_ID.values());
    }

    /** Get a registered ModType by id. Never returns null — falls back to GENERIC. */
    public static ModType byId(String id) {
        return BY_ID.getOrDefault(id, GENERIC);
    }

    // ── classification ────────────────────────────────────────────

    /**
     * Classify a recipe using longest-prefix matching across all registered
     * mod types. Longer prefixes take priority (e.g. SpiritFocusingRecipe
     * matches malum_spirit_crucible before malum).
     * Returns null for unknown types.
     */
    @Nullable
    public static ModType classifyRecipe(Recipe<?> recipe) {
        if (recipe instanceof com.huanghuang.rsintegration.mods.forbidden.FaRitualWrapper) {
            return byId(ModIds.FORBIDDEN_ARCANUS);
        }
        String cn = recipe.getClass().getName();
        // ApplyModifierRecipe is a smithing-table recipe, not a Hephaestus Forge ritual
        if (cn.endsWith("ApplyModifierRecipe")) return null;
        ModType best = null;
        int bestLen = 0;
        for (ModType mt : BY_ID.values()) {
            if (mt == GENERIC) continue;
            for (String prefix : mt.recipePrefixes) {
                if (cn.startsWith(prefix) && prefix.length() > bestLen) {
                    best = mt;
                    bestLen = prefix.length();
                }
            }
        }
        return best;
    }

    /**
     * Map a blockKey from {@code BindingEntry} to a ModType using longest-prefix
     * matching. blockKey format: "{prefix}||block.description.id" or just
     * "block.description.id". Explicit prefix matches are checked first and the
     * longest wins; segment keyword matching is used as fallback.
     */
    @Nullable
    public static ModType fromBlockKey(@Nullable String blockKey) {
        if (blockKey == null) return null;
        String lower = blockKey.toLowerCase(Locale.ROOT);

        // 1. Longest explicit prefix match
        ModType best = null;
        int bestLen = 0;
        for (ModType mt : BY_ID.values()) {
            if (mt == GENERIC) continue;
            for (String prefix : mt.blockKeyPrefixes) {
                String prefixed = prefix.toLowerCase(Locale.ROOT) + "||";
                if (lower.startsWith(prefixed) && prefixed.length() > bestLen) {
                    best = mt;
                    bestLen = prefixed.length();
                }
            }
        }
        if (best != null) return best;

        // 2. Segment keyword matching — longest keyword wins
        best = null;
        bestLen = 0;
        for (ModType mt : BY_ID.values()) {
            if (mt == GENERIC) continue;
            for (String kw : mt.blockKeyKeywords) {
                String kwLower = kw.toLowerCase(Locale.ROOT);
                if (containsSegment(lower, kwLower) && kwLower.length() > bestLen) {
                    best = mt;
                    bestLen = kwLower.length();
                }
            }
            // 3. Fallback: mod type id as segment
            String idLower = mt.id().toLowerCase(Locale.ROOT);
            if (containsSegment(lower, idLower) && idLower.length() > bestLen) {
                best = mt;
                bestLen = idLower.length();
            }
        }
        if (best != null) return best;

        // 4. Config-driven: check customGuiMachineMods list
        for (String modId : RSIntegrationConfig.CUSTOM_GUI_MACHINE_MODS.get()) {
            if (containsSegment(lower, modId.toLowerCase(Locale.ROOT))) return byId("custom_gui");
        }
        return null;
    }

    /** Check whether {@code segment} appears in {@code lower} bounded by
     *  dots, underscores, pipes, or string edges.  Avoids false matches
     *  like "goety" matching "some_mod.goety_stone". */
    private static boolean containsSegment(String lower, String segment) {
        int idx = lower.indexOf(segment);
        while (idx >= 0) {
            char leftChar = idx == 0 ? 0 : lower.charAt(idx - 1);
            boolean leftOk = idx == 0 || leftChar == '.' || leftChar == '_' || leftChar == '|';
            int end = idx + segment.length();
            char rightChar = end == lower.length() ? 0 : lower.charAt(end);
            boolean rightOk = end == lower.length() || rightChar == '.' || rightChar == '_' || rightChar == '|';
            if (leftOk && rightOk) return true;
            idx = lower.indexOf(segment, idx + 1);
        }
        return false;
    }

    // ── helpers ───────────────────────────────────────────────────

    @Override
    public String toString() { return id; }

    /**
    /**
     * Supplier that lazily loads a delegate class via reflection to avoid
     * {@link ClassNotFoundException} when the target mod is absent.
     * Shared lazy delegate supplier. Public so RSModules can use it.
     */
    @SuppressWarnings("unchecked")
    public static Supplier<IBatchDelegate> delegateSupplier(String className) {
        return () -> {
            try {
                Class<? extends IBatchDelegate> clazz =
                        (Class<? extends IBatchDelegate>) Class.forName(className);
                return clazz.getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.error(
                        "[RSI] Failed to load delegate class '{}': {}", className, e.toString());
                return null;
            }
        };
    }
}
