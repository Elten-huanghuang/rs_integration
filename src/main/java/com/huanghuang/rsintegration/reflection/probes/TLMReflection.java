package com.huanghuang.rsintegration.reflection.probes;

import com.huanghuang.rsintegration.reflection.contract.ContractValidation;
import com.huanghuang.rsintegration.reflection.contract.ReflectionContract;
import com.huanghuang.rsintegration.util.ModIds;

@ModReflection(modId = ModIds.TOUHOU_LITTLE_MAID, description = "Touhou Little Maid altar system")
public final class TLMReflection {

    private static final String MOD = ModIds.TOUHOU_LITTLE_MAID;

    public static Class<?> altarBEClass;
    public static Class<?> blockAltarClass;
    public static Class<?> altarRecipeClass;
    public static Class<?> powerCapProviderClass;
    public static Class<?> initRecipesClass;

    public static boolean ready;

    static {
        register("com.github.tartaricacid.touhoulittlemaid.tileentity.TileEntityAltar", "altarBEClass");
        register("com.github.tartaricacid.touhoulittlemaid.block.BlockAltar", "blockAltarClass");
        register("com.github.tartaricacid.touhoulittlemaid.crafting.AltarRecipe", "altarRecipeClass");
        register("com.github.tartaricacid.touhoulittlemaid.capability.PowerCapabilityProvider", "powerCapProviderClass");
        register("com.github.tartaricacid.touhoulittlemaid.init.InitRecipes", "initRecipesClass");
    }

    private static void register(String className, String fieldName) {
        String description = MOD + "." + className.substring(className.lastIndexOf('.') + 1);
        try {
            java.lang.reflect.Field targetField = TLMReflection.class.getDeclaredField(fieldName);
            ContractValidation.register(new ReflectionContract(MOD, description, className, true));
            ContractValidation.registerTarget(description, targetField);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException("TLMReflection field not found: " + fieldName, e);
        }
    }

    public static boolean isAvailable() { return altarBEClass != null; }
}
