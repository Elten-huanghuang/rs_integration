package com.huanghuang.rsintegration.crafting.fluid;

import com.huanghuang.rsintegration.crafting.IngredientSpec;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.crafting.StrictNBTIngredient;
import net.minecraftforge.fluids.FluidStack;

import java.util.List;

/** 确定的容器转换；流体凭据数量以 mB 计，空容器作为真实材料消耗。 */
public final class FluidContainerRecipe implements Recipe<Container> {
    public static final ResourceLocation TYPE_ID = new ResourceLocation("rs_integration", "fluid_container");
    private final ResourceLocation id;
    private final boolean filling;
    private final ItemStack empty;
    private final ItemStack filled;
    private final ItemStack token;
    private final FluidStack fluid;
    private final List<IngredientSpec> specs;

    FluidContainerRecipe(ResourceLocation id, boolean filling, ItemStack empty,
                         ItemStack filled, FluidStack fluid, ItemStack token) {
        this.id = id;
        this.filling = filling;
        this.empty = empty.copyWithCount(1);
        this.filled = filled.copyWithCount(1);
        this.fluid = fluid.copy();
        this.token = token.copyWithCount(fluid.getAmount());
        this.specs = filling
                ? List.of(exact(this.empty, 1), exact(this.token, fluid.getAmount()))
                : List.of(exact(this.filled, 1));
    }

    private static IngredientSpec exact(ItemStack stack, int count) {
        return new IngredientSpec(StrictNBTIngredient.of(stack.copyWithCount(1)), count);
    }

    public boolean filling() { return filling; }
    public ItemStack emptyContainer() { return empty.copy(); }
    public ItemStack filledContainer() { return filled.copy(); }
    public FluidStack fluid() { return fluid.copy(); }
    public List<IngredientSpec> specs() { return specs; }
    public ItemStack output() { return (filling ? filled : token).copy(); }
    public List<ItemStack> secondaryOutputs() { return filling ? List.of() : List.of(empty.copy()); }

    public boolean acceptsMaterials(List<ItemStack> materials, int executions) {
        if (executions <= 0 || materials == null || materials.size() != specs.size()) return false;
        for (int i = 0; i < specs.size(); i++) {
            ItemStack material = materials.get(i);
            IngredientSpec spec = specs.get(i);
            if (material == null || !spec.ingredient().test(material)
                    || material.getCount() != (long) spec.count() * executions) return false;
        }
        return true;
    }

    @Override public boolean matches(Container container, Level level) { return false; }
    @Override public ItemStack assemble(Container container, RegistryAccess access) { return output(); }
    @Override public boolean canCraftInDimensions(int width, int height) { return false; }
    @Override public ItemStack getResultItem(RegistryAccess access) { return output(); }
    @Override public ResourceLocation getId() { return id; }
    @Override public RecipeSerializer<?> getSerializer() { return RecipeSerializer.SHAPELESS_RECIPE; }
    @Override public RecipeType<?> getType() { return RecipeType.CRAFTING; }
    @Override public boolean isSpecial() { return true; }
    @Override public NonNullList<Ingredient> getIngredients() {
        NonNullList<Ingredient> result = NonNullList.create();
        for (IngredientSpec spec : specs) result.add(spec.ingredient());
        return result;
    }
}
