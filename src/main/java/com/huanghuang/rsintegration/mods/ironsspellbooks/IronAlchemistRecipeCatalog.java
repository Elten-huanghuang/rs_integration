package com.huanghuang.rsintegration.mods.ironsspellbooks;

import com.mojang.logging.LogUtils;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.brewing.BrewingRecipeRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Function;

/** 将炼金锅原生数据包和酿造规则转换为 RS 内部索引。 */
public final class IronAlchemistRecipeCatalog {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String BREW_CLASS = "io.redspace.ironsspellbooks.recipe_types.alchemist_cauldron.BrewAlchemistCauldronRecipe";
    private static final String EMPTY_CLASS = "io.redspace.ironsspellbooks.recipe_types.alchemist_cauldron.EmptyAlchemistCauldronRecipe";
    private static final Map<RecipeManager, List<IronSpellBooksRecipe>> CATALOGS = new WeakHashMap<>();
    private static final Map<ResourceLocation, IronSpellBooksRecipe> BY_ID = new LinkedHashMap<>();

    private IronAlchemistRecipeCatalog() { }

    public static synchronized List<IronSpellBooksRecipe> allRecipes(Level level) {
        if (level == null) return List.of();
        return CATALOGS.computeIfAbsent(level.getRecipeManager(), manager -> {
            List<IronSpellBooksRecipe> recipes = discover(manager.getRecipes(), InkFluidSupport::token);
            for (IronSpellBooksRecipe recipe : recipes) BY_ID.put(recipe.getId(), recipe);
            return recipes;
        });
    }

    public static synchronized IronSpellBooksRecipe byId(ResourceLocation id) { return BY_ID.get(id); }
    public static synchronized List<IronSpellBooksRecipe> cachedRecipes() { return List.copyOf(BY_ID.values()); }
    public static synchronized void invalidate() { CATALOGS.clear(); BY_ID.clear(); }

    static List<IronSpellBooksRecipe> discover(Collection<Recipe<?>> nativeRecipes,
                                               Function<FluidStack, ItemStack> tokens) {
        Map<ResourceLocation, IronSpellBooksRecipe> recipes = new LinkedHashMap<>();
        for (Recipe<?> nativeRecipe : nativeRecipes) {
            try {
                Class<?> type = nativeRecipe.getClass();
                if (type.getName().equals(BREW_CLASS)) {
                    FluidStack fluid = (FluidStack) type.getMethod("fluidIn").invoke(nativeRecipe);
                    Ingredient reagent = (Ingredient) type.getMethod("reagent").invoke(nativeRecipe);
                    List<?> results = (List<?>) type.getMethod("results").invoke(nativeRecipe);
                    Optional<?> byproduct = (Optional<?>) type.getMethod("byproduct").invoke(nativeRecipe);
                    List<ItemStack> outputs = new ArrayList<>();
                    for (Object result : results) outputs.add(tokens.apply((FluidStack) result));
                    if (byproduct.orElse(null) instanceof ItemStack extra && !extra.isEmpty()) outputs.add(extra.copy());
                    if (!fluid.isEmpty() && !outputs.isEmpty() && outputs.stream().noneMatch(ItemStack::isEmpty)
                            && reagent.getItems().length > 0) {
                        ResourceLocation id = nativeId("brew", nativeRecipe.getId());
                        recipes.put(id, IronSpellBooksRecipe.brewRecipe(id, tokens.apply(fluid), reagent, outputs));
                    }
                } else if (type.getName().equals(EMPTY_CLASS)) {
                    FluidStack fluid = (FluidStack) type.getMethod("fluid").invoke(nativeRecipe);
                    Ingredient input = (Ingredient) type.getMethod("input").invoke(nativeRecipe);
                    ItemStack output = (ItemStack) type.getMethod("result").invoke(nativeRecipe);
                    if (!fluid.isEmpty() && !output.isEmpty() && input.test(new ItemStack(Items.GLASS_BOTTLE))) {
                        ResourceLocation id = nativeId("bottle", nativeRecipe.getId());
                        recipes.put(id, IronSpellBooksRecipe.bottleRecipe(id, tokens.apply(fluid), output));
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                LOGGER.warn("无法读取炼金锅配方 {}", nativeRecipe.getId(), failure);
            }
        }
        indexPotionRecipes(recipes, tokens, allowsPotionBrewing());
        return List.copyOf(recipes.values());
    }

    /** 药水酿造仅用于 RS 材料规划和执行；JEI 沿用炼金锅原生分类。 */
    static void indexPotionRecipes(Map<ResourceLocation, IronSpellBooksRecipe> recipes,
                                   Function<FluidStack, ItemStack> tokens, boolean brewing) {
        if (!ForgeRegistries.FLUIDS.containsKey(new ResourceLocation("irons_spellbooks", "potion"))) return;
        List<ItemStack> reagents = new ArrayList<>();
        if (brewing) for (var item : BuiltInRegistries.ITEM) {
            ItemStack stack = item.getDefaultInstance();
            if (BrewingRecipeRegistry.isValidIngredient(stack)) reagents.add(stack);
        }
        for (var potion : BuiltInRegistries.POTION) {
            if (potion == Potions.EMPTY) continue;
            for (var item : List.of(Items.POTION, Items.SPLASH_POTION, Items.LINGERING_POTION)) {
                ItemStack input = PotionUtils.setPotion(new ItemStack(item), potion);
                FluidStack fluid = AlchemistPotionSupport.fluid(input);
                if (fluid.isEmpty()) continue;
                ResourceLocation bottleId = potionId("bottle", input, ItemStack.EMPTY, input);
                recipes.put(bottleId, IronSpellBooksRecipe.bottleRecipe(bottleId, tokens.apply(fluid), input));
                for (ItemStack reagent : reagents) {
                    ItemStack output = BrewingRecipeRegistry.getOutput(input.copy(), reagent.copy());
                    if (output.isEmpty() && PotionBrewing.hasMix(input, reagent)) output = PotionBrewing.mix(reagent, input);
                    FluidStack produced = AlchemistPotionSupport.fluid(output);
                    if (produced.isEmpty() || produced.isFluidEqual(fluid)) continue;
                    ResourceLocation id = potionId("brew", input, reagent, output);
                    recipes.put(id, IronSpellBooksRecipe.brewRecipe(id, tokens.apply(fluid),
                            Ingredient.of(reagent), List.of(tokens.apply(produced))));
                }
            }
        }
    }

    private static boolean allowsPotionBrewing() {
        try {
            Object setting = Class.forName("io.redspace.ironsspellbooks.config.ServerConfigs")
                    .getField("ALLOW_CAULDRON_BREWING").get(null);
            return setting instanceof ForgeConfigSpec.ConfigValue<?> value && Boolean.TRUE.equals(value.get());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            return false;
        }
    }

    private static ResourceLocation nativeId(String kind, ResourceLocation id) {
        return new ResourceLocation("rs_integration", "irons_spellbooks/alchemist/" + kind
                + "/" + id.getNamespace() + "/" + id.getPath());
    }

    private static ResourceLocation potionId(String kind, ItemStack input, ItemStack reagent, ItemStack output) {
        String identity = input.save(new CompoundTag()) + "|"
                + reagent.save(new CompoundTag()) + "|" + output.save(new CompoundTag());
        return new ResourceLocation("rs_integration", "irons_spellbooks/alchemist/potion/" + kind + "/"
                + UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)));
    }

