package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.batch.GenericCraftPacket;
import com.huanghuang.rsintegration.mods.slashblade.SlashBladeRSModule;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlashBladeLogicalCraftingTest extends BootstrapTest {
    @Test
    void slashBladeCraftingRecipesDoNotRequireABoundMachine() {
        SlashBladeRSModule.INSTANCE.registerModType();
        ModType slashBlade = ModType.byId("slashblade");
        ShapelessRecipe craftingRecipe = new ShapelessRecipe(
                new ResourceLocation("slashblade", "slashblade_silverbamboo"), "",
                CraftingBookCategory.MISC, new ItemStack(Items.IRON_SWORD),
                NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.BAMBOO)));

        assertTrue(slashBlade.isVirtual());
        assertFalse(GenericCraftPacket.requiresBoundMachine(craftingRecipe, slashBlade));
    }
}
