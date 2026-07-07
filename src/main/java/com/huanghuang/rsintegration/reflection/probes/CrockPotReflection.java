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
        register("com.sihenzhang.crockpot.block.entity.CrockPotBlockEntity", "crockPotBEClass");
        register("com.sihenzhang.crockpot.base.FoodCategory", "foodCategoryClass");
        register("com.sihenzhang.crockpot.base.FoodValues", "foodValuesClass");
        register("com.sihenzhang.crockpot.base.FoodValuesDefinition", "foodValuesDefinitionClass");
    }

    private static void register(String className, String fieldName) {
        String description = className.substring(className.lastIndexOf('.') + 1);
        try {
            java.lang.reflect.Field targetField = CrockPotReflection.class.getDeclaredField(fieldName);
            ContractValidation.register(new ReflectionContract(MOD, description, className, true));
            ContractValidation.registerTarget(description, targetField);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException("CrockPotReflection field not found: " + fieldName, e);
        }
    }

    public static boolean isAvailable() { return crockPotBEClass != null; }
}
