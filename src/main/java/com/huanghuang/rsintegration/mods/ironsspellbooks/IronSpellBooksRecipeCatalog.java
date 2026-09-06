package com.huanghuang.rsintegration.mods.ironsspellbooks;

import com.huanghuang.rsintegration.RSIntegrationMod;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.ISpellContainer;
import io.redspace.ironsspellbooks.item.InkItem;
import io.redspace.ironsspellbooks.registries.ItemRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Runtime recipes for the JEI-only Scroll Forge and Arcane Anvil categories. */
public final class IronSpellBooksRecipeCatalog {
    public static final RecipeType<IronSpellBooksRecipe> TYPE =
            RecipeType.simple(new ResourceLocation("rs_integration", "irons_spellbooks"));
    private static volatile Catalog catalog;
    private static final Map<Class<?>, SpellEntryAccess> SPELL_ENTRY_ACCESS =
            new ConcurrentHashMap<>();
    private static final Map<Class<?>, Method> SPELL_ID_ACCESS = new ConcurrentHashMap<>();

    private IronSpellBooksRecipeCatalog() {}

    public record SpellScrollKey(ResourceLocation spellId, int level) {}

    public static Collection<IronSpellBooksRecipe> allRecipes() { return catalog().byId().values(); }

    @Nullable
    public static IronSpellBooksRecipe byId(ResourceLocation id) {
        if (!"rs_integration".equals(id.getNamespace())
                || !id.getPath().startsWith("irons_spellbooks/")) return null;
        return catalog().byId().get(id);
    }

    @Nullable
    public static IronSpellBooksRecipe recipeForTarget(IronSpellBooksRecipe.Machine machine,
                                                        ItemStack target) {
        if (target == null || target.isEmpty()) return null;
        Catalog current = catalog();
        IronSpellBooksRecipe exact = current.byOutput().get(OutputKey.of(machine, target));
        if (exact != null) return exact;
        for (IronSpellBooksRecipe recipe : current.byId().values()) {
            if (recipe.machine() == machine
                    && sameSpellScroll(recipe.getResultItem(net.minecraft.core.RegistryAccess.EMPTY), target)) {
                return recipe;
            }
        }
        return null;
    }

    /** Compares single-spell scrolls by their game semantics, not NBT numeric tag types. */
    public static boolean sameSpellScroll(ItemStack left, ItemStack right) {
        if (left == null || right == null || left.isEmpty() || right.isEmpty()
                || left.getItem() != ItemRegistry.SCROLL.get()
                || right.getItem() != ItemRegistry.SCROLL.get()) return false;
        SpellScrollKey leftKey = spellScrollKey(left);
        return leftKey != null && leftKey.equals(spellScrollKey(right));
    }

