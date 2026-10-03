package com.huanghuang.rsintegration.crafting.fluid;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.RecipeIndex;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraphProjector;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandlerItem;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Function;

/** 从真实 Forge 容器能力生成执行与 JEI 共用的转换定义。 */
public final class FluidContainerCatalog {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<ResourceLocation, FluidContainerRecipe> BY_ID = new LinkedHashMap<>();
    private static final Map<RecipeManager, List<FluidContainerRecipe>> CATALOGS = new WeakHashMap<>();

    private FluidContainerCatalog() {}

    public static synchronized void index(Level level, Map<Item, List<RecipeIndex.Entry>> index,
            Set<ResourceLocation> seen,
            Map<ImmutableRecipeGraph.MaterialRef, List<ImmutableRecipeGraph.RecipeNode>> projected) {
        List<Recipe<?>> recipes = new ArrayList<>(level.getRecipeManager().getRecipes());
        for (List<RecipeIndex.Entry> entries : index.values()) {
            for (RecipeIndex.Entry entry : entries) recipes.add(entry.recipe());
        }
        List<FluidContainerRecipe> definitions = build(level, recipes);
        CATALOGS.put(level.getRecipeManager(), definitions);
        publish(definitions, index, seen, projected);
    }

    static void publish(List<FluidContainerRecipe> definitions, Map<Item, List<RecipeIndex.Entry>> index,
            Set<ResourceLocation> seen,
            Map<ImmutableRecipeGraph.MaterialRef, List<ImmutableRecipeGraph.RecipeNode>> projected) {
        for (FluidContainerRecipe recipe : definitions) {
            BY_ID.put(recipe.getId(), recipe);
            if (!seen.add(recipe.getId())) continue;
            index.computeIfAbsent(recipe.output().getItem(), ignored -> new ArrayList<>())
                    .add(new RecipeIndex.Entry(recipe, ModType.FLUID_CONTAINER,
                            FluidContainerRecipe.TYPE_ID, true));
            var node = ImmutableRecipeGraphProjector.projectRecipe(recipe.getId(), recipe.output(),
                    recipe.specs(), ModType.FLUID_CONTAINER.id(), FluidContainerRecipe.TYPE_ID);
            if (node != null) projected.computeIfAbsent(node.output(), ignored -> new ArrayList<>()).add(node);
        }
    }

    public static synchronized List<FluidContainerRecipe> allRecipes(Level level) {
        if (level == null) return List.of();
        return CATALOGS.computeIfAbsent(level.getRecipeManager(), ignored -> {
            List<FluidContainerRecipe> recipes = build(level, level.getRecipeManager().getRecipes());
            for (FluidContainerRecipe recipe : recipes) BY_ID.put(recipe.getId(), recipe);
            return recipes;
        });
    }

    public static synchronized FluidContainerRecipe byId(ResourceLocation id) { return BY_ID.get(id); }

    public static synchronized void invalidate() {
        BY_ID.clear();
        CATALOGS.clear();
    }

    public static FluidContainerRecipe resolve(Level level, ResourceLocation id) {
        if (!"rs_integration".equals(id.getNamespace()) || !id.getPath().startsWith("fluid_container/")) return null;
        FluidContainerRecipe recipe = byId(id);
        if (recipe == null) { allRecipes(level); recipe = byId(id); }
        return recipe;
    }

