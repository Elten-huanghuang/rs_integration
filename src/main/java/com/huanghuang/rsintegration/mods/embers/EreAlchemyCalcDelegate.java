package com.huanghuang.rsintegration.mods.embers;

import net.minecraft.resources.ResourceLocation;

import java.util.Random;

/**
 * Core deterministic alchemy code computation.
 *
 * <p>Replicates {@code AlchemyRecipe.getCode(seed)} exactly:
 * <pre>
 *   seed = worldSeed - recipeId.toString().hashCode();  // int→long sign-extend
 *   Random rnd = new Random(seed);
 *   for (int i = 0; i &lt; inputsSize; i++)
 *       code[i] = rnd.nextInt(aspectsSize);
 * </pre>
 */
public final class EreAlchemyCalcDelegate {

    private EreAlchemyCalcDelegate() {}

    /**
     * Compute the aspect index array for one alchemy recipe.
     *
     * @param worldSeed   the dimension's world seed ({@code ServerLevel.getSeed()})
     * @param recipeId    the full recipe ID, e.g. {@code "embers:alchemy/ashen_fabric"}
     * @param aspectsSize number of aspect types in this recipe
     * @param inputsSize  number of pedestal positions (inputs) in this recipe
     * @return code[i] ∈ [0, aspectsSize), one per input position
     */
    public static int[] computeCode(long worldSeed, ResourceLocation recipeId,
                                     int aspectsSize, int inputsSize) {
        int hash = recipeId.toString().hashCode();
        long seed = worldSeed - (long) hash; // int→long sign-extend (critical)
        Random rnd = new Random(seed);
        int[] code = new int[inputsSize];
        for (int i = 0; i < inputsSize; i++) {
            code[i] = rnd.nextInt(aspectsSize);
        }
        return code;
    }
}