    /** Stable identity shared by native, addon and datapack spell scroll NBT. */
    @Nullable
    public static SpellScrollKey spellScrollKey(ItemStack stack) {
        if (stack == null || stack.isEmpty() || stack.getItem() != ItemRegistry.SCROLL.get()) {
            return null;
        }
        try {
            // Iron's 3.15 changed this list from SpellData to SpellSlot while
            // retaining the same erased getActiveSpells() descriptor. Avoid a
            // compile-time element cast so one build supports both layouts.
            List<?> spells = ISpellContainer.get(stack).getActiveSpells();
            if (spells.size() != 1) return null;
            return spellEntryKey(spells.get(0));
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    @Nullable
    static SpellScrollKey spellEntryKey(@Nullable Object entry) {
        if (entry == null) return null;
        try {
            SpellEntryAccess access = SPELL_ENTRY_ACCESS.computeIfAbsent(
                    entry.getClass(), IronSpellBooksRecipeCatalog::resolveSpellEntryAccess);
            Object spell = access.getSpell().invoke(entry);
            if (spell == null) return null;
            Method spellId = SPELL_ID_ACCESS.computeIfAbsent(
                    spell.getClass(), IronSpellBooksRecipeCatalog::resolveSpellIdAccess);
            Object rawId = spellId.invoke(spell);
            Object rawLevel = access.getLevel().invoke(entry);
            if (!(rawId instanceof String idText) || !(rawLevel instanceof Number number)) {
                return null;
            }
            ResourceLocation id = ResourceLocation.tryParse(idText);
            int level = number.intValue();
            return id == null || level <= 0 ? null : new SpellScrollKey(id, level);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static SpellEntryAccess resolveSpellEntryAccess(Class<?> type) {
        try {
            return new SpellEntryAccess(type.getMethod("getSpell"), type.getMethod("getLevel"));
        } catch (ReflectiveOperationException exception) {
            throw new IllegalArgumentException("unsupported Iron spell entry " + type.getName(),
                    exception);
        }
    }

    private static Method resolveSpellIdAccess(Class<?> type) {
        try {
            return type.getMethod("getSpellId");
        } catch (ReflectiveOperationException exception) {
            throw new IllegalArgumentException("unsupported Iron spell " + type.getName(), exception);
        }
    }

    public static void invalidate() {
        synchronized (IronSpellBooksRecipeCatalog.class) {
            catalog = null;
        }
    }

    public static void onSpellConfigApplied() {
        synchronized (IronSpellBooksRecipeCatalog.class) {
            // Match catalog-build -> native-initialization lock order. Never acquire
            // RecipeIndex's lock here: its worker may be waiting for this catalog.
            IronSpellRarityCache.resetAll(SpellRegistry.none(), SpellRegistry.REGISTRY.get());
            catalog = null;
            com.huanghuang.rsintegration.crafting.CraftPlanningRevision.bump();
        }
        RSIntegrationMod.LOGGER.info(
                "[RSI-IronSpells] Applied spell config: reset native rarity caches and invalidated dynamic recipes");
    }

    /** Detects spell-config changes applied after the dynamic catalog was built. */
    public static boolean hasRuntimeDrift() {
        Catalog cached = catalog;
        return cached != null && cached.runtimeFingerprint() != runtimeFingerprint();
    }

    private static Catalog catalog() {
        Catalog cached = catalog;
        if (cached != null) return cached;
        synchronized (IronSpellBooksRecipeCatalog.class) {
            cached = catalog;
            if (cached == null) catalog = cached = build();
        }
        return cached;
    }

    private static Catalog build() {
        Map<ResourceLocation, IronSpellBooksRecipe> result = new LinkedHashMap<>();
        addScrollForgeRecipes(result, findFocuses());
        addArcaneAnvilRecipes(result);
        Map<OutputKey, IronSpellBooksRecipe> byOutput = new HashMap<>();
        for (IronSpellBooksRecipe recipe : result.values()) {
            ItemStack output = recipe.getResultItem(net.minecraft.core.RegistryAccess.EMPTY);
            if (!output.isEmpty()) byOutput.putIfAbsent(OutputKey.of(recipe.machine(), output), recipe);
        }
        return new Catalog(Collections.unmodifiableMap(result), Map.copyOf(byOutput),
                runtimeFingerprint());
    }

    private static void addScrollForgeRecipes(Map<ResourceLocation, IronSpellBooksRecipe> result,
                                               Map<ResourceLocation, List<ItemStack>> focuses) {
        // Mirror Iron's own JEI maker: every registered InkItem is a valid
        // input, and its rarity determines the first scroll level. Do not
        // reverse-map a level through spell.getRarity(level); custom spells
        // and older ISB versions can have non-identical rarity curves.
        List<InkItem> inks = findInks();
        for (AbstractSpell spell : SpellRegistry.getEnabledSpells()) {
            if (spell == SpellRegistry.none() || !spell.allowCrafting()) continue;
            List<ItemStack> focusOptions = focuses.getOrDefault(
                    spell.getSchoolType().getId(), List.of());
            if (focusOptions.isEmpty()) continue;
            Map<Integer, List<InkItem>> inksByLevel = new LinkedHashMap<>();
            for (InkItem ink : inks) {
                int level = spell.getMinLevelForRarity(ink.getRarity());
                if (level <= 0 || level > spell.getMaxLevel()) continue;
                inksByLevel.computeIfAbsent(level, ignored -> new ArrayList<>()).add(ink);
            }
            for (Map.Entry<Integer, List<InkItem>> entry : inksByLevel.entrySet()) {
                int level = entry.getKey();
                List<InkItem> levelInks = entry.getValue();
                ItemStack displayInk = new ItemStack(levelInks.get(0));
                Ingredient inkIngredient = Ingredient.of(levelInks.stream()
                        .map(ItemStack::new));
                ItemStack output = scroll(spell, level);
                ResourceLocation id = id("scroll_forge", spell.getSpellResource(), level, null);
                result.put(id, new IronSpellBooksRecipe(id,
                        IronSpellBooksRecipe.Machine.SCROLL_FORGE,
                        List.of(displayInk, new ItemStack(Items.PAPER), focusOptions.get(0)),
                        List.of(inkIngredient, Ingredient.of(Items.PAPER),
                                ingredientOf(focusOptions)), output,
                        spell.getSpellId(), level));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void addArcaneAnvilRecipes(Map<ResourceLocation, IronSpellBooksRecipe> result) {
        Map<ResourceLocation, IronSpellBooksRecipe> reflected = new LinkedHashMap<>();
        boolean reflectionCompleted = false;
        try {
            Class<?> recipeClass = findClass(
                    "io.redspace.ironsspellbooks.jei.ArcaneAnvilJeiRecipe",
                    "io.redspace.ironsspellbooks.jei.ArcaneAnvilRecipe");
            Constructor<?> constructor = recipeClass.getConstructor(AbstractSpell.class, int.class);
            Method getRecipeItems = recipeClass.getMethod("getRecipeItems");
            for (AbstractSpell spell : SpellRegistry.getEnabledSpells()) {
                for (int level = spell.getMinLevel(); level < spell.getMaxLevel(); level++) {
                    Object tuple = getRecipeItems.invoke(constructor.newInstance(spell, level));
                    List<ItemStack> leftItems = tupleItems(tuple, "a");
                    List<ItemStack> rightItems = tupleItems(tuple, "b");
                    List<ItemStack> outputs = tupleItems(tuple, "c");
                    if (leftItems.isEmpty() || rightItems.isEmpty() || outputs.isEmpty()) continue;
                    ResourceLocation id = id("arcane_anvil/scroll_upgrade",
                            spell.getSpellResource(), level + 1, null);
                    reflected.put(id, new IronSpellBooksRecipe(id,
                            IronSpellBooksRecipe.Machine.ARCANE_ANVIL,
                            List.of(leftItems.get(0), rightItems.get(0)),
                            List.of(exactIngredientOf(leftItems), exactIngredientOf(rightItems)),
                            outputs.get(0),
                            spell.getSpellId(), level + 1));
                }
            }
            reflectionCompleted = true;
        } catch (ReflectiveOperationException | LinkageError e) {
            RSIntegrationMod.LOGGER.debug(
                    "[RSI-IronSpells] Arcane Anvil JEI recipe reflection unavailable; using native fallback", e);
        }
        if (reflectionCompleted) {
            result.putAll(reflected);
            return;
        }

        // Dedicated servers may not load the JEI-only recipe class at all. The
        // native rule is intentionally only a fallback: supported JEI versions
        // remain authoritative, while servers still get the same level -> rarity
        // -> ink mapping instead of an empty or partial catalog.
        RSIntegrationMod.LOGGER.warn(
                "[RSI-IronSpells] Arcane Anvil JEI recipes unavailable; generating native scroll upgrades");
        for (AbstractSpell spell : SpellRegistry.getEnabledSpells()) {
            for (int level = spell.getMinLevel(); level < spell.getMaxLevel(); level++) {
                InkItem ink = InkItem.getInkForRarity(spell.getRarity(level + 1));
                if (ink == null) continue;
                ItemStack left = scroll(spell, level);
                ItemStack right = new ItemStack(ink);
                ItemStack output = scroll(spell, level + 1);
                ResourceLocation id = id("arcane_anvil/scroll_upgrade",
                        spell.getSpellResource(), level + 1, null);
                result.put(id, new IronSpellBooksRecipe(id,
                        IronSpellBooksRecipe.Machine.ARCANE_ANVIL,
                        List.of(left, right),
                        List.of(exactIngredientOf(List.of(left)), exactIngredientOf(List.of(right))),
                        output, spell.getSpellId(), level + 1));
            }
        }
    }

    private static Class<?> findClass(String... names) throws ClassNotFoundException {
        ClassNotFoundException failure = null;
        for (String name : names) {
            try {
                return Class.forName(name);
            } catch (ClassNotFoundException e) {
                failure = e;
            }
        }
        throw failure != null ? failure : new ClassNotFoundException();
    }

    @SuppressWarnings("unchecked")
    private static List<ItemStack> tupleItems(Object tuple, String component)
            throws ReflectiveOperationException {
        Object value = tuple.getClass().getMethod(component).invoke(tuple);
        if (!(value instanceof List<?> list)) return List.of();
        List<ItemStack> result = new ArrayList<>();
        for (Object entry : list) {
            if (entry instanceof ItemStack stack && !stack.isEmpty()) result.add(stack.copy());
        }
        return result;
    }

    private static ItemStack scroll(AbstractSpell spell, int level) {
        ItemStack stack = new ItemStack(ItemRegistry.SCROLL.get());
        ISpellContainer.createScrollContainer(spell, level, stack);
        return stack;
    }

    static ItemStack scrollFor(AbstractSpell spell, int level) {
        return level <= 0 ? ItemStack.EMPTY : scroll(spell, level);
    }

    private static Map<ResourceLocation, List<ItemStack>> findFocuses() {
        Map<ResourceLocation, List<ItemStack>> focuses = new LinkedHashMap<>();
        for (AbstractSpell spell : SpellRegistry.getEnabledSpells()) {
            ResourceLocation schoolId = spell.getSchoolType().getId();
            if (focuses.containsKey(schoolId)) continue;
            List<ItemStack> matches = new ArrayList<>();
            for (var item : ForgeRegistries.ITEMS.getValues()) {
                ItemStack stack = new ItemStack(item);
                if (spell.getSchoolType().isFocus(stack)) {
                    matches.add(stack);
                }
            }
            if (!matches.isEmpty()) focuses.put(schoolId, List.copyOf(matches));
        }
        return focuses;
    }

    private static Ingredient ingredientOf(List<ItemStack> options) {
        return Ingredient.of(options.stream().map(ItemStack::copy));
    }

    private static Ingredient exactIngredientOf(List<ItemStack> options) {
        Ingredient[] alternatives = options.stream()
                .map(stack -> stack.hasTag()
                        ? (Ingredient) net.minecraftforge.common.crafting.StrictNBTIngredient.of(stack.copy())
                        : Ingredient.of(stack.copy()))
                .toArray(Ingredient[]::new);
        return alternatives.length == 1 ? alternatives[0]
                : net.minecraftforge.common.crafting.CompoundIngredient.of(alternatives);
    }

    private static List<InkItem> findInks() {
        List<InkItem> result = new ArrayList<>();
        for (var item : ForgeRegistries.ITEMS.getValues()) {
            if (item instanceof InkItem ink) result.add(ink);
        }
        return result;
    }

    private static long runtimeFingerprint() {
        List<AbstractSpell> spells = new ArrayList<>(SpellRegistry.getEnabledSpells());
        spells.sort(java.util.Comparator.comparing(spell -> spell.getSpellResource().toString()));
        List<InkItem> inks = findInks();
        inks.sort(java.util.Comparator.comparing(ink -> {
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(ink);
            return id == null ? "" : id.toString();
        }));
        long hash = 0xcbf29ce484222325L;
        for (AbstractSpell spell : spells) {
            hash = fingerprint(hash, spell.getSpellId());
            hash = fingerprint(hash, spell.allowCrafting() ? 1 : 0);
            hash = fingerprint(hash, spell.getMinLevel());
            hash = fingerprint(hash, spell.getMaxLevel());
            for (int level = spell.getMinLevel(); level <= spell.getMaxLevel(); level++) {
                hash = fingerprint(hash, spell.getRarity(level).getValue());
            }
            for (InkItem ink : inks) {
                ResourceLocation inkId = ForgeRegistries.ITEMS.getKey(ink);
                hash = fingerprint(hash, inkId == null ? "" : inkId.toString());
                hash = fingerprint(hash, spell.getMinLevelForRarity(ink.getRarity()));
            }
        }
        return hash;
    }

    private static long fingerprint(long hash, String value) {
        for (int i = 0; i < value.length(); i++) {
            hash ^= value.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static long fingerprint(long hash, int value) {
        hash ^= value;
        return hash * 0x100000001b3L;
    }

    private static ResourceLocation id(String kind, ResourceLocation spell, int level,
                                       @Nullable ResourceLocation material) {
        String path = "irons_spellbooks/" + kind + "/" + spell.getNamespace() + "/"
                + spell.getPath() + "/" + level;
        if (material != null) path += "/" + material.getNamespace() + "/" + material.getPath();
        return new ResourceLocation("rs_integration", path);
    }

    private record Catalog(Map<ResourceLocation, IronSpellBooksRecipe> byId,
                           Map<OutputKey, IronSpellBooksRecipe> byOutput,
                           long runtimeFingerprint) {}

    private record SpellEntryAccess(Method getSpell, Method getLevel) {}

    private record OutputKey(IronSpellBooksRecipe.Machine machine, ResourceLocation itemId,
                             @Nullable net.minecraft.nbt.CompoundTag tag) {
        private static OutputKey of(IronSpellBooksRecipe.Machine machine, ItemStack stack) {
            ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(stack.getItem());
            return new OutputKey(machine, itemId, stack.hasTag() ? stack.getTag().copy() : null);
        }
    }
}
