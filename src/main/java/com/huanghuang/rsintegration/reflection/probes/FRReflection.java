package com.huanghuang.rsintegration.reflection.probes;

import com.huanghuang.rsintegration.reflection.contract.ContractValidation;
import com.huanghuang.rsintegration.reflection.contract.ReflectionContract;
import com.huanghuang.rsintegration.util.ModIds;

@ModReflection(modId = ModIds.FARMERSRESPITE, description = "Farmer's Respite kettle system")
public final class FRReflection {

    private static final String MOD = ModIds.FARMERSRESPITE;

    public static Class<?> kettleBEClass;
    public static Class<?> kettleRecipeClass;
    public static Class<?> kettlePouringRecipeClass;


    static {
        register("umpaz.farmersrespite.common.block.entity.KettleBlockEntity", "kettleBEClass");
        register("umpaz.farmersrespite.common.crafting.KettleRecipe", "kettleRecipeClass");
        register("umpaz.farmersrespite.common.crafting.KettlePouringRecipe", "kettlePouringRecipeClass");
    }

    private static void register(String className, String fieldName) {
        String description = MOD + "." + className.substring(className.lastIndexOf('.') + 1);
        try {
            java.lang.reflect.Field targetField = FRReflection.class.getDeclaredField(fieldName);
            ContractValidation.register(new ReflectionContract(MOD, description, className, true));
            ContractValidation.registerTarget(description, targetField);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException("FRReflection field not found: " + fieldName, e);
        }
    }

    public static boolean isAvailable() { return kettleBEClass != null; }
}
