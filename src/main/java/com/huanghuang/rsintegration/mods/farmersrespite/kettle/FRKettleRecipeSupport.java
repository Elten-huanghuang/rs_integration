package com.huanghuang.rsintegration.mods.farmersrespite.kettle;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.reflection.probes.FRReflection;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.fluids.capability.IFluidHandler.FluidAction;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public final class FRKettleRecipeSupport {

    private static final ResourceLocation POURING_TYPE =
            ResourceLocation.fromNamespaceAndPath("farmersrespite", "kettle_pouring");

    private FRKettleRecipeSupport() {}

    @Nullable
    public static RecipeManager currentRecipeManager() {
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server != null && server.isSameThread()) return server.getRecipeManager();
        return DistExecutor.unsafeCallWhenOn(Dist.CLIENT, () -> ClientRecipes::recipeManager);
    }

    private static final class ClientRecipes {
        @Nullable
        private static RecipeManager recipeManager() {
            var minecraft = Minecraft.getInstance();
            return minecraft == null || minecraft.level == null ? null : minecraft.level.getRecipeManager();
        }
    }

    public static FluidStack fluidIn(Recipe<?> recipe) {
        return invokeFluid(recipe, "getFluidIn");
    }

    public static FluidStack fluidOut(Recipe<?> recipe) {
        return invokeFluid(recipe, "getFluidOut");
    }

    public static boolean isWater(FluidStack fluid) {
        return fluid != null && !fluid.isEmpty()
                && (fluid.getFluid() == Fluids.WATER || fluid.getFluid() == Fluids.FLOWING_WATER);
    }

    @Nullable
    @SuppressWarnings("unchecked")
    public static Brewing brewing(Recipe<?> recipe, @Nullable RecipeManager manager) {
        if (manager == null) return null;
        RecipeType<?> type = ForgeRegistries.RECIPE_TYPES.getValue(POURING_TYPE);
        Class<?> pouringClass = FRReflection.kettlePouringRecipeClass;
        if (type == null || pouringClass == null) return null;
        return brewing(recipe, manager.getAllRecipesFor((RecipeType<Recipe<Container>>) type), pouringClass);
    }

    @Nullable
    static Brewing brewing(Recipe<?> recipe, List<? extends Recipe<?>> pouringRecipes, Class<?> pouringClass) {
        FluidStack fluidOut = fluidOut(recipe);
        FluidStack fluidIn = fluidIn(recipe);
        Bottling output = findBottling(pouringRecipes, pouringClass, fluidOut, false);
        if (output == null || fluidIn.isEmpty()) return null;
        if (isWater(fluidIn)) return new Brewing(fluidIn, fluidOut, null, output);

        // 苹果酒等流体的装瓶产物可来自其他命名空间，必须使用实际装瓶配方。
        Bottling input = findBottling(pouringRecipes, pouringClass, fluidIn, true);
        if (input == null) input = findBucketInput(fluidIn);
        return input == null ? null : new Brewing(fluidIn, fluidOut, input, output);
    }

    @Nullable
    private static Bottling findBucketInput(FluidStack fluid) {
        Item bucket = fluid.getFluid().getBucket();
        if (bucket == Items.AIR) return null;
        ItemStack filled = new ItemStack(bucket);
        return FluidUtil.getFluidHandler(filled.copy()).map(handler -> {
            // 原生容器槽一次只会排空一个有流体能力的容器。
            FluidStack drained = handler.drain(Integer.MAX_VALUE, FluidAction.EXECUTE);
            ItemStack empty = handler.getContainer();
            return drained.isFluidStackIdentical(fluid) && !empty.isEmpty()
                    ? new Bottling(empty.copy(), filled) : null;
        }).orElse(null);
    }

    @Nullable
    private static Bottling findBottling(List<? extends Recipe<?>> recipes, Class<?> pouringClass,
                                        FluidStack batch, boolean exactVolume) {
        if (batch.isEmpty()) return null;
        try {
            Method getFluid = pouringClass.getMethod("getFluid");
            Method getAmount = pouringClass.getMethod("getAmount");
            Method getContainer = pouringClass.getMethod("getContainer");
            Method getOutput = pouringClass.getMethod("getOutput");
            for (Recipe<?> recipe : recipes) {
                if (!pouringClass.isInstance(recipe)
                        || !(getFluid.invoke(recipe) instanceof Fluid fluid)
                        || !fluid.isSame(batch.getFluid())) continue;
                int amount = (int) getAmount.invoke(recipe);
                if (amount <= 0 || amount > batch.getAmount()
                        || exactVolume && batch.getAmount() % amount != 0) continue;
                if (!(getContainer.invoke(recipe) instanceof ItemStack container) || container.isEmpty()
                        || !(getOutput.invoke(recipe) instanceof ItemStack output) || output.isEmpty()) continue;

                int pours = batch.getAmount() / amount;
                return new Bottling(container.copyWithCount(container.getCount() * pours),
                        output.copyWithCount(output.getCount() * pours));
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            RSIntegrationMod.LOGGER.debug("[RSI-FRKettle] 装瓶配方读取失败", e);
        }
        return null;
    }

    record Bottling(ItemStack container, ItemStack drink) {}

    public record Brewing(FluidStack fluidIn, FluidStack fluidOut, @Nullable Bottling input, Bottling output) {
        public ItemStack result() {
            return output.drink().copy();
        }

        private int reusedContainers() {
            return input != null && ItemStack.isSameItemSameTags(input.container(), output.container())
                    ? Math.min(input.container().getCount(), output.container().getCount()) : 0;
        }

        int extraContainers() {
            return output.container().getCount() - reusedContainers();
        }

        public ItemStack returnedContainer() {
            return input == null ? ItemStack.EMPTY
                    : input.container().copyWithCount(input.container().getCount() - reusedContainers());
        }

        public List<IngredientSpec> ingredients(Recipe<?> recipe) {
            List<IngredientSpec> specs = new ArrayList<>();
            for (Ingredient ingredient : recipe.getIngredients()) {
                if (!ingredient.isEmpty()) specs.add(new IngredientSpec(ingredient, 1));
            }
            if (input != null) {
                specs.add(new IngredientSpec(Ingredient.of(input.drink()), input.drink().getCount()));
            }
            int extra = extraContainers();
            if (extra > 0) specs.add(new IngredientSpec(Ingredient.of(output.container()), extra));
            return List.copyOf(specs);
        }
    }

    private static FluidStack invokeFluid(Recipe<?> recipe, String methodName) {
        if (recipe == null) return FluidStack.EMPTY;
        try {
            Method method = recipe.getClass().getMethod(methodName);
            Object value = method.invoke(recipe);
            return value instanceof FluidStack stack ? stack.copy() : FluidStack.EMPTY;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return FluidStack.EMPTY;
        }
    }
}
