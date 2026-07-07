package com.huanghuang.rsintegration.reflection.probes;

import com.huanghuang.rsintegration.reflection.contract.ContractValidation;
import com.huanghuang.rsintegration.reflection.contract.ReflectionContract;
import com.huanghuang.rsintegration.util.ModIds;

@ModReflection(modId = ModIds.WIZARDS_REBORN, description = "Wizard's Reborn crafting system")
public final class WRReflection {

    private static final String MOD = ModIds.WIZARDS_REBORN;

    public static Class<?> wissenCrystallizerBEClass;
    public static Class<?> arcaneIteratorBEClass;
    public static Class<?> arcaneWorkbenchBEClass;
    public static Class<?> crystalRitualBEClass;
    public static Class<?> crystalRitualClass;
    public static Class<?> ritualAreaClass;
    public static Class<?> crystalInfusionRecipeClass;
    public static Class<?> runicPedestalBEClass;

    public static boolean ready;

    static {
        register("mod.maxbogomol.wizards_reborn.common.block.wissen_crystallizer.WissenCrystallizerBlockEntity",
                "wissenCrystallizerBEClass");
        register("mod.maxbogomol.wizards_reborn.common.block.arcane_iterator.ArcaneIteratorBlockEntity",
                "arcaneIteratorBEClass");
        register("mod.maxbogomol.wizards_reborn.common.block.arcane_workbench.ArcaneWorkbenchBlockEntity",
                "arcaneWorkbenchBEClass");
        // CrystalRitualBlockEntity was renamed to CrystalBlockEntity in newer WR.
        // Register both; whichever resolves will populate crystalRitualBEClass.
        // Ordered so CrystalBlockEntity (newer name) wins when both are present.
        register("mod.maxbogomol.wizards_reborn.common.block.crystal_ritual.CrystalRitualBlockEntity",
                "crystalRitualBEClass", false);
        register("mod.maxbogomol.wizards_reborn.common.block.crystal.CrystalBlockEntity",
                "crystalRitualBEClass");
        register("mod.maxbogomol.wizards_reborn.api.crystalritual.CrystalRitual",
                "crystalRitualClass");
        register("mod.maxbogomol.wizards_reborn.api.crystalritual.CrystalRitualArea",
                "ritualAreaClass");
        register("mod.maxbogomol.wizards_reborn.common.recipe.CrystalInfusionRecipe",
                "crystalInfusionRecipeClass");
        register("mod.maxbogomol.wizards_reborn.common.block.runic_pedestal.RunicPedestalBlockEntity",
                "runicPedestalBEClass");
    }

    private static void register(String className, String fieldName) {
        register(className, fieldName, true);
    }

    private static void register(String className, String fieldName, boolean required) {
        String description = className.substring(className.lastIndexOf('.') + 1);
        try {
            java.lang.reflect.Field targetField = WRReflection.class.getDeclaredField(fieldName);
            ContractValidation.register(new ReflectionContract(MOD, description, className, required));
            ContractValidation.registerTarget(description, targetField);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException("WRReflection field not found: " + fieldName, e);
        }
    }

    public static boolean isAvailable() { return arcaneWorkbenchBEClass != null; }
}
