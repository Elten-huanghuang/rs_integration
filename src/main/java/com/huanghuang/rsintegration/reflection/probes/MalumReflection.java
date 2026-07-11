package com.huanghuang.rsintegration.reflection.probes;

import com.huanghuang.rsintegration.reflection.contract.ContractValidation;
import com.huanghuang.rsintegration.reflection.contract.ReflectionContract;
import com.huanghuang.rsintegration.util.ModIds;

@ModReflection(modId = ModIds.MALUM, description = "Malum spirit altar/crucible system")
public final class MalumReflection {

    private static final String MOD = ModIds.MALUM;

    public static Class<?> spiritAltarBEClass;
    public static Class<?> crucibleBEClass;
    public static Class<?> ingredientWithCountClass;
    public static Class<?> runicWorkbenchBEClass;
    public static Class<?> altarCraftingHelperClass;
    public static Class<?> spiritInfusionRecipeClass;


    static {
        register("com.sammy.malum.common.block.curiosities.spirit_altar.SpiritAltarBlockEntity", "spiritAltarBEClass");
        register("com.sammy.malum.common.block.curiosities.spirit_crucible.SpiritCrucibleCoreBlockEntity", "crucibleBEClass");
        register("team.lodestar.lodestone.systems.recipe.IngredientWithCount", "ingredientWithCountClass");
        register("com.sammy.malum.common.block.curiosities.runic_workbench.RunicWorkbenchBlockEntity", "runicWorkbenchBEClass");
        register("com.sammy.malum.common.block.curiosities.spirit_altar.AltarCraftingHelper", "altarCraftingHelperClass");
        register("com.sammy.malum.common.recipe.SpiritInfusionRecipe", "spiritInfusionRecipeClass");
    }

    private static void register(String className, String fieldName) {
        String description = MOD + "." + className.substring(className.lastIndexOf('.') + 1);
        try {
            java.lang.reflect.Field targetField = MalumReflection.class.getDeclaredField(fieldName);
            ContractValidation.register(new ReflectionContract(MOD, description, className, true));
            ContractValidation.registerTarget(description, targetField);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException("MalumReflection field not found: " + fieldName, e);
        }
    }

    public static boolean isAvailable() { return spiritAltarBEClass != null; }
}
