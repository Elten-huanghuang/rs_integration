package com.huanghuang.rsintegration.reflection.probes;

import com.huanghuang.rsintegration.reflection.contract.ContractValidation;
import com.huanghuang.rsintegration.reflection.contract.ReflectionContract;
import com.huanghuang.rsintegration.util.ModIds;

@ModReflection(modId = ModIds.JEI, description = "JEI/REI/EMI recipe viewer integration")
public final class JEIReflection {

    private static final String MOD = ModIds.JEI;

    public static Class<?> ingredientListOverlayClass;
    public static Class<?> bookmarkOverlayClass;
    public static Class<?> ingredientBookmarkClass;

    public static boolean ready;

    static {
        register("mezz.jei.library.ingredients.IngredientListOverlay", "ingredientListOverlayClass");
        register("mezz.jei.library.bookmarks.BookmarkOverlay", "bookmarkOverlayClass");
        register("mezz.jei.library.ingredients.IngredientBookmark", "ingredientBookmarkClass");
    }

    private static void register(String className, String fieldName) {
        String description = className.substring(className.lastIndexOf('.') + 1);
        try {
            java.lang.reflect.Field targetField = JEIReflection.class.getDeclaredField(fieldName);
            ContractValidation.register(new ReflectionContract(MOD, description, className, false));
            ContractValidation.registerTarget(description, targetField);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException("JEIReflection field not found: " + fieldName, e);
        }
    }

    public static boolean isAvailable() { return ready; }
}
