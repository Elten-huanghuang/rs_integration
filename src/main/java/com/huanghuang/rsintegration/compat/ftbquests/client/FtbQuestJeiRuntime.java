package com.huanghuang.rsintegration.compat.ftbquests.client;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.compat.ftbquests.FtbQuestSubmissionScanner;
import com.huanghuang.rsintegration.compat.ftbquests.QuestSubmissionSnapshot;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.List;

/** Adds player-specific quest recipes after FTB Quests finishes its client sync. */
public final class FtbQuestJeiRuntime {

    private static final List<QuestSubmissionSnapshot> REGISTERED = new ArrayList<>();
    private static IJeiRuntime runtime;
    private static boolean waiting;

    private FtbQuestJeiRuntime() {}

    public static void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        runtime = jeiRuntime;
        waiting = true;
        refreshIfReady();
    }

    public static void onRuntimeUnavailable() {
        runtime = null;
        waiting = false;
        REGISTERED.clear();
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END && waiting) refreshIfReady();
    }

    public static void requestRefresh() {
        waiting = runtime != null;
    }

    private static void refreshIfReady() {
        if (runtime == null || !dev.ftb.mods.ftbquests.client.ClientQuestFile.exists()) return;
        var file = dev.ftb.mods.ftbquests.client.ClientQuestFile.INSTANCE;
        var data = file != null ? file.selfTeamData : null;
        if (file == null || data == null) return;

        List<QuestSubmissionSnapshot> snapshots = FtbQuestSubmissionScanner.scan(file, data, true);
        var manager = runtime.getRecipeManager();
        if (!REGISTERED.isEmpty()) {
            manager.hideRecipes(FtbQuestSubmissionRecipe.TYPE, REGISTERED);
        }
        manager.addRecipes(FtbQuestSubmissionRecipe.TYPE, snapshots);
        REGISTERED.clear();
        REGISTERED.addAll(snapshots);
        waiting = false;
        RSIntegrationMod.LOGGER.info("[RSI-JEI] Registered {} FTB Quest submission entries",
                snapshots.size());
    }
}
