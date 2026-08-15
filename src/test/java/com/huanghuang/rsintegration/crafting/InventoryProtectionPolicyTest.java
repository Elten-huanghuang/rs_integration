package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.enchantment.Enchantments;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryProtectionPolicyTest extends BootstrapTest {

    @Test
    void protectsEnchantedDamagedAndNamedTools() {
        ItemStack enchanted = new ItemStack(Items.DIAMOND_PICKAXE);
        enchanted.enchant(Enchantments.BLOCK_EFFICIENCY, 1);
        ItemStack damaged = new ItemStack(Items.DIAMOND_PICKAXE);
        damaged.setDamageValue(1);
        ItemStack named = new ItemStack(Items.DIAMOND_PICKAXE);
        named.setHoverName(Component.literal("Mining tool"));

        assertTrue(InventoryProtectionPolicy.isProtectedBackpackItem(enchanted));
        assertTrue(InventoryProtectionPolicy.isProtectedBackpackItem(damaged));
        assertTrue(InventoryProtectionPolicy.isProtectedBackpackItem(named));
        assertFalse(InventoryProtectionPolicy.isProtectedBackpackItem(
                new ItemStack(Items.DIAMOND_PICKAXE)));
    }

    @Test
    void broadIngredientsCannotConsumeProtectedBackpackEquipment() {
        ItemStack tool = new ItemStack(Items.DIAMOND_PICKAXE);
        tool.enchant(Enchantments.BLOCK_EFFICIENCY, 1);

        assertFalse(InventoryProtectionPolicy.mayUseFromBackpack(
                tool, Ingredient.of(Items.DIAMOND_PICKAXE)));
    }
}
