package com.huanghuang.rsintegration.voidupgrade;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoidUpgradeConfigTest extends BootstrapTest {
    @Test
    void emptyRulesMatchNothingAndMultipleRulesUseOrSemantics() {
        assertFalse(VoidUpgradeConfig.EMPTY.matches(new ItemStack(Items.STONE)));
        VoidUpgradeConfig config = new VoidUpgradeConfig(false, List.of(
                VoidUpgradeRule.item(new ItemStack(Items.DIAMOND)),
                VoidUpgradeRule.mod("minecraft")));
        assertTrue(config.matches(new ItemStack(Items.DIAMOND)));
        assertTrue(config.matches(new ItemStack(Items.APPLE)));
    }

    @Test
    void itemRulesCanMatchOrIgnoreNbt() {
        ItemStack red = taggedDiamond("red");
        ItemStack blue = taggedDiamond("blue");
        assertTrue(new VoidUpgradeConfig(false, List.of(VoidUpgradeRule.item(red))).matches(blue));
        assertFalse(new VoidUpgradeConfig(true, List.of(VoidUpgradeRule.item(red))).matches(blue));
        assertTrue(new VoidUpgradeConfig(true, List.of(VoidUpgradeRule.item(red))).matches(red));
    }

    @Test
    void damageEnchantmentsAndOtherNbtCanBeMatchedIndependently() {
        ItemStack template = separatedNbtSword(5, "sharpness", "red");
        ItemStack otherDamage = separatedNbtSword(9, "sharpness", "red");
        ItemStack otherEnchant = separatedNbtSword(5, "smite", "red");
        ItemStack otherNbt = separatedNbtSword(5, "sharpness", "blue");
        VoidUpgradeRule rule = VoidUpgradeRule.item(template);

        assertTrue(new VoidUpgradeConfig(true, false, false, List.of(rule)).matches(otherDamage));
        assertTrue(new VoidUpgradeConfig(true, false, false, List.of(rule)).matches(otherEnchant));
        assertFalse(new VoidUpgradeConfig(true, false, false, List.of(rule)).matches(otherNbt));
        assertFalse(new VoidUpgradeConfig(false, true, false, List.of(rule)).matches(otherDamage));
        assertTrue(new VoidUpgradeConfig(false, true, false, List.of(rule)).matches(otherEnchant));
        assertFalse(new VoidUpgradeConfig(false, false, true, List.of(rule)).matches(otherEnchant));
        assertTrue(new VoidUpgradeConfig(false, false, true, List.of(rule)).matches(otherNbt));

        ItemStack ordered = separatedNbtSword(5, "sharpness", "red");
        addEnchantment(ordered, "unbreaking");
        ItemStack reversed = separatedNbtSword(5, "unbreaking", "red");
        addEnchantment(reversed, "sharpness");
        assertTrue(new VoidUpgradeConfig(false, false, true,
                List.of(VoidUpgradeRule.item(ordered))).matches(reversed));
    }

    @Test
    void legacyNbtSwitchMigratesToAllThreeMatchingOptions() {
        VoidUpgradeConfig original = new VoidUpgradeConfig(true,
                List.of(VoidUpgradeRule.item(new ItemStack(Items.DIAMOND_SWORD))));
        CompoundTag legacy = original.toTag();
        legacy.putInt("Version", 1);
        legacy.remove("MatchDamage");
        legacy.remove("MatchEnchantments");

        VoidUpgradeConfig loaded = VoidUpgradeConfig.fromTag(legacy);
        assertTrue(loaded.matchNbt());
        assertTrue(loaded.matchDamage());
        assertTrue(loaded.matchEnchantments());
    }

    @Test
    void equipmentCategoriesAreIndependentRules() {
        VoidUpgradeConfig chest = new VoidUpgradeConfig(false,
                List.of(VoidUpgradeRule.equipment(EquipmentSlot.CHEST)));
        assertTrue(chest.matches(new ItemStack(Items.DIAMOND_CHESTPLATE)));
        assertFalse(chest.matches(new ItemStack(Items.DIAMOND_LEGGINGS)));
    }

    @Test
    void nameRulesFuzzyMatchOnlyTheHoverNameText() {
        ItemStack matching = new ItemStack(Items.DIAMOND_CHESTPLATE);
        matching.setHoverName(Component.literal("Ancient Flame Chestplate"));
        ItemStack different = new ItemStack(Items.DIAMOND_CHESTPLATE);
        different.setHoverName(Component.literal("Ancient Ice Chestplate"));

        VoidUpgradeConfig config = new VoidUpgradeConfig(false,
                List.of(VoidUpgradeRule.name("FLAME CHEST")));
        assertTrue(config.matches(matching));
        assertFalse(config.matches(different));
        CompiledVoidRules compiled = CompiledVoidRules.compile(List.of(config));
        assertTrue(compiled.matches(matching));
        assertFalse(compiled.matches(different));
    }

    @Test
    void serializationIsBoundedAndRejectsInvalidRules() {
        List<VoidUpgradeRule> many = new ArrayList<>();
        for (int i = 0; i < 100; i++) many.add(VoidUpgradeRule.mod("mod" + i));
        VoidUpgradeConfig saved = new VoidUpgradeConfig(true, many);
        assertEquals(VoidUpgradeConfig.MAX_RULES, saved.rules().size());
        assertEquals(saved, VoidUpgradeConfig.fromTag(saved.toTag()));

        CompoundTag malformed = saved.toTag();
        malformed.getList("Rules", CompoundTag.TAG_COMPOUND).getCompound(0)
                .putString("Value", "Not A Mod Id");
        assertEquals(VoidUpgradeConfig.MAX_RULES - 1,
                VoidUpgradeConfig.fromTag(malformed).rules().size());
    }

    private static ItemStack taggedDiamond(String value) {
        ItemStack stack = new ItemStack(Items.DIAMOND);
        CompoundTag tag = new CompoundTag();
        tag.putString("variant", value);
        stack.setTag(tag);
        return stack;
    }

    private static ItemStack separatedNbtSword(int damage, String enchantment, String variant) {
        ItemStack stack = new ItemStack(Items.DIAMOND_SWORD);
        CompoundTag tag = new CompoundTag();
        tag.putInt("Damage", damage);
        tag.putString("variant", variant);
        ListTag enchantments = new ListTag();
        CompoundTag enchantmentTag = new CompoundTag();
        enchantmentTag.putString("id", "minecraft:" + enchantment);
        enchantmentTag.putShort("lvl", (short) 1);
        enchantments.add(enchantmentTag);
        tag.put("Enchantments", enchantments);
        stack.setTag(tag);
        return stack;
    }

    private static void addEnchantment(ItemStack stack, String enchantment) {
        ListTag enchantments = stack.getOrCreateTag().getList(
                "Enchantments", CompoundTag.TAG_COMPOUND);
        CompoundTag enchantmentTag = new CompoundTag();
        enchantmentTag.putString("id", "minecraft:" + enchantment);
        enchantmentTag.putShort("lvl", (short) 1);
        enchantments.add(enchantmentTag);
        stack.getOrCreateTag().put("Enchantments", enchantments);
    }
}
