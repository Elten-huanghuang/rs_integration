package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.NbtMatchMode;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.common.crafting.CompoundIngredient;
import net.minecraftforge.common.crafting.PartialNBTIngredient;
import net.minecraftforge.common.crafting.StrictNBTIngredient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CompoundIngredientProjectionTest extends BootstrapTest {
    @ParameterizedTest
    @EnumSource(value = DemandRole.class, names = {"CONSUMED", "CATALYST", "CONTAINER_RETURNING"})
    void partialAlternativesKeepTheirOwnRequirementsAndDemandRole(DemandRole role) {
        CompoundTag first = tag(1);
        CompoundTag second = tag(2);
        Ingredient ingredient = CompoundIngredient.of(
                PartialNBTIngredient.of(Items.DIAMOND, first),
                PartialNBTIngredient.of(Items.EMERALD, second));
        var projected = ImmutableRecipeGraphProjector.projectIngredient(new IngredientSpec(ingredient, 3, role));
        assertNotNull(projected);
        assertEquals(NbtMatchMode.PARTIAL, projected.nbtMatchMode());
        assertEquals(role, projected.role());
        assertEquals(3, projected.count());
        assertEquals(2, projected.alternatives().size());

        // 实际物品携带额外 NBT 时仍满足子集要求；各候选不能串用另一分支的等级。
        MaterialRef diamond = material(Items.DIAMOND, 1, true);
        MaterialRef emerald = material(Items.EMERALD, 2, true);
        MaterialRef wrong = material(Items.EMERALD, 1, true);
        var bound = ImmutableRecipeGraphProjector.bindIngredient(projected,
                Map.of(diamond, 3L, emerald, 3L, wrong, 3L));
        assertTrue(bound.alternatives().contains(diamond));
        assertTrue(bound.alternatives().contains(emerald));
        assertFalse(bound.alternatives().contains(wrong));
        first.putInt("level", 99);
        second.putInt("level", 99);
        assertEquals(projected, ImmutableRecipeGraphProjector.projectIngredient(new IngredientSpec(
                CompoundIngredient.of(PartialNBTIngredient.of(Items.DIAMOND, tag(1)),
                        PartialNBTIngredient.of(Items.EMERALD, tag(2))), 3, role)),
                "后台值不能继续引用主线程的可变 NBT");
    }

    @Test
    void strictAlternativesDoNotAcceptExtraNbt() {
        Ingredient ingredient = CompoundIngredient.of(
                StrictNBTIngredient.of(stack(Items.DIAMOND, 1)),
                StrictNBTIngredient.of(stack(Items.EMERALD, 2)));
        var projected = ImmutableRecipeGraphProjector.projectIngredient(new IngredientSpec(ingredient, 1));
        assertNotNull(projected);
        assertEquals(NbtMatchMode.EXACT, projected.nbtMatchMode());
        MaterialRef extra = material(Items.DIAMOND, 1, true);
        assertFalse(ImmutableRecipeGraphProjector.bindIngredient(projected, Map.of(extra, 1L))
                .alternatives().contains(extra));
    }

    @Test
    void mixedMatchRulesCannotSilentlyLoseConstraints() {
        Ingredient strict = StrictNBTIngredient.of(stack(Items.DIAMOND, 1));
        Ingredient partial = PartialNBTIngredient.of(Items.EMERALD, tag(2));
        assertNull(ImmutableRecipeGraphProjector.projectIngredient(new IngredientSpec(
                CompoundIngredient.of(strict, partial), 1)));
        assertNull(ImmutableRecipeGraphProjector.projectIngredient(new IngredientSpec(
                CompoundIngredient.of(Ingredient.of(Items.IRON_INGOT), partial), 1)));
    }

    @Test
    void nestedAlternativesAreFlattenedWithoutDuplicatingMaterials() {
        Ingredient diamond = PartialNBTIngredient.of(Items.DIAMOND, tag(1));
        Ingredient emerald = PartialNBTIngredient.of(Items.EMERALD, tag(2));
        var projected = ImmutableRecipeGraphProjector.projectIngredient(new IngredientSpec(
                CompoundIngredient.of(CompoundIngredient.of(diamond, emerald), diamond), 1));
        assertNotNull(projected);
        assertEquals(NbtMatchMode.PARTIAL, projected.nbtMatchMode());
        assertEquals(2, projected.alternatives().size());
    }

    private static CompoundTag tag(int level) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("level", level);
        return tag;
    }

    private static ItemStack stack(Item item, int level) {
        ItemStack stack = new ItemStack(item);
        stack.setTag(tag(level));
        return stack;
    }

    private static MaterialRef material(Item item, int level, boolean extra) {
        ItemStack stack = stack(item, level);
        if (extra) stack.getOrCreateTag().putString("owner", "test");
        return ImmutableRecipeGraphProjector.material(stack, true);
    }
}
