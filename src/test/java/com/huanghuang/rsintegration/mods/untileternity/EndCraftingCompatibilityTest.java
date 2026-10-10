package com.huanghuang.rsintegration.mods.untileternity;

import com.carrot123.until_eternity.recipe.EndCraftingIngredient;
import com.carrot123.until_eternity.recipe.EndCraftingRecipe;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraphProjector;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EndCraftingCompatibilityTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void indexesActualResultAndPreservesPatternSlots() {
        EndCraftingRecipe recipe = recipe(2, 2, List.of(
                slot(Items.DIAMOND), EndCraftingIngredient.EMPTY,
                slot(Items.EMERALD), slot(Items.GOLD_INGOT)));
        EndCraftingRecipeHandler handler = new EndCraftingRecipeHandler();
        List<IngredientSpec> specs = handler.getIngredients(recipe);

        assertEquals(Items.NETHER_STAR, handler.getResultItem(recipe, RegistryAccess.EMPTY).getItem());
        assertEquals(4, specs.size());
        assertTrue(specs.get(1).isEmpty());
        assertTrue(handler.supportsBackgroundPlanning(recipe));
        ImmutableRecipeGraph.RecipeNode node = ImmutableRecipeGraphProjector.projectRecipe(
                recipe.getId(), handler.getResultItem(recipe, RegistryAccess.EMPTY), specs,
                UntilEternityRSModule.TYPE_ID,
                new ResourceLocation("until_eternity", "end_crafting"));
        assertNotNull(node);
        assertEquals(3, node.inputs().size());

        List<ItemStack> materials = List.of(new ItemStack(Items.DIAMOND), ItemStack.EMPTY,
                new ItemStack(Items.EMERALD), new ItemStack(Items.GOLD_INGOT));
        EndCraftingBatchDelegate.CraftedOutputs crafted =
                EndCraftingBatchDelegate.craft(recipe, materials, RegistryAccess.EMPTY, 1);
        assertNotNull(crafted);
        assertEquals(Items.NETHER_STAR, crafted.result().getItem());
        assertTrue(crafted.secondary().isEmpty());
        assertNull(EndCraftingBatchDelegate.craft(recipe, List.of(
                new ItemStack(Items.DIAMOND), ItemStack.EMPTY,
                new ItemStack(Items.GOLD_INGOT), new ItemStack(Items.EMERALD)),
                RegistryAccess.EMPTY, 1));
    }

    @Test
    void validatesNbtBeforeCommittingAndReturnsContainerRemainders() {
        CompoundTag required = new CompoundTag();
        required.putInt("charge", 3);
        EndCraftingRecipe recipe = recipe(2, 1, List.of(
                new EndCraftingIngredient(Ingredient.of(Items.PAPER), required),
                slot(Items.MILK_BUCKET)));
        ItemStack charged = new ItemStack(Items.PAPER, 2);
        charged.getOrCreateTag().putInt("charge", 3);
        charged.getOrCreateTag().putString("owner", "test");
        EndCraftingRecipeHandler handler = new EndCraftingRecipeHandler();
        assertTrue(handler.getIngredients(recipe).get(0).ingredient().test(charged));
        ImmutableRecipeGraph.RecipeNode node = ImmutableRecipeGraphProjector.projectRecipe(
                recipe.getId(), handler.getResultItem(recipe, RegistryAccess.EMPTY),
                handler.getIngredients(recipe), UntilEternityRSModule.TYPE_ID,
                new ResourceLocation("until_eternity", "end_crafting"));
        assertNotNull(node);
        assertEquals(ImmutableRecipeGraph.NbtMatchMode.PARTIAL,
                node.inputs().get(0).nbtMatchMode());

        EndCraftingBatchDelegate.CraftedOutputs crafted = EndCraftingBatchDelegate.craft(
                recipe, List.of(charged, new ItemStack(Items.MILK_BUCKET, 2)),
                RegistryAccess.EMPTY, 2);
        assertNotNull(crafted);
        assertEquals(2, crafted.result().getCount());
        assertEquals(1, crafted.secondary().size());
        assertEquals(Items.BUCKET, crafted.secondary().get(0).getItem());
        assertEquals(2, crafted.secondary().get(0).getCount());

        ItemStack uncharged = new ItemStack(Items.PAPER, 2);
        assertFalse(handler.getIngredients(recipe).get(0).ingredient().test(uncharged));
        assertNull(EndCraftingBatchDelegate.craft(recipe,
                List.of(uncharged, new ItemStack(Items.MILK_BUCKET, 2)),
                RegistryAccess.EMPTY, 2));
    }

    @Test
    void acceptsCompactReservationForSparseFiveByFivePattern() {
        Map<Character, Item> keys = Map.of(
                'A', Items.DIAMOND,
                'B', Items.GOLD_INGOT,
                'C', Items.EMERALD,
                'E', Items.NETHER_STAR,
                'F', Items.IRON_INGOT,
                'M', Items.BOOK,
                'P', Items.REDSTONE);
        List<EndCraftingIngredient> slots = new ArrayList<>();
        List<ItemStack> compactMaterials = new ArrayList<>();
        for (String row : List.of("  B  ", " APA ", "BFECB", " AMA ", "  B  ")) {
            for (char symbol : row.toCharArray()) {
                Item item = keys.get(symbol);
                slots.add(item == null ? EndCraftingIngredient.EMPTY : slot(item));
                if (item != null) compactMaterials.add(new ItemStack(item));
            }
        }
        EndCraftingRecipe recipe = recipe(5, 5, slots);
        assertEquals(25, slots.size());
        assertEquals(13, compactMaterials.size());

        EndCraftingBatchDelegate.CraftedOutputs crafted = EndCraftingBatchDelegate.craft(
                recipe, compactMaterials, RegistryAccess.EMPTY, 1);
        assertNotNull(crafted);
        assertEquals(Items.NETHER_STAR, crafted.result().getItem());
        assertNull(EndCraftingBatchDelegate.craft(recipe,
                compactMaterials.subList(0, compactMaterials.size() - 1),
                RegistryAccess.EMPTY, 1));
    }

    private static EndCraftingRecipe recipe(int width, int height,
                                            List<EndCraftingIngredient> ingredients) {
        return new EndCraftingRecipe(new ResourceLocation("until_eternity", "test"),
                width, height, ingredients, new ItemStack(Items.NETHER_STAR));
    }

    private static EndCraftingIngredient slot(Item item) {
        return new EndCraftingIngredient(Ingredient.of(item), null);
    }
}
