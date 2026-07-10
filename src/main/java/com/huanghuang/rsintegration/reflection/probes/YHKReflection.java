package com.huanghuang.rsintegration.reflection.probes;

import com.huanghuang.rsintegration.reflection.contract.ContractValidation;
import com.huanghuang.rsintegration.reflection.contract.ReflectionContract;
import com.huanghuang.rsintegration.util.ModIds;

@ModReflection(modId = ModIds.YOUKAISHOMECOMING, description = "Youkais Homecoming cooking system")
public final class YHKReflection {

    private static final String MOD = ModIds.YOUKAISHOMECOMING;

    // -- Cooking Pot --
    public static Class<?> cookingBEClass;
    public static Class<?> potCookingRecipeClass;
    // -- Kettle --
    public static Class<?> kettleBEClass;
    public static Class<?> kettleRecipeClass;
    public static Class<?> yhFluidClass;
    public static Class<?> yhFluidHolderClass;
    // -- Cuisine Board --
    public static Class<?> cuisineBoardBEClass;
    public static Class<?> tableItemClass;
    public static Class<?> cuisineRecipeClass;
    public static Class<?> variantTableItemBaseClass;
    public static Class<?> ingredientTableItemClass;
    // -- Fermentation Tank --
    public static Class<?> fermentationTankBEClass;
    public static Class<?> simpleFermentationRecipeClass;
    public static Class<?> fluidItemTileClass;
    public static Class<?> fermentationTankBlockClass;
    // -- Steamer --
    public static Class<?> steamerBEClass;
    public static Class<?> rackDataClass;
    public static Class<?> rackItemDataClass;
    public static Class<?> steamerPotBlockClass;
    // -- Moka Pot --
    public static Class<?> mokaMakerBEClass;
    public static Class<?> mokaRecipeClass;
    public static Class<?> mokaMakerBlockClass;
    // -- Base (shared parent class) --
    public static Class<?> timedRecipeBEClass;

    public static boolean ready;

    static {
        // Cooking Pot
        register("dev.xkmc.youkaishomecoming.content.pot.cooking.core.CookingBlockEntity", "cookingBEClass");
        register("dev.xkmc.youkaishomecoming.content.pot.cooking.core.PotCookingRecipe", "potCookingRecipeClass");
        // Kettle
        register("dev.xkmc.youkaishomecoming.content.pot.kettle.KettleBlockEntity", "kettleBEClass");
        register("dev.xkmc.youkaishomecoming.content.pot.kettle.KettleRecipe", "kettleRecipeClass");
        register("dev.xkmc.youkaishomecoming.content.item.fluid.YHFluid", "yhFluidClass");
        register("dev.xkmc.youkaishomecoming.content.item.fluid.IYHFluidHolder", "yhFluidHolderClass");
        // Cuisine Board
        register("dev.xkmc.youkaishomecoming.content.pot.table.board.CuisineBoardBlockEntity", "cuisineBoardBEClass");
        register("dev.xkmc.youkaishomecoming.content.pot.table.item.TableItem", "tableItemClass");
        register("dev.xkmc.youkaishomecoming.content.pot.table.recipe.CuisineRecipe", "cuisineRecipeClass");
        register("dev.xkmc.youkaishomecoming.content.pot.table.item.VariantTableItemBase", "variantTableItemBaseClass");
        register("dev.xkmc.youkaishomecoming.content.pot.table.item.IngredientTableItem", "ingredientTableItemClass");
        // Fermentation Tank
        register("dev.xkmc.youkaishomecoming.content.pot.ferment.FermentationTankBlockEntity", "fermentationTankBEClass");
        register("dev.xkmc.youkaishomecoming.content.pot.ferment.SimpleFermentationRecipe", "simpleFermentationRecipeClass");
        register("dev.xkmc.youkaishomecoming.content.pot.base.FluidItemTile", "fluidItemTileClass");
        register("dev.xkmc.youkaishomecoming.content.pot.ferment.FermentationTankBlock", "fermentationTankBlockClass");
        // Steamer
        register("dev.xkmc.youkaishomecoming.content.pot.steamer.SteamerBlockEntity", "steamerBEClass");
        register("dev.xkmc.youkaishomecoming.content.pot.steamer.RackData", "rackDataClass");
        register("dev.xkmc.youkaishomecoming.content.pot.steamer.RackItemData", "rackItemDataClass");
        // SteamerPotBlock was removed in YHK 2.6.x — water is now vanilla WATERLOGGED on BasePotBlock
        registerOptional("dev.xkmc.youkaishomecoming.content.pot.steamer.SteamerPotBlock", "steamerPotBlockClass");
        registerOptional("dev.xkmc.youkaishomecoming.content.pot.base.BasePotBlock", "steamerPotBlockClass");
        // Moka Pot
        register("dev.xkmc.youkaishomecoming.content.pot.moka.MokaMakerBlockEntity", "mokaMakerBEClass");
        register("dev.xkmc.youkaishomecoming.content.pot.moka.MokaRecipe", "mokaRecipeClass");
        register("dev.xkmc.youkaishomecoming.content.pot.moka.MokaMakerBlock", "mokaMakerBlockClass");
        // Base
        register("dev.xkmc.youkaishomecoming.content.pot.base.TimedRecipeBlockEntity", "timedRecipeBEClass");
    }

    private static void register(String className, String fieldName) {
        register(className, fieldName, true);
    }

    private static void registerOptional(String className, String fieldName) {
        register(className, fieldName, false);
    }

    private static void register(String className, String fieldName, boolean required) {
        String description = MOD + "." + className.substring(className.lastIndexOf('.') + 1);
        try {
            java.lang.reflect.Field targetField = YHKReflection.class.getDeclaredField(fieldName);
            ContractValidation.register(new ReflectionContract(MOD, description, className, required));
            ContractValidation.registerTarget(description, targetField);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException("YHKReflection field not found: " + fieldName, e);
        }
    }

    public static boolean isAvailable() { return cookingBEClass != null; }
}
