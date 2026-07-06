package com.huanghuang.rsintegration.mods.youkaishomecoming;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.batch.GenericBatchDelegate;
import com.huanghuang.rsintegration.mods.IModIntegration;
import com.huanghuang.rsintegration.network.binding.BindingEventHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.DistExecutor;

import java.util.List;
import java.util.function.Supplier;

public final class YoukaisHomecomingRSModule implements IModIntegration {

    public static final YoukaisHomecomingRSModule INSTANCE = new YoukaisHomecomingRSModule();

    private YoukaisHomecomingRSModule() {}

    @Override
    public ForgeConfigSpec.BooleanValue configFlag() {
        return RSIntegrationConfig.ENABLE_YOUKAISHOMECOMING;
    }

    @Override
    public String modId() {
        return "youkaishomecoming";
    }

    @Override
    public void registerModType() {
        // ── Moka Pot ──
        ModType.register("youkaishomecoming_moka",
                new String[]{"dev.xkmc.youkaishomecoming.content.pot.moka.MokaRecipe"},
                new String[]{"moka_pot", "moka"},
                new String[]{"youkaishomecoming_moka"},
                ModType.delegateSupplier("com.huanghuang.rsintegration.mods.youkaishomecoming.moka.MokaPotBatchDelegate"));
        ModType.configureJei("youkaishomecoming_moka",
                new String[][]{{"youkaishomecoming:moka"}},
                new String[][]{{"dev.xkmc.youkaishomecoming.content.pot.moka.MokaRecipe", "youkaishomecoming_moka"}},
                "gui.rs_integration.jei.yhk_moka_craft");

        // ── Fermentation Tank ──
        ModType.register("youkaishomecoming_ferment",
                new String[]{"dev.xkmc.youkaishomecoming.content.pot.ferment.SimpleFermentationRecipe"},
                new String[]{"fermentation_tank"},
                new String[]{"youkaishomecoming_ferment"},
                ModType.delegateSupplier("com.huanghuang.rsintegration.mods.youkaishomecoming.ferment.FermentationTankBatchDelegate"));
        ModType.configureJei("youkaishomecoming_ferment",
                new String[][]{{"youkaishomecoming:ferment", "youkaishomecoming_ferment"}},
                new String[][]{{"dev.xkmc.youkaishomecoming.content.pot.ferment.SimpleFermentationRecipe", "youkaishomecoming_ferment"}},
                "gui.rs_integration.jei.yhk_ferment_craft");

        // ── Steamer Pot ──
        ModType.register("youkaishomecoming_steamer",
                new String[]{"dev.xkmc.youkaishomecoming.content.pot.steamer.SteamingRecipe"},
                new String[]{"steamer_pot", "steamer"},
                new String[]{"youkaishomecoming_steamer"},
                ModType.delegateSupplier("com.huanghuang.rsintegration.mods.youkaishomecoming.steamer.SteamerBatchDelegate"));
        ModType.configureJei("youkaishomecoming_steamer",
                new String[][]{{"youkaishomecoming:steaming"}},
                new String[][]{{"dev.xkmc.youkaishomecoming.content.pot.steamer.SteamingRecipe", "youkaishomecoming_steamer"}},
                "gui.rs_integration.jei.yhk_steamer_craft");

        // ── Kettle ──
        ModType.register("youkaishomecoming_kettle",
                new String[]{"dev.xkmc.youkaishomecoming.content.pot.kettle.KettleRecipe"},
                new String[]{"kettle"},
                new String[]{"youkaishomecoming_kettle"},
                ModType.delegateSupplier("com.huanghuang.rsintegration.mods.youkaishomecoming.kettle.KettleBatchDelegate"));
        ModType.configureJei("youkaishomecoming_kettle",
                new String[][]{{"youkaishomecoming:kettle", "youkaishomecoming_kettle"}},
                new String[][]{{"dev.xkmc.youkaishomecoming.content.pot.kettle.KettleRecipe", "youkaishomecoming_kettle"}},
                "gui.rs_integration.jei.yhk_kettle_craft");

        // ── Cooking Pots (3 sizes, each a separate ModType) ──
        // Recipe classification uses result.getCraftingRemainingItem() to
        // tell which pot a recipe needs — class-name prefix alone can't
        // distinguish them since all PotCookingRecipe subclasses share the
        // same package.
        ModType.register("youkaishomecoming_cooking_small",
                new String[]{"dev.xkmc.youkaishomecoming.content.pot.cooking."},
                new String[]{"cooking_small"},
                new String[]{"youkaishomecoming_cooking_small"},
                ModType.delegateSupplier("com.huanghuang.rsintegration.mods.youkaishomecoming.cooking.SmallPotBatchDelegate"));
        ModType.configureJei("youkaishomecoming_cooking_small",
                new String[][]{{"youkaishomecoming:pot_cooking", "youkaishomecoming_cooking_small"}},
                new String[][]{{"dev.xkmc.youkaishomecoming.content.pot.cooking.", "youkaishomecoming_cooking_small"}},
                "gui.rs_integration.jei.yhk_cooking_pot_craft");

        ModType.register("youkaishomecoming_cooking_short",
                new String[]{"dev.xkmc.youkaishomecoming.content.pot.cooking."},
                new String[]{"cooking_short"},
                new String[]{"youkaishomecoming_cooking_short"},
                ModType.delegateSupplier("com.huanghuang.rsintegration.mods.youkaishomecoming.cooking.ShortPotBatchDelegate"));
        ModType.configureJei("youkaishomecoming_cooking_short",
                new String[][]{{"youkaishomecoming:pot_cooking", "youkaishomecoming_cooking_short"}},
                new String[][]{{"dev.xkmc.youkaishomecoming.content.pot.cooking.", "youkaishomecoming_cooking_short"}},
                "gui.rs_integration.jei.yhk_cooking_pot_craft");

        ModType.register("youkaishomecoming_cooking_large",
                new String[]{"dev.xkmc.youkaishomecoming.content.pot.cooking."},
                new String[]{"cooking_large", "stockpot"},
                new String[]{"youkaishomecoming_cooking_large"},
                ModType.delegateSupplier("com.huanghuang.rsintegration.mods.youkaishomecoming.cooking.LargePotBatchDelegate"));
        ModType.configureJei("youkaishomecoming_cooking_large",
                new String[][]{{"youkaishomecoming:pot_cooking", "youkaishomecoming_cooking_large"}},
                new String[][]{{"dev.xkmc.youkaishomecoming.content.pot.cooking.", "youkaishomecoming_cooking_large"}},
                "gui.rs_integration.jei.yhk_cooking_pot_craft");

        // ── Cuisine Board ──
        ModType.register("youkaishomecoming_cuisine",
                new String[]{"dev.xkmc.youkaishomecoming.content.pot.table.recipe."},
                new String[]{"cuisine_board"},
                new String[]{"youkaishomecoming_cuisine"},
                ModType.delegateSupplier("com.huanghuang.rsintegration.mods.youkaishomecoming.cuisine.CuisineBoardBatchDelegate"));
        ModType.configureJei("youkaishomecoming_cuisine",
                new String[][]{{"youkaishomecoming:cuisine", "youkaishomecoming_cuisine"}},
                new String[][]{{"dev.xkmc.youkaishomecoming.content.pot.table.recipe.CuisineRecipe", "youkaishomecoming_cuisine"}},
                null);

        // ── General fallback ──
        ModType.register("youkaishomecoming",
                new String[]{"dev.xkmc.youkaishomecoming.content."},
                new String[]{"youkaishomecoming"},
                new String[0],
                GenericBatchDelegate::new);
        ModType.configureJei("youkaishomecoming",
                new String[][]{{"youkaishomecoming:moka", "youkaishomecoming_moka"},
                        {"youkaishomecoming:steaming", "youkaishomecoming_steamer"},
                        {"youkaishomecoming:ferment", "youkaishomecoming_ferment"},
                        {"youkaishomecoming:pot_cooking", "youkaishomecoming_cooking_small"},
                        {"youkaishomecoming:kettle", "youkaishomecoming_kettle"},
                        {"youkaishomecoming:cuisine", "youkaishomecoming_cuisine"}},
                new String[][]{{"dev.xkmc.youkaishomecoming.content.", "youkaishomecoming"}},
                null);
    }

