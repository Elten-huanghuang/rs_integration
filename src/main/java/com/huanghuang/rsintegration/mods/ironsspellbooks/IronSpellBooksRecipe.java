package com.huanghuang.rsintegration.mods.ironsspellbooks;

import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.crafting.StrictNBTIngredient;

import java.util.List;

/** One concrete NBT-sensitive operation exposed by an Iron's Spell Books workstation. */
public final class IronSpellBooksRecipe implements Recipe<Container> {
    public enum Machine { SCROLL_FORGE, ARCANE_ANVIL, ALCHEMIST_CAULDRON }

    private final ResourceLocation id;
    private final Machine machine;
    private final List<ItemStack> inputs;
    private final List<Ingredient> inputIngredients;
    private final ItemStack output;
    private final String spellId;
    private final int spellLevel;
    private List<ItemStack> secondaryOutputs = List.of();
    private boolean brewing;

    IronSpellBooksRecipe(ResourceLocation id, Machine machine, List<ItemStack> inputs,
                         ItemStack output, String spellId) {
        this(id, machine, inputs,
                inputs.stream().map(stack -> (Ingredient) StrictNBTIngredient.of(stack.copy())).toList(),
                output, spellId, 0);
    }

    IronSpellBooksRecipe(ResourceLocation id, Machine machine, List<ItemStack> inputs,
                         List<Ingredient> inputIngredients, ItemStack output, String spellId,
                         int spellLevel) {
        if (inputs.size() != inputIngredients.size()) {
            throw new IllegalArgumentException("Display inputs and ingredients must have equal sizes");
        }
        this.id = id;
        this.machine = machine;
        this.inputs = inputs.stream().map(ItemStack::copy).toList();
        this.inputIngredients = List.copyOf(inputIngredients);
        this.output = output.copy();
        this.spellId = spellId;
        this.spellLevel = spellLevel;
    }

    public Machine machine() { return machine; }
    public List<ItemStack> inputs() { return inputs.stream().map(ItemStack::copy).toList(); }
    public List<Ingredient> inputIngredients() { return inputIngredients; }
    public String spellId() { return spellId; }
    public int spellLevel() { return spellLevel; }

    public boolean isScrollRecycling() {
        return machine == Machine.ALCHEMIST_CAULDRON && !brewing && InkFluidSupport.isToken(output);
    }

    public boolean isInkBottling() {
        return isBottling() && InkFluidSupport.isInk(InkFluidSupport.fluid(inputs.get(0)));
    }

    public boolean isBottling() {
        return machine == Machine.ALCHEMIST_CAULDRON && inputs.size() == 2
                && InkFluidSupport.isToken(inputs.get(0)) && inputs.get(1).is(Items.GLASS_BOTTLE)
                && !InkFluidSupport.isToken(output);
    }

    public boolean isBrewing() { return brewing; }
    public List<ItemStack> secondaryOutputs() { return secondaryOutputs.stream().map(ItemStack::copy).toList(); }

    static IronSpellBooksRecipe brewRecipe(ResourceLocation id, ItemStack fluid, Ingredient reagent,
                                           List<ItemStack> outputs) {
        ItemStack[] items = reagent.getItems();
        if (items.length == 0 || outputs.isEmpty()) throw new IllegalArgumentException("酿造配方缺少材料或产物");
        IronSpellBooksRecipe recipe = new IronSpellBooksRecipe(id, Machine.ALCHEMIST_CAULDRON,
                List.of(fluid, items[0].copyWithCount(1)),
                List.of(StrictNBTIngredient.of(fluid), reagent), outputs.get(0), "", 0);
        recipe.brewing = true;
        recipe.secondaryOutputs = outputs.subList(1, outputs.size()).stream().map(ItemStack::copy).toList();
        return recipe;
    }

    static IronSpellBooksRecipe bottleRecipe(ResourceLocation id, ItemStack fluid, ItemStack ink) {
        return new IronSpellBooksRecipe(id, Machine.ALCHEMIST_CAULDRON,
                List.of(fluid, new ItemStack(Items.GLASS_BOTTLE)),
                List.of(StrictNBTIngredient.of(fluid), Ingredient.of(Items.GLASS_BOTTLE)), ink, "", 0);
    }

    @Override public boolean matches(Container container, Level level) { return false; }
    @Override public ItemStack assemble(Container container, RegistryAccess access) { return output.copy(); }
    @Override public boolean canCraftInDimensions(int width, int height) { return true; }
    @Override public ItemStack getResultItem(RegistryAccess access) { return output.copy(); }
    @Override public ResourceLocation getId() { return id; }
    @Override public RecipeSerializer<?> getSerializer() { return null; }
    @Override public RecipeType<?> getType() { return IronSpellBooksRecipeCatalog.TYPE; }

    @Override
    public NonNullList<Ingredient> getIngredients() {
        NonNullList<Ingredient> result = NonNullList.create();
        result.addAll(inputIngredients);
        return result;
    }
}
