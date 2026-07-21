package com.huanghuang.rsintegration.mods.botania;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.recipe.ModRecipeHandler;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import vazkii.botania.api.recipe.BotanicalBreweryRecipe;
import vazkii.botania.api.recipe.ElvenTradeRecipe;
import vazkii.botania.api.recipe.ManaInfusionRecipe;
import vazkii.botania.api.recipe.PetalApothecaryRecipe;
import vazkii.botania.api.recipe.PureDaisyRecipe;
import vazkii.botania.api.recipe.RunicAltarRecipe;
import vazkii.botania.api.recipe.TerrestrialAgglomerationRecipe;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Strongly typed extraction for Botania 448 recipe APIs. */
public final class BotaniaRecipeHandler implements ModRecipeHandler {
    public enum Kind { MANA_POOL, APOTHECARY, RUNIC_ALTAR, BREWERY, ELVEN_TRADE, TERRA_PLATE, PURE_DAISY }

    private final Kind kind;
    private final String modTypeId;

    public BotaniaRecipeHandler(Kind kind, String modTypeId) {
        this.kind = kind;
        this.modTypeId = modTypeId;
    }

    @Override public @Nonnull ModType modType() { return ModType.byId(modTypeId); }

    @Override public boolean canHandle(@Nonnull Recipe<?> recipe) {
        return switch (kind) {
            case MANA_POOL -> recipe instanceof ManaInfusionRecipe;
            case APOTHECARY -> recipe instanceof PetalApothecaryRecipe;
            case RUNIC_ALTAR -> recipe instanceof RunicAltarRecipe;
            case BREWERY -> recipe instanceof BotanicalBreweryRecipe;
            case ELVEN_TRADE -> recipe instanceof ElvenTradeRecipe;
            case TERRA_PLATE -> recipe instanceof TerrestrialAgglomerationRecipe;
            case PURE_DAISY -> recipe instanceof PureDaisyRecipe;
        };
    }

    @Override public @Nonnull ItemStack getResultItem(@Nonnull Recipe<?> recipe, @Nonnull RegistryAccess access) {
        if (recipe instanceof ManaInfusionRecipe r) return r.getResultItem(access).copy();
        if (recipe instanceof ElvenTradeRecipe r) return copyFirst(r.getOutputs());
        if (recipe instanceof BotanicalBreweryRecipe r) {
            var vial = BuiltInRegistries.ITEM.get(new ResourceLocation("botania", "vial"));
            ItemStack output = r.getOutput(new ItemStack(vial));
            return output == null ? ItemStack.EMPTY : output.copy();
        }
        if (recipe instanceof PureDaisyRecipe r) return new ItemStack(r.getOutputState().getBlock());
        return recipe.getResultItem(access).copy();
    }

    private static ItemStack copyFirst(List<ItemStack> stacks) {
        return stacks.isEmpty() ? ItemStack.EMPTY : stacks.get(0).copy();
    }

    @Override public @Nullable List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        List<IngredientSpec> result = new ArrayList<>();
        if (recipe instanceof PureDaisyRecipe r) {
            ItemStack[] displayed = r.getInput().getDisplayedStacks().toArray(ItemStack[]::new);
            if (displayed.length == 0) return null;
            result.add(new IngredientSpec(Ingredient.of(Arrays.stream(displayed)), 1));
            return result;
        }
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (!ingredient.isEmpty()) result.add(new IngredientSpec(ingredient, 1));
        }
        if (recipe instanceof PetalApothecaryRecipe r && !r.getReagent().isEmpty()) {
            result.add(new IngredientSpec(r.getReagent(), 1));
        } else if (recipe instanceof RunicAltarRecipe r && !r.getReagent().isEmpty()) {
            result.add(new IngredientSpec(r.getReagent(), 1));
        } else if (recipe instanceof BotanicalBreweryRecipe) {
            var vial = BuiltInRegistries.ITEM.get(new ResourceLocation("botania", "vial"));
            result.add(0, new IngredientSpec(Ingredient.of(vial), 1));
        }
        return result.isEmpty() ? null : result;
    }

    @Override public @Nonnull List<ItemStack> getSecondaryOutputs(@Nonnull Recipe<?> recipe,
                                                                   @Nonnull RegistryAccess access) {
        if (!(recipe instanceof ElvenTradeRecipe r)) return List.of();
        List<ItemStack> outputs = r.getOutputs();
        if (outputs.size() <= 1) return List.of();
        return outputs.subList(1, outputs.size()).stream().map(ItemStack::copy).toList();
    }
}
