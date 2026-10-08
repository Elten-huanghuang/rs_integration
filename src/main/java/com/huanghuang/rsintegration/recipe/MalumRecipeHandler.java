package com.huanghuang.rsintegration.recipe;
import java.lang.reflect.Method;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.util.Reflect;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import java.util.Locale;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class MalumRecipeHandler extends AbstractRecipeHandler {

    private static final String IWC_CLASS = "team.lodestar.lodestone.systems.recipe.IngredientWithCount";
    private static volatile Optional<Class<?>> iwcClassCache;

    static {
        registerRecipePrefixes(MalumRecipeHandler.class, "com.sammy.malum.");
    }

    @Override
    public ModType modType() { return ModType.byId("malum"); }

    /**
     * Spirit focusing recipes implement CraftingRecipe for JEI compatibility,
     * but their central input has Malum-specific durability semantics. The
     * generic crafting probe cannot infer those semantics from a Forge crafting
     * remainder, so this handler must win before CraftTweaker/vanilla extraction.
     */
    @Override
    public boolean preferHandlerIngredients() { return true; }

    @Override
    public boolean hasRuntimeDependentPrimaryNbt(Recipe<?> recipe) {
        Class<?> scan = recipe.getClass();
        while (scan != null && scan != Object.class) {
            try {
                Field field = scan.getDeclaredField("useNbtFromInput");
                field.setAccessible(true);
                return field.getBoolean(recipe);
            } catch (NoSuchFieldException e) {
                scan = scan.getSuperclass();
            } catch (ReflectiveOperationException e) {
                return false;
            }
        }
        return false;
    }

    @Override
    public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
        // NOTE: must NOT call RecipeIndex.tryGetResultItem here — for Malum recipes
        // that method dispatches straight back to this handler, causing infinite
        // recursion / StackOverflowError. All resolution is done inline below.

        // Malum's ILodestoneRecipe base exposes generic result methods that may return
        // a one-item display stack. Concrete recipes such as SpiritInfusionRecipe keep
        // the authoritative stack (including its batch count) in an output field, so
        // prefer that field before probing inherited methods.
        ItemStack fieldOutput = findNamedOutputField(recipe);
        if (!fieldOutput.isEmpty()) return fieldOutput;

        // Method fallback for Malum recipe types that do not expose an output field.
        // getDeclaredMethods() (not getMethod, which is no-arg only) lets us find both
        // no-arg and RegistryAccess-arg overloads, including the vanilla
        // getResultItem(m_8043_) override on mod recipes.
        for (String name : new String[]{"getResultItem", "m_8043_", "getResult", "getOutput"}) {
            for (Method m : recipe.getClass().getMethods()) {
                if (!m.getName().equals(name)) continue;
                if (!ItemStack.class.isAssignableFrom(m.getReturnType())) continue;
                try {
                    Object r;
                    if (m.getParameterCount() == 1
                            && m.getParameterTypes()[0].isAssignableFrom(RegistryAccess.class)) {
                        r = m.invoke(recipe, access);
                    } else if (m.getParameterCount() == 0) {
                        r = m.invoke(recipe);
                    } else {
                        continue;
                    }
                    if (r instanceof ItemStack s && !s.isEmpty()) return s;
                } catch (Exception e) {
                    RSIntegrationMod.LOGGER.debug("[RSI-Recipe] reflection probe failed", e);
                }
            }
        }
        return ItemStack.EMPTY;
    }

    private ItemStack findNamedOutputField(Recipe<?> recipe) {
        Class<?> scan = recipe.getClass();
        while (scan != null && scan != Object.class) {
            for (Field f : scan.getDeclaredFields()) {
                if (!ItemStack.class.isAssignableFrom(f.getType())) continue;
                String fn = f.getName().toLowerCase(Locale.ROOT);
                if (!fn.contains("output") && !fn.contains("result") && !fn.contains("assembled")) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(recipe);
                    if (v instanceof ItemStack s && !s.isEmpty()) return s.copy();
                } catch (Exception e) {
                    RSIntegrationMod.LOGGER.debug("[RSI-Recipe] reflection probe failed", e);
                }
            }
            scan = scan.getSuperclass();
        }
        return ItemStack.EMPTY;
    }

    @Nullable
    @Override
    public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        Optional<Class<?>> iwcClass = ingredientWithCountClass();
        if (iwcClass.isEmpty()) return null;

        try {
            Field ingField = iwcClass.get().getDeclaredField("ingredient");
            ingField.setAccessible(true);
            Field countField = iwcClass.get().getDeclaredField("count");
            countField.setAccessible(true);
            List<IngredientSpec> result = new ArrayList<>();
            boolean focusingRecipe = recipe.getClass().getName()
                    .endsWith(".SpiritFocusingRecipe");

            // Spirit/Focusing recipes: single "input" field. In older Malum this
            // is an IngredientWithCount; in malum 1.6.6+ SpiritFocusingRecipe.input
            // is a plain Ingredient (count 1). Handle both so the plan tree shows
            // the catalyst material, not just the spirits.
            Reflect.findField(recipe.getClass(), "input").ifPresent(inputField -> {
                inputField.setAccessible(true);
                try {
                    Object iwc = inputField.get(recipe);
                    if (iwc == null) return;
                    if (iwcClass.get().isInstance(iwc)) {
                        Ingredient ing = (Ingredient) ingField.get(iwc);
                        int count = countField.getInt(iwc);
                        if (ing != null && count > 0) result.add(new IngredientSpec(ing, count,
                                focusingRecipe ? focusingInputRole(recipe, ing) : DemandRole.CONSUMED));
                    } else if (iwc instanceof Ingredient plain && !plain.isEmpty()) {
                        result.add(new IngredientSpec(plain, 1,
                                focusingRecipe ? focusingInputRole(recipe, plain) : DemandRole.CONSUMED));
                    }
                } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
            });

            // RunicWorkbenchRecipe: primaryInput + secondaryInput (IngredientWithCount)
            for (String fn : new String[]{"primaryInput", "secondaryInput"}) {
                readIwcField(recipe, fn, iwcClass.get(), ingField, countField, result);
            }

            // extraItems list (IngredientWithCount objects)
            readIwcList(recipe, "extraItems", iwcClass.get(), ingField, countField, result);

            // spirits list — SpiritWithCount implements IRecipeComponent, NOT IngredientWithCount.
            readSpiritList(recipe, "spirits", result);

            return result.isEmpty() ? null : result;
        } catch (Exception e) {
            return null;
        }
    }

    private static Optional<Class<?>> ingredientWithCountClass() {
        Optional<Class<?>> cached = iwcClassCache;
        if (cached != null) return cached;

        synchronized (MalumRecipeHandler.class) {
            cached = iwcClassCache;
            if (cached == null) {
                try {
                    cached = Optional.of(Class.forName(IWC_CLASS, false,
                            MalumRecipeHandler.class.getClassLoader()));
                } catch (ClassNotFoundException | LinkageError ignored) {
                    cached = Optional.empty();
                }
                iwcClassCache = cached;
            }
        }
        return cached;
    }

    /**
     * Malum mutates damageable focusing inputs itself instead of exposing a
     * Forge crafting remainder. Inputs that are guaranteed to exhaust their
     * remaining durability in one operation must be supplied per operation;
     * multi-use tools remain installed and reusable by the physical worker.
     */
    public static DemandRole focusingInputRole(Object recipe, Ingredient input) {
        int durabilityCost = focusingDurabilityCost(recipe);
        if (durabilityCost <= 0) return DemandRole.CATALYST;

        ItemStack[] candidates = input == null ? new ItemStack[0] : input.getItems();
        if (candidates.length == 0) return DemandRole.CATALYST;
        for (ItemStack candidate : candidates) {
            if (candidate != null && !candidate.isEmpty() && candidate.isDamageableItem()
                    && durabilityCost >= candidate.getMaxDamage() - candidate.getDamageValue()) {
                return DemandRole.CONTAINER_RETURNING;
            }
        }
        return DemandRole.CATALYST;
    }

    private static int focusingDurabilityCost(Object recipe) {
        if (recipe == null) return 0;
        for (Class<?> scan = recipe.getClass(); scan != null && scan != Object.class;
             scan = scan.getSuperclass()) {
            try {
                Field field = scan.getDeclaredField("durabilityCost");
                field.setAccessible(true);
                return field.getInt(recipe);
            } catch (NoSuchFieldException ignored) {
                // Compatibility subclasses may inherit the Malum field.
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return 0;
            }
        }
        return 0;
    }

    @SuppressWarnings("unchecked")
    private void readIwcList(Object recipe, String fieldName, Class<?> iwcClass,
                              Field ingField, Field countField, List<IngredientSpec> out) {
        Reflect.findField(recipe.getClass(), fieldName).ifPresent(f -> {
            if (!List.class.isAssignableFrom(f.getType())) return;
            f.setAccessible(true);
            try {
                List<?> list = (List<?>) f.get(recipe);
                if (list == null) return;
                for (Object iwc : list) {
                    if (iwcClass.isInstance(iwc)) {
                        Ingredient ing = (Ingredient) ingField.get(iwc);
                        int count = countField.getInt(iwc);
                        if (ing != null && count > 0) out.add(new IngredientSpec(ing, count));
                    }
                }
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
        });
    }

    private void readIwcField(Object recipe, String fieldName, Class<?> iwcClass,
                               Field ingField, Field countField, List<IngredientSpec> out) {
        Reflect.findField(recipe.getClass(), fieldName).ifPresent(f -> {
            if (!iwcClass.isAssignableFrom(f.getType())) return;
            f.setAccessible(true);
            try {
                Object iwc = f.get(recipe);
                if (iwc != null) {
                    Ingredient ing = (Ingredient) ingField.get(iwc);
                    int count = countField.getInt(iwc);
                    if (ing != null && count > 0) out.add(new IngredientSpec(ing, count));
                }
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
        });
    }

    @SuppressWarnings("unchecked")
    private void readSpiritList(Object recipe, String fieldName, List<IngredientSpec> out) {
        Reflect.findField(recipe.getClass(), fieldName).ifPresent(f -> {
            if (!List.class.isAssignableFrom(f.getType())) return;
            f.setAccessible(true);
            try {
                List<?> list = (List<?>) f.get(recipe);
                if (list == null) return;
                for (Object swc : list) {
                    // SpiritWithCount.getItem() returns the spirit shard Item
                    Optional<Object> itemOpt = Reflect.invoke(swc, "getItem");
                    if (itemOpt.isEmpty() || !(itemOpt.get() instanceof Item it)) continue;
                    int count = Reflect.getIntField(swc, "count").orElse(1);
                    if (count > 0) out.add(new IngredientSpec(Ingredient.of(it), count));
                }
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
        });
    }
}
