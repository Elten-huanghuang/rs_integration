package com.example.rsiintegration.apismoke;

import com.huanghuang.rsintegration.api.RSIIntegrationRegistrationEvent;
import com.huanghuang.rsintegration.api.RegistrationOptions;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;

@Mod(RsiApiSmokeMod.MOD_ID)
public final class RsiApiSmokeMod {
    public static final String MOD_ID = "rsi_api_smoke";

    public RsiApiSmokeMod() {
        MinecraftForge.EVENT_BUS.addListener(this::registerIntegrations);
    }

    private void registerIntegrations(RSIIntegrationRegistrationEvent event) {
        FarmersDelightCookingPotIntegration cookingPot =
                new FarmersDelightCookingPotIntegration();
        IronFurnacesSmeltingIntegration ironFurnaces =
                new IronFurnacesSmeltingIntegration();
        RegistrationOptions smokePriority =
                new RegistrationOptions(1000, Map.of("source", MOD_ID));
        event.api().registerRecipe(cookingPot.recipe(), smokePriority);
        event.api().registerMachine(cookingPot.machine(), smokePriority);
        event.api().registerRecipe(ironFurnaces.recipe(), smokePriority);
        event.api().registerMachine(ironFurnaces.machine(), smokePriority);
    }
}
