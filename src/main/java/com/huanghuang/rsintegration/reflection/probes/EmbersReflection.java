package com.huanghuang.rsintegration.reflection.probes;

import com.huanghuang.rsintegration.reflection.contract.ContractValidation;
import com.huanghuang.rsintegration.reflection.contract.ReflectionContract;
import com.huanghuang.rsintegration.util.ModIds;

@ModReflection(modId = ModIds.EMBERS, description = "Embers Rekindled alchemy system")
public final class EmbersReflection {

    private static final String MOD = ModIds.EMBERS;

    public static Class<?> alchemyTabletBEClass;
    public static Class<?> alchemyPedestalTopBEClass;
    public static Class<?> alchemyPedestalBEClass;
    public static Class<?> alchemyRecipeClass;
    public static Class<?> ibinClass;
    public static Class<?> iemberCapabilityClass;
    public static Class<?> registryManagerClass;


    static {
        register("com.rekindled.embers.blockentity.AlchemyTabletBlockEntity", "alchemyTabletBEClass");
        register("com.rekindled.embers.blockentity.AlchemyPedestalTopBlockEntity", "alchemyPedestalTopBEClass");
        register("com.rekindled.embers.blockentity.AlchemyPedestalBlockEntity", "alchemyPedestalBEClass");
        register("com.rekindled.embers.recipe.AlchemyRecipe", "alchemyRecipeClass");
        register("com.rekindled.embers.api.tile.IBin", "ibinClass");
        register("com.rekindled.embers.api.power.IEmberCapability", "iemberCapabilityClass");
        register("com.rekindled.embers.RegistryManager", "registryManagerClass");
    }

    private static void register(String className, String fieldName) {
        String description = MOD + "." + className.substring(className.lastIndexOf('.') + 1);
        try {
            java.lang.reflect.Field targetField = EmbersReflection.class.getDeclaredField(fieldName);
            ContractValidation.register(new ReflectionContract(MOD, description, className, true));
            ContractValidation.registerTarget(description, targetField);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException("EmbersReflection field not found: " + fieldName, e);
        }
    }

    public static boolean isAvailable() { return alchemyTabletBEClass != null; }
}
