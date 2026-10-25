package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.tree.IngredientKey;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class MaterialIdentityConsistencyTest extends BootstrapTest {
    @Test
    void reorderedCompoundsHaveOneCanonicalIdentityAcrossProjections() {
        ItemStack first = tagged(false);
        ItemStack second = tagged(true);

        MaterialKey firstMaterial = MaterialKey.of(first);
        MaterialKey secondMaterial = MaterialKey.of(second);
        CraftingResolver.StackKey firstStack = CraftingResolver.StackKey.of(first, true);
        CraftingResolver.StackKey secondStack = CraftingResolver.StackKey.of(second, true);
        IngredientKey firstIngredient = IngredientKey.of(first);
        IngredientKey secondIngredient = IngredientKey.of(second);
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(Items.DIAMOND);
        MaterialRef firstRef = new MaterialRef(itemId, first.getTag().toString());
        MaterialRef secondRef = new MaterialRef(itemId, second.getTag().toString());

        assertEquals(firstMaterial, secondMaterial);
        assertEquals(firstStack, secondStack);
        assertEquals(firstIngredient, secondIngredient);
        assertEquals(firstRef, secondRef);
        assertEquals(CanonicalNbt.exactSnbt(first.getTag()), firstMaterial.tag());
    }

    @Test
    void exactIdentityPreservesDefaultDamageTag() {
        ItemStack plain = new ItemStack(Items.DIAMOND_SWORD);
        ItemStack pristine = new ItemStack(Items.DIAMOND_SWORD);
        pristine.getOrCreateTag().putInt("Damage", 0);

        MaterialKey plainKey = MaterialKey.of(plain);
        MaterialKey pristineKey = MaterialKey.of(pristine);

        assertEquals(plainKey, pristineKey);
        assertNotNull(plainKey.tag());
        assertNotNull(pristineKey.tag());
        assertEquals(
                CraftingResolver.StackKey.of(plain, true),
                CraftingResolver.StackKey.of(pristine, true));
    }

    @Test
    void runtimeNbtCannotBecomeExactNbt() {
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(Items.DIAMOND_SWORD);

        MaterialRef runtime = new MaterialRef(itemId, "", true);
        MaterialRef exact = new MaterialRef(itemId, "{Damage:0}");

        assertNotEquals(runtime, exact);
    }

    private static ItemStack tagged(boolean reverse) {
        ItemStack stack = new ItemStack(Items.DIAMOND);
        CompoundTag nested = new CompoundTag();
        CompoundTag tag = new CompoundTag();
        if (reverse) {
            nested.putString("owner", "player");
            nested.putInt("level", 3);
            tag.put("nested", nested);
            ListTag list = new ListTag();
            list.add(net.minecraft.nbt.StringTag.valueOf("tail"));
            list.add(net.minecraft.nbt.StringTag.valueOf("head"));
            tag.put("list", list);
            tag.putString("name", "canonical");
        } else {
            tag.putString("name", "canonical");
            ListTag list = new ListTag();
            list.add(net.minecraft.nbt.StringTag.valueOf("tail"));
            list.add(net.minecraft.nbt.StringTag.valueOf("head"));
            tag.put("list", list);
            nested.putInt("level", 3);
            nested.putString("owner", "player");
            tag.put("nested", nested);
        }
        stack.setTag(tag);
        return stack;
    }
}
