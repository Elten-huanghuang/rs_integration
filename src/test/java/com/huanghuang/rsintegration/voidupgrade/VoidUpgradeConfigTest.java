package com.huanghuang.rsintegration.voidupgrade;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.CompoundTag;
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
}
