package com.huanghuang.rsintegration.crafting;

import net.minecraft.SharedConstants;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NonProductiveTagConversionGuardTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void skipsColorConversionForBroadWoolDemand() {
        Ingredient wool = Ingredient.of(Items.WHITE_WOOL, Items.RED_WOOL, Items.BLUE_WOOL);
        ShapelessRecipe recolor = recipe("recolor", new ItemStack(Items.RED_WOOL),
                wool, Ingredient.of(Items.RED_DYE));

        assertTrue(NonProductiveTagConversionGuard.shouldSkip(
                wool, recolor, new ItemStack(Items.RED_WOOL)));
    }

    @Test
    void preservesExactColorTargetAndRealWoolProducer() {
        Ingredient wool = Ingredient.of(Items.WHITE_WOOL, Items.RED_WOOL, Items.BLUE_WOOL);
        ShapelessRecipe recolor = recipe("recolor", new ItemStack(Items.RED_WOOL),
                wool, Ingredient.of(Items.RED_DYE));
        ShapelessRecipe fromString = recipe("from_string", new ItemStack(Items.WHITE_WOOL),
                Ingredient.of(Items.STRING), Ingredient.of(Items.STRING),
                Ingredient.of(Items.STRING), Ingredient.of(Items.STRING));

        assertFalse(NonProductiveTagConversionGuard.shouldSkip(
                Ingredient.of(Items.RED_WOOL), recolor, new ItemStack(Items.RED_WOOL)));
        assertFalse(NonProductiveTagConversionGuard.shouldSkip(
                wool, fromString, new ItemStack(Items.WHITE_WOOL)));
    }

    @Test
    void handlesWoodFamiliesByDemandMembershipInsteadOfHardcodedNames() {
        Ingredient planks = Ingredient.of(Items.OAK_PLANKS, Items.SPRUCE_PLANKS);
        ShapelessRecipe speciesSwap = recipe("species_swap", new ItemStack(Items.SPRUCE_PLANKS),
                Ingredient.of(Items.OAK_PLANKS));
        ShapelessRecipe fromLog = recipe("from_log", new ItemStack(Items.OAK_PLANKS, 4),
                Ingredient.of(Items.OAK_LOG));

        assertTrue(NonProductiveTagConversionGuard.shouldSkip(
                planks, speciesSwap, new ItemStack(Items.SPRUCE_PLANKS)));
        assertFalse(NonProductiveTagConversionGuard.shouldSkip(
                planks, fromLog, new ItemStack(Items.OAK_PLANKS, 4)));
    }

    @Test
    void skipsConcretePowderRecolorButPreservesConcreteHardening() {
        Ingredient powders = Ingredient.of(
                Items.WHITE_CONCRETE_POWDER, Items.RED_CONCRETE_POWDER,
                Items.BLUE_CONCRETE_POWDER);
        ShapelessRecipe recolor = recipe("powder_recolor",
                new ItemStack(Items.RED_CONCRETE_POWDER),
                powders, Ingredient.of(Items.RED_DYE));
        Ingredient concretes = Ingredient.of(Items.WHITE_CONCRETE, Items.RED_CONCRETE);
        ShapelessRecipe hardening = recipe("hardening", new ItemStack(Items.RED_CONCRETE),
                Ingredient.of(Items.RED_CONCRETE_POWDER));

        assertTrue(NonProductiveTagConversionGuard.shouldSkip(
                powders, recolor, new ItemStack(Items.RED_CONCRETE_POWDER)));
        assertFalse(NonProductiveTagConversionGuard.shouldSkip(
                concretes, hardening, new ItemStack(Items.RED_CONCRETE)));
    }

    @Test
    void preservesConversionsThatIncreaseFamilyQuantity() {
        Ingredient family = Ingredient.of(Items.WHITE_WOOL, Items.RED_WOOL);
        ShapelessRecipe duplication = recipe("duplication", new ItemStack(Items.RED_WOOL, 2),
                Ingredient.of(Items.WHITE_WOOL));

        assertFalse(NonProductiveTagConversionGuard.shouldSkip(
                family, duplication, new ItemStack(Items.RED_WOOL, 2)));
    }

    private static ShapelessRecipe recipe(String path, ItemStack output, Ingredient... inputs) {
        NonNullList<Ingredient> ingredients = NonNullList.create();
        for (Ingredient input : inputs) ingredients.add(input);
        return new ShapelessRecipe(new ResourceLocation("test", path), "",
                CraftingBookCategory.MISC, output, ingredients);
    }
}
