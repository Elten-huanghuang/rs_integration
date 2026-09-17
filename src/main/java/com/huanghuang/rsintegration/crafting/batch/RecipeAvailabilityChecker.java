package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.CraftStorageEndpoints;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.DirectMaterialAllocator;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.IngredientMatcher;
import com.huanghuang.rsintegration.crafting.MaterialSources;
import com.huanghuang.rsintegration.crafting.availability.MaterialAvailability;
import com.huanghuang.rsintegration.crafting.availability.RecipeAvailabilityKey;
import com.huanghuang.rsintegration.mods.arsnouveau.ArsDynamicApparatusRecipe;
import com.huanghuang.rsintegration.mods.goety.GoetyDynamicRitualRecipe;
import com.huanghuang.rsintegration.mods.goety.GoetyMaterialAvailability;
import com.huanghuang.rsintegration.mods.farmersdelight.MinersDelightCopperPotSupport;
import com.huanghuang.rsintegration.mods.vanilla.SmithingRecipeHandler;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import com.huanghuang.rsintegration.recipe.CrockPotRecipeHandler;
import com.huanghuang.rsintegration.recipe.GoetyRecipeHandler;
import com.huanghuang.rsintegration.recipe.WRRecipeHandler;
import com.huanghuang.rsintegration.sidepanel.network.OpenBoundMachineGuiPacket;
import com.huanghuang.rsintegration.storage.StorageRestockSupport;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.common.crafting.StrictNBTIngredient;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.item.Item;

/** Read-only item-material check using the same recipe catalog and allocation as crafting. */
public final class RecipeAvailabilityChecker {
    private RecipeAvailabilityChecker() {}

    public static MaterialAvailability check(ServerPlayer player, RecipeAvailabilityKey key) {
        Recipe<?> recipe = GenericCraftPacket.resolveRecipe(player.serverLevel(), key.recipeId());
        if (recipe == null) return MaterialAvailability.UNKNOWN;
        ModType type = GenericCraftPacket.resolveExecutionModType(player, recipe, key.dimension(), key.machinePos());
        var dimension = ResourceKey.create(Registries.DIMENSION, key.dimension());
        var machineLevel = player.server.getLevel(dimension);
        if (GenericCraftPacket.requiresBoundMachine(recipe, type)
                && (machineLevel == null || !machineLevel.hasChunkAt(key.machinePos())
                    || !AltarBindingRegistry.isBound(dimension, key.machinePos(), player))) {
            return MaterialAvailability.UNKNOWN;
        }
        List<IngredientSpec> specs = ingredients(recipe, key.baseStack(), key.outputStack());
        if (specs != null && type != null
                && com.huanghuang.rsintegration.util.ModIds.ID_MD_COPPER_POT.equals(type.id())) {
            specs = MinersDelightCopperPotSupport.adaptIngredientSpecs(specs, recipe, player.serverLevel().registryAccess());
        }
        if (specs == null || specs.stream().noneMatch(spec -> spec != null && !spec.isEmpty())) {
            return MaterialAvailability.UNKNOWN;
        }
        CraftStorageEndpoint endpoint = StorageRestockSupport.resolve(player).orElse(null);
        if (ModList.get().isLoaded("refinedstorage")
                && (endpoint == null || !"beyonddimensions".equals(endpoint.session().reference().backendId().value()))) {
            var network = GenericCraftPacket.resolveNetworkForRecipe(player, key.dimension(), key.machinePos(), type);
            if (network != null) endpoint = CraftStorageEndpoints.fromLegacyNetwork(network);
        }
        Map<StackKey, Integer> available = new HashMap<>(MaterialSources.countInventory(player));
        if (endpoint != null) {
            if (!StorageRestockSupport.canExtract(endpoint, player)) return MaterialAvailability.UNKNOWN;
            // Availability checks only need variants whose item type can satisfy
            // one of this recipe's ingredients. Unknown custom predicates keep
            // the complete-snapshot fallback so compatibility is not weakened.
            Set<Item> itemTypes = itemTypes(specs);
            var snapshot = itemTypes == null
                    ? endpoint.snapshot(player)
                    : endpoint.snapshot(player, itemTypes);
            if (!snapshot.successful()) return MaterialAvailability.UNKNOWN;
            snapshot.snapshot().orElseThrow().items().forEach(item ->
                    add(available, item.stack(), item.amount()));
        }
        if (GoetyRecipeHandler.isRitualRecipe(recipe)) {
            if (machineLevel == null) return MaterialAvailability.UNKNOWN;
            List<ItemStack> pedestals = GoetyMaterialAvailability.pedestalItems(machineLevel, key.machinePos(), recipe);
            if (pedestals == null) return MaterialAvailability.UNKNOWN;
            pedestals.forEach(stack -> add(available, stack, stack.getCount()));
            player.getInventory().offhand.forEach(stack -> add(available, stack, stack.getCount()));
        }
        return evaluate(specs, available);
    }

