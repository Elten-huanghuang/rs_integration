package com.huanghuang.rsintegration.reflection.probes;

import com.huanghuang.rsintegration.reflection.contract.ContractValidation;
import com.huanghuang.rsintegration.reflection.contract.ReflectionContract;
import com.huanghuang.rsintegration.util.ModIds;

@ModReflection(modId = ModIds.IMMORTERS_DELIGHT, description = "Immersal's Delight enchantal cooler system")
public final class ImmersalsDelightReflection {

    private static final String MOD = ModIds.IMMORTERS_DELIGHT;

    public static Class<?> enchantalCoolerBEClass;

    public static boolean ready;

    static {
        register("com.renyigesai.immortalers_delight.block.enchantal_cooler.EnchantalCoolerBlockEntity", "enchantalCoolerBEClass");
    }

    private static void register(String className, String fieldName) {
        String description = className.substring(className.lastIndexOf('.') + 1);
        try {
            java.lang.reflect.Field targetField = ImmersalsDelightReflection.class.getDeclaredField(fieldName);
            ContractValidation.register(new ReflectionContract(MOD, description, className, true));
            ContractValidation.registerTarget(description, targetField);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException("ImmersalsDelightReflection field not found: " + fieldName, e);
        }
    }

    public static boolean isAvailable() { return enchantalCoolerBEClass != null; }
}
