package com.huanghuang.rsintegration.mods.crockpot;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrockPotFuelPolicyTest extends BootstrapTest {

    private static int burnTime(ItemStack stack) {
        if (stack.is(Items.COAL) || stack.is(Items.CHARCOAL)) return 1600;
        if (stack.is(Items.COAL_BLOCK)) return 16000;
        if (stack.is(Items.OAK_PLANKS)) return 300;
        if (stack.is(Items.STICK)) return 100;
        if (stack.is(Items.WOODEN_PICKAXE)) return 200;
        if (stack.is(Items.LAVA_BUCKET)) return 20000;
        return 0;
    }

    @Test
    void configuredCoalWinsOverLargerFallbackStack() {
        ItemStack selected = CrockPotFuelPolicy.select(
                List.of(new ItemStack(Items.STICK, 64), new ItemStack(Items.COAL, 8)),
                List.of("minecraft:coal", "minecraft:charcoal"),
                CrockPotFuelPolicyTest::burnTime);

        assertNotNull(selected);
        assertTrue(selected.is(Items.COAL));
    }

    @Test
    void fallbackUsesBestBurnCoverageRegardlessOfCandidateOrder() {
        ItemStack first = CrockPotFuelPolicy.select(
                List.of(new ItemStack(Items.STICK, 64), new ItemStack(Items.OAK_PLANKS, 32)),
                List.of(), CrockPotFuelPolicyTest::burnTime);
        ItemStack second = CrockPotFuelPolicy.select(
                List.of(new ItemStack(Items.OAK_PLANKS, 32), new ItemStack(Items.STICK, 64)),
                List.of(), CrockPotFuelPolicyTest::burnTime);

        assertNotNull(first);
        assertNotNull(second);
        assertTrue(first.is(Items.OAK_PLANKS));
        assertTrue(second.is(Items.OAK_PLANKS));
    }

    @Test
    void unsafeItemsAreExcludedFromFallback() {
        ItemStack taggedCoal = new ItemStack(Items.COAL);
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("protected", true);
        taggedCoal.setTag(tag);

        assertNull(CrockPotFuelPolicy.select(List.of(
                        new ItemStack(Items.WOODEN_PICKAXE),
                        new ItemStack(Items.LAVA_BUCKET),
                        taggedCoal),
                List.of(), CrockPotFuelPolicyTest::burnTime));
    }

    @Test
    void insertionRoomRespectsExistingTypeAndLimits() {
        assertEquals(60, CrockPotFuelPolicy.insertionRoom(
                new ItemStack(Items.COAL, 4), new ItemStack(Items.COAL), 64));
        assertEquals(0, CrockPotFuelPolicy.insertionRoom(
                new ItemStack(Items.CHARCOAL), new ItemStack(Items.COAL), 64));
        assertEquals(15, CrockPotFuelPolicy.insertionRoom(
                new ItemStack(Items.COAL), new ItemStack(Items.COAL), 16));
    }

    @Test
    void refundIsLimitedToMatchingUnconsumedSupply() {
        ItemStack supplied = new ItemStack(Items.COAL);

        assertEquals(3, CrockPotFuelPolicy.refundableCount(
                supplied, 8, new ItemStack(Items.COAL, 3)));
        assertEquals(0, CrockPotFuelPolicy.refundableCount(
                supplied, 8, new ItemStack(Items.CHARCOAL, 8)));
        assertEquals(0, CrockPotFuelPolicy.refundableCount(
                supplied, 0, new ItemStack(Items.COAL, 8)));
    }
}