    public static MaterialAvailability evaluate(@Nullable List<IngredientSpec> specs,
                                                Map<StackKey, Integer> available) {
        if (specs == null || specs.isEmpty() || specs.stream().anyMatch(spec -> spec == null || spec.count() < 0)
                || specs.stream().noneMatch(spec -> !spec.isEmpty())) return MaterialAvailability.UNKNOWN;
        return DirectMaterialAllocator.allocate(specs, available).feasible()
                ? MaterialAvailability.READY : MaterialAvailability.MISSING;
    }

    private static void add(Map<StackKey, Integer> items, ItemStack stack, long count) {
        if (stack.isEmpty() || count <= 0) return;
        var material = com.huanghuang.rsintegration.crafting.graph.MaterialKey.of(stack);
        items.merge(new StackKey(material.item(), material.tag()), (int) Math.min(Integer.MAX_VALUE, count),
                (a, b) -> (int) Math.min(Integer.MAX_VALUE, (long) a + b));
    }

    @Nullable
    private static Set<Item> itemTypes(List<IngredientSpec> specs) {
        java.util.HashSet<Item> types = new java.util.HashSet<>();
        for (IngredientSpec spec : specs) {
            if (spec == null || spec.isEmpty()) continue;
            Set<Item> candidates = IngredientMatcher.itemTypesForMatching(spec.ingredient());
            if (candidates == null) return null;
            types.addAll(candidates);
        }
        return Set.copyOf(types);
    }

    @Nullable
    static List<IngredientSpec> ingredients(Recipe<?> recipe, ItemStack base, ItemStack output) {
        if (CrockPotRecipeHandler.hasCategoryConstraints(recipe)
                || OpenBoundMachineGuiPacket.isFaApplyModifier(recipe)) return null;
        List<IngredientSpec> specs;
        if (ArsDynamicApparatusRecipe.isSupported(recipe)) {
            if (ArsDynamicApparatusRecipe.validatedOutput(recipe, output).isEmpty()) return null;
            specs = ArsDynamicApparatusRecipe.buildMaterials(recipe, output);
        } else if (GoetyDynamicRitualRecipe.isSupported(recipe)) {
            if (GoetyDynamicRitualRecipe.validatedOutput(recipe, output).isEmpty()) return null;
            specs = GoetyDynamicRitualRecipe.buildMaterials(recipe, output);
        } else {
            specs = recipe instanceof CraftingRecipe crafting
                    ? CraftPacketUtils.extractCraftingIngredientSpecs(crafting)
                    : CraftPacketUtils.extractIngredientSpecs(recipe);
        }
        if (specs == null) return null;
        if (recipe instanceof SmithingTransformRecipe smithing) {
            if (!base.isEmpty() && !smithing.isBaseIngredient(base)) return null;
            specs = SmithingRecipeHandler.requireExactBase(smithing, specs, base);
        }
        if (recipe.getClass().getName().endsWith("ArcaneIteratorRecipe") && !output.isEmpty()) {
            int level = WRRecipeHandler.inferTargetLevel(recipe, output);
            if (level >= 2) {
                ItemStack previous = WRRecipeHandler.buildEnchantedBookOutput(recipe, level - 1);
                if (previous.isEmpty() || specs.isEmpty()) return null;
                specs = new ArrayList<>(specs);
                IngredientSpec center = specs.get(0);
                specs.set(0, new IngredientSpec(StrictNBTIngredient.of(previous), center.count(), center.role()));
            }
        }
        return specs;
    }
}
