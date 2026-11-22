package com.huanghuang.rsintegration.mods.farmersdelight;

import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import io.redspace.ironsspellbooks.api.item.IScroll;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.ISpellContainer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.wrapper.RecipeWrapper;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

final class FarmersSpellPotSupport {
    static final String RECIPE_CLASS = "com.chenjdy.farmers_spell.recipe.AlchemistCookingRecipe";
    static final String BLOCK_CLASS = "com.chenjdy.farmers_spell.block.AlchemistPotBlock";
    static final String BLOCK_ID = "farmers_spell:alchemist_pot";
    static final String BLOCK_DESCRIPTION = "block.farmers_spell.alchemist_pot";
    static final String RECIPE_TYPE_ID = "farmers_spell:alchemist_cooking";
    private static final ResourceLocation RECIPE_TYPE = ResourceLocation.parse(RECIPE_TYPE_ID);
    private static final ResourceLocation SCROLL = ResourceLocation.parse("irons_spellbooks:scroll");
    static final ResourceLocation FIRE = ResourceLocation.parse("irons_spellbooks:fire");

    private FarmersSpellPotSupport() {}

    static boolean isRecipe(@Nullable Recipe<?> recipe) {
        return recipe != null && RECIPE_CLASS.equals(recipe.getClass().getName());
    }

    @Nullable
    static ResourceLocation requiredSchool(Recipe<?> recipe) {
        if (!isRecipe(recipe)) return null;
        try {
            return (ResourceLocation) recipe.getClass().getMethod("getRequiredSchool").invoke(recipe);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Unable to read Arcane Cooking Pot recipe school", ex);
        }
    }

    static boolean isPotBinding(@Nullable String blockKey) {
        return blockKey != null && (blockKey.equals(BLOCK_DESCRIPTION)
                || blockKey.endsWith("||" + BLOCK_DESCRIPTION));
    }

    static IngredientSpec scrollSpec(ResourceLocation schoolId) {
        var scrollItem = ForgeRegistries.ITEMS.getValue(SCROLL);
        List<ItemStack> displays = new ArrayList<>();
        if (scrollItem instanceof IScroll) {
            // 按当前配置的流派 ID 枚举，避免复用模组按流派对象缓存的旧结果。
            for (var spell : SpellRegistry.REGISTRY.get().getValues()) {
                if (spell == SpellRegistry.none() || !schoolId.equals(spell.getSchoolType().getId())) continue;
                for (int level = spell.getMinLevel(), max = spell.getMaxLevel(); level <= max; level++) {
                    ItemStack stack = new ItemStack(scrollItem);
                    ISpellContainer.createScrollContainer(spell, level, stack);
                    displays.add(stack);
                }
            }
        }
        // 后台规划按带 NBT 的候选枚举。执行时按流派验证实际卷轴。
        return new IngredientSpec(new ScrollIngredient(displays, stack -> schoolId.equals(schoolOf(stack))),
                1, DemandRole.CATALYST);
    }

    @Nullable
    static ResourceLocation schoolOf(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof IScroll)) return null;
        var container = ISpellContainer.get(stack);
        if (container == null) return null;
        var spell = container.getSpellAtIndex(0);
        return spell == null ? null : spell.getSpell().getSchoolType().getId();
    }

    static boolean matchesNativeChoice(ServerLevel level, Recipe<?> requested, List<ItemStack> food,
                                       ItemStack scroll, boolean hasServingContainer) {
        int foodSlots = food.size() - (hasServingContainer ? 1 : 0);
        if (foodSlots < 1 || foodSlots > 6) return false;
        var preview = new ItemStackHandler(9);
        for (int slot = 0; slot < foodSlots; slot++) {
            preview.setStackInSlot(slot, food.get(slot).copy());
        }
        RecipeType<?> type = ForgeRegistries.RECIPE_TYPES.getValue(RECIPE_TYPE);
        if (type == null) return false;
        var magic = firstMatchingRecipe(level, type, new RecipeWrapper(preview));
        if (magic != null) {
            ResourceLocation school = requiredSchool(magic);
            if (school == null || school.equals(schoolOf(scroll))) return magic.getId().equals(requested.getId());
        }
        return !isRecipe(requested);
    }

    static boolean hasHeat(BlockEntity pot, Level level, BlockPos pos, ItemStack selectedScroll) {
        if (FIRE.equals(schoolOf(selectedScroll))) return true;
        try {
            return Boolean.TRUE.equals(pot.getClass().getMethod("isHeated", Level.class, BlockPos.class)
                    .invoke(pot, level, pos));
        } catch (ReflectiveOperationException ex) {
            return false;
        }
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private static Recipe<?> firstMatchingRecipe(ServerLevel level, RecipeType<?> type, RecipeWrapper inventory) {
        return level.getRecipeManager().getRecipeFor((RecipeType<Recipe<RecipeWrapper>>) type, inventory, level)
                .orElse(null);
    }

    static final class ScrollIngredient extends Ingredient {
        private final Predicate<ItemStack> matchesSchool;

        ScrollIngredient(List<ItemStack> displays, Predicate<ItemStack> matchesSchool) {
            super(displays.stream().map(ItemValue::new));
            this.matchesSchool = matchesSchool;
        }

        @Override
        public boolean test(@Nullable ItemStack stack) {
            return stack != null && !stack.isEmpty() && matchesSchool.test(stack);
        }

        @Override
        public boolean isEmpty() {
            // 展示候选为空时，库存中已有的正确卷轴仍可满足这项需求。
            return false;
        }

        @Override
        public boolean isSimple() {
            return false;
        }
    }
}