    static IronSpellBooksRecipe find(Ingredient input, FluidStack fluid, List<?> outputs, ItemStack extra,
                                     ItemStack displayed, Collection<IronSpellBooksRecipe> recipes) {
        // 原生 JEI 将水瓶展示为带 Potion 标签的流体，实际锅内使用原版水。
        ItemStack potion = AlchemistPotionSupport.bottle(fluid);
        if (!potion.isEmpty()) {
            FluidStack normalized = AlchemistPotionSupport.fluid(potion);
            if (normalized.getFluid() == Fluids.WATER) {
                normalized.setAmount(fluid.getAmount());
                fluid = normalized;
            }
        }
        for (IronSpellBooksRecipe recipe : recipes) {
            if (!recipe.isBrewing()) continue;
            List<ItemStack> materials = recipe.inputs();
            if (!InkFluidSupport.fluid(materials.get(0)).isFluidStackIdentical(fluid)) continue;
            if (displayed != null && !displayed.isEmpty()) {
                if (!input.test(displayed) || !recipe.inputIngredients().get(1).test(displayed)) continue;
            } else if (List.of(input.getItems()).stream().noneMatch(recipe.inputIngredients().get(1)::test)) continue;
            List<ItemStack> expected = new ArrayList<>();
            expected.add(recipe.getResultItem(RegistryAccess.EMPTY));
            expected.addAll(recipe.secondaryOutputs());
            List<FluidStack> fluids = expected.stream().filter(InkFluidSupport::isToken).map(InkFluidSupport::fluid).toList();
            List<ItemStack> items = expected.stream().filter(stack -> !InkFluidSupport.isToken(stack)).toList();
            if (fluids.size() != outputs.size() || items.size() != (extra.isEmpty() ? 0 : 1)) continue;
            boolean same = true;
            for (int i = 0; i < fluids.size(); i++) {
                if (!(outputs.get(i) instanceof FluidStack output) || !fluids.get(i).isFluidStackIdentical(output)) same = false;
            }
            if (same && (items.isEmpty() || ItemStack.matches(items.get(0), extra))) return recipe;
        }
        return null;
    }
}
