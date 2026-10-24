package com.huanghuang.rsintegration.mods.tacz;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.tacz.guns.crafting.GunSmithTableRecipe;
import com.tacz.guns.crafting.result.GunSmithTableResult;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TaczRecipeHandlerTest extends BootstrapTest {
    @Test
    void readsInitializedTaczResultThroughOutputAccessor() {
        ItemStack expected = new ItemStack(Items.DIAMOND, 2);
        var result = new GunSmithTableResult(expected, GunSmithTableResult.CUSTOM);
        var recipe = new GunSmithTableRecipe(
                new ResourceLocation("test", "tacz_output"), result, List.of());

        ItemStack actual = new TaczRecipeHandler().getResultItem(recipe, RegistryAccess.EMPTY);

        assertEquals(Items.DIAMOND, actual.getItem());
        assertEquals(2, actual.getCount());
        assertFalse(actual == expected);
    }
}
