package com.huanghuang.rsintegration.reflection.probes;

import com.huanghuang.rsintegration.reflection.contract.ContractValidation;
import com.huanghuang.rsintegration.reflection.contract.ReflectionContract;
import com.huanghuang.rsintegration.util.ModIds;

@ModReflection(modId = ModIds.DISTANT_WORLDS, description = "Distant Worlds Lithum Altar")
public final class DistantWorldsReflection {
    private static final String MOD = ModIds.DISTANT_WORLDS;

    public static Class<?> lithumCoreBlockClass;
    public static Class<?> lithumCoreBEClass;
    public static Class<?> lithumPedestalBEClass;
    public static Class<?> lithumFurnaceBEClass;
    public static Class<?> structureIntegrityProcedureClass;
    public static Class<?> recipePickerProcedureClass;
    public static Class<?> coreUpdateTickProcedureClass;
    public static Class<?> coreResultProcedureClass;
    public static Class<?> furnaceUpdateTickProcedureClass;
    public static Class<?> coreRightClickProcedureClass;

    static {
        register("net.mcreator.distantworlds.block.LithumCoreBlock", "lithumCoreBlockClass");
        register("net.mcreator.distantworlds.block.entity.LithumCoreBlockEntity", "lithumCoreBEClass");
        register("net.mcreator.distantworlds.block.entity.LithumPedestalBlockEntity", "lithumPedestalBEClass");
        register("net.mcreator.distantworlds.block.entity.LithumFurnaceBlockEntity", "lithumFurnaceBEClass");
        register("net.mcreator.distantworlds.procedures.LithumStructureIntegrityCheckProcedure", "structureIntegrityProcedureClass");
        register("net.mcreator.distantworlds.procedures.LithumStructureRecipePickerProcedure", "recipePickerProcedureClass");
        register("net.mcreator.distantworlds.procedures.LithumCoreUpdateTickProcedure", "coreUpdateTickProcedureClass");
        register("net.mcreator.distantworlds.procedures.LithumCoreUpdateResultProcedure", "coreResultProcedureClass");
        register("net.mcreator.distantworlds.procedures.LithumFurnaceUpdateTickProcedure", "furnaceUpdateTickProcedureClass");
        register("net.mcreator.distantworlds.procedures.LithumCoreOnBlockRightClickedProcedure", "coreRightClickProcedureClass");
    }

    private static void register(String className, String fieldName) {
        String description = MOD + "." + className.substring(className.lastIndexOf('.') + 1);
        try {
            java.lang.reflect.Field target = DistantWorldsReflection.class.getDeclaredField(fieldName);
            ContractValidation.register(new ReflectionContract(MOD, description, className, true));
            ContractValidation.registerTarget(description, target);
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException("DistantWorldsReflection field not found: " + fieldName, e);
        }
    }

    public static boolean hasAltarContract() {
        return lithumCoreBlockClass != null && lithumCoreBEClass != null
                && lithumPedestalBEClass != null && structureIntegrityProcedureClass != null
                && recipePickerProcedureClass != null && coreUpdateTickProcedureClass != null
                && coreResultProcedureClass != null;
    }

    public static boolean hasFuelContract() {
        return lithumFurnaceBEClass != null && furnaceUpdateTickProcedureClass != null;
    }

    public static boolean isAvailable() {
        return hasAltarContract();
    }
}
