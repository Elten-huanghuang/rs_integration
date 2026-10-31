package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.consumption.InputConsumption;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class ResolutionContextDurabilityTest extends BootstrapTest {

    @Test
    void damagePerOperationIsAppliedOnceToExistingAndFreshTools() {
        IngredientSpec spec = new IngredientSpec(Ingredient.of(Items.IRON_PICKAXE),
                1, InputConsumption.durability(2));
        ItemStack worn = new ItemStack(Items.IRON_PICKAXE);
        worn.setDamageValue(worn.getMaxDamage() - 4);
        Level level = mock(Level.class);
        try (var routes = mockStatic(RecipeIndex.class)) {
            routes.when(() -> RecipeIndex.reusableCatalystRoutes(any())).thenReturn(Map.of());
            ResolutionContext context = new ResolutionContext(level, Map.of(),
                    Map.of(CraftingResolver.StackKey.of(worn, true), 1L), null, false, null);

            assertEquals(1L, context.requiredDurabilityItems(spec, 2L));
            assertEquals(2L, context.requiredDurabilityItems(spec, 127L));
            assertEquals(3L, context.requiredDurabilityItems(spec, 128L));
        }
    }

    @Test
    void wornToolCapacityUsesItsActualDamageInsteadOfTheTemplateDurability() {
        Ingredient ingredient = Ingredient.of(Items.IRON_PICKAXE);
        IngredientSpec spec = new IngredientSpec(
                ingredient, 1, InputConsumption.durability(1));
        ItemStack worn = new ItemStack(Items.IRON_PICKAXE);
        worn.setDamageValue(worn.getMaxDamage() - 2);
        CraftingResolver.StackKey wornKey =
                CraftingResolver.StackKey.of(worn, true);

        Level level = mock(Level.class);
        when(level.registryAccess())
                .thenReturn(RegistryAccess.EMPTY);
        try (var routes = mockStatic(RecipeIndex.class)) {
            routes.when(() -> RecipeIndex.reusableCatalystRoutes(any()))
                    .thenReturn(Map.of());
            ResolutionContext context = new ResolutionContext(
                    level, Map.of(), Map.of(wornKey, 1L),
                    null, false, null);

            assertEquals(2L, context.requiredDurabilityItems(spec, 6L));
            assertEquals(3L, context.requiredDurabilityItems(spec, 300L));
        }
    }
}
