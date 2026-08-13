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

/** Runtime recipes for the JEI-only Scroll Forge and Arcane Anvil categories. */
public final class IronSpellBooksRecipeCatalog {
    public static final RecipeType<IronSpellBooksRecipe> TYPE =
            RecipeType.simple(new ResourceLocation("rs_integration", "irons_spellbooks"));
    private static volatile Catalog catalog;

    private IronSpellBooksRecipeCatalog() {}

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
        try {
            List<?> leftSpells = ISpellContainer.get(left).getActiveSpells();
            List<?> rightSpells = ISpellContainer.get(right).getActiveSpells();
            if (leftSpells.size() != 1 || rightSpells.size() != 1) return false;
            Object leftSpell = leftSpells.get(0);
            Object rightSpell = rightSpells.get(0);
            AbstractSpell leftDefinition = (AbstractSpell) leftSpell.getClass()
                    .getMethod("getSpell").invoke(leftSpell);
            AbstractSpell rightDefinition = (AbstractSpell) rightSpell.getClass()
                    .getMethod("getSpell").invoke(rightSpell);
            int leftLevel = ((Number) leftSpell.getClass().getMethod("getLevel")
                    .invoke(leftSpell)).intValue();
            int rightLevel = ((Number) rightSpell.getClass().getMethod("getLevel")
                    .invoke(rightSpell)).intValue();
            return leftDefinition.getSpellId().equals(rightDefinition.getSpellId())
                    && leftLevel == rightLevel;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    public static void invalidate() {
        synchronized (IronSpellBooksRecipeCatalog.class) {
            catalog = null;
        }
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
        return new Catalog(Collections.unmodifiableMap(result), Map.copyOf(byOutput));
    }

    private static void addScrollForgeRecipes(Map<ResourceLocation, IronSpellBooksRecipe> result,
                                               Map<ResourceLocation, List<ItemStack>> focuses) {
        List<InkItem> inks = findInks();
        for (AbstractSpell spell : SpellRegistry.getEnabledSpells()) {
            if (spell == SpellRegistry.none() || !spell.allowCrafting()) continue;
            List<ItemStack> focusOptions = focuses.getOrDefault(
                    spell.getSchoolType().getId(), List.of());
            if (focusOptions.isEmpty()) continue;
            Map<Integer, InkItem> inksByLevel = new LinkedHashMap<>();
            for (InkItem ink : inks) {
                int level = spell.getMinLevelForRarity(ink.getRarity());
                if (level <= 0 || level > spell.getMaxLevel()) continue;
                InkItem canonical = InkItem.getInkForRarity(spell.getRarity(level));
                if (canonical != null) inksByLevel.putIfAbsent(level, canonical);
            }
            for (Map.Entry<Integer, InkItem> entry : inksByLevel.entrySet()) {
                int level = entry.getKey();
                ItemStack ink = new ItemStack(entry.getValue());
                ItemStack output = scroll(spell, level);
                ResourceLocation id = id("scroll_forge", spell.getSpellResource(), level, null);
                result.put(id, new IronSpellBooksRecipe(id,
                        IronSpellBooksRecipe.Machine.SCROLL_FORGE,
                        List.of(ink, new ItemStack(Items.PAPER), focusOptions.get(0)),
                        List.of(Ingredient.of(ink), Ingredient.of(Items.PAPER),
                                ingredientOf(focusOptions)), output,
                        spell.getSpellId()));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void addArcaneAnvilRecipes(Map<ResourceLocation, IronSpellBooksRecipe> result) {
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
                    result.put(id, new IronSpellBooksRecipe(id,
                            IronSpellBooksRecipe.Machine.ARCANE_ANVIL,
                            List.of(leftItems.get(0), rightItems.get(0)), outputs.get(0),
                            spell.getSpellId()));
                }
            }
        } catch (ReflectiveOperationException | LinkageError e) {
            RSIntegrationMod.LOGGER.warn(
                    "[RSI-IronSpells] Arcane Anvil recipe catalog is unavailable for this version", e);
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

    private static List<InkItem> findInks() {
        List<InkItem> result = new ArrayList<>();
        for (var item : ForgeRegistries.ITEMS.getValues()) {
            if (item instanceof InkItem ink) result.add(ink);
        }
        return result;
    }

    private static ResourceLocation id(String kind, ResourceLocation spell, int level,
                                       @Nullable ResourceLocation material) {
        String path = "irons_spellbooks/" + kind + "/" + spell.getNamespace() + "/"
                + spell.getPath() + "/" + level;
        if (material != null) path += "/" + material.getNamespace() + "/" + material.getPath();
        return new ResourceLocation("rs_integration", path);
    }

    private record Catalog(Map<ResourceLocation, IronSpellBooksRecipe> byId,
                           Map<OutputKey, IronSpellBooksRecipe> byOutput) {}

    private record OutputKey(IronSpellBooksRecipe.Machine machine, ResourceLocation itemId,
                             @Nullable net.minecraft.nbt.CompoundTag tag) {
        private static OutputKey of(IronSpellBooksRecipe.Machine machine, ItemStack stack) {
            ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(stack.getItem());
            return new OutputKey(machine, itemId, stack.hasTag() ? stack.getTag().copy() : null);
        }
    }
}
