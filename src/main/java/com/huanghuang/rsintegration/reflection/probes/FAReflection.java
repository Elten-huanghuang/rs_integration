package com.huanghuang.rsintegration.reflection.probes;

import com.huanghuang.rsintegration.reflection.contract.ContractValidation;
import com.huanghuang.rsintegration.reflection.contract.ReflectionContract;
import com.huanghuang.rsintegration.util.ModIds;

@ModReflection(modId = ModIds.FORBIDDEN_ARCANUS, description = "Forbidden & Arcanus ritual system")
public final class FAReflection {

    private static final String MOD = ModIds.FORBIDDEN_ARCANUS;

    public static Class<?> hephaestusForgeBEClass;
    public static Class<?> pedestalBEClass;
    public static Class<?> ritualClass;
    public static Class<?> essencesDefinitionClass;
    public static Class<?> essencesStorageClass;
    public static Class<?> ritualManagerClass;
    public static Class<?> booleanConsumerClass;
    public static Class<?> essenceManagerClass;
    public static Class<?> createItemResultClass;
    public static Class<?> upgradeTierResultClass;
    public static Class<?> ritualStarterItemClass;
    public static Class<?> enhancerAccessorClass;
    public static Class<?> enhancerDefinitionClass;
    public static Class<?> enhancerEffectClass;
    public static Class<?> essenceModifierClass;
    public static Class<?> faRegistriesClass;

    public static boolean ready;

    static {
        // Register contracts -- ContractValidation.validateAll() will verify and populate
        register("com.stal111.forbidden_arcanus.common.block.entity.forge.HephaestusForgeBlockEntity", "hephaestusForgeBEClass");
        register("com.stal111.forbidden_arcanus.common.block.entity.PedestalBlockEntity", "pedestalBEClass");
        register("com.stal111.forbidden_arcanus.common.block.entity.forge.ritual.Ritual", "ritualClass");
        register("com.stal111.forbidden_arcanus.common.block.entity.forge.essence.EssencesDefinition", "essencesDefinitionClass");
        register("com.stal111.forbidden_arcanus.common.block.entity.forge.essence.EssencesStorage", "essencesStorageClass");
        register("com.stal111.forbidden_arcanus.common.block.entity.forge.ritual.RitualManager", "ritualManagerClass");
        register("it.unimi.dsi.fastutil.booleans.BooleanConsumer", "booleanConsumerClass");
        register("com.stal111.forbidden_arcanus.common.block.entity.forge.essence.EssenceManager", "essenceManagerClass");
        register("com.stal111.forbidden_arcanus.common.block.entity.forge.ritual.result.CreateItemResult", "createItemResultClass");
        register("com.stal111.forbidden_arcanus.common.block.entity.forge.ritual.result.UpgradeTierResult", "upgradeTierResultClass");
        register("com.stal111.forbidden_arcanus.common.item.RitualStarterItem", "ritualStarterItemClass");
        register("com.stal111.forbidden_arcanus.common.item.enhancer.EnhancerAccessor", "enhancerAccessorClass");
        register("com.stal111.forbidden_arcanus.common.item.enhancer.EnhancerDefinition", "enhancerDefinitionClass");
        register("com.stal111.forbidden_arcanus.common.item.enhancer.EnhancerEffect", "enhancerEffectClass");
        register("com.stal111.forbidden_arcanus.common.block.entity.forge.essence.EssenceModifier", "essenceModifierClass");
        register("com.stal111.forbidden_arcanus.core.registry.FARegistries", "faRegistriesClass");
    }

    private static void register(String className, String fieldName) {
        String description = className.substring(className.lastIndexOf('.') + 1);
        try {
            java.lang.reflect.Field targetField = FAReflection.class.getDeclaredField(fieldName);
            ContractValidation.register(new ReflectionContract(MOD, description, className, true));
            ContractValidation.registerTarget(description, targetField);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException("FAReflection field not found: " + fieldName, e);
        }
    }

    public static boolean isAvailable() { return hephaestusForgeBEClass != null; }
}
