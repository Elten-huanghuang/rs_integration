package com.huanghuang.rsintegration.api.recipe;

import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.api.ResourceMatchMode;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IngredientViewContractTest {
    @Test
    void itemAlternativesAreDeduplicatedWithoutReordering() {
        ItemAlternative first = item("first");
        ItemAlternative second = item("second");

        ItemIngredientView view = new ItemIngredientView(
                List.of(first, second, first), ResourceMatchMode.EXACT_IDENTITY);

        assertEquals(List.of(first, second), view.alternatives());
    }

    @Test
    void partialIdentityRequiresNonEmptyNbt() {
        ItemAlternative empty = item("empty");
        CompoundTag identity = new CompoundTag();
        identity.putString("variant", "charged");
        ItemAlternative partial = new ItemAlternative(new IntegrationResourceKey(
                IntegrationResourceKey.ResourceKind.ITEM,
                new ResourceLocation("test", "partial"),
                identity));

        assertThrows(IllegalArgumentException.class, () -> new ItemIngredientView(
                List.of(empty), ResourceMatchMode.PARTIAL_IDENTITY));
        assertEquals(List.of(partial), new ItemIngredientView(
                List.of(partial), ResourceMatchMode.PARTIAL_IDENTITY).alternatives());
    }

    @Test
    void fluidViewOnlyAcceptsFluidResources() {
        IntegrationResourceKey fluid = new IntegrationResourceKey(
                IntegrationResourceKey.ResourceKind.FLUID,
                new ResourceLocation("minecraft", "water"),
                new CompoundTag());

        assertEquals(List.of(fluid), new FluidIngredientView(
                List.of(fluid, fluid), ResourceMatchMode.ANY_IDENTITY).alternatives());
        assertThrows(IllegalArgumentException.class, () -> new FluidIngredientView(
                List.of(item("wrong").resource()), ResourceMatchMode.ANY_IDENTITY));
    }

    private static ItemAlternative item(String path) {
        return new ItemAlternative(new IntegrationResourceKey(
                IntegrationResourceKey.ResourceKind.ITEM,
                new ResourceLocation("test", path),
                new CompoundTag()));
    }
}
