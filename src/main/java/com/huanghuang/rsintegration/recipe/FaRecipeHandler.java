package com.huanghuang.rsintegration.recipe;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.mods.forbidden.FaRitualWrapper;
import com.huanghuang.rsintegration.util.Reflect;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class FaRecipeHandler extends AbstractRecipeHandler {

    @Override
    public ModType modType() { return ModType.byId("forbidden_arcanus"); }

    @Override
    public boolean canHandle(Recipe<?> recipe) {
        if (recipe instanceof FaRitualWrapper) return true;
        return recipe.getClass().getName().startsWith("com.stal111.forbidden_arcanus.");
    }

    @Override
    public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
        if (recipe instanceof FaRitualWrapper w) return w.getResultItem(access);
        ItemStack result = recipe.getResultItem(access);
        if (result != null && !result.isEmpty()) return result;

        // ApplyModifierRecipe: output depends on the input base item (slot 1).
        // The virtual-container assemble fallback below would stuff a template
        // ingredient into the base slot and produce a garbage result (template
        // item with modifier applied).  Instead, produce a representative
        // preview by applying the modifier to the first valid base item.
        if (recipe.getClass().getSimpleName().equals("ApplyModifierRecipe")) {
            return buildModifierPreview(recipe);
        }

        // ApplyModifierRecipe et al. return EMPTY from getResultItem() because
        // the output depends on the input base item.  Try to assemble via a
        // virtual container populated with the recipe's own ingredients.
        Object target = recipe instanceof FaRitualWrapper w ? w.ritual() : recipe;
        List<IngredientSpec> specs = collectIngredients(target, recipe);
        if (specs.isEmpty()) return ItemStack.EMPTY;

        SimpleContainer container = new SimpleContainer(3);
        for (IngredientSpec spec : specs) {
            ItemStack[] items = spec.ingredient().getItems();
            if (items.length > 0) {
                // Fill slots 0..2 — SmithingRecipe uses template(0), base(1), addition(2)
                for (int s = 0; s < 3 && s < items.length; s++) {
                    if (container.getItem(s).isEmpty()) {
                        container.setItem(s, items[s].copy());
                    }
                }
            }
        }
        // Ensure at least template (slot 0) and addition (slot 2) are populated
        if (container.getItem(0).isEmpty() && specs.size() > 0) {
            ItemStack[] items = specs.get(0).ingredient().getItems();
            if (items.length > 0) container.setItem(0, items[0].copy());
        }
        if (container.getItem(2).isEmpty() && specs.size() > 1) {
            ItemStack[] items = specs.get(1).ingredient().getItems();
            if (items.length > 0) container.setItem(2, items[0].copy());
        }
        // Use template item as stand-in base if slot 1 is empty
        if (container.getItem(1).isEmpty()) {
            container.setItem(1, container.getItem(0).copy());
        }

        try {
            java.lang.reflect.Method assemble = Reflect.findMethod(recipe.getClass(),
                    "assemble", new Class<?>[]{net.minecraft.world.Container.class, RegistryAccess.class});
            if (assemble == null) {
                assemble = Reflect.findMethod(recipe.getClass(),
                        "m_5874_", new Class<?>[]{net.minecraft.world.Container.class, RegistryAccess.class});
            }
            if (assemble != null) {
                Object assembled = assemble.invoke(recipe, container, access);
                if (assembled instanceof ItemStack st && !st.isEmpty()) return st;
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-FaHandler] assemble failed", e);
        }
        return ItemStack.EMPTY;
    }

    @Nullable
    @Override
    @SuppressWarnings("unchecked")
    public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        Object target = recipe instanceof FaRitualWrapper w ? w.ritual() : recipe;
        List<IngredientSpec> specs = collectIngredients(target, recipe);
        return specs.isEmpty() ? null : specs;
    }

    private List<IngredientSpec> collectIngredients(Object target, Recipe<?> recipe) {
        List<IngredientSpec> specs = new ArrayList<>();

        // mainIngredient field (Hephaestus Forge wrapper recipes)
        Reflect.findField(target.getClass(), "mainIngredient").ifPresent(f -> {
            if (!Ingredient.class.isAssignableFrom(f.getType())) return;
            f.setAccessible(true);
            try {
                Ingredient ing = (Ingredient) f.get(target);
                if (ing != null && !ing.isEmpty()) specs.add(new IngredientSpec(ing, 1));
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
        });

        // inputs field — list of objects with ingredient + amount (Hephaestus Forge)
        Reflect.findField(target.getClass(), "inputs").ifPresent(f -> {
            if (!List.class.isAssignableFrom(f.getType())) return;
            f.setAccessible(true);
            try {
                List<?> inputs = (List<?>) f.get(target);
                if (inputs == null) return;
                for (Object ri : inputs) {
                    Optional<Field> ingField = Reflect.findField(ri.getClass(), "ingredient");
                    Optional<Field> amtField = Reflect.findField(ri.getClass(), "amount");
                    if (ingField.isEmpty() || !Ingredient.class.isAssignableFrom(ingField.get().getType())) continue;
                    ingField.get().setAccessible(true);
                    Ingredient ing = (Ingredient) ingField.get().get(ri);
                    if (ing == null || ing.isEmpty()) continue;
                    int amt = 1;
                    if (amtField.isPresent()) {
                        amtField.get().setAccessible(true);
                        amt = Math.max(1, amtField.get().getInt(ri));
                    }
                    specs.add(new IngredientSpec(ing, amt));
                }
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
        });

        // ApplyModifierRecipe — has getTemplate() + getAddition() (not on SmithingRecipe
        // interface in 1.20.1, must reflect the concrete class)
        if (specs.isEmpty() && recipe.getClass().getName().startsWith("com.stal111.forbidden_arcanus.")) {
            try {
                java.lang.reflect.Method getTemplate = Reflect.findMethod(
                        recipe.getClass(), "getTemplate", new Class<?>[0]);
                if (getTemplate != null) {
                    Ingredient tmpl = (Ingredient) getTemplate.invoke(recipe);
                    if (tmpl != null && !tmpl.isEmpty()) specs.add(new IngredientSpec(tmpl, 1));
                }
                java.lang.reflect.Method getAddition = Reflect.findMethod(
                        recipe.getClass(), "getAddition", new Class<?>[0]);
                if (getAddition != null) {
                    Ingredient add = (Ingredient) getAddition.invoke(recipe);
                    if (add != null && !add.isEmpty()) specs.add(new IngredientSpec(add, 1));
                }
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Smithing ingredient extraction failed", e); }
        }

        // Fallback: for non-FaRitualWrapper recipes, try recipe.getIngredients()
        if (specs.isEmpty() && !(recipe instanceof FaRitualWrapper)) {
            var ingredients = recipe.getIngredients();
            for (Ingredient ing : ingredients) {
                if (!ing.isEmpty()) {
                    specs.add(new IngredientSpec(ing, 1));
                }
            }
        }

        return specs;
    }

    /**
     * Build a representative preview output for {@code ApplyModifierRecipe}
     * by applying the modifier to the first valid base item.  Uses reflection
     * to avoid compile-time dependency on Forbidden & Arcanus.
     */
    private static ItemStack buildModifierPreview(Recipe<?> recipe) {
        try {
            java.lang.reflect.Method getModifier = Reflect.findMethod(
                    recipe.getClass(), "getModifier", new Class<?>[0]);
            if (getModifier == null) return ItemStack.EMPTY;
            Object modifier = getModifier.invoke(recipe);
            if (modifier == null) return ItemStack.EMPTY;

            java.lang.reflect.Method getValidItems = Reflect.findMethod(
                    modifier.getClass(), "getValidItems", new Class<?>[0]);
            if (getValidItems == null) return ItemStack.EMPTY;
            @SuppressWarnings("unchecked")
            List<ItemStack> valid = (List<ItemStack>) getValidItems.invoke(modifier);
            if (valid == null || valid.isEmpty()) return ItemStack.EMPTY;

            // Prefer a vanilla item as the preview representative so the
            // plan doesn't show an arbitrary mod item (e.g. "candy cane sword").
            ItemStack preview = null;
            for (ItemStack v : valid) {
                if (v.isEmpty()) continue;
                if (preview == null) preview = v;
                if (net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(v.getItem()).getNamespace().equals("minecraft")) {
                    preview = v;
                    break;
                }
            }
            if (preview == null) return ItemStack.EMPTY;
            preview = preview.copy();

            // ModifierHelper.setModifier(preview, modifier)
            // The method signature is setModifier(ItemStack, Modifier) — not
            // the concrete subclass. Find it by name + param count to avoid
            // NoSuchMethodException from getMethod() exact-type matching.
            Class<?> helperClass = Class.forName(
                    "com.stal111.forbidden_arcanus.common.item.modifier.ModifierHelper");
            java.lang.reflect.Method setModifier = null;
            for (java.lang.reflect.Method m : helperClass.getMethods()) {
                if (m.getName().equals("setModifier")
                        && m.getParameterCount() == 2
                        && m.getParameterTypes()[0].isAssignableFrom(ItemStack.class)) {
                    setModifier = m;
                    break;
                }
            }
            if (setModifier == null) return ItemStack.EMPTY;
            setModifier.invoke(null, preview, modifier);

            return preview;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-FaHandler] buildModifierPreview failed", e);
            return ItemStack.EMPTY;
        }
    }
}
