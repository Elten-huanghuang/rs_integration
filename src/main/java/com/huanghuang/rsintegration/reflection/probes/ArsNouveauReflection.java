package com.huanghuang.rsintegration.reflection.probes;

import com.huanghuang.rsintegration.reflection.contract.ContractValidation;
import com.huanghuang.rsintegration.reflection.contract.ReflectionContract;
import com.huanghuang.rsintegration.util.ModIds;

/**
 * Reflection probe for Ars Nouveau internals.
 *
 * <p>Only Ars-Nouveau-owned classes are referenced here — never vanilla or
 * Forge types, which are SRG-remapped at runtime.  The batch delegates cast
 * the machine BlockEntity to the vanilla {@link net.minecraft.world.Container}
 * interface (both tiles implement it) for slot I/O; these class references are
 * used only for {@code instanceof}-style machine-type validation and for the
 * mod-own crafting-state fields ({@code counter}/{@code isCrafting} on the
 * apparatus, {@code craftTicks}/{@code stack} on the imbuement chamber),
 * which are read via {@link com.huanghuang.rsintegration.mods.arsnouveau.ArsTileAccess}.</p>
 */
@ModReflection(modId = ModIds.ARS_NOUVEAU, description = "Ars Nouveau imbuement + enchanting apparatus")
public final class ArsNouveauReflection {

    private static final String MOD = ModIds.ARS_NOUVEAU;

    public static volatile Class<?> imbuementTileClass;
    public static volatile Class<?> apparatusTileClass;
    public static volatile Class<?> imbuementRecipeClass;
    public static volatile Class<?> apparatusRecipeClass;

    static {
        register("com.hollingsworth.arsnouveau.common.block.tile.ImbuementTile", "imbuementTileClass");
        register("com.hollingsworth.arsnouveau.common.block.tile.EnchantingApparatusTile", "apparatusTileClass");
        register("com.hollingsworth.arsnouveau.common.crafting.recipes.ImbuementRecipe", "imbuementRecipeClass");
        register("com.hollingsworth.arsnouveau.api.enchanting_apparatus.EnchantingApparatusRecipe", "apparatusRecipeClass");
    }

    private static void register(String className, String fieldName) {
        String description = MOD + "." + className.substring(className.lastIndexOf('.') + 1);
        try {
            java.lang.reflect.Field targetField = ArsNouveauReflection.class.getDeclaredField(fieldName);
            ContractValidation.register(new ReflectionContract(MOD, description, className, true));
            ContractValidation.registerTarget(description, targetField);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException("ArsNouveauReflection field not found: " + fieldName, e);
        }
    }

    public static boolean isAvailable() { return imbuementTileClass != null && apparatusTileClass != null; }

    private ArsNouveauReflection() {}
}
