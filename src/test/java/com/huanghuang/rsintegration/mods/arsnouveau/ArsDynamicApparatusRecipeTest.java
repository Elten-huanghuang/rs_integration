package com.huanghuang.rsintegration.mods.arsnouveau;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArsDynamicApparatusRecipeTest extends BootstrapTest {
    @Test
    void levelOneEnchantStartsFromPlainBook() {
        var input = ArsDynamicApparatusRecipe.buildEnchantmentInput(Enchantments.FIRE_ASPECT, 1);
        var output = ArsDynamicApparatusRecipe.buildEnchantmentOutput(
                input, Enchantments.FIRE_ASPECT, 1);

        assertTrue(input.is(Items.BOOK));
        assertTrue(output.is(Items.ENCHANTED_BOOK));
        assertEquals(1, EnchantmentHelper.getEnchantments(output)
                .getOrDefault(Enchantments.FIRE_ASPECT, 0));
    }

    @Test
    void higherEnchantStartsFromPreviousLevelBook() {
        var input = ArsDynamicApparatusRecipe.buildEnchantmentInput(Enchantments.FIRE_ASPECT, 2);
        var output = ArsDynamicApparatusRecipe.buildEnchantmentOutput(
                input, Enchantments.FIRE_ASPECT, 2);

        assertTrue(input.getItem() instanceof EnchantedBookItem);
        assertEquals(1, EnchantmentHelper.getEnchantments(input)
                .getOrDefault(Enchantments.FIRE_ASPECT, 0));
        assertEquals(2, EnchantmentHelper.getEnchantments(output)
                .getOrDefault(Enchantments.FIRE_ASPECT, 0));
    }

    @Test
    void levelFourEnchantRequiresTheExactLevelThreeBook() {
        var input = ArsDynamicApparatusRecipe.buildEnchantmentInput(Enchantments.BLOCK_EFFICIENCY, 4);
        var output = ArsDynamicApparatusRecipe.buildEnchantmentOutput(
                input, Enchantments.BLOCK_EFFICIENCY, 4);

        assertTrue(input.getItem() instanceof EnchantedBookItem);
        assertEquals(3, EnchantmentHelper.getEnchantments(input)
                .getOrDefault(Enchantments.BLOCK_EFFICIENCY, 0));
        assertEquals(4, EnchantmentHelper.getEnchantments(output)
                .getOrDefault(Enchantments.BLOCK_EFFICIENCY, 0));
    }

    @Test
    void tierZeroArmorMaterialUsesTheNormalTaglessPerkRepresentation() {
        var input = new net.minecraft.world.item.ItemStack(Items.DIAMOND_HELMET);
        input.getOrCreateTag().putInt("Damage", 0);
        CompoundTag perkData = new CompoundTag();
        perkData.putInt("tier", 0);
        perkData.putString("color", "");
        perkData.put("perks", new net.minecraft.nbt.ListTag());
        input.getOrCreateTag().put("an_stack_perks", perkData);

        ArsDynamicApparatusRecipe.stripDefaultTierZeroPerkData(input);

        assertTrue(input.hasTag());
        assertEquals(1, input.getTag().size());
        assertTrue(input.getTag().contains("Damage"));
    }
}
