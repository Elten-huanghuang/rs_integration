package com.example.rsiintegration.apismoke;

import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(
        modid = RsiApiSmokeMod.MOD_ID,
        bus = Mod.EventBusSubscriber.Bus.FORGE
)
public final class ApiSmokeForgeEvents {
    private ApiSmokeForgeEvents() {}

    @SubscribeEvent
    public static void registerCommands(RegisterCommandsEvent event) {
        ConsumerSmokeCommand.register(event.getDispatcher());
    }

    @SubscribeEvent
    public static void addCreativeTabItems(
            BuildCreativeModeTabContentsEvent event
    ) {
        if (event.getTabKey() == CreativeModeTabs.INGREDIENTS) {
            event.accept(RsiApiSmokeMod.API_SMOKE_RESULT);
        }
    }
}
