package com.huanghuang.rsintegration.mods.youkaishomecoming.cuisine;

import com.huanghuang.rsintegration.RSIntegrationMod;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 统一规划和执行使用的料理材料，包含基础模型或作为基底的成品食物。 */
public final class CuisineRecipeMaterials {
    private static final String MODEL_PACKAGE =
            "dev.xkmc.youkaishomecoming.content.pot.table.item.";

    private CuisineRecipeMaterials() {}

    @SuppressWarnings("unchecked")
    public static List<Ingredient> collect(Recipe<?> recipe) {
        try {
            ResourceLocation base = (ResourceLocation) recipe.getClass().getMethod("base").invoke(recipe);
            List<Ingredient> custom = (List<Ingredient>) recipe.getClass()
                    .getMethod("getCustomIngredients").invoke(recipe);
            ClassLoader loader = recipe.getClass().getClassLoader();
            Map<?, ?> variants = (Map<?, ?>) Class.forName(MODEL_PACKAGE + "VariantTableItemBase", true, loader)
                    .getField("MAP").get(null);
            Map<?, ?> fixed = (Map<?, ?>) Class.forName(MODEL_PACKAGE + "IngredientTableItem", true, loader)
                    .getField("FIXED").get(null);
            return collect(base, custom, variants, fixed);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            RSIntegrationMod.LOGGER.warn("[RSI-Cuisine] 无法读取完整料理材料：recipe={}",
                    recipe.getId(), failure);
            return List.of();
        }
    }

    static List<Ingredient> collect(ResourceLocation base, List<Ingredient> custom,
                                    Map<?, ?> variants, Map<?, ?> fixed)
            throws ReflectiveOperationException {
        List<Ingredient> all = new ArrayList<>();
        Object variant = variants.get(base);
        Object fixedModel = fixed.get(base);
        if (variant != null) {
            variant.getClass().getMethod("collectIngredients", List.class, List.class)
                    .invoke(variant, all, new ArrayList<Ingredient>());
        } else if (fixedModel != null) {
            fixedModel.getClass().getMethod("collectIngredients", List.class).invoke(fixedModel, all);
        } else {
            // 与原生 CuisineRecipe.recreate() 一致：非模型基底按成品物品投入，
            // 例如蟹籽加州卷必须先放入一个加州卷，不能只投入蟹籽。
            Item item = BuiltInRegistries.ITEM.getOptional(base).orElse(Items.AIR);
            if (item == Items.AIR) return List.of();
            all.add(Ingredient.of(item));
        }
        if (custom != null) all.addAll(custom);
        all.removeIf(Ingredient::isEmpty);
        return List.copyOf(all);
    }
}
