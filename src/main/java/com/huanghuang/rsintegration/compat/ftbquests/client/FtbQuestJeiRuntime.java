package com.huanghuang.rsintegration.compat.ftbquests.client;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.compat.ftbquests.FtbQuestSubmissionScanner;
import com.huanghuang.rsintegration.compat.ftbquests.QuestSubmissionSnapshot;
import dev.ftb.mods.ftbquests.client.ClientQuestFile;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.List;

/** Adds player-specific quest recipes after FTB Quests finishes its client sync. */
public final class FtbQuestJeiRuntime {

    private static final int REFRESH_DEBOUNCE_TICKS = 20;
    private static final int MAX_REFRESH_DELAY_TICKS = 100;

    private static final List<QuestSubmissionSnapshot> REGISTERED = new ArrayList<>();
    private static IJeiRuntime runtime;
    private static boolean waiting;
    private static int ticksUntilRefresh;
    private static int ticksWaiting;

    private FtbQuestJeiRuntime() {}

    public static void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        runtime = jeiRuntime;
        waiting = true;
        ticksUntilRefresh = 0;
        ticksWaiting = 0;
        refreshIfReady();
    }

    public static void onRuntimeUnavailable() {
        runtime = null;
        waiting = false;
        ticksUntilRefresh = 0;
        ticksWaiting = 0;
        REGISTERED.clear();
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (runtime == null) return;
        if (waiting && (++ticksWaiting >= MAX_REFRESH_DELAY_TICKS
                || --ticksUntilRefresh <= 0)) {
            refreshIfReady();
        }
    }

    public static void requestRefresh() {
        if (runtime == null) return;
        if (!waiting) ticksWaiting = 0;
        waiting = true;
        ticksUntilRefresh = REFRESH_DEBOUNCE_TICKS;
    }

    private static void refreshIfReady() {
        if (runtime == null || !ClientQuestFile.exists()) return;
        var file = ClientQuestFile.INSTANCE;
        var data = file != null ? file.selfTeamData : null;
        if (file == null || data == null) return;

        List<QuestSubmissionSnapshot> snapshots = FtbQuestSubmissionScanner.scan(file, data, true);
        if (sameJeiContent(snapshots, REGISTERED)) {
            waiting = false;
            ticksWaiting = 0;
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
        waiting = false;
        ticksWaiting = 0;
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
