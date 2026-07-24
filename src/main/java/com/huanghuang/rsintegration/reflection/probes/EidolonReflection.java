package com.huanghuang.rsintegration.reflection.probes;

import com.huanghuang.rsintegration.reflection.contract.ContractValidation;
import com.huanghuang.rsintegration.reflection.contract.ReflectionContract;
import com.huanghuang.rsintegration.util.ModIds;

@ModReflection(modId = ModIds.EIDOLON, description = "Eidolon alchemy system")
public final class EidolonReflection {

    private static final String MOD = ModIds.EIDOLON;

    public static volatile Class<?> crucibleRecipeClass;
    public static volatile Class<?> crucibleRecipeStepClass;
    public static volatile Class<?> crucibleTileEntityClass;
    public static volatile Class<?> crucibleStepInnerClass;
    public static volatile Class<?> worktableBlockClass;
    public static volatile Class<?> worktableRecipeClass;
    public static volatile Class<?> ritualRecipeClass;
    public static volatile Class<?> brazierTileEntityClass;


    static {
        register("elucent.eidolon.recipe.CrucibleRecipe", "crucibleRecipeClass");
        register("elucent.eidolon.recipe.CrucibleRecipe$Step", "crucibleRecipeStepClass");
        register("elucent.eidolon.common.tile.CrucibleTileEntity", "crucibleTileEntityClass");
        register("elucent.eidolon.common.tile.CrucibleTileEntity$CrucibleStep", "crucibleStepInnerClass");
        register("elucent.eidolon.common.block.WorktableBlock", "worktableBlockClass");
        register("elucent.eidolon.recipe.WorktableRecipe", "worktableRecipeClass");
        register("elucent.eidolon.recipe.RitualRecipe", "ritualRecipeClass");
        register("elucent.eidolon.common.tile.BrazierTileEntity", "brazierTileEntityClass");
    }

    private static void register(String className, String fieldName) {
        String description = MOD + "." + className.substring(className.lastIndexOf('.') + 1);
        try {
            java.lang.reflect.Field targetField = EidolonReflection.class.getDeclaredField(fieldName);
            ContractValidation.register(new ReflectionContract(MOD, description, className, true));
            ContractValidation.registerTarget(description, targetField);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException("EidolonReflection field not found: " + fieldName, e);
        }
    }

    public static boolean isAvailable() { return crucibleTileEntityClass != null; }
}
