package com.huanghuang.rsintegration.mods.botania;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunicAltarOutputRulesTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void acceptsOnlyPrimaryAndExactReusableInputs() {
        ItemStack primary = new ItemStack(Items.DIAMOND);
        ItemStack reusable = new ItemStack(Items.BUCKET);
        CompoundTag tag = new CompoundTag();
        tag.putInt("owner", 7);
        reusable.setTag(tag);

        assertTrue(RunicAltarOutputRules.isOwnedOutput(
                new ItemStack(Items.DIAMOND, 3), primary, List.of(reusable)));
        assertTrue(RunicAltarOutputRules.isOwnedOutput(
                reusable.copyWithCount(2), primary, List.of(reusable)));
        assertFalse(RunicAltarOutputRules.isOwnedOutput(
                new ItemStack(Items.STONE), primary, List.of(reusable)));
        assertFalse(RunicAltarOutputRules.isOwnedOutput(
                new ItemStack(Items.BUCKET), primary, List.of(reusable)));
    }
}
