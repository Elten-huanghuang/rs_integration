package com.huanghuang.rsintegration.reflection.probes;

import com.huanghuang.rsintegration.reflection.contract.ContractValidation;
import com.huanghuang.rsintegration.reflection.contract.ReflectionContract;
import com.huanghuang.rsintegration.util.ModIds;

@ModReflection(modId = ModIds.FARMINGFORBLOCKHEADS, description = "Farming for Blockheads market system")
public final class FarmingForBlockheadsReflection {

    private static final String MOD = ModIds.FARMINGFORBLOCKHEADS;

    public static Class<?> marketRegistryClass;
    public static Class<?> marketBEClass;

    public static boolean ready;

    static {
        register("net.blay09.mods.farmingforblockheads.registry.MarketRegistry", "marketRegistryClass");
        register("net.blay09.mods.farmingforblockheads.block.entity.MarketBlockEntity", "marketBEClass");
    }

    private static void register(String className, String fieldName) {
        String description = className.substring(className.lastIndexOf('.') + 1);
        try {
            java.lang.reflect.Field targetField = FarmingForBlockheadsReflection.class.getDeclaredField(fieldName);
            ContractValidation.register(new ReflectionContract(MOD, description, className, true));
            ContractValidation.registerTarget(description, targetField);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException("FarmingForBlockheadsReflection field not found: " + fieldName, e);
        }
    }

    public static boolean isAvailable() { return marketBEClass != null; }
}
