package com.huanghuang.rsintegration.reflection.probes;

import com.huanghuang.rsintegration.reflection.contract.ContractValidation;
import com.huanghuang.rsintegration.reflection.contract.ReflectionContract;
import com.huanghuang.rsintegration.util.ModIds;

@ModReflection(modId = ModIds.CROCKPOT, description = "Crock Pot cooking system")
public final class CrockPotReflection {

    private static final String MOD = ModIds.CROCKPOT;

    public static Class<?> crockPotBEClass;
    public static Class<?> foodCategoryClass;
    public static Class<?> foodValuesClass;
    public static Class<?> foodValuesDefinitionClass;

    public static boolean ready;

    static {
        register("com.sihenzhang.crockpot.block.entity.CrockPotBlockEntity", "crockPotBEClass", true);
        register("com.sihenzhang.crockpot.base.FoodCategory", "foodCategoryClass", true);
        register("com.sihenzhang.crockpot.base.FoodValues", "foodValuesClass", true);
        // Moved from base to recipe package in CrockPot 1.0.4; still exposes static getFoodValues(ItemStack, Level).
        register("com.sihenzhang.crockpot.recipe.FoodValuesDefinition", "foodValuesDefinitionClass", true);
    }

    private static void register(String className, String fieldName, boolean required) {
        String description = MOD + "." + className.substring(className.lastIndexOf('.') + 1);
        try {
            java.lang.reflect.Field targetField = CrockPotReflection.class.getDeclaredField(fieldName);
            ContractValidation.register(new ReflectionContract(MOD, description, className, required));
            ContractValidation.registerTarget(description, targetField);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException("CrockPotReflection field not found: " + fieldName, e);
        }
    }

    public static boolean isAvailable() { return crockPotBEClass != null; }
}
