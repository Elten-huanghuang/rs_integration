package com.huanghuang.rsintegration.mods.ironsspellbooks;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler.FluidAction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class AlchemistPotionSupportTest extends BootstrapTest {
    @BeforeAll static void registerPotionFluid() { InkFluidTestFixtures.ink("potion", 250); }

    static ItemStack potion(String type) {
        Item item = switch (type) {
            case "SPLASH" -> Items.SPLASH_POTION;
            case "LINGERING" -> Items.LINGERING_POTION;
            default -> Items.POTION;
        };
        return PotionUtils.setPotion(new ItemStack(item), Potions.STRONG_HEALING);
    }

    @ParameterizedTest @ValueSource(strings = {"REGULAR", "SPLASH", "LINGERING"})
    void potionRoundTripRetainsTypeEffectAndCustomTags(String type) {
        ItemStack potion = potion(type);
        potion.getOrCreateTag().putInt("CustomPotionColor", 123456);
        potion.getOrCreateTag().putString("custom", "retained");
        FluidStack fluid = AlchemistPotionSupport.fluid(potion);
        assertEquals(250, fluid.getAmount());
        assertEquals(type, fluid.getTag().getString("irons_spellbooks:bottle_type"));
        assertTrue(ItemStack.isSameItemSameTags(potion, AlchemistPotionSupport.bottle(fluid)));
        var handler = InkBottleFluidHandler.create(new ItemStack(Items.GLASS_BOTTLE));
        assertEquals(250, handler.fill(fluid, handlerAction(false)));
        assertTrue(handler.getContainer().is(Items.GLASS_BOTTLE));
        assertEquals(250, handler.fill(fluid, handlerAction(true)));
        assertTrue(ItemStack.isSameItemSameTags(potion, handler.getContainer()));
    }

    @Test void invalidPotionTypeOrMissingPotionCannotConsumeBottle() {
        FluidStack fluid = InkFluidTestFixtures.ink("potion", 250);
        var handler = InkBottleFluidHandler.create(new ItemStack(Items.GLASS_BOTTLE));
        assertEquals(0, handler.fill(fluid, handlerAction(true)));
        fluid = AlchemistPotionSupport.fluid(potion("REGULAR"));
        fluid.getTag().putString("irons_spellbooks:bottle_type", "INVALID");
        assertEquals(0, handler.fill(fluid, handlerAction(true)));
        assertTrue(handler.getContainer().is(Items.GLASS_BOTTLE));
    }

    private static FluidAction handlerAction(boolean execute) {
        return execute ? FluidAction.EXECUTE : FluidAction.SIMULATE;
    }
}
