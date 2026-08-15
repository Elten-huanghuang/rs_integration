package com.huanghuang.rsintegration.mods.botania;

import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MythicBotanyManaInfuserTest extends BootstrapTest {
    @Test
    void recognizesNativeClassAndKubeJsSerializerPath() {
        assertTrue(MythicBotanyInfuserRecipeHandler.isInfuserRecipeClassName(
                "mythicbotany.infuser.InfuserRecipe"));
        assertFalse(MythicBotanyInfuserRecipeHandler.isInfuserRecipeClassName(
                "dev.latvian.mods.kubejs.recipe.KubeJSRecipe"));
        assertTrue(MythicBotanyInfuserRecipeHandler.isInfuserSerializerId(
                new ResourceLocation("mythicbotany", "infuser")));
        assertFalse(MythicBotanyInfuserRecipeHandler.isInfuserSerializerId(
                new ResourceLocation("minecraft", "crafting_shaped")));
    }

    @Test
    void acceptsExactlyOneEntityPerIngredient() {
        assertTrue(MythicBotanyManaInfuserBatchDelegate.acceptsMaterialLayout(3, List.of(
                new ItemStack(Items.STONE),
                new ItemStack(Items.DIAMOND),
                new ItemStack(Items.FEATHER))));
        assertFalse(MythicBotanyManaInfuserBatchDelegate.acceptsMaterialLayout(3, List.of(
                new ItemStack(Items.STONE),
                new ItemStack(Items.DIAMOND))));
        assertFalse(MythicBotanyManaInfuserBatchDelegate.acceptsMaterialLayout(1, List.of(
                new ItemStack(Items.STONE, 2))));
    }

    @Test
    void rejectsMaterialsThatDoNotMatchTheirIngredients() {
        List<IngredientSpec> specs = List.of(
                new IngredientSpec(Ingredient.of(Items.STONE), 1),
                new IngredientSpec(Ingredient.of(Items.DIAMOND), 1));

        assertTrue(MythicBotanyManaInfuserBatchDelegate.matchesMaterialLayout(specs, List.of(
                new ItemStack(Items.STONE), new ItemStack(Items.DIAMOND))));
        assertFalse(MythicBotanyManaInfuserBatchDelegate.matchesMaterialLayout(specs, List.of(
                new ItemStack(Items.DIAMOND), new ItemStack(Items.STONE))));
    }

    @Test
    void adjacentCaptureRegionsDoNotOverlap() {
        assertFalse(MythicBotanyManaInfuserBatchDelegate.itemRegion(BlockPos.ZERO)
                .intersects(MythicBotanyManaInfuserBatchDelegate.itemRegion(BlockPos.ZERO.east())));
    }
}
