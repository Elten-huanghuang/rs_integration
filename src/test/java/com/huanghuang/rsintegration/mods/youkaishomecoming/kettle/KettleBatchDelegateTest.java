package com.huanghuang.rsintegration.mods.youkaishomecoming.kettle;

import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.mods.youkaishomecoming.kettle.KettleRecipeSupport.Brewing;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.templates.FluidTank;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KettleBatchDelegateTest extends BootstrapTest {
    @Test
    void requiresTheDeclaredContainersSeparatelyFromSolids() {
        Brewing brewing = brewing();
        ItemStack apple = new ItemStack(Items.APPLE);
        assertEquals(1, brewing.solids().size());
        assertEquals(4, brewing.ingredients().get(1).count());
        assertFalse(KettleBatchDelegate.matchesMaterials(brewing,
                List.of(apple, new ItemStack(Items.GLASS_BOTTLE, 3))));
        assertTrue(KettleBatchDelegate.matchesMaterials(brewing,
                List.of(apple, new ItemStack(Items.GLASS_BOTTLE, 4))));
    }

    @Test
    void partialOrWrongFluidCannotBeCollectedAndAFullBatchCanBeCollectedOnlyOnce() {
        Brewing brewing = brewing();
        FluidTank tank = new FluidTank(2000);
        tank.setFluid(new FluidStack(Fluids.WATER, 1000));
        assertTrue(KettleBatchDelegate.collectBrewed(brewing, tank).isEmpty());
        assertEquals(1000, tank.getFluidAmount());
        tank.setFluid(new FluidStack(Fluids.LAVA, 750));
        assertTrue(KettleBatchDelegate.collectBrewed(brewing, tank).isEmpty());
        assertEquals(750, tank.getFluidAmount());
        tank.setFluid(new FluidStack(Fluids.LAVA, 1000));
        ItemStack collected = KettleBatchDelegate.collectBrewed(brewing, tank);
        assertTrue(collected.is(Items.HONEY_BOTTLE));
        assertEquals(4, collected.getCount());
        assertTrue(KettleBatchDelegate.collectBrewed(brewing, tank).isEmpty());
    }

    private static Brewing brewing() {
        return new Brewing(new FluidStack(Fluids.LAVA, 1000),
                List.of(new IngredientSpec(Ingredient.of(Items.APPLE), 1)),
                new ItemStack(Items.GLASS_BOTTLE, 4), new ItemStack(Items.HONEY_BOTTLE, 4));
    }
}
