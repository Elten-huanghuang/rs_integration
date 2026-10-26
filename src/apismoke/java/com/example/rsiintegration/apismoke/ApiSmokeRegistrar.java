package com.example.rsiintegration.apismoke;

import com.huanghuang.rsintegration.api.RSIIntegrationApi;
import com.huanghuang.rsintegration.api.RegistrationOptions;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.ModList;

import java.util.Map;

@Mod.EventBusSubscriber(
        modid = RsiApiSmokeMod.MOD_ID,
        bus = Mod.EventBusSubscriber.Bus.MOD
)
public final class ApiSmokeRegistrar {
    private static final Logger LOGGER =
            LogManager.getLogger("RSI-API-Smoke");

    private ApiSmokeRegistrar() {}

    @SubscribeEvent
    public static void registerIntegrations(
            FMLCommonSetupEvent event
    ) {
        RSIIntegrationApi api = RSIIntegrationApi.get().orElseThrow();
        LOGGER.info("[Smoke] direct API registration");
        FarmersDelightCookingPotIntegration cookingPot =
                new FarmersDelightCookingPotIntegration();
        IronFurnacesSmeltingIntegration ironFurnaces =
                new IronFurnacesSmeltingIntegration();
        RegistrationOptions smokePriority = new RegistrationOptions(
                1000,
                Map.of("source", RsiApiSmokeMod.MOD_ID)
        );
        api.registerRecipe(cookingPot.recipe(), smokePriority);
        api.registerMachine(cookingPot.machine(), smokePriority);
        api.registerRecipe(ironFurnaces.recipe(), smokePriority);
        api.registerMachine(ironFurnaces.machine(), smokePriority);
        if (ModList.get().isLoaded("ars_nouveau")) {
            ArsNouveauIntegration arsNouveau =
                    new ArsNouveauIntegration();
            api.registerRecipe(arsNouveau.recipe(), smokePriority);
            api.registerMachine(arsNouveau.machine(), smokePriority);
        }
        if (ModList.get().isLoaded("irons_spellbooks")) {
            IronAlchemistCauldronIntegration alchemist =
                    new IronAlchemistCauldronIntegration();
            api.registerRecipe(alchemist.recipe(), smokePriority);
            api.registerMachine(alchemist.machine(), smokePriority);
            api.registerExecution(
                    alchemist.execution(),
                    smokePriority
            );
        }
    }
}
