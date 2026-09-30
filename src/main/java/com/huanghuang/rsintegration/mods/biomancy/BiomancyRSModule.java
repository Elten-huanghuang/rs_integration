package com.huanghuang.rsintegration.mods.biomancy;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.mods.IModIntegration;
import com.huanghuang.rsintegration.network.binding.BindingEventHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.util.ModIds;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

public final class BiomancyRSModule implements IModIntegration {

    public static final BiomancyRSModule INSTANCE = new BiomancyRSModule();

    private BiomancyRSModule() {}

    @Override
    public ForgeConfigSpec.BooleanValue configFlag() {
        return RSIntegrationConfig.ENABLE_BIOMANCY;
    }

    @Override
    public String modId() {
        return ModIds.BIOMANCY;
    }

    @Override
    public void registerModType() {
        registerType(ModIds.ID_BIOMANCY_DIGESTER, "DigestingRecipe", "digesting", "digester");
        registerType(ModIds.ID_BIOMANCY_BIO_LAB, "BioBrewingRecipe", "bio_brewing", "bio_lab");
        registerType(ModIds.ID_BIOMANCY_DECOMPOSER, "DecomposingRecipe", "decomposing", "decomposer");
        registerType(ModIds.ID_BIOMANCY_BIO_FORGE, "BioForgingRecipe", "bio_forging", "bio_forge");
    }

    private static void registerType(String typeId, String recipeClass, String categoryId, String blockId) {
        String recipePrefix = "com.github.elenterius.biomancy.crafting.recipe.";
        String[] recipePrefixes = "DigestingRecipe".equals(recipeClass)
                ? new String[]{recipePrefix + recipeClass, recipePrefix + "FoodDigestingRecipe",
                        recipePrefix + "StaticDigestingRecipe"}
                : new String[]{recipePrefix + recipeClass};
        ModType.register(typeId,
                recipePrefixes,
                new String[]{"block.biomancy." + blockId}, new String[]{typeId},
                BiomancyBatchDelegate::new);
        ModType.configureJei(typeId,
                new String[][]{{"biomancy:" + categoryId}},
                List.of(recipePrefixes).stream().map(prefix -> new String[]{prefix}).toArray(String[][]::new),
                "gui.rs_integration.jei.vanilla_machine_craft");
        ModType.byId(typeId).requireFlatExecution(
                "Biomancy machine state and nutrient fuel are runtime-managed");
    }

    @Override
    public void registerBindingTargets() {
        registerBindingTarget(ModIds.ID_BIOMANCY_DIGESTER,
                "com.github.elenterius.biomancy.block.digester.DigesterBlock");
        registerBindingTarget(ModIds.ID_BIOMANCY_BIO_LAB,
                "com.github.elenterius.biomancy.block.biolab.BioLabBlock");
        registerBindingTarget(ModIds.ID_BIOMANCY_DECOMPOSER,
                "com.github.elenterius.biomancy.block.decomposer.DecomposerBlock");
        registerBindingTarget(ModIds.ID_BIOMANCY_BIO_FORGE,
                "com.github.elenterius.biomancy.block.bioforge.BioForgeBlock");
    }

    private static void registerBindingTarget(String typeId, String blockClass) {
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                ModIds.BIOMANCY, ModType.byId(typeId), RSIntegrationConfig.ENABLE_BIOMANCY,
                List.of(blockClass), typeId, true));
    }

    @Override
    public void registerRecipeHandler() {
        ModRecipeHandlers.register(new BiomancyRecipeHandler(
                ModIds.ID_BIOMANCY_DIGESTER, BiomancyRecipeHandler.Kind.DIGESTER));
        ModRecipeHandlers.register(new BiomancyRecipeHandler(
                ModIds.ID_BIOMANCY_BIO_LAB, BiomancyRecipeHandler.Kind.BIO_LAB));
        ModRecipeHandlers.register(new BiomancyRecipeHandler(
                ModIds.ID_BIOMANCY_DECOMPOSER, BiomancyRecipeHandler.Kind.DECOMPOSER));
        ModRecipeHandlers.register(new BiomancyRecipeHandler(
                ModIds.ID_BIOMANCY_BIO_FORGE, BiomancyRecipeHandler.Kind.BIO_FORGE));
    }

    @Override
    public void registerNetworkPackets() {}

    @Override
    public void initCommon() {}
}
