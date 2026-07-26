package com.huanghuang.rsintegration.mods.malum;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MalumVoidFavorVirtualRecipeHandlerTest extends BootstrapTest {

    @Test
    void removesOutputItemFromTagCandidatesToPreventIdentityCycles() {
        ItemStack output = new ItemStack(Items.COAL);
        ItemStack[] filtered = MalumVoidFavorVirtualRecipeHandler.withoutIdentityOutput(
                new ItemStack[]{new ItemStack(Items.COAL), new ItemStack(Items.CHARCOAL)},
                output);

        assertEquals(1, filtered.length);
        assertEquals(Items.CHARCOAL, filtered[0].getItem());
        assertEquals(1, filtered[0].getCount());
    }

    @Test
    void rejectsAnIdentityOnlyIngredient() {
        ItemStack output = new ItemStack(Items.COAL);

        assertEquals(0, MalumVoidFavorVirtualRecipeHandler.withoutIdentityOutput(
                new ItemStack[]{new ItemStack(Items.COAL)}, output).length);
    }

    @Test
    void retainsSameItemWhenNbtActuallyChanges() {
        ItemStack input = new ItemStack(Items.WRITTEN_BOOK);
        input.getOrCreateTag().putString("state", "before");
        ItemStack output = new ItemStack(Items.WRITTEN_BOOK);
        output.getOrCreateTag().putString("state", "after");

        ItemStack[] filtered = MalumVoidFavorVirtualRecipeHandler.withoutIdentityOutput(
                new ItemStack[]{input}, output);

        assertEquals(1, filtered.length);
        assertEquals("before", filtered[0].getTag().getString("state"));
    }
}
