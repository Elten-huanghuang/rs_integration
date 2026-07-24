package com.huanghuang.rsintegration.reflection.probes;

import com.huanghuang.rsintegration.reflection.contract.ContractValidation;
import com.huanghuang.rsintegration.reflection.contract.ReflectionContract;
import com.huanghuang.rsintegration.util.ModIds;

@ModReflection(modId = ModIds.CRABBERS_DELIGHT, description = "Crabber's Delight crab trap system")
public final class CrabbersDelightReflection {

    private static final String MOD = ModIds.CRABBERS_DELIGHT;

    public static volatile Class<?> crabTrapBEClass;


    static {
        register("alabaster.crabbersdelight.common.block.entity.CrabTrapBlockEntity", "crabTrapBEClass");
    }

    private static void register(String className, String fieldName) {
        String description = MOD + "." + className.substring(className.lastIndexOf('.') + 1);
        try {
            java.lang.reflect.Field targetField = CrabbersDelightReflection.class.getDeclaredField(fieldName);
            ContractValidation.register(new ReflectionContract(MOD, description, className, true));
            ContractValidation.registerTarget(description, targetField);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException("CrabbersDelightReflection field not found: " + fieldName, e);
        }
    }

    public static boolean isAvailable() { return crabTrapBEClass != null; }
}
