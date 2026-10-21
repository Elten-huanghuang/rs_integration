package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.CraftingResolver;
import com.huanghuang.rsintegration.crafting.MaterialLocks;
import com.huanghuang.rsintegration.crafting.plan.RecipeChoice;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** 服务端规划使用的不可变持久偏好快照。 */
public record PlanningPreferences(
        Map<String, RecipeChoice> recipeChoices,
        Map<String, ItemStack> ingredientChoices,
        Set<ResourceLocation> excludedRecipes) {

    public static final int MAX_RECIPE_CHOICES = 4_096;
    public static final int MAX_INGREDIENT_CHOICES = 4_096;
    public static final int MAX_EXCLUDED_RECIPES = 4_096;

    private static final PlanningPreferences EMPTY =
            new PlanningPreferences(Map.of(), Map.of(), Set.of());

    public PlanningPreferences {
        recipeChoices = immutableRecipeChoices(recipeChoices);
        ingredientChoices = immutableIngredientChoices(ingredientChoices);
        excludedRecipes = excludedRecipes == null ? Set.of() : Set.copyOf(excludedRecipes);
        if (excludedRecipes.size() > MAX_EXCLUDED_RECIPES) {
            throw new IllegalArgumentException("too many excluded recipes");
        }
    }

    public static PlanningPreferences empty() {
        return EMPTY;
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(recipeChoices.size());
        recipeChoices.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    buf.writeUtf(entry.getKey());
                    buf.writeUtf(entry.getValue().serialize());
                });
        buf.writeVarInt(ingredientChoices.size());
        ingredientChoices.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    buf.writeUtf(entry.getKey(), MaterialLocks.MAX_KEY_LENGTH);
                    buf.writeItem(entry.getValue().copyWithCount(1));
                });
        buf.writeVarInt(excludedRecipes.size());
        excludedRecipes.stream()
                .sorted(Comparator.comparing(ResourceLocation::toString))
                .forEach(buf::writeResourceLocation);
    }

    public static PlanningPreferences read(FriendlyByteBuf buf) {
        int recipeCount = buf.readVarInt();
        if (recipeCount < 0 || recipeCount > MAX_RECIPE_CHOICES) {
            throw new IllegalArgumentException("recipe choice count out of range");
        }
        Map<String, RecipeChoice> recipes = new LinkedHashMap<>();
        for (int i = 0; i < recipeCount; i++) {
            String key = buf.readUtf();
            RecipeChoice choice = RecipeChoice.parse(buf.readUtf());
            if (choice != null && ResourceLocation.tryParse(key) != null) {
                recipes.put(key, choice);
            }
        }
        int ingredientCount = buf.readVarInt();
        if (ingredientCount < 0 || ingredientCount > MAX_INGREDIENT_CHOICES) {
            throw new IllegalArgumentException("ingredient choice count out of range");
        }
        Map<String, ItemStack> ingredients = new LinkedHashMap<>();
        for (int i = 0; i < ingredientCount; i++) {
            String key = buf.readUtf(MaterialLocks.MAX_KEY_LENGTH);
            ItemStack stack = buf.readItem();
            if (!key.isBlank() && !stack.isEmpty()) ingredients.put(key, stack.copyWithCount(1));
        }
        int excludedCount = buf.readVarInt();
        if (excludedCount < 0 || excludedCount > MAX_EXCLUDED_RECIPES) {
            throw new IllegalArgumentException("excluded recipe count out of range");
        }
        Set<ResourceLocation> excluded = new LinkedHashSet<>();
        for (int i = 0; i < excludedCount; i++) {
            excluded.add(buf.readResourceLocation());
        }
        return new PlanningPreferences(recipes, ingredients, excluded);
    }

    public boolean isEmpty() {
        return recipeChoices.isEmpty() && ingredientChoices.isEmpty() && excludedRecipes.isEmpty();
    }

    @Nullable
    public RecipeChoice choiceFor(ItemStack output) {
        if (output == null || output.isEmpty()) return null;
        ResourceLocation exact = CraftingResolver.preferenceKey(output);
        RecipeChoice choice = exact == null ? null : recipeChoices.get(exact.toString());
        if (choice != null) return choice;
        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(output.getItem());
        return itemId == null ? null : recipeChoices.get(itemId.toString());
    }

    @Nullable
    public RecipeChoice choiceFor(ImmutableRecipeGraph.MaterialRef output) {
        if (output == null) return null;
        RecipeChoice exact = recipeChoices.get(
                ForcedRecipeGraphProjector.preferenceKey(output).toString());
        return exact != null ? exact : recipeChoices.get(output.itemId().toString());
    }

    public boolean isNoRecipe(ItemStack output) {
        return choiceFor(output) == RecipeChoice.NoRecipe.INSTANCE;
    }

    public boolean isNoRecipe(ImmutableRecipeGraph.MaterialRef output) {
        return choiceFor(output) == RecipeChoice.NoRecipe.INSTANCE;
    }

    @Nullable
    public ResourceLocation preferredRecipe(ItemStack output) {
        RecipeChoice choice = choiceFor(output);
        return choice instanceof RecipeChoice.Normal normal ? normal.recipeId() : null;
    }

    @Nullable
    public ResourceLocation preferredRecipe(ImmutableRecipeGraph.MaterialRef output) {
        RecipeChoice choice = choiceFor(output);
        return choice instanceof RecipeChoice.Normal normal ? normal.recipeId() : null;
    }

    public boolean excludes(ResourceLocation recipeId) {
        return recipeId != null && excludedRecipes.contains(recipeId);
    }

    @Nullable
    public ItemStack ingredientChoice(String key) {
        if (key == null || key.isEmpty()) return null;
        ItemStack value = ingredientChoices.get(key);
        return value == null || value.isEmpty() ? null : value.copyWithCount(1);
    }

    private static Map<String, RecipeChoice> immutableRecipeChoices(
            @Nullable Map<String, RecipeChoice> values) {
        if (values == null || values.isEmpty()) return Map.of();
        if (values.size() > MAX_RECIPE_CHOICES) {
            throw new IllegalArgumentException("too many recipe choices");
        }
        Map<String, RecipeChoice> copy = new LinkedHashMap<>();
        for (Map.Entry<String, RecipeChoice> entry : values.entrySet()) {
            String key = entry.getKey();
            RecipeChoice value = entry.getValue();
            if (key == null || key.isBlank() || value == null
                    || ResourceLocation.tryParse(key) == null) continue;
            copy.put(key, value);
        }
        return Map.copyOf(copy);
    }

    private static Map<String, ItemStack> immutableIngredientChoices(
            @Nullable Map<String, ItemStack> values) {
        if (values == null || values.isEmpty()) return Map.of();
        if (values.size() > MAX_INGREDIENT_CHOICES) {
            throw new IllegalArgumentException("too many ingredient choices");
        }
        Map<String, ItemStack> copy = new LinkedHashMap<>();
        for (Map.Entry<String, ItemStack> entry : values.entrySet()) {
            String key = entry.getKey();
            ItemStack value = entry.getValue();
            if (key == null || key.isBlank() || value == null || value.isEmpty()) continue;
            copy.put(key, value.copyWithCount(1));
        }
        return Map.copyOf(copy);
    }
}
