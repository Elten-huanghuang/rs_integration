package com.huanghuang.rsintegration.recipe;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MachineBindingCompatibilityTest extends BootstrapTest {

    @Test
    void eidolonRecipesRequireTheirConcreteMachineFamily() {
        EidolonRecipeHandler handler = new EidolonRecipeHandler();

        assertTrue(handler.isCompatibleBinding(new CrucibleRecipe(),
                "eidolon_ritual||block.eidolon.crucible"));
        assertFalse(handler.isCompatibleBinding(new CrucibleRecipe(),
                "eidolon_ritual||block.eidolon.brazier"));
        assertTrue(handler.isCompatibleBinding(new ItemRitualRecipe(),
                "eidolon_ritual||block.eidolon.brazier"));
        assertFalse(handler.isCompatibleBinding(new ItemRitualRecipe(),
                "eidolon_ritual||block.eidolon.crucible"));
    }

    @Test
    void avaritiaRecipesRequireTheMatchingTableTier() {
        AvaritiaRecipeHandler handler = new AvaritiaRecipeHandler();
        Recipe<?> tierThree = new TieredAvaritiaRecipe(3);

        assertTrue(handler.isCompatibleBinding(tierThree,
                "avaritia_crafting||block.avaritia.end_crafting_table"));
        assertFalse(handler.isCompatibleBinding(tierThree,
                "avaritia_crafting||block.avaritia.sculk_crafting_table"));
        assertFalse(handler.isCompatibleBinding(tierThree,
                "avaritia_crafting||block.avaritia.extreme_crafting_table"));
    }

    @Test
    void crockPotTierOneRecipesRequireThePortablePot() {
        CrockPotRecipeHandler handler = new CrockPotRecipeHandler();

        assertTrue(handler.isCompatibleBinding(new PotRecipe(0),
                "crockpot||block.crockpot.crock_pot"));
        assertTrue(handler.isCompatibleBinding(new PotRecipe(1),
                "crockpot||block.crockpot.portable_crock_pot"));
        assertFalse(handler.isCompatibleBinding(new PotRecipe(1),
                "crockpot||block.crockpot.crock_pot"));
    }

    @Test
    void crystalRitualRecipeUsesArcaneIteratorRatherThanCrystalBlock() {
        WRRecipeHandler handler = new WRRecipeHandler();
        Recipe<?> recipe = new CrystalRitualRecipe();

        assertTrue(handler.isCompatibleBinding(recipe,
                "wizards_reborn||block.wizards_reborn.arcane_iterator"));
        assertFalse(handler.isCompatibleBinding(recipe,
                "crystal_ritual||block.wizards_reborn.crystal"));
    }

    public abstract static class StubRecipe implements Recipe<Container> {
        @Override public boolean matches(Container container, Level level) { return false; }
        @Override public ItemStack assemble(Container container, RegistryAccess access) {
            return ItemStack.EMPTY;
        }
        @Override public boolean canCraftInDimensions(int width, int height) { return false; }
        @Override public ItemStack getResultItem(RegistryAccess access) { return ItemStack.EMPTY; }
        @Override public ResourceLocation getId() { return new ResourceLocation("test", "recipe"); }
        @Override public RecipeSerializer<?> getSerializer() { return RecipeSerializer.SHAPELESS_RECIPE; }
        @Override public RecipeType<?> getType() { return RecipeType.CRAFTING; }
    }

    public static final class CrucibleRecipe extends StubRecipe {}
    public static final class ItemRitualRecipe extends StubRecipe {}
    public static final class CrystalRitualRecipe extends StubRecipe {}

    public static final class TieredAvaritiaRecipe extends StubRecipe {
        private final int tier;
        TieredAvaritiaRecipe(int tier) { this.tier = tier; }
        public int getTier() { return tier; }
    }

    public static final class PotRecipe extends StubRecipe {
        private final int tier;
        PotRecipe(int tier) { this.tier = tier; }
        public int getPotLevel() { return tier; }
    }
}
