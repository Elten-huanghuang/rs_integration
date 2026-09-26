package com.huanghuang.rsintegration.mods.vanilla.brewing;
import java.lang.reflect.Method;

import com.mojang.logging.LogUtils;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.RecipeIndex;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraphProjector;
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
import org.slf4j.Logger;
import java.util.HashMap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import net.minecraft.world.item.crafting.Ingredient;

/** Builds deterministic synthetic recipes from the live Forge brewing registry. */
public final class VanillaBrewingCatalog {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<ResourceLocation, VanillaBrewingRecipeDefinition> BY_ID = new LinkedHashMap<>();

    private VanillaBrewingCatalog() {}

    public static synchronized int index(Level level, Map<Item, List<RecipeIndex.Entry>> index,
                                         Set<ResourceLocation> seen) {
        return index(level, index, seen, null);
    }

    /**
     * Builds the runtime index and, when requested, publishes the same dynamic
     * recipes to the immutable planner graph.  Brewing recipes are not owned by
     * RecipeManager, so omitting this projection makes preview and execution see
     * different producer sets for potion intermediates.
     */
    public static synchronized int index(
            Level level, Map<Item, List<RecipeIndex.Entry>> index,
            Set<ResourceLocation> seen,
            Map<ImmutableRecipeGraph.MaterialRef, List<ImmutableRecipeGraph.RecipeNode>> projected) {
        IncrementalIndex build = incrementalIndex(level, index, seen);
        while (!build.advance(() -> false)) { }
        if (projected != null) projectDefinitions(projected);
        return build.indexedCount();
    }

    public static synchronized IncrementalIndex incrementalIndex(
            Level level, Map<Item, List<RecipeIndex.Entry>> index,
            Set<ResourceLocation> seen) {
        return new IncrementalIndex(index, seen, inputCandidates(), itemCandidates());
    }

    /** Splits registry probing into resumable units while retaining main-thread access. */
    public static final class IncrementalIndex {
        private final Map<Item, List<RecipeIndex.Entry>> index;
        private final Set<ResourceLocation> seen;
        private final List<ItemStack> inputs;
        private final List<ItemStack> reagents;
        private final List<ItemStack> potionReagents;
        private final List<IBrewingRecipe> forgeRecipes;
        private final Set<IBrewingRecipe> rejectedForgeRecipes =
                Collections.newSetFromMap(new IdentityHashMap<>());
        private int phase;
        private int inputIndex;
        private int reagentIndex;
        private int forgeIndex;
        private int filterIndex;
        private int definitionIndex;
        private int indexedCount;
        private IBrewingRecipe currentForgeRecipe;
        private final List<ItemStack> acceptedInputs = new ArrayList<>();
        private final List<ItemStack> acceptedReagents = new ArrayList<>();
        private List<VanillaBrewingRecipeDefinition> existingDefinitions = List.of();

        private IncrementalIndex(Map<Item, List<RecipeIndex.Entry>> index,
                                 Set<ResourceLocation> seen,
                                 List<ItemStack> inputs, List<ItemStack> reagents) {
            this(index, seen, inputs, reagents, BrewingRecipeRegistry.getRecipes());
        }

        IncrementalIndex(Map<Item, List<RecipeIndex.Entry>> index,
                         Set<ResourceLocation> seen,
                         List<ItemStack> inputs, List<ItemStack> reagents,
                         List<IBrewingRecipe> forgeRecipes) {
            this.index = index;
            this.seen = seen;
            this.inputs = inputs;
            this.reagents = reagents;
            this.potionReagents = reagents.stream().filter(PotionBrewing::isIngredient).toList();
            this.forgeRecipes = List.copyOf(forgeRecipes);
        }

        public boolean advance(BooleanSupplier budgetExpired) {
            synchronized (VanillaBrewingCatalog.class) {
                return advanceLocked(budgetExpired);
            }
        }

        private boolean advanceLocked(BooleanSupplier budgetExpired) {
            int processed = 0;
            while (processed++ == 0 || !budgetExpired.getAsBoolean()) {
                if (phase == 0) {
                    if (!advancePotionMix()) {
                        phase = 1;
                        inputIndex = reagentIndex = 0;
                    }
                } else if (phase == 1) {
                    if (!advanceForgeRecipe()) {
                        phase = 2;
                        existingDefinitions = List.copyOf(BY_ID.values());
                    }
                } else if (phase == 2) {
                    if (!advanceExistingDefinition()) phase = 3;
                } else {
                    return true;
                }
            }
            return phase >= 3;
        }

        public int indexedCount() {
            return indexedCount;
        }

        private boolean advancePotionMix() {
            if (inputIndex >= inputs.size() || potionReagents.isEmpty()) return false;
            ItemStack input = inputs.get(inputIndex);
            ItemStack reagent = potionReagents.get(reagentIndex++);
            if (PotionBrewing.hasMix(input, reagent)) {
                int before = seen.size();
                addDefinition(input, reagent, PotionBrewing.mix(reagent, input), index, seen);
                if (seen.size() > before) indexedCount++;
            }
            if (reagentIndex >= potionReagents.size()) {
                reagentIndex = 0;
                inputIndex++;
            }
            return inputIndex < inputs.size();
        }

