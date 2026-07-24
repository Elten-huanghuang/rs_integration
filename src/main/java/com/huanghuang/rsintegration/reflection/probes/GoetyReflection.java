package com.huanghuang.rsintegration.reflection.probes;

import com.huanghuang.rsintegration.reflection.contract.ContractValidation;
import com.huanghuang.rsintegration.reflection.contract.ReflectionContract;
import com.huanghuang.rsintegration.util.ModIds;

@ModReflection(modId = ModIds.GOETY, description = "Goety 2 ritual system")
public final class GoetyReflection {

    private static final String MOD = ModIds.GOETY;

    public static volatile Class<?> darkAltarBEClass;
    public static volatile Class<?> seHelperClass;
    public static volatile Class<?> cursedCageBEClass;
    public static volatile Class<?> necroBrazierBEClass;
    public static volatile Class<?> pedestalBEClass;
    public static volatile Class<?> soulCandlestickBEClass;
    public static volatile Class<?> brazierRecipeClass;
    public static volatile Class<?> ritualRecipeClass;
    public static volatile Class<?> ritualClass;
    public static volatile Class<?> enchantItemRitualClass;
    public static volatile Class<?> convertRitualClass;
    public static volatile Class<?> teleportRitualClass;
    public static volatile Class<?> ritualRequirementsClass;
    public static volatile Class<?> ritualBlockEntityClass;
    public static volatile Class<?> researchListClass;
    public static volatile Class<?> darkAltarBlockClass;
    public static volatile Class<?> necroBrazierBlockClass;
    public static volatile Class<?> cursedCageBlockClass;
    public static volatile Class<?> soulCandlestickBlockClass;


    // ── Reflection field/method names (centralized so renames only touch one file) ──

    // Fields
    public static final String F_CURRENT_RITUAL_RECIPE = "currentRitualRecipe";
    public static final String F_CURSED_CAGE_TILE = "cursedCageTile";
    public static final String F_RECIPE = "recipe";
    public static final String F_RECIPE_ID = "recipeId";
    public static final String F_CURRENT_TIME = "currentTime";

    // Methods
    public static final String M_GET_RITUAL = "getRitual";
    public static final String M_GET_SOUL_COST = "getSoulCost";
    public static final String M_GET_ACTIVATION_ITEM = "getActivationItem";
    public static final String M_GET_PEDESTALS = "getPedestals";
    public static final String M_IS_EMPTY = "isEmpty";
    public static final String M_GET_CONTAINER = "getContainer";
    public static final String M_GET_CONTAINER_SIZE = "getContainerSize";
    public static final String M_SET_ITEMS = "setItems";
    public static final String M_ACTIVATE = "activate";
    public static final String M_GET_SOULS = "getSouls";
    public static final String M_GET_ENCHANTMENT = "getEnchantment";

    static {
        // Register contracts -- ContractValidation.validateAll() will verify and populate
        register("com.Polarice3.Goety.common.blocks.entities.DarkAltarBlockEntity", "darkAltarBEClass");
        register("com.Polarice3.Goety.utils.SEHelper", "seHelperClass");
        register("com.Polarice3.Goety.common.blocks.entities.CursedCageBlockEntity", "cursedCageBEClass");
        register("com.Polarice3.Goety.common.blocks.entities.NecroBrazierBlockEntity", "necroBrazierBEClass");
        register("com.Polarice3.Goety.common.blocks.entities.PedestalBlockEntity", "pedestalBEClass");
        register("com.Polarice3.Goety.common.blocks.entities.SoulCandlestickBlockEntity", "soulCandlestickBEClass");
        register("com.Polarice3.Goety.common.crafting.BrazierRecipe", "brazierRecipeClass");
        register("com.Polarice3.Goety.common.crafting.RitualRecipe", "ritualRecipeClass");
        register("com.Polarice3.Goety.common.ritual.Ritual", "ritualClass");
        register("com.Polarice3.Goety.common.ritual.EnchantItemRitual", "enchantItemRitualClass");
        register("com.Polarice3.Goety.common.ritual.ConvertRitual", "convertRitualClass");
        register("com.Polarice3.Goety.common.ritual.TeleportRitual", "teleportRitualClass");
        register("com.Polarice3.Goety.common.ritual.RitualRequirements", "ritualRequirementsClass");
        register("com.Polarice3.Goety.common.blocks.entities.RitualBlockEntity", "ritualBlockEntityClass");
        register("com.Polarice3.Goety.common.research.ResearchList", "researchListClass");
        register("com.Polarice3.Goety.common.blocks.DarkAltarBlock", "darkAltarBlockClass");
        register("com.Polarice3.Goety.common.blocks.NecroBrazierBlock", "necroBrazierBlockClass");
        register("com.Polarice3.Goety.common.blocks.CursedCageBlock", "cursedCageBlockClass");
        register("com.Polarice3.Goety.common.blocks.SoulCandlestickBlock", "soulCandlestickBlockClass");
    }

    private static void register(String className, String fieldName) {
        String description = MOD + "." + className.substring(className.lastIndexOf('.') + 1);
        try {
            java.lang.reflect.Field targetField = GoetyReflection.class.getDeclaredField(fieldName);
            ContractValidation.register(new ReflectionContract(MOD, description, className, true));
            ContractValidation.registerTarget(description, targetField);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException("GoetyReflection field not found: " + fieldName, e);
        }
    }

    public static boolean isAvailable() { return darkAltarBEClass != null; }
}
