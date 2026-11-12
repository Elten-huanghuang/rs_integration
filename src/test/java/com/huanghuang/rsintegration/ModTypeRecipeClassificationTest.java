package com.huanghuang.rsintegration;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModTypeRecipeClassificationTest extends BootstrapTest {
    @Test
    void unknownDisplayRecipeWithoutSerializerRemainsUnclassified() {
        CraftingRecipe recipe = new DisplayRecipe();

        assertThrows(UnsupportedOperationException.class, recipe::getSerializer);
        assertNull(ModType.classifyRecipe(recipe));
    }

    @Test
    void displayRecipeWithoutSerializerStillUsesRegisteredClassRoute() {
        ModType expected = registerType("test_display_recipe", RegisteredDisplayRecipe.class);

        assertSame(expected, ModType.classifyRecipe(new RegisteredDisplayRecipe()));
    }

    @Test
    void ordinaryRecipeWithSerializerKeepsRegisteredClassRoute() {
        ModType expected = registerType("test_serializable_recipe", SerializableRecipe.class);

        assertSame(expected, ModType.classifyRecipe(new SerializableRecipe()));
    }

    private static ModType registerType(String id, Class<?> recipeClass) {
        return ModType.register(id, new String[]{recipeClass.getName()},
                new String[0], new String[0], () -> null);
    }

    /** 匠魂的 JEI 展示配方实现 CraftingRecipe，但明确不提供序列化器。 */
    private static class DisplayRecipe implements CraftingRecipe {
        @Override
        public RecipeSerializer<?> getSerializer() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ResourceLocation getId() {
            return new ResourceLocation("test", "display_recipe");
        }

        @Override
        public CraftingBookCategory category() {
            return CraftingBookCategory.MISC;
        }

        @Override
        public boolean matches(CraftingContainer container, Level level) {
            return false;
        }

        @Override
        public boolean canCraftInDimensions(int width, int height) {
            return false;
        }

        @Override
        public ItemStack getResultItem(RegistryAccess access) {
            return ItemStack.EMPTY;
        }

        @Override
        public ItemStack assemble(CraftingContainer container, RegistryAccess access) {
            return ItemStack.EMPTY;
        }
    }

    private static final class RegisteredDisplayRecipe extends DisplayRecipe {}

    private static final class SerializableRecipe extends DisplayRecipe {
        @Override
        public RecipeSerializer<?> getSerializer() {
            return RecipeSerializer.SHAPELESS_RECIPE;
        }
    }
}
