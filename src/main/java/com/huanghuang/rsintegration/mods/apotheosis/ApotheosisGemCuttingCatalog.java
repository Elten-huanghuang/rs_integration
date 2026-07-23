package com.huanghuang.rsintegration.mods.apotheosis;

import dev.shadowsoffire.apotheosis.adventure.loot.LootRarity;
import dev.shadowsoffire.apotheosis.adventure.loot.RarityRegistry;
import dev.shadowsoffire.apotheosis.adventure.socket.gem.Gem;
import dev.shadowsoffire.apotheosis.adventure.socket.gem.GemRegistry;
import dev.shadowsoffire.apotheosis.adventure.socket.gem.GemInstance;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;

/** Builds the 1/3/9 alternatives from the live rarity registry, including addon rarities. */
public final class ApotheosisGemCuttingCatalog {
    private ApotheosisGemCuttingCatalog() {}

    public static List<ApotheosisGemCuttingRecipe> recipesFor(Gem gem) {
        List<LootRarity> rarities = orderedRarities();
        List<ApotheosisGemCuttingRecipe> recipes = new ArrayList<>();
        for (int i = 0; i + 1 < rarities.size(); i++) {
            LootRarity from = rarities.get(i);
            LootRarity to = rarities.get(i + 1);
            add(recipes, gem, from, to, to.getMaterial(), 1, 0);
            add(recipes, gem, from, to, from.getMaterial(), 3, 1);
            if (i > 0) add(recipes, gem, from, to, rarities.get(i - 1).getMaterial(), 9, 2);
        }
        return recipes;
    }

    public static Collection<ApotheosisGemCuttingRecipe> allRecipes() {
        return build().values();
    }

    public static ApotheosisGemCuttingRecipe byId(ResourceLocation id) {
        if (!"rs_integration".equals(id.getNamespace())
                || !id.getPath().startsWith("gem_cutting/")) return null;
        return build().get(id);
    }

    public static ApotheosisGemCuttingRecipe recipeForTarget(ItemStack target) {
        GemInstance instance = GemInstance.unsocketed(target);
        if (!instance.isValidUnsocketed()) return null;
        ResourceLocation gemId = instance.gem().getId();
        int targetOrdinal = instance.rarity().get().ordinal();
        return allRecipes().stream()
                .filter(recipe -> recipe.targetOrdinal() == targetOrdinal
                        && recipe.gemId().equals(gemId))
                .findFirst().orElse(null);
    }

    private static Map<ResourceLocation, ApotheosisGemCuttingRecipe> build() {
        Map<ResourceLocation, ApotheosisGemCuttingRecipe> result = new LinkedHashMap<>();
        for (Gem gem : GemRegistry.INSTANCE.getValues()) {
            for (ApotheosisGemCuttingRecipe recipe : recipesFor(gem)) {
                result.put(recipe.getId(), recipe);
            }
        }
        return result;
    }

    private static void add(List<ApotheosisGemCuttingRecipe> out, Gem gem,
                            LootRarity from, LootRarity to,
                            net.minecraft.world.item.Item material, int count, int branch) {
        if (material != null) out.add(new ApotheosisGemCuttingRecipe(
                gem, from, to, new ItemStack(material, count), branch));
    }

    private static List<LootRarity> orderedRarities() {
        List<LootRarity> result = new ArrayList<>();
        try {
            Object holders = RarityRegistry.class.getMethod("getOrderedRarities")
                    .invoke(RarityRegistry.INSTANCE);
            if (holders instanceof Iterable<?> iterable) {
                for (Object holder : iterable) {
                    Object rarity = holder.getClass().getMethod("get").invoke(holder);
                    if (rarity instanceof LootRarity value) result.add(value);
                }
            }
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to read Apotheosis rarity registry", exception);
        }
        result.sort(java.util.Comparator.comparingInt(LootRarity::ordinal));
        return result;
    }
}
