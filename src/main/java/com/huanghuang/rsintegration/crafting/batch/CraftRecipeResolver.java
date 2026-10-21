package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.recipe.viewer.RecipeIdNormalizer;
import com.huanghuang.rsintegration.crafting.fluid.FluidContainerCatalog;
import com.huanghuang.rsintegration.mods.apotheosis.ApotheosisGemCuttingCatalog;
import com.huanghuang.rsintegration.mods.biomancy.BiomancyDigestingRecipeResolver;
import com.huanghuang.rsintegration.mods.crabbersdelight.CrabTrapRecipeResolver;
import com.huanghuang.rsintegration.mods.crockpot.BirdcageEggCatalog;
import com.huanghuang.rsintegration.mods.distantworlds.LithumAltarRecipeResolver;
import com.huanghuang.rsintegration.mods.distantworlds.LithumAltarRecipeWrapper;
import com.huanghuang.rsintegration.mods.farmersdelight.CosmopolitanTisaneRecipeResolver;
import com.huanghuang.rsintegration.mods.farmingforblockheads.MarketBatchDelegate;
import com.huanghuang.rsintegration.mods.forbidden.FaRitualHelper;
import com.huanghuang.rsintegration.mods.ironsspellbooks.IronSpellBooksRecipeCatalog;
import com.huanghuang.rsintegration.mods.pmmo.PmmoSalvageCatalog;
import com.huanghuang.rsintegration.mods.vanilla.brewing.VanillaBrewingCatalog;
import com.huanghuang.rsintegration.util.ModIds;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import net.minecraft.world.item.crafting.SmithingTrimRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraftforge.fml.ModList;

import javax.annotation.Nullable;

/** 服务端配方 ID 到实际 Recipe 对象的统一解析入口。 */
final class CraftRecipeResolver {
    private CraftRecipeResolver() {}

    @Nullable
    static Recipe<?> resolve(ServerLevel level, ResourceLocation recipeId) {
        recipeId = RecipeIdNormalizer.normalize(recipeId);
        Recipe<?> recipe = level.getRecipeManager().byKey(recipeId).orElse(null);
        if (recipe != null) return recipe;

        recipe = FluidContainerCatalog.resolve(level, recipeId);
        if (recipe != null) return recipe;

        recipe = resolveBiomancyDigesting(level, recipeId);
        if (recipe != null) return recipe;

        recipe = BirdcageEggCatalog.resolve(level, recipeId);
        if (recipe != null) return recipe;

        recipe = CosmopolitanTisaneRecipeResolver.resolve(recipeId);
        if (recipe != null) return recipe;

        if ("rs_integration".equals(recipeId.getNamespace())
                && recipeId.getPath().startsWith("vanilla_brewing/")) {
            VanillaBrewingCatalog.ensureBuilt(level);
            recipe = VanillaBrewingCatalog.byId(recipeId);
            if (recipe != null) return recipe;
        }

        if (ModIds.FORBIDDEN_ARCANUS.equals(recipeId.getNamespace())) {
            recipe = level.getRecipeManager().byKey(
                    new ResourceLocation(recipeId.getNamespace(),
                            "smithing/" + recipeId.getPath())).orElse(null);
            if (recipe != null) return recipe;
        }

        if (ModIds.FORBIDDEN_ARCANUS.equals(recipeId.getNamespace())) {
            recipe = resolveFARitual(level, recipeId);
            if (recipe != null) return recipe;
        }
        recipe = MarketBatchDelegate.resolveMarketEntry(recipeId);
        if (recipe != null) return recipe;
        recipe = ApotheosisGemCuttingCatalog.byId(recipeId);
        if (recipe != null) return recipe;
        if (ModList.get().isLoaded(ModIds.IRONS_SPELLBOOKS)) {
            recipe = IronSpellBooksRecipeCatalog.byId(level, recipeId);
            if (recipe != null) return recipe;
        }
        if (ModList.get().isLoaded(ModIds.PMMO)) {
            recipe = PmmoSalvageCatalog.byId(recipeId);
            if (recipe != null) return recipe;
        }
        recipe = CrabTrapRecipeResolver.resolveRecipe(level, recipeId);
        if (recipe != null) return recipe;
        var firon = LithumAltarRecipeResolver.resolve(recipeId);
        if (firon != null && LithumAltarRecipeResolver.isComplete(firon)) {
            return new LithumAltarRecipeWrapper(recipeId, firon);
        }

        if (ModIds.FORBIDDEN_ARCANUS.equals(recipeId.getNamespace())) {
            recipe = FaRitualHelper.resolveFARitualScan(level, recipeId);
            if (recipe != null) return recipe;
        }

        RSIntegrationMod.LOGGER.warn("[RSI-resolveRecipe] All lookups failed for {}", recipeId);
        return null;
    }

    @Nullable
    static Recipe<?> resolveBiomancyDigesting(
            ServerLevel level, ResourceLocation recipeId) {
        ModList loadedMods = ModList.get();
        if (loadedMods == null || !loadedMods.isLoaded(ModIds.BIOMANCY)) return null;
        try {
            return BiomancyDigestingRecipeResolver.resolve(level, recipeId);
        } catch (LinkageError | RuntimeException unavailable) {
            RSIntegrationMod.debug(
                    "[RSI-resolveRecipe] Biomancy digesting compatibility unavailable: {}",
                    unavailable.toString());
            return null;
        }
    }

    @Nullable
    static String machineKeyword(Recipe<?> recipe) {
        if (recipe instanceof SmithingTransformRecipe
                || recipe instanceof SmithingTrimRecipe) {
            return "smithing_table";
        }
        if (recipe instanceof AbstractCookingRecipe) {
            String className = recipe.getClass().getName();
            if (className.contains("Campfire")) return "campfire";
            if (className.contains("Blasting")) return "blast_furnace";
            if (className.contains("Smoking")) return "smoker";
            return "furnace";
        }
        if (recipe instanceof StonecutterRecipe) return "stonecutter";
        return null;
    }

    @Nullable
    private static Recipe<?> resolveFARitual(ServerLevel level, ResourceLocation recipeId) {
        if (!ModIds.FORBIDDEN_ARCANUS.equals(recipeId.getNamespace())) return null;
        Object ritual = FaRitualHelper.getRitualById(recipeId, level);
        if (ritual == null) {
            RSIntegrationMod.debug("[RSI-Generic] FA ritual not found in registry: {}", recipeId);
            return null;
        }
        return FaRitualHelper.wrapFaRitual(recipeId, ritual);
    }
}
