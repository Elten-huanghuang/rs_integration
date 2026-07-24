package com.huanghuang.rsintegration.reflection.probes;

import com.huanghuang.rsintegration.reflection.contract.ContractValidation;
import com.huanghuang.rsintegration.reflection.contract.ReflectionContract;
import com.huanghuang.rsintegration.util.ModIds;

@ModReflection(modId = ModIds.SOPHISTICATED_BACKPACKS + "+" + ModIds.BETTER_COMBAT,
               description = "Sophisticated Backpacks + Better Combat integration")
public final class BackpackReflection {

    private static final String MOD = ModIds.SOPHISTICATED_BACKPACKS;

    public static volatile Class<?> backpackBEClass;
    public static volatile Class<?> playerAttackHelperClass;


    static {
        register("net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackBlockEntity", "backpackBEClass");
        // PlayerAttackHelper is from bettercombat, not sophisticatedbackpacks
        try {
            java.lang.reflect.Field targetField = BackpackReflection.class.getDeclaredField("playerAttackHelperClass");
            ContractValidation.register(new ReflectionContract(ModIds.BETTER_COMBAT, "PlayerAttackHelper",
                    "net.bettercombat.logic.PlayerAttackHelper", false));
            ContractValidation.registerTarget("PlayerAttackHelper", targetField);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException("BackpackReflection field not found: playerAttackHelperClass", e);
        }
    }

    private static void register(String className, String fieldName) {
        String description = MOD + "." + className.substring(className.lastIndexOf('.') + 1);
        try {
            java.lang.reflect.Field targetField = BackpackReflection.class.getDeclaredField(fieldName);
            ContractValidation.register(new ReflectionContract(MOD, description, className, true));
            ContractValidation.registerTarget(description, targetField);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException("BackpackReflection field not found: " + fieldName, e);
        }
    }

    public static boolean isAvailable() { return backpackBEClass != null; }
}
