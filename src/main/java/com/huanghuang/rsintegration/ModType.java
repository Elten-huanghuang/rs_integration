package com.huanghuang.rsintegration;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.batch.GenericBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.mods.forbidden.FaRitualWrapper;
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

    private final String id;
    private final String[] recipePrefixes;
    private final String[] blockKeyKeywords;
    private final String[] blockKeyPrefixes;
    private final Supplier<IBatchDelegate> delegateFactory;
    @Nullable private final Supplier<IBatchDelegate> inferDelegateFactory;

    // JEI integration — set via configureJei() after register()
    // uidToFilter: [[jeiUid, filterString], ...] — filterString is what getBindingFilter returns
    // recipePrefixes: [[classPrefix, filterString], ...] — single-element [prefix] defaults to id
    @Nullable private String[][] jeiUidToFilter;
    @Nullable private String[][] jeiRecipePrefixes;
    @Nullable private String jeiTooltipKey;


    public static final ModType GENERIC;
    public static final ModType CUSTOM_GUI;
    public static final ModType FARMINGFORBLOCKHEADS_MARKET;

    static {
        GENERIC = register("generic",
                new String[0], new String[0], new String[0],
                GenericBatchDelegate::new);

        CUSTOM_GUI = register("custom_gui",
                new String[0],
                new String[0],
                new String[0],
                GenericBatchDelegate::new);

        FARMINGFORBLOCKHEADS_MARKET = register("farmingforblockheads",
                new String[]{"com.huanghuang.rsintegration.mods.farmingforblockheads.MarketRecipeWrapper"},
                new String[]{"market", "farmingforblockheads"},
                new String[]{"market"},
                delegateSupplier("com.huanghuang.rsintegration.mods.farmingforblockheads.MarketBatchDelegate"));
        configureJei("farmingforblockheads",
                new String[][]{{"farmingforblockheads:market"}},
                new String[][]{{"net.blay09.mods.farmingforblockheads.", "farmingforblockheads"}},
                "gui.rs_integration.jei.market_craft");
    }

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

    /** Call after {@link #register} to link JEI category UIDs and tooltip key.
     *  @param jeiUidToFilter [jeiUid, filterString] pairs — filterString is what
     *                         {@code getBindingFilter} returns.  When the array is
     *                         a single-element {@code [uid]}, filter defaults to
     *                         the mod type's {@code id}.
     *  @param jeiRecipePrefixes [classPrefix, filterString] pairs — filterString is
     *                         what {@code getBindingFilter} returns.  When the array
     *                         is a single-element {@code [prefix]}, filter defaults
     *                         to the mod type's {@code id}. */
    public static void configureJei(String id, @Nullable String[][] jeiUidToFilter,
                                    @Nullable String[][] jeiRecipePrefixes,
                                    @Nullable String jeiTooltipKey) {
        ModType mt = byId(id);
        if (mt == GENERIC) return;
        mt.jeiUidToFilter = jeiUidToFilter;
        mt.jeiRecipePrefixes = jeiRecipePrefixes;
        mt.jeiTooltipKey = jeiTooltipKey;
    }

    /** Returns the tooltip key for this mod type's JEI button, or null. */
    @Nullable
    public String jeiTooltipKey() { return jeiTooltipKey; }

    /** Look up a JEI category UID and return the corresponding filter string
     *  for {@code getBindingFilter}, or null if no match. */
    @Nullable
    public static String filterForJeiUid(String uid) {
        for (ModType mt : BY_ID.values()) {
            if (mt.jeiUidToFilter != null) {
                for (String[] pair : mt.jeiUidToFilter) {
                    if (pair[0].equals(uid)) return pair.length >= 2 ? pair[1] : mt.id;
                }
            }
        }
        return null;
    }

    /** Find a ModType by recipe class name prefix (longest-match). Returns null if no match. */
    @Nullable
    public static ModType findByRecipeClass(String className) {
        ModType best = null;
        int bestLen = 0;
        for (ModType mt : BY_ID.values()) {
            if (mt.jeiRecipePrefixes != null) {
                for (String[] pair : mt.jeiRecipePrefixes) {
                    String prefix = pair[0];
                    if (className.startsWith(prefix) && prefix.length() > bestLen) {
                        best = mt;
                        bestLen = prefix.length();
                    }
                }
            }
        }
        return best;
    }

    /** Find the binding filter string by recipe class name prefix (longest-match).
     *  Returns null if no match.  Mirrors {@link #filterForJeiUid} for class-name fallback. */
    @Nullable
    public static String filterForRecipeClass(String className) {
        String best = null;
        int bestLen = 0;
        for (ModType mt : BY_ID.values()) {
            if (mt.jeiRecipePrefixes != null) {
                for (String[] pair : mt.jeiRecipePrefixes) {
                    String prefix = pair[0];
                    if (className.startsWith(prefix) && prefix.length() > bestLen) {
                        best = pair.length >= 2 ? pair[1] : mt.id;
                        bestLen = prefix.length();
                    }
                }
            }
        }
        return best;
    }

    /**
     * Classify a recipe using longest-prefix matching across all registered
     * mod types. Longer prefixes take priority (e.g. SpiritFocusingRecipe
     * matches malum_spirit_crucible before malum).
     * Returns null for unknown types.
     */
    @Nullable
    public static ModType classifyRecipe(Recipe<?> recipe) {
        if (recipe instanceof FaRitualWrapper) {
            return byId(ModIds.FORBIDDEN_ARCANUS);
        }
        String cn = recipe.getClass().getName();
        // ApplyModifierRecipe is a smithing-table recipe, not a Hephaestus Forge ritual
        if (cn.endsWith("ApplyModifierRecipe")) return null;

        // Cooking pot recipes: distinguish by result's crafting remainder (the bowl/pot type)
        if (cn.startsWith("dev.xkmc.youkaishomecoming.content.pot.cooking.")) {
            return classifyCookingPotRecipe(recipe);
        }

        ModType best = null;
        int bestLen = 0;
        for (ModType mt : BY_ID.values()) {
            if (mt == GENERIC) continue;
            for (String prefix : mt.recipePrefixes) {
                if (cn.startsWith(prefix) && prefix.length() >= bestLen) {
                    best = mt;
                    bestLen = prefix.length();
                }
            }
        }
        return best;
    }

    /**
     * Classify a YHK cooking pot recipe by the pot/bowl type its result
     * needs as a container (stored as {@code getCraftingRemainingItem}).
     * Falls back to {@code youkaishomecoming_cooking_small} if undetermined.
     */
    @Nullable
    private static ModType classifyCookingPotRecipe(Recipe<?> recipe) {
        try {
            java.lang.reflect.Method getResult = recipe.getClass().getMethod("getResult");
            net.minecraft.world.item.ItemStack result =
                    (net.minecraft.world.item.ItemStack) getResult.invoke(recipe);
            if (!result.isEmpty() && result.hasCraftingRemainingItem()) {
                net.minecraft.world.item.ItemStack container = result.getCraftingRemainingItem();
                net.minecraft.resources.ResourceLocation key =
                        net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(
                                container.getItem());
                String k = key.toString();
                // IRON_BOWL  → small_iron_pot   (SmallCookingPotBlockEntity)
                // IRON_POT   → short_iron_pot   (MidCookingPotBlockEntity)
                // STOCKPOT   → stockpot         (LargeCookingPotBlockEntity)
                if ("youkaishomecoming:short_iron_pot".equals(k))
                    return byId("youkaishomecoming_cooking_short");
                if ("youkaishomecoming:stockpot".equals(k))
                    return byId("youkaishomecoming_cooking_large");
                if ("youkaishomecoming:small_iron_pot".equals(k))
                    return byId("youkaishomecoming_cooking_small");
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI] Cooking pot recipe classification failed", e);
        }
        return byId("youkaishomecoming_cooking_small");
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

        // 1. Longest explicit prefix match (longest prefix wins; ties
        //    go to the first match — iteration order is not deterministic
        //    so tying prefixes must be avoided by using distinct names).
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

        // 2. Segment keyword matching — longest keyword wins.
        //    Use > (not >=) so that when two keywords have the same
        //    length the first match sticks; HashMap iteration order is
        //    non-deterministic and with >= the later entry silently
        //    steals the match (e.g. aether "altar" stealing malum||
        //    spirit_altar from the malum ModType).
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

    @Override
    public String toString() { return id; }

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
                        "[RSI] Failed to load delegate class '{}'", className, e);
                return null;
            }
        };
    }
}
