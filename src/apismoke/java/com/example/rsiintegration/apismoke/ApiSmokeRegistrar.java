package com.example.rsiintegration.apismoke;

import com.huanghuang.rsintegration.api.RSIIntegrationApi;
import com.huanghuang.rsintegration.api.RegistrationOptions;
import com.huanghuang.rsintegration.api.RegistrationResult;
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
        if (!api.version().supports(1, 1, 0)) {
            throw new IllegalStateException(
                    "RSI API 1.1 or newer is required, found "
                            + api.version()
            );
        }
        LOGGER.info("[Smoke] direct API registration");
        FarmersDelightCookingPotIntegration cookingPot =
                new FarmersDelightCookingPotIntegration();
        IronFurnacesSmeltingIntegration ironFurnaces =
                new IronFurnacesSmeltingIntegration();
        RegistrationOptions smokePriority = new RegistrationOptions(
                1000,
                Map.of("source", RsiApiSmokeMod.MOD_ID)
        );
        requireAccepted(
                "Farmers Delight recipe",
                api.registerRecipe(cookingPot.recipe(), smokePriority)
        );
        requireAccepted(
                "Farmers Delight machine",
                api.registerMachine(cookingPot.machine(), smokePriority)
        );
        requireAccepted(
                "Iron Furnaces recipe",
                api.registerRecipe(ironFurnaces.recipe(), smokePriority)
        );
        requireAccepted(
                "Iron Furnaces machine",
                api.registerMachine(ironFurnaces.machine(), smokePriority)
        );
        if (ModList.get().isLoaded("ars_nouveau")) {
            ArsNouveauIntegration arsNouveau =
                    new ArsNouveauIntegration();
            requireAccepted(
                    "Ars Nouveau recipe",
                    api.registerRecipe(arsNouveau.recipe(), smokePriority)
            );
            requireAccepted(
                    "Ars Nouveau machine",
                    api.registerMachine(arsNouveau.machine(), smokePriority)
            );
        }
        if (ModList.get().isLoaded("irons_spellbooks")) {
            IronAlchemistCauldronIntegration alchemist =
                    new IronAlchemistCauldronIntegration();
            requireAccepted(
                    "Iron's Spellbooks recipe",
                    api.registerRecipe(alchemist.recipe(), smokePriority)
            );
            requireAccepted(
                    "Iron's Spellbooks machine",
                    api.registerMachine(alchemist.machine(), smokePriority)
            );
            requireAccepted(
                    "Iron's Spellbooks execution",
                    api.registerExecution(
                            alchemist.execution(),
                            smokePriority
                    )
            );
        }
    }

    private static void requireAccepted(
            String label,
            RegistrationResult result
    ) {
        if (result.accepted()) return;
        throw new IllegalStateException(
                "RSI smoke registration failed: " + label
                        + ", status=" + result.status()
                        + ", id=" + result.registeredId()
                        + ", diagnostics=" + result.diagnostics()
        );
    }
}
