package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeCycleGuardTest extends BootstrapTest {

    @Test
    void terminalRecipeBoundaryRejectsDependencyThatReturnsToItsOutput() {
        RecipeCycleGuard guard = new RecipeCycleGuard();
        ResourceLocation terminal = new ResourceLocation("test", "ingot_from_nuggets");
        ItemStack ingot = new ItemStack(Items.IRON_INGOT);

        guard.enter(terminal, ingot);

        assertTrue(guard.containsBranch(terminal, ingot));
        assertTrue(guard.containsOutput(ingot));
        assertFalse(guard.containsOutput(new ItemStack(Items.IRON_NUGGET)));

        guard.leave(terminal, ingot);
        assertFalse(guard.containsBranch(terminal, ingot));
        assertFalse(guard.containsOutput(ingot));
    }

    @Test
    void outputCycleIdentityPreservesNbtVariants() {
        RecipeCycleGuard guard = new RecipeCycleGuard();
        ResourceLocation recipe = new ResourceLocation("test", "level_upgrade");
        ItemStack levelTwo = taggedPaper(2);

        guard.enter(recipe, levelTwo);

        assertTrue(guard.containsOutput(taggedPaper(2)));
        assertFalse(guard.containsOutput(taggedPaper(1)));
    }

    private static ItemStack taggedPaper(int level) {
        ItemStack stack = new ItemStack(Items.PAPER);
        CompoundTag tag = new CompoundTag();
        tag.putInt("level", level);
        stack.setTag(tag);
        return stack;
    }
}
