package com.huanghuang.rsintegration.mods.youkaishomecoming.cooking;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CookingPotCompletionPolicyTest extends BootstrapTest {

    @Test
    void acceptsExpectedRecipeResultBlockAfterCookingBlockEntityDisappears() {
        assertTrue(CookingPotCompletionPolicy.isExpectedResultBlock(
                Blocks.PUMPKIN.defaultBlockState(),
                new ItemStack(Items.PUMPKIN)));
    }

    @Test
    void rejectsAirWhenMachineAndResultAreBothMissing() {
        assertFalse(CookingPotCompletionPolicy.isExpectedResultBlock(
                Blocks.AIR.defaultBlockState(),
                new ItemStack(Items.PUMPKIN)));
    }

    @Test
    void rejectsDifferentBlockAtBoundPosition() {
        assertFalse(CookingPotCompletionPolicy.isExpectedResultBlock(
                Blocks.DIRT.defaultBlockState(),
                new ItemStack(Items.PUMPKIN)));
    }

    @Test
    void rejectsNonBlockRecipeOutput() {
        assertFalse(CookingPotCompletionPolicy.isExpectedResultBlock(
                Blocks.DIAMOND_BLOCK.defaultBlockState(),
                new ItemStack(Items.DIAMOND)));
    }

    @Test
    void acceptsMatchingIdleBowlWithoutBlockEntity() {
        assertTrue(CookingPotCompletionPolicy.isIdleBowl(
                Blocks.CAULDRON.defaultBlockState(), "minecraft:cauldron"));
    }

    @Test
    void rejectsAirAndWrongIdleBlockWithoutBlockEntity() {
        assertFalse(CookingPotCompletionPolicy.isIdleBowl(
                Blocks.AIR.defaultBlockState(), "minecraft:cauldron"));
        assertFalse(CookingPotCompletionPolicy.isIdleBowl(
                Blocks.DIRT.defaultBlockState(), "minecraft:cauldron"));
    }

    @Test
    void rejectsEmptyRecipeOutput() {
        assertFalse(CookingPotCompletionPolicy.isExpectedResultBlock(
                Blocks.PUMPKIN.defaultBlockState(),
                ItemStack.EMPTY));
    }
}
