package com.huanghuang.rsintegration.reflection.probes;

import com.huanghuang.rsintegration.reflection.contract.ContractValidation;
import com.huanghuang.rsintegration.reflection.contract.ReflectionContract;
import com.huanghuang.rsintegration.util.ModIds;

@ModReflection(modId = ModIds.JEI, description = "JEI/REI/EMI recipe viewer integration")
public final class JEIReflection {

    private static final String MOD = ModIds.JEI;

    public static volatile Class<?> ingredientListOverlayClass;
    public static volatile Class<?> bookmarkOverlayClass;
    public static volatile Class<?> ingredientBookmarkClass;


    static {
        // JEI 15.x moved these from mezz.jei.library.* to mezz.jei.gui.*
        register("mezz.jei.gui.overlay.IngredientListOverlay", "ingredientListOverlayClass");
        register("mezz.jei.gui.overlay.bookmarks.BookmarkOverlay", "bookmarkOverlayClass");
        register("mezz.jei.gui.bookmarks.IngredientBookmark", "ingredientBookmarkClass");
    }

    private static void register(String className, String fieldName) {
        String description = MOD + "." + className.substring(className.lastIndexOf('.') + 1);
        try {
            java.lang.reflect.Field targetField = JEIReflection.class.getDeclaredField(fieldName);
            ContractValidation.register(new ReflectionContract(MOD, description, className, false));
            ContractValidation.registerTarget(description, targetField);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException("JEIReflection field not found: " + fieldName, e);
        }
    }

    public static boolean isAvailable() { return ingredientListOverlayClass != null; }
}
