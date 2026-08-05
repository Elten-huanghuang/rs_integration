package com.huanghuang.rsintegration.recipe;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AetherworksRecipeAccessTest extends BootstrapTest {
    @Test
    void skipsAnIncompatibleOneArgumentOverload() {
        ItemStack result = AetherworksRecipeAccess.result(
                new MixedOverloads(), RegistryAccess.EMPTY, List.of("getOutput"));

        assertEquals(Items.DIAMOND, result.getItem());
    }

    @Test
    void findsAnInheritedOutputFieldWithoutRepeatedProbing() {
        ItemStack first = AetherworksRecipeAccess.result(
                new FieldRecipe(), RegistryAccess.EMPTY, List.of("missing"));
        ItemStack second = AetherworksRecipeAccess.result(
                new FieldRecipe(), RegistryAccess.EMPTY, List.of("missing"));

        assertEquals(Items.EMERALD, first.getItem());
        assertEquals(Items.EMERALD, second.getItem());
    }

    public static final class MixedOverloads {
        public ItemStack getOutput(String ignored) {
            throw new AssertionError("incompatible overload must not be invoked");
        }

        public ItemStack getOutput() {
            return new ItemStack(Items.DIAMOND);
        }
    }

    private static class FieldBase {
        @SuppressWarnings("unused")
        private final ItemStack output = new ItemStack(Items.EMERALD);
    }

    private static final class FieldRecipe extends FieldBase {}
}