    private static List<FluidContainerRecipe> build(Level level, Collection<Recipe<?>> recipes) {
        List<ItemStack> samples = new ArrayList<>();
        List<FluidStack> fluids = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) samples.add(item.getDefaultInstance());
        for (Fluid fluid : BuiltInRegistries.FLUID) {
            if (fluid != Fluids.EMPTY && (!(fluid instanceof FlowingFluid flowing) || flowing.getSource() == fluid)) {
                fluids.add(new FluidStack(fluid, 1));
            }
        }
        Set<ResourceLocation> inspected = new HashSet<>();
        for (Recipe<?> recipe : recipes) {
            if (!inspected.add(recipe.getId()) || recipe instanceof FluidContainerRecipe) continue;
            try {
                samples.add(ModRecipeHandlers.tryGetResultItem(recipe, level.registryAccess()));
                samples.addAll(ModRecipeHandlers.tryGetSecondaryOutputs(recipe, level.registryAccess()));
                List<IngredientSpec> specs = CraftPacketUtils.extractIngredientSpecs(recipe);
                if (specs != null) for (IngredientSpec spec : specs) {
                    samples.addAll(List.of(spec.ingredient().getItems()));
                }
            } catch (RuntimeException | LinkageError error) {
                LOGGER.debug("无法读取液体容器候选配方 {}", recipe.getId(), error);
            }
        }
        for (ItemStack sample : samples) {
            if (InkFluidSupport.isToken(sample)) fluids.add(InkFluidSupport.fluid(sample));
        }
        return discover(samples, fluids, FluidContainerCatalog::handler, InkFluidSupport::token);
    }

    private static IFluidHandlerItem handler(ItemStack stack) {
        return FluidUtil.getFluidHandler(stack.copyWithCount(1)).orElse(null);
    }

    static List<FluidContainerRecipe> discover(List<ItemStack> samples, List<FluidStack> candidates,
            Function<ItemStack, IFluidHandlerItem> handlers, Function<FluidStack, ItemStack> tokens) {
        handlers = FluidContainerBucketSupport.handlers(samples, handlers);
        Map<String, ItemStack> empty = new LinkedHashMap<>();
        Map<String, FluidStack> fluids = new LinkedHashMap<>();
        Map<ResourceLocation, FluidContainerRecipe> recipes = new LinkedHashMap<>();
        for (FluidStack fluid : candidates) addFluid(fluids, fluid);
        Set<String> seen = new HashSet<>();
        for (ItemStack sample : samples) {
            if (sample.isEmpty() || !seen.add(stackKey(sample))) continue;
            try {
                IFluidHandlerItem handler = handlers.apply(sample.copyWithCount(1));
                if (handler == null || handler.getTanks() != 1) continue;
                FluidStack content = handler.getFluidInTank(0).copy();
                if (content.isEmpty()) empty.putIfAbsent(stackKey(sample), sample.copyWithCount(1));
                else {
                    addFluid(fluids, content);
                    ItemStack drained = drain(sample, content, handlers);
                    if (!drained.isEmpty()) {
                        empty.putIfAbsent(stackKey(drained), drained);
                        add(recipes, false, drained, sample, content, tokens);
                    }
                }
            } catch (RuntimeException | LinkageError error) {
                LOGGER.debug("无法探测液体容器 {}", sample, error);
            }
        }
        for (ItemStack container : empty.values()) for (FluidStack identity : fluids.values()) {
            try {
                IFluidHandlerItem handler = handlers.apply(container.copyWithCount(1));
                if (handler == null || handler.getTanks() != 1 || !handler.getFluidInTank(0).isEmpty()) continue;
                int capacity = handler.getTankCapacity(0);
                if (capacity <= 0) continue;
                FluidStack offered = identity.copy();
                offered.setAmount(capacity);
                int accepted = handler.fill(offered.copy(), IFluidHandler.FluidAction.SIMULATE);
                if (accepted <= 0 || accepted > capacity || !handler.getFluidInTank(0).isEmpty()) continue;
                offered.setAmount(accepted);
                if (handler.fill(offered.copy(), IFluidHandler.FluidAction.EXECUTE) != accepted) continue;
                ItemStack filled = handler.getContainer().copy();
                if (filled.isEmpty() || filled.getCount() != 1 || ItemStack.isSameItemSameTags(container, filled)) continue;
                IFluidHandlerItem result = handlers.apply(filled.copy());
                if (result == null || result.getTanks() != 1 || !sameFluid(result.getFluidInTank(0), offered)) continue;
                add(recipes, true, container, filled, offered, tokens);
                ItemStack drained = drain(filled, offered, handlers);
                if (!drained.isEmpty()) add(recipes, false, drained, filled, offered, tokens);
            } catch (RuntimeException | LinkageError error) {
                LOGGER.debug("无法装填液体容器 {}", container, error);
            }
        }
        return List.copyOf(recipes.values());
    }

    private static ItemStack drain(ItemStack filled, FluidStack content,
                                  Function<ItemStack, IFluidHandlerItem> handlers) {
        IFluidHandlerItem handler = handlers.apply(filled.copyWithCount(1));
        if (handler == null || handler.getTanks() != 1 || !sameFluid(handler.getFluidInTank(0), content)) return ItemStack.EMPTY;
        if (!sameFluid(handler.drain(content.copy(), IFluidHandler.FluidAction.SIMULATE), content)
                || !sameFluid(handler.getFluidInTank(0), content)) return ItemStack.EMPTY;
        if (!sameFluid(handler.drain(content.copy(), IFluidHandler.FluidAction.EXECUTE), content)) return ItemStack.EMPTY;
        ItemStack empty = handler.getContainer().copy();
        if (empty.isEmpty() || empty.getCount() != 1 || ItemStack.isSameItemSameTags(empty, filled)) return ItemStack.EMPTY;
        IFluidHandlerItem result = handlers.apply(empty.copy());
        return result != null && result.getTanks() == 1 && result.getFluidInTank(0).isEmpty() ? empty : ItemStack.EMPTY;
    }

    public static boolean isValid(FluidContainerRecipe recipe) {
        try {
            Function<ItemStack, IFluidHandlerItem> handlers = FluidContainerBucketSupport.handlers(
                    List.of(recipe.emptyContainer(), recipe.filledContainer()), FluidContainerCatalog::handler);
            if (!recipe.filling()) return ItemStack.isSameItemSameTags(recipe.emptyContainer(),
                    drain(recipe.filledContainer(), recipe.fluid(), handlers));
            IFluidHandlerItem handler = handlers.apply(recipe.emptyContainer());
            if (handler == null || handler.getTanks() != 1 || !handler.getFluidInTank(0).isEmpty()) return false;
            FluidStack fluid = recipe.fluid();
            if (handler.fill(fluid.copy(), IFluidHandler.FluidAction.SIMULATE) != fluid.getAmount()
                    || handler.fill(fluid.copy(), IFluidHandler.FluidAction.EXECUTE) != fluid.getAmount()) return false;
            if (!ItemStack.isSameItemSameTags(handler.getContainer(), recipe.filledContainer())) return false;
            IFluidHandlerItem result = handlers.apply(handler.getContainer());
            return result != null && result.getTanks() == 1 && sameFluid(result.getFluidInTank(0), fluid);
        } catch (RuntimeException | LinkageError error) { return false; }
    }

    private static boolean sameFluid(FluidStack left, FluidStack right) {
        return left.isFluidEqual(right) && left.getAmount() == right.getAmount();
    }

    private static void addFluid(Map<String, FluidStack> fluids, FluidStack fluid) {
        if (fluid.isEmpty()) return;
        FluidStack identity = fluid.copy();
        identity.setAmount(1);
        fluids.putIfAbsent(identity.writeToNBT(new CompoundTag()).toString(), identity);
    }

    private static String stackKey(ItemStack stack) {
        return stack.copyWithCount(1).save(new CompoundTag()).toString();
    }

    private static void add(Map<ResourceLocation, FluidContainerRecipe> recipes, boolean filling,
                            ItemStack empty, ItemStack filled, FluidStack fluid,
                            Function<FluidStack, ItemStack> tokens) {
        CompoundTag identity = new CompoundTag();
        identity.put("empty", empty.copyWithCount(1).save(new CompoundTag()));
        identity.put("filled", filled.copyWithCount(1).save(new CompoundTag()));
        identity.put("fluid", fluid.writeToNBT(new CompoundTag()));
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(identity.toString().getBytes(StandardCharsets.UTF_8)));
            ResourceLocation id = new ResourceLocation("rs_integration",
                    "fluid_container/" + (filling ? "fill/" : "drain/") + hash);
            recipes.putIfAbsent(id, new FluidContainerRecipe(id, filling, empty, filled, fluid, tokens.apply(fluid.copy())));
        } catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
