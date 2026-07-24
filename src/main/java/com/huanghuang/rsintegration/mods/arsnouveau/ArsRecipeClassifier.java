package com.huanghuang.rsintegration.mods.arsnouveau;

import javax.annotation.Nullable;

/**
 * Pure recipe-type classification for Ars Nouveau, keyed on the recipe-type
 * registry id string (e.g. {@code ars_nouveau:imbuement}).
 *
 * <p>Kept free of any Minecraft/Refined-Storage type so it can be unit-tested
 * directly against the recipe {@code type} field read out of the datapack JSONs
 * — Ars Nouveau classes are absent at test runtime.</p>
 *
 * <p>Only two of Ars' 16 recipe types are automatable through slot/pedestal
 * insertion: {@code imbuement} and {@code enchanting_apparatus}. All others
 * (enchantment, glyph, crush, dye, potion_flask, book_upgrade, armor_upgrade,
 * spell_write, reactive_enchantment, caster_tome, summon_ritual,
 * budding_conversion, dispel_entity, scry_ritual) either mutate NBT on an
 * existing item, target entities/world, or have no deterministic item output,
 * and are deliberately excluded.</p>
 */
public final class ArsRecipeClassifier {

    public static final String TYPE_IMBUEMENT = "ars_nouveau:imbuement";
    public static final String TYPE_APPARATUS = "ars_nouveau:enchanting_apparatus";

    private ArsRecipeClassifier() {}

    public static boolean isImbuement(@Nullable String recipeTypeId) {
        return TYPE_IMBUEMENT.equals(recipeTypeId);
    }

    public static boolean isApparatus(@Nullable String recipeTypeId) {
        return TYPE_APPARATUS.equals(recipeTypeId);
    }

    /** True when RSI can automate this Ars recipe type (imbuement or apparatus). */
    public static boolean isAutomatable(@Nullable String recipeTypeId) {
        return isImbuement(recipeTypeId) || isApparatus(recipeTypeId);
    }
}
