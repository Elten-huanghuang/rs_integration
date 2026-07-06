package com.huanghuang.rsintegration.mods.farmersdelight;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.batch.GenericBatchDelegate;
import com.huanghuang.rsintegration.mods.IModIntegration;
import com.huanghuang.rsintegration.network.binding.BindingEventHandler;
import com.huanghuang.rsintegration.recipe.FarmersDelightRecipeHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.DistExecutor;

import java.util.List;
import java.util.function.Supplier;

public final class FarmersDelightRSModule implements IModIntegration {

    public static final FarmersDelightRSModule INSTANCE = new FarmersDelightRSModule();

    private FarmersDelightRSModule() {}

    @Override
    public ForgeConfigSpec.BooleanValue configFlag() {
        return RSIntegrationConfig.ENABLE_FARMERSDELIGHT;
    }

    @Override
    public String modId() {
        return "farmersdelight";
    }

    @Override
    public void registerModType() {
        // Specific: Cooking Pot recipes
        ModType.register("farmersdelight_cooking_pot",
                new String[]{"vectorwing.farmersdelight.common.crafting.CookingPotRecipe"},
                new String[]{"cooking_pot"},
                new String[]{"farmersdelight_cooking_pot"},
                CookingPotBatchDelegate::new);
        ModType.configureJei("farmersdelight_cooking_pot",
                new String[][]{{"farmersdelight:cooking"}},
                new String[][]{{"vectorwing.farmersdelight.common.crafting.CookingPotRecipe", "farmersdelight_cooking_pot"}},
                "gui.rs_integration.jei.fd_cooking_pot_craft");

        // Specific: Skillet (campfire-like recipes)
        // Registered after vanilla_machine (modules run in onCommonSetup, after
        // constructor).  With >= in classifyRecipe, this takes priority over
        // vanilla_machine for CampfireCookingRecipe classification.
        // vanilla_campfire blockKey prefix is included as an alias so that old
        // campfire bindings (created before FD support was added) still resolve
        // to farmersdelight_skillet and match CampfireCookingRecipe recipes.
        ModType.register("farmersdelight_skillet",
                new String[]{"net.minecraft.world.item.crafting.CampfireCookingRecipe"},
                new String[]{"skillet"},
                new String[]{"farmersdelight_skillet", "vanilla_campfire"},
                SkilletBatchDelegate::new);
        ModType.configureJei("farmersdelight_skillet",
                new String[][]{{"minecraft:campfire", "farmersdelight_skillet"}},
                new String[][]{{"net.minecraft.world.item.crafting.CampfireCookingRecipe", "farmersdelight_skillet"}},
                "gui.rs_integration.jei.fd_skillet_craft");

        // General fallback for any future Farmer's Delight recipe types
        ModType.register("farmersdelight",
                new String[]{"vectorwing.farmersdelight."},
                new String[]{"farmersdelight"},
                new String[0],
                GenericBatchDelegate::new);
        ModType.configureJei("farmersdelight",
                new String[][]{{"farmersdelight:cooking", "farmersdelight_cooking_pot"}},
                new String[][]{{"vectorwing.farmersdelight.", "farmersdelight"}},
                null);
    }

    @Override
    public void registerBindingTargets() {
        // Cooking Pot — has GUI (MenuProvider)
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "farmersdelight", ModType.byId("farmersdelight_cooking_pot"),
                RSIntegrationConfig.ENABLE_FARMERSDELIGHT,
                List.of("vectorwing.farmersdelight.common.block.CookingPotBlock"),
                "farmersdelight_cooking_pot", true
        ));

        // Skillet — no GUI, world-interaction (QUICK)
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "farmersdelight", ModType.byId("farmersdelight_skillet"),
                RSIntegrationConfig.ENABLE_FARMERSDELIGHT,
                List.of("vectorwing.farmersdelight.common.block.SkilletBlock"),
                "farmersdelight_skillet", false
        ));

        // Campfire — also registered under farmersdelight_skillet so that
        // vanilla campfires continue to work after CampfireCookingRecipe
        // classification is taken over by this module.
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "minecraft", ModType.byId("farmersdelight_skillet"),
                RSIntegrationConfig.ENABLE_FARMERSDELIGHT,
                List.of("net.minecraft.world.level.block.CampfireBlock"),
                "farmersdelight_skillet", false
        ));
    }

    @Override
    public void registerRecipeHandler() {
        ModRecipeHandlers.register(new FarmersDelightRecipeHandler());
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
