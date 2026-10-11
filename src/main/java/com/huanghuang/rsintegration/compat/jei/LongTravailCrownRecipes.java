package com.huanghuang.rsintegration.compat.jei;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;

/** 春之冠的真实配方 ID 与 JEI 合并展示 ID。 */
public final class LongTravailCrownRecipes {
    private static final String MOD_ID = "the_long_travail";
    private static final String DISPLAY_PREFIX = "jei/spring_game/";
    private static final String RECIPE_PREFIX = "spring_game/";

    private LongTravailCrownRecipes() {}

    public static boolean isDisplayId(@Nullable ResourceLocation id) {
        return id != null && MOD_ID.equals(id.getNamespace())
                && id.getPath().startsWith(DISPLAY_PREFIX);
    }

    public static boolean isRecipeId(@Nullable ResourceLocation id) {
        return id != null && MOD_ID.equals(id.getNamespace())
                && id.getPath().startsWith(RECIPE_PREFIX);
    }

    @Nullable
    public static ResourceLocation forFlowerItem(@Nullable ResourceLocation itemId) {
        if (itemId == null || !"minecraft".equals(itemId.getNamespace())) return null;
        return new ResourceLocation(MOD_ID, RECIPE_PREFIX + itemId.getPath());
    }

    /** 仅花冠根配方允许把另一朵花的 NBT 产物列为切换候选。 */
    public static boolean isVariantAlternative(ResourceLocation currentId,
                                               ResourceLocation candidateId,
                                               ItemStack currentOutput,
                                               ItemStack candidateOutput) {
        return isRecipeId(currentId) && isRecipeId(candidateId)
                && !currentId.equals(candidateId)
                && !currentOutput.isEmpty() && !candidateOutput.isEmpty()
                && currentOutput.hasTag() && candidateOutput.hasTag()
                && ItemStack.isSameItem(currentOutput, candidateOutput);
    }

    /** 配方树换配方后，不能继续使用上一个花冠的 NBT 作为目标产物。 */
    public static ItemStack targetForSelectedRecipe(ResourceLocation recipeId,
                                                    @Nullable ItemStack recipeOutput,
                                                    ItemStack previousTarget) {
        if (isRecipeId(recipeId) && recipeOutput != null && !recipeOutput.isEmpty()) {
            return recipeOutput.copy();
        }
        return previousTarget;
    }
}
