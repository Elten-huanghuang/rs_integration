package com.huanghuang.rsintegration.reflection.probes;

import com.huanghuang.rsintegration.reflection.contract.ContractValidation;
import com.huanghuang.rsintegration.reflection.contract.ReflectionContract;
import com.huanghuang.rsintegration.util.ModIds;

@ModReflection(modId = ModIds.FARMERSDELIGHT, description = "Farmer's Delight cooking system")
public final class FarmersDelightReflection {

    private static final String MOD = ModIds.FARMERSDELIGHT;

    public static Class<?> cookingPotBEClass;
    public static Class<?> cookingPotRecipeClass;
    public static Class<?> skilletBEClass;
    public static Class<?> stoveBEClass;


    static {
        register("vectorwing.farmersdelight.common.block.entity.CookingPotBlockEntity", "cookingPotBEClass");
        register("vectorwing.farmersdelight.common.crafting.CookingPotRecipe", "cookingPotRecipeClass");
        register("vectorwing.farmersdelight.common.block.entity.SkilletBlockEntity", "skilletBEClass");
        register("vectorwing.farmersdelight.common.block.entity.StoveBlockEntity", "stoveBEClass");
    }

    private static void register(String className, String fieldName) {
        String description = MOD + "." + className.substring(className.lastIndexOf('.') + 1);
        try {
            java.lang.reflect.Field targetField = FarmersDelightReflection.class.getDeclaredField(fieldName);
            ContractValidation.register(new ReflectionContract(MOD, description, className, true));
            ContractValidation.registerTarget(description, targetField);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException("FarmersDelightReflection field not found: " + fieldName, e);
        }
    }

    public static boolean isAvailable() { return cookingPotBEClass != null; }
}