        private boolean advanceForgeRecipe() {
            if (currentForgeRecipe == null) {
                while (forgeIndex < forgeRecipes.size()) {
                    IBrewingRecipe next = forgeRecipes.get(forgeIndex++);
                    if (rejectedForgeRecipes.contains(next)) continue;
                    currentForgeRecipe = next;
                    indexDeclaredMappings(currentForgeRecipe, index, seen);
                    acceptedInputs.clear();
                    acceptedReagents.clear();
                    filterIndex = -inputs.size();
                    inputIndex = reagentIndex = 0;
                    break;
                }
                if (currentForgeRecipe == null) return false;
            }
            if (filterIndex < 0) {
                ItemStack candidate = inputs.get(inputs.size() + filterIndex++);
                try {
                    if (currentForgeRecipe.isInput(candidate)) acceptedInputs.add(candidate);
                } catch (RuntimeException | LinkageError error) {
                    rejectForgeRecipe(error, "isInput");
                    return forgeIndex < forgeRecipes.size();
                }
                return true;
            }
            if (filterIndex < reagents.size()) {
                ItemStack candidate = reagents.get(filterIndex++);
                try {
                    if (currentForgeRecipe.isIngredient(candidate)) acceptedReagents.add(candidate);
                } catch (RuntimeException | LinkageError error) {
                    rejectForgeRecipe(error, "isIngredient");
                    return forgeIndex < forgeRecipes.size();
                }
                return true;
            }
            if (acceptedInputs.isEmpty() || acceptedReagents.isEmpty()) {
                currentForgeRecipe = null;
                return forgeIndex < forgeRecipes.size();
            }
            ItemStack input = acceptedInputs.get(inputIndex);
            ItemStack reagent = acceptedReagents.get(reagentIndex++);
            ItemStack output;
            try {
                output = currentForgeRecipe.getOutput(input.copy(), reagent.copy());
            } catch (RuntimeException | LinkageError error) {
                rejectForgeRecipe(error, "getOutput");
                return forgeIndex < forgeRecipes.size();
            }
            if (output == null) output = ItemStack.EMPTY;
            int before = seen.size();
            addDefinition(input, reagent, output, index, seen);
            if (seen.size() > before) indexedCount++;
            if (reagentIndex >= acceptedReagents.size()) {
                reagentIndex = 0;
                inputIndex++;
            }
            if (inputIndex >= acceptedInputs.size()) currentForgeRecipe = null;
            return currentForgeRecipe != null || forgeIndex < forgeRecipes.size();
        }

        private void rejectForgeRecipe(Throwable error, String operation) {
            IBrewingRecipe rejected = currentForgeRecipe;
            if (rejected != null && rejectedForgeRecipes.add(rejected)) {
                LOGGER.warn(
                        "[RecipeIndex] skipping broken Forge brewing recipe {} during {}",
                        rejected.getClass().getName(), operation, error);
            }
            currentForgeRecipe = null;
            acceptedInputs.clear();
            acceptedReagents.clear();
        }

        private boolean advanceExistingDefinition() {
            if (definitionIndex >= existingDefinitions.size()) return false;
            VanillaBrewingRecipeDefinition definition =
                    existingDefinitions.get(definitionIndex++);
            if (seen.add(definition.getId())) {
                index.computeIfAbsent(definition.outputUnit().getItem(), ignored -> new ArrayList<>())
                        .add(new RecipeIndex.Entry(definition,
                                ModType.byId("vanilla_brewing_stand"),
                                new ResourceLocation("minecraft", "brewing"), true));
                indexedCount++;
            }
            return definitionIndex < existingDefinitions.size();
        }
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
            Method method = brewing.getClass().getMethod("getProcessingMappings");
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
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOGGER.debug(
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

    private static void projectDefinitions(
            Map<ImmutableRecipeGraph.MaterialRef, List<ImmutableRecipeGraph.RecipeNode>> projected) {
        ResourceLocation recipeType = new ResourceLocation("minecraft", "brewing");
        for (VanillaBrewingRecipeDefinition definition : BY_ID.values()) {
            List<IngredientSpec> specs = List.of(
                    new IngredientSpec(definition.getIngredients().get(0), 3),
                    new IngredientSpec(definition.getIngredients().get(1), 1),
                    new IngredientSpec(definition.getIngredients().get(2), 1));
            ImmutableRecipeGraph.RecipeNode node = ImmutableRecipeGraphProjector.projectRecipe(
                    definition.getId(), definition.output(), specs,
                    "vanilla_brewing_stand", recipeType);
            if (node != null) {
                projected.computeIfAbsent(node.output(), ignored -> new ArrayList<>()).add(node);
            }
        }
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
        if (BY_ID.isEmpty()) index(level, new HashMap<>(), new HashSet<>());
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
