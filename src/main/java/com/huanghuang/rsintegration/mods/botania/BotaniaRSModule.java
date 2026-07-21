package com.huanghuang.rsintegration.mods.botania;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.mods.IModIntegration;
import com.huanghuang.rsintegration.network.binding.BindingEventHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

public final class BotaniaRSModule implements IModIntegration {
    public static final BotaniaRSModule INSTANCE = new BotaniaRSModule();
    private BotaniaRSModule() {}

    @Override public ForgeConfigSpec.BooleanValue configFlag() { return RSIntegrationConfig.ENABLE_BOTANIA; }
    @Override public String modId() { return "botania"; }

    @Override public void registerModType() {
        register("botania_mana_pool", "ManaInfusionRecipe", "mana_pool", "ManaPoolBatchDelegate");
        configureJei("botania_mana_pool", "botania:mana_pool", "ManaInfusionRecipe", "mana_pool", "mana_pool");
        register("botania_apothecary", "PetalsRecipe", "apothecary", "PetalApothecaryBatchDelegate");
        configureJei("botania_apothecary", "botania:petals", "PetalsRecipe", "apothecary", "apothecary");
        register("botania_runic_altar", "RunicAltarRecipe", "runic_altar", "RunicAltarBatchDelegate");
        configureJei("botania_runic_altar", "botania:runic_altar", "RunicAltarRecipe", "runic_altar", "runic_altar");
        register("botania_brewery", "BotanicalBreweryRecipe", "brewery", "BotanicalBreweryBatchDelegate");
        configureJei("botania_brewery", "botania:brewery", "BotanicalBreweryRecipe", "brewery", "brewery");
        register("botania_elven_trade", "ElvenTradeRecipe", "alfheim_portal", "ElvenTradeBatchDelegate");
        configureJei("botania_elven_trade", "botania:elven_trade", "ElvenTradeRecipe", "alfheim_portal", "elven_trade");
        register("botania_terra_plate", "RecipeTerraPlate", "terra_plate", "TerraPlateBatchDelegate");
        configureJei("botania_terra_plate", "botania:terra_plate", "RecipeTerraPlate", "terra_plate", "terra_plate");
        register("botania_pure_daisy", "PureDaisyRecipe", "pure_daisy", "PureDaisyBlockConversionDelegate");
        configureJei("botania_pure_daisy", "botania:pure_daisy", "PureDaisyRecipe", "pure_daisy", "pure_daisy");
    }

    private static void configureJei(String id, String uid, String recipeClass,
                                     String bindingFilter, String tooltipSuffix) {
        ModType.configureJei(id,
                new String[][]{{uid, bindingFilter}},
                new String[][]{{"vazkii.botania.common.crafting." + recipeClass, bindingFilter}},
                "gui.rs_integration.jei.botania_" + tooltipSuffix + "_craft");
    }
    private static void register(String id, String recipeClass, String blockKey, String delegate) {
        ModType.register(id,
                new String[]{"vazkii.botania.common.crafting." + recipeClass},
                new String[]{blockKey}, new String[]{blockKey},
                ModType.delegateSupplier("com.huanghuang.rsintegration.mods.botania." + delegate));
    }

    @Override public void registerBindingTargets() {
        // ConjurationCatalystBlock extends AlchemyCatalystBlock; register the leaf first so binding uses the concrete key.
        target("botania_mana_pool", "conjuration_catalyst", "vazkii.botania.common.block.mana.ConjurationCatalystBlock");
        target("botania_mana_pool", "alchemy_catalyst", "vazkii.botania.common.block.mana.AlchemyCatalystBlock");
        target("botania_mana_pool", "mana_pool", "vazkii.botania.common.block.mana.ManaPoolBlock");
        target("botania_apothecary", "apothecary", "vazkii.botania.common.block.PetalApothecaryBlock");
        target("botania_runic_altar", "runic_altar", "vazkii.botania.common.block.mana.RunicAltarBlock");
        target("botania_brewery", "brewery", "vazkii.botania.common.block.mana.BotanicalBreweryBlock");
        target("botania_elven_trade", "alfheim_portal", "vazkii.botania.common.block.AlfheimPortalBlock");
        target("botania_terra_plate", "terra_plate", "vazkii.botania.common.block.mana.TerrestrialAgglomerationPlateBlock");
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "botania", ModType.byId("botania_pure_daisy"), RSIntegrationConfig.ENABLE_BOTANIA,
                List.of(), List.of("botania:pure_daisy"), "pure_daisy", false));
    }

    private static void target(String type, String key, String blockClass) {
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "botania", ModType.byId(type), RSIntegrationConfig.ENABLE_BOTANIA,
                List.of(blockClass), key, false));
    }

    @Override public void registerNetworkPackets() {}
    @Override public void initCommon() {}

    @Override public void registerRecipeHandler() {
        ModRecipeHandlers.register(new BotaniaRecipeHandler(BotaniaRecipeHandler.Kind.MANA_POOL, "botania_mana_pool"));
        ModRecipeHandlers.register(new BotaniaRecipeHandler(BotaniaRecipeHandler.Kind.APOTHECARY, "botania_apothecary"));
        ModRecipeHandlers.register(new BotaniaRecipeHandler(BotaniaRecipeHandler.Kind.RUNIC_ALTAR, "botania_runic_altar"));
        ModRecipeHandlers.register(new BotaniaRecipeHandler(BotaniaRecipeHandler.Kind.BREWERY, "botania_brewery"));
        ModRecipeHandlers.register(new BotaniaRecipeHandler(BotaniaRecipeHandler.Kind.ELVEN_TRADE, "botania_elven_trade"));
        ModRecipeHandlers.register(new BotaniaRecipeHandler(BotaniaRecipeHandler.Kind.TERRA_PLATE, "botania_terra_plate"));
        ModRecipeHandlers.register(new BotaniaRecipeHandler(BotaniaRecipeHandler.Kind.PURE_DAISY, "botania_pure_daisy"));
    }
}
