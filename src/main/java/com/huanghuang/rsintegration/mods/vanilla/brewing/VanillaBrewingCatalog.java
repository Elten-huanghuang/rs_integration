package com.huanghuang.rsintegration.mods.vanilla.brewing;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.RecipeIndex;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.brewing.BrewingRecipeRegistry;
import net.minecraftforge.common.brewing.IBrewingRecipe;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.item.crafting.Ingredient;

/** Builds deterministic synthetic recipes from the live Forge brewing registry. */
public final class VanillaBrewingCatalog {
    private static final Map<ResourceLocation, VanillaBrewingRecipeDefinition> BY_ID = new LinkedHashMap<>();

    private VanillaBrewingCatalog() {}

    public static synchronized int index(Level level, Map<Item, List<RecipeIndex.Entry>> index,
                                         Set<ResourceLocation> seen) {
        List<ItemStack> inputs = inputCandidates();
        List<ItemStack> reagents = itemCandidates();

        // Container and ordinary potion mixes registered through PotionBrewing
        // are separate from Forge's IBrewingRecipe registry. Modded potion
        // families commonly use this path for bottle -> splash -> lingering.
        List<ItemStack> potionReagents = reagents.stream()
                .filter(PotionBrewing::isIngredient).toList();
        for (ItemStack input : inputs) {
            for (ItemStack reagent : potionReagents) {
                if (!PotionBrewing.hasMix(input, reagent)) continue;
                addDefinition(input, reagent, PotionBrewing.mix(reagent, input), index, seen);
            }
        }

        for (IBrewingRecipe brewing : BrewingRecipeRegistry.getRecipes()) {
            indexDeclaredMappings(brewing, index, seen);
            List<ItemStack> acceptedInputs = inputs.stream().filter(brewing::isInput).toList();
            List<ItemStack> acceptedReagents = reagents.stream().filter(brewing::isIngredient).toList();
            for (ItemStack input : acceptedInputs) {
                for (ItemStack reagent : acceptedReagents) {
                    addDefinition(input, reagent,
                            brewing.getOutput(input.copy(), reagent.copy()), index, seen);
                }
            }
        }
        for (VanillaBrewingRecipeDefinition definition : BY_ID.values()) {
            if (!seen.add(definition.getId())) continue;
            index.computeIfAbsent(definition.outputUnit().getItem(), ignored -> new ArrayList<>())
                    .add(new RecipeIndex.Entry(definition,
                            ModType.byId("vanilla_brewing_stand"),
                            new ResourceLocation("minecraft", "brewing"), true));
        }
        return BY_ID.size();
    }

    /**
     * Some advanced-potion recipes distinguish variants through NBT stored in
     * their Ingredient stacks. Registry-wide default ItemStacks cannot discover
     * those variants, but several implementations expose the exact mapping.
     */
    private static void indexDeclaredMappings(IBrewingRecipe brewing,
                                              Map<Item, List<RecipeIndex.Entry>> index,
                                              Set<ResourceLocation> seen) {
        try {
            java.lang.reflect.Method method = brewing.getClass().getMethod("getProcessingMappings");
            Object value = method.invoke(brewing);
            if (!(value instanceof Map<?, ?> mappings)) return;
            for (Map.Entry<?, ?> mapping : mappings.entrySet()) {
                if (!(mapping.getKey() instanceof Ingredient inputIngredient)
                        || !(mapping.getValue() instanceof Ingredient reagentIngredient)) continue;
                for (ItemStack input : inputIngredient.getItems()) {
                    for (ItemStack reagent : reagentIngredient.getItems()) {
                        if (!brewing.isInput(input) || !brewing.isIngredient(reagent)) continue;
                        addDefinition(input, reagent,
                                brewing.getOutput(input.copy(), reagent.copy()), index, seen);
                    }
                }
            }
        } catch (NoSuchMethodException ignored) {
            // Most brewing recipes do not expose a mapping; the ordinary
            // candidate scan below remains the generic fallback.
        } catch (ReflectiveOperationException | RuntimeException e) {
            com.huanghuang.rsintegration.RSIntegrationMod.LOGGER.debug(
                    "[RecipeIndex] unable to inspect brewing mappings for {}",
                    brewing.getClass().getName(), e);
        }
    }

