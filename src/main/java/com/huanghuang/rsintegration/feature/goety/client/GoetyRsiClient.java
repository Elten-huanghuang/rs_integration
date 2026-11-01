package com.huanghuang.rsintegration.feature.goety.client;

import com.huanghuang.rsintegration.crafting.plan.CraftingPlanScreen;
import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import com.huanghuang.rsintegration.feature.goety.integration.BrewMaterialPlan;
import com.huanghuang.rsintegration.feature.goety.menu.GoetyRsiMenus;
import com.huanghuang.rsintegration.feature.goety.menu.WitchCauldronMenu;
import com.huanghuang.rsintegration.feature.goety.network.BrewNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

public final class GoetyRsiClient {
    private GoetyRsiClient() {}

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> MenuScreens.register(
                GoetyRsiMenus.WITCH_CAULDRON.get(), WitchCauldronScreen::new));
    }

    public static void accept(BrewNetwork.State state) {
        var player = Minecraft.getInstance().player;
        if (player != null && player.containerMenu instanceof WitchCauldronMenu menu
                && menu.containerId == state.menuId()) menu.accept(state.data());
    }

    public static void acceptPlan(PlanResponse plan, long requestId) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || !(mc.player.containerMenu instanceof WitchCauldronMenu menu)
                || !BrewMaterialPlan.targetId(menu.containerId, menu.clientRevision).toString().equals(plan.recipeId())) return;
        if (mc.screen instanceof CraftingPlanScreen existing) {
            existing.acceptResponse(requestId, plan);
        } else if (mc.screen instanceof WitchCauldronScreen parent && parent.getMenu() == menu
                && parent.consumeMaterialPlan(menu.clientRevision)) {
            int revision = menu.clientRevision;
            mc.setScreen(new CraftingPlanScreen(plan, requestId,
                    (preview, id, forced, locks, preferences) ->
                            BrewNetwork.CHANNEL.sendToServer(new BrewNetwork.PlanAction(
                                    menu.containerId, revision, preview, id, forced, locks, preferences)),
                    () -> mc.setScreen(mc.player != null && mc.player.containerMenu == menu ? parent : null)));
        }
    }
}
