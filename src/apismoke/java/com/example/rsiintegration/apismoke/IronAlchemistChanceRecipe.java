package com.example.rsiintegration.apismoke;

import com.google.gson.JsonObject;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * API 烟测专用的 Iron 炼金锅概率配方。
 *
 * <p>方块、流体和实际随机判定仍由 Iron 炼金锅执行；本配方只把
 * 公共 API 无法直接读取的卷轴回收流程投影成可规划的配方。</p>
 */
public final class IronAlchemistChanceRecipe implements Recipe<Container> {
    public static final ResourceLocation TYPE_ID =
            new ResourceLocation(
                    RsiApiSmokeMod.MOD_ID,
                    "iron_alchemist_chance");
    public static final RecipeType<IronAlchemistChanceRecipe> TYPE =
            RecipeType.simple(TYPE_ID);

    private final ResourceLocation id;
    private final Ingredient input;
    private final ResourceLocation outputFluid;
    private final int outputAmount;

    public IronAlchemistChanceRecipe(
            ResourceLocation id,
            Ingredient input,
            ResourceLocation outputFluid,
            int outputAmount
    ) {
        if (id == null || input == null || input.isEmpty()
                || outputFluid == null || outputAmount <= 0) {
            throw new IllegalArgumentException(
                    "invalid Iron alchemist chance recipe");
        }
        this.id = id;
        this.input = input;
        this.outputFluid = outputFluid;
        this.outputAmount = outputAmount;
    }

    public Ingredient input() {
        return input;
    }

    public ResourceLocation outputFluid() {
        return outputFluid;
    }

    public int outputAmount() {
        return outputAmount;
    }

    public Fluid resolvedOutputFluid() {
        return ForgeRegistries.FLUIDS.getValue(outputFluid);
    }

    @Override
    public boolean matches(Container container, Level level) {
        return false;
    }

    @Override
    public ItemStack assemble(
            Container container,
            RegistryAccess registries
    ) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return true;
    }

    @Override
    public ItemStack getResultItem(RegistryAccess registries) {
        return ItemStack.EMPTY;
    }

    @Override
    public ResourceLocation getId() {
        return id;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return RsiApiSmokeMod.IRON_ALCHEMIST_CHANCE_SERIALIZER.get();
    }

    @Override
    public RecipeType<?> getType() {
        return TYPE;
    }

    @Override
    public NonNullList<Ingredient> getIngredients() {
        NonNullList<Ingredient> ingredients = NonNullList.create();
        ingredients.add(input);
        return ingredients;
    }

    public static final class Serializer
            implements RecipeSerializer<IronAlchemistChanceRecipe> {
        @Override
        public IronAlchemistChanceRecipe fromJson(
                ResourceLocation id,
                JsonObject json
        ) {
            Ingredient input = itemIngredient(
                    new ResourceLocation(
                            GsonHelper.getAsString(
                                    json, "input_item")));
            ResourceLocation fluid = new ResourceLocation(
                    GsonHelper.getAsString(json, "output_fluid"));
            int amount = GsonHelper.getAsInt(
                    json, "output_amount", 250);
            return new IronAlchemistChanceRecipe(
                    id, input, fluid, amount);
        }

        @Override
        public IronAlchemistChanceRecipe fromNetwork(
                ResourceLocation id,
                FriendlyByteBuf buffer
        ) {
            Ingredient input = itemIngredient(
                    buffer.readResourceLocation());
            ResourceLocation fluid = buffer.readResourceLocation();
            int amount = buffer.readVarInt();
            return new IronAlchemistChanceRecipe(
                    id, input, fluid, amount);
        }

        @Override
        public void toNetwork(
                FriendlyByteBuf buffer,
                IronAlchemistChanceRecipe recipe
        ) {
            ItemStack[] inputs = recipe.input.getItems();
            ResourceLocation itemId = inputs.length == 0
                    ? null : ForgeRegistries.ITEMS.getKey(
                    inputs[0].getItem());
            if (itemId == null) {
                throw new IllegalArgumentException(
                        "chance recipe input item is missing");
            }
            buffer.writeResourceLocation(itemId);
            buffer.writeResourceLocation(recipe.outputFluid);
            buffer.writeVarInt(recipe.outputAmount);
        }
    }

    private static Ingredient itemIngredient(
            ResourceLocation itemId
    ) {
        Item item = ForgeRegistries.ITEMS.getValue(itemId);
        if (item == null) {
            throw new IllegalArgumentException(
                    "unknown item " + itemId);
        }
        return Ingredient.of(item);
    }
}
