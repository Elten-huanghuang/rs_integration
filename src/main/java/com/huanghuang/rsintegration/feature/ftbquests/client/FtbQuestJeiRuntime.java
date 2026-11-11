package com.huanghuang.rsintegration.feature.ftbquests.client;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.feature.ftbquests.FtbQuestSubmissionScanner;
import com.huanghuang.rsintegration.feature.ftbquests.QuestSubmissionSnapshot;
import dev.ftb.mods.ftbquests.client.ClientQuestFile;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.List;

/** Adds player-specific quest recipes after FTB Quests finishes its client sync. */
public final class FtbQuestJeiRuntime {

    private static final List<QuestSubmissionSnapshot> REGISTERED = new ArrayList<>();
    private static IJeiRuntime runtime;
    private static QuestRecipeRefreshState refreshState = new QuestRecipeRefreshState();

    private FtbQuestJeiRuntime() {}

    public static void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        runtime = jeiRuntime;
        refreshState = new QuestRecipeRefreshState();
        refreshIfReady();
    }

    public static void onRuntimeUnavailable() {
        runtime = null;
        refreshState = new QuestRecipeRefreshState();
        REGISTERED.clear();
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (runtime == null) return;
        refreshIfReady();
    }

    public static void requestRefresh() {
        if (runtime == null) return;
        refreshState.requestRefresh();
    }

    private static void refreshIfReady() {
        if (runtime == null) return;
        var file = ClientQuestFile.exists() ? ClientQuestFile.INSTANCE : null;
        var data = file != null ? file.selfTeamData : null;
        if (!refreshState.tick(file, data, file != null && file.isLoading())) return;

        List<QuestSubmissionSnapshot> snapshots = FtbQuestSubmissionScanner.scan(file, data, true);
        if (sameJeiContent(snapshots, REGISTERED)) {
            refreshState.refreshed();
            return;
        }

        var manager = runtime.getRecipeManager();
        if (!REGISTERED.isEmpty()) {
            manager.hideRecipes(FtbQuestSubmissionRecipe.TYPE, REGISTERED);
        }
        manager.addRecipes(FtbQuestSubmissionRecipe.TYPE, snapshots);
        manager.unhideRecipes(FtbQuestSubmissionRecipe.TYPE, snapshots);
        REGISTERED.clear();
        REGISTERED.addAll(snapshots);
        refreshState.refreshed();
        RSIntegrationMod.LOGGER.info("[RSI-JEI] Refreshed FTB Quest submission entries: {}",
                snapshots.size());
    }

    static boolean sameJeiContent(List<QuestSubmissionSnapshot> left,
                                  List<QuestSubmissionSnapshot> right) {
        if (left.size() != right.size()) return false;
        for (int i = 0; i < left.size(); i++) {
            if (!left.get(i).jeiContentEquals(right.get(i))) return false;
        }
        return true;
    }
}
