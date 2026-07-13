package com.huanghuang.rsintegration.mods.vanilla;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VanillaFurnaceFuelPolicyTest extends BootstrapTest {

    private static final List<String> DEFAULT_PRIORITY =
            List.of("minecraft:coal", "minecraft:charcoal");

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
    void coalWinsOverCharcoalRegardlessOfCandidateOrder() {
        var selection = select(List.of(
                new ItemStack(Items.CHARCOAL, 8),
                new ItemStack(Items.COAL, 8)));

        assertNotNull(selection);
        assertTrue(selection.fuel().is(Items.COAL));
        assertEquals(1, selection.amount());
        assertFalse(selection.partial());
    }

    @Test
    void charcoalIsUsedWhenCoalCannotCoverTheCook() {
        var selection = VanillaFurnaceFuelPolicy.select(List.of(
                        new ItemStack(Items.COAL, 1),
                        new ItemStack(Items.CHARCOAL, 2)),
                DEFAULT_PRIORITY, 2000, VanillaFurnaceFuelPolicyTest::burnTime);

        assertNotNull(selection);
        assertTrue(selection.fuel().is(Items.CHARCOAL));
        assertEquals(2, selection.amount());
        assertFalse(selection.partial());
    }

    @Test
    void safeNonPreferredFuelIsFallback() {
        var selection = select(List.of(new ItemStack(Items.OAK_PLANKS, 4)));

        assertNotNull(selection);
        assertTrue(selection.fuel().is(Items.OAK_PLANKS));
        assertFalse(selection.partial());
    }

    @Test
    void coalBlockIsFallbackButNotPreferredByDefault() {
        var selection = select(List.of(
                new ItemStack(Items.COAL_BLOCK, 1),
                new ItemStack(Items.CHARCOAL, 1)));

        assertNotNull(selection);
        assertTrue(selection.fuel().is(Items.CHARCOAL));
    }

    @Test
    void configuredCoalBlockCanTakePriority() {
        var selection = VanillaFurnaceFuelPolicy.select(List.of(
                        new ItemStack(Items.COAL, 1),
                        new ItemStack(Items.COAL_BLOCK, 1)),
                List.of("minecraft:coal_block", "minecraft:coal"),
                200, VanillaFurnaceFuelPolicyTest::burnTime);

        assertNotNull(selection);
        assertTrue(selection.fuel().is(Items.COAL_BLOCK));
    }

    @Test
    void bestPartialCoverageIsSelected() {
        var selection = VanillaFurnaceFuelPolicy.select(List.of(
                        new ItemStack(Items.STICK, 2),
                        new ItemStack(Items.OAK_PLANKS, 2)),
                DEFAULT_PRIORITY, 2000, VanillaFurnaceFuelPolicyTest::burnTime);

        assertNotNull(selection);
        assertTrue(selection.fuel().is(Items.OAK_PLANKS));
        assertEquals(2, selection.amount());
        assertTrue(selection.partial());
    }

    @Test
    void unsafeFuelsAreNeverAutomaticallySelected() {
        ItemStack taggedCoal = new ItemStack(Items.COAL);
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("protected", true);
        taggedCoal.setTag(tag);

        assertNull(select(List.of(
                new ItemStack(Items.WOODEN_PICKAXE),
                new ItemStack(Items.LAVA_BUCKET),
                taggedCoal)));
    }

    @Test
    void invalidAndDuplicatePriorityIdsAreIgnored() {
        var selection = VanillaFurnaceFuelPolicy.select(
                List.of(new ItemStack(Items.CHARCOAL, 2)),
                List.of("not an id", "minecraft:charcoal", "minecraft:charcoal"),
                200, VanillaFurnaceFuelPolicyTest::burnTime);

        assertNotNull(selection);
        assertTrue(selection.fuel().is(Items.CHARCOAL));
    }

    @Test
    void requiredAmountRoundsUp() {
        assertEquals(2, VanillaFurnaceFuelPolicy.requiredAmount(1601, 1600));
        assertEquals(1, VanillaFurnaceFuelPolicy.requiredAmount(200, 1600));
        assertEquals(0, VanillaFurnaceFuelPolicy.requiredAmount(0, 1600));
    }

    private static VanillaFurnaceFuelPolicy.Selection select(List<ItemStack> candidates) {
        return VanillaFurnaceFuelPolicy.select(
                candidates, DEFAULT_PRIORITY, 200, VanillaFurnaceFuelPolicyTest::burnTime);
    }
}