    @Override
    public void registerBindingTargets() {
        // Moka Pot — has GUI (MokaMenu)
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "youkaishomecoming", ModType.byId("youkaishomecoming_moka"),
                RSIntegrationConfig.ENABLE_YOUKAISHOMECOMING,
                List.of("dev.xkmc.youkaishomecoming.content.pot.moka.MokaMakerBlock"),
                "youkaishomecoming_moka", true
        ));

        // Steamer Pot — no GUI, world-interaction
        // Bind ONLY the pot; the delegate auto-detects racks and lid above.
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "youkaishomecoming", ModType.byId("youkaishomecoming_steamer"),
                RSIntegrationConfig.ENABLE_YOUKAISHOMECOMING,
                List.of(),
                List.of("youkaishomecoming:steamer_pot"),
                "youkaishomecoming_steamer", false
        ));

        // Fermentation Tank — no GUI, world-interaction
        // Uses L2ModularBlock DelegateBlock, so match by registry key only.
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "youkaishomecoming", ModType.byId("youkaishomecoming_ferment"),
                RSIntegrationConfig.ENABLE_YOUKAISHOMECOMING,
                List.of(),
                List.of("youkaishomecoming:fermentation_tank"),
                "youkaishomecoming_ferment", false
        ));

        // ── Small Iron Pot (矮锅) ──
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "youkaishomecoming", ModType.byId("youkaishomecoming_cooking_small"),
                RSIntegrationConfig.ENABLE_YOUKAISHOMECOMING,
                List.of(),
                List.of("youkaishomecoming:cooking_small_iron_pot",
                        "youkaishomecoming:small_iron_pot"),
                "youkaishomecoming_cooking_small", false
        ));

        // ── Short Iron Pot (短锅) ──
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "youkaishomecoming", ModType.byId("youkaishomecoming_cooking_short"),
                RSIntegrationConfig.ENABLE_YOUKAISHOMECOMING,
                List.of(),
                List.of("youkaishomecoming:cooking_short_iron_pot",
                        "youkaishomecoming:short_iron_pot"),
                "youkaishomecoming_cooking_short", false
        ));

        // ── Stockpot / Large Pot (汤锅) ──
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "youkaishomecoming", ModType.byId("youkaishomecoming_cooking_large"),
                RSIntegrationConfig.ENABLE_YOUKAISHOMECOMING,
                List.of(),
                List.of("youkaishomecoming:cooking_stockpot",
                        "youkaishomecoming:stockpot"),
                "youkaishomecoming_cooking_large", false
        ));

        // Kettle — has GUI (KettleContainer)
        // Uses L2ModularBlock DelegateEntityBlockImpl, so match by registry key only.
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "youkaishomecoming", ModType.byId("youkaishomecoming_kettle"),
                RSIntegrationConfig.ENABLE_YOUKAISHOMECOMING,
                List.of(),
                List.of("youkaishomecoming:kettle"),
                "youkaishomecoming_kettle", true
        ));

        // Cuisine Board — no GUI, world-interaction
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "youkaishomecoming", ModType.byId("youkaishomecoming_cuisine"),
                RSIntegrationConfig.ENABLE_YOUKAISHOMECOMING,
                List.of(),
                List.of("youkaishomecoming:cuisine_board"),
                "youkaishomecoming_cuisine", false
        ));
    }

    @Override
    public void registerRecipeHandler() {
        ModRecipeHandlers.register(new YoukaisHomecomingRecipeHandler());
    }

    @Override
    public void registerNetworkPackets() {}

    @Override
    public void initCommon() {}

    @Override
    public Supplier<DistExecutor.SafeRunnable> clientInitSupplier() {
        return () -> () -> {};
    }
}
