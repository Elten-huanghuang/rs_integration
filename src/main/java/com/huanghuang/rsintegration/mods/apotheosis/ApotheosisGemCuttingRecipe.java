package com.huanghuang.rsintegration.mods.apotheosis;

import dev.shadowsoffire.apotheosis.adventure.loot.LootRarity;
import dev.shadowsoffire.apotheosis.adventure.socket.gem.Gem;
import dev.shadowsoffire.apotheosis.adventure.socket.gem.GemRegistry;
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

/** A runtime recipe for one concrete gem rarity transition and material choice. */
public final class ApotheosisGemCuttingRecipe implements Recipe<Container> {
    public static final RecipeType<ApotheosisGemCuttingRecipe> TYPE =
            RecipeType.simple(new ResourceLocation("rs_integration", "gem_cutting"));

    private final ResourceLocation id;
    private final ItemStack input;
    private final ItemStack output;
    private final ItemStack material;
    private final int dust;
    private final ResourceLocation gemId;
    private final int targetOrdinal;

    ApotheosisGemCuttingRecipe(Gem gem, LootRarity from, LootRarity to,
                               ItemStack material, int branch) {
        this.input = GemRegistry.createGemStack(gem, from);
        this.output = GemRegistry.createGemStack(gem, to);
        this.material = material.copy();
        this.dust = 1 + from.ordinal() * 2;
        this.gemId = gem.getId();
        this.targetOrdinal = to.ordinal();
        String gemPath = gemId.toString().replace(':', '/');
        this.id = new ResourceLocation("rs_integration", "gem_cutting/" + gemPath + "/"
                + from.ordinal() + "_" + to.ordinal() + "/" + branch);
    }

    public ItemStack inputGem() { return input.copy(); }
    public ItemStack material() { return material.copy(); }
    public int dustCost() { return dust; }
    public ResourceLocation gemId() { return gemId; }
    public int targetOrdinal() { return targetOrdinal; }

    @Override public boolean matches(Container container, Level level) { return false; }
    @Override public ItemStack assemble(Container container, RegistryAccess access) { return output.copy(); }
    @Override public boolean canCraftInDimensions(int width, int height) { return true; }
    @Override public ItemStack getResultItem(RegistryAccess access) { return output.copy(); }
    @Override public ResourceLocation getId() { return id; }
    @Override public RecipeSerializer<?> getSerializer() { return null; }
    @Override public RecipeType<?> getType() { return TYPE; }

    @Override
    public NonNullList<Ingredient> getIngredients() {
        NonNullList<Ingredient> result = NonNullList.create();
        result.add(StrictNBTIngredient.of(input.copy()));
        result.add(StrictNBTIngredient.of(input.copy()));
        result.add(Ingredient.of(new ItemStack(
                net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(
                        new ResourceLocation("apotheosis", "gem_dust")), dust)));
        result.add(Ingredient.of(material.copy()));
        return result;
    }
}
