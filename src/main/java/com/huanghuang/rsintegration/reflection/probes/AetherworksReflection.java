package com.huanghuang.rsintegration.reflection.probes;

import com.huanghuang.rsintegration.reflection.contract.ContractValidation;
import com.huanghuang.rsintegration.reflection.contract.ReflectionContract;
import com.huanghuang.rsintegration.util.ModIds;

@ModReflection(modId = ModIds.AETHERWORKS, description = "Aetherworks anvil/forge system")
public final class AetherworksReflection {

    private static final String MOD = ModIds.AETHERWORKS;

    public static volatile Class<?> anvilBEClass;
    public static volatile Class<?> forgeBEClass;
    public static volatile Class<?> anvilRecipeClass;
    public static volatile Class<?> coolerBEClass;
    public static volatile Class<?> heaterBEClass;
    public static volatile Class<?> toolStationBEClass;
    public static volatile Class<?> toolStationRecipeClass;
    public static volatile Class<?> iheatCapabilityClass;
    public static volatile Class<?> awRegistryClass;


    static {
        register("net.sirplop.aetherworks.blockentity.AetheriumAnvilBlockEntity", "anvilBEClass");
        register("net.sirplop.aetherworks.blockentity.AetherForgeBlockEntity", "forgeBEClass");
        register("net.sirplop.aetherworks.recipe.IAetheriumAnvilRecipe", "anvilRecipeClass");
        register("net.sirplop.aetherworks.blockentity.ForgeCoolerBlockEntity", "coolerBEClass", false);
        register("net.sirplop.aetherworks.blockentity.ForgeHeaterBlockEntity", "heaterBEClass", false);
        register("net.sirplop.aetherworks.blockentity.ToolStationBlockEntity", "toolStationBEClass");
        register("net.sirplop.aetherworks.recipe.IToolStationRecipe", "toolStationRecipeClass");
        register("net.sirplop.aetherworks.api.capabilities.IHeatCapability", "iheatCapabilityClass");
        register("net.sirplop.aetherworks.AWRegistry", "awRegistryClass");
    }

    private static void register(String className, String fieldName) {
        register(className, fieldName, true);
    }

    private static void register(String className, String fieldName, boolean required) {
        String description = MOD + "." + className.substring(className.lastIndexOf('.') + 1);
        try {
            java.lang.reflect.Field targetField = AetherworksReflection.class.getDeclaredField(fieldName);
            ContractValidation.register(new ReflectionContract(MOD, description, className, required));
            ContractValidation.registerTarget(description, targetField);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException("AetherworksReflection field not found: " + fieldName, e);
        }
    }

    public static boolean isAvailable() { return anvilBEClass != null; }
}