    private static void addDefinition(ItemStack input, ItemStack reagent, ItemStack output,
                                      Map<Item, List<RecipeIndex.Entry>> index,
                                      Set<ResourceLocation> seen) {
        if (output.isEmpty() || ItemStack.isSameItemSameTags(input, output)) return;
        ResourceLocation id = recipeId(input, reagent, output);
        if (!seen.add(id)) return;
        VanillaBrewingRecipeDefinition definition =
                new VanillaBrewingRecipeDefinition(id, input, reagent, output);
        BY_ID.put(id, definition);
        index.computeIfAbsent(output.getItem(), ignored -> new ArrayList<>())
                .add(new RecipeIndex.Entry(definition,
                        ModType.byId("vanilla_brewing_stand"),
                        new ResourceLocation("minecraft", "brewing"), true));
    }

    public static synchronized VanillaBrewingRecipeDefinition byId(ResourceLocation id) {
        return BY_ID.get(id);
    }

    public static synchronized ResourceLocation findId(ItemStack input, ItemStack reagent, ItemStack output) {
        for (VanillaBrewingRecipeDefinition recipe : BY_ID.values()) {
            if (ItemStack.isSameItemSameTags(recipe.input(), input)
                    && ItemStack.isSameItemSameTags(recipe.reagent(), reagent)
                    && ItemStack.isSameItemSameTags(recipe.outputUnit(), output)) return recipe.getId();
        }
        return null;
    }

    /** Register an exact JEI-visible conversion that candidate enumeration missed. */
    public static synchronized ResourceLocation registerExact(ItemStack input, ItemStack reagent,
                                                               ItemStack output) {
        if (input.isEmpty() || reagent.isEmpty() || output.isEmpty()) return null;
        ResourceLocation existing = findId(input, reagent, output);
        if (existing != null) return existing;
        ResourceLocation id = recipeId(input, reagent, output);
        BY_ID.putIfAbsent(id, new VanillaBrewingRecipeDefinition(id, input, reagent, output));
        return id;
    }

    public static synchronized void ensureBuilt(Level level) {
        if (BY_ID.isEmpty()) index(level, new java.util.HashMap<>(), new java.util.HashSet<>());
    }

    private static List<ItemStack> inputCandidates() {
        List<ItemStack> candidates = itemCandidates();
        for (Potion potion : BuiltInRegistries.POTION) {
            candidates.add(PotionUtils.setPotion(new ItemStack(Items.POTION), potion));
            candidates.add(PotionUtils.setPotion(new ItemStack(Items.SPLASH_POTION), potion));
            candidates.add(PotionUtils.setPotion(new ItemStack(Items.LINGERING_POTION), potion));
        }
        return deduplicate(candidates);
    }

    private static List<ItemStack> itemCandidates() {
        List<ItemStack> candidates = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            ItemStack stack = item.getDefaultInstance();
            if (!stack.isEmpty()) candidates.add(stack);
        }
        return candidates;
    }

    private static List<ItemStack> deduplicate(List<ItemStack> stacks) {
        List<ItemStack> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ItemStack stack : stacks) {
            CompoundTag saved = new CompoundTag();
            stack.copyWithCount(1).save(saved);
            if (seen.add(saved.toString())) result.add(stack);
        }
        return result;
    }

    private static ResourceLocation recipeId(ItemStack input, ItemStack reagent, ItemStack output) {
        CompoundTag identity = new CompoundTag();
        identity.put("input", input.copyWithCount(1).save(new CompoundTag()));
        identity.put("reagent", reagent.copyWithCount(1).save(new CompoundTag()));
        identity.put("output", output.copyWithCount(1).save(new CompoundTag()));
        String hash = Integer.toUnsignedString(identity.toString().hashCode(), 16);
        return new ResourceLocation("rs_integration", "vanilla_brewing/" + hash);
    }
}
